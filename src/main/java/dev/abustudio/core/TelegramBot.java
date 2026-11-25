package dev.abustudio.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Long-polling bot that runs INSIDE the game process. Only messages from the configured owner (private chat) are
 * ever turned into commands. Everything else is rejected before any text reaches the agent.
 * When the game is closed nothing here runs: the bot cannot answer, control the game or take screenshots.
 */
public final class TelegramBot
{
    public interface Hooks
    {
        String story(String text);
        String stop();
        String resume();
        String status();
        String providers();
        String probe();
        String screenshot(String quality);
        String export();
    }

    private final Supplier<StudioConfig> cfg;
    private final Telegram tg;
    private final Outbox outbox;
    private final Path stateFile;
    private final Hooks hooks;
    private Thread thread;
    private volatile boolean running;
    private long offset;
    private boolean firstBatch = true;
    private final Map<Long, Long> lastRejectReply = new HashMap<>();
    public volatile int rejectedCount;
    public volatile long lastPollOkAt;
    public volatile long pollWaitMs = 1000;
    public volatile int pollTimeoutSec = 25;

    public TelegramBot(Supplier<StudioConfig> cfg, Telegram tg, Outbox outbox, Path dir, Hooks hooks)
    {
        this.cfg = cfg;
        this.tg = tg;
        this.outbox = outbox;
        this.stateFile = dir.resolve("telegram-state.json");
        this.hooks = hooks;
        JsonObject o = Json.read(stateFile, JsonObject.class);
        if (o != null) offset = Json.num(o, "offset", 0);
    }

    public synchronized void start()
    {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "abu-telegram");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() { running = false; if (thread != null) thread.interrupt(); }

    private void loop()
    {
        long backoff = 2000;
        boolean warnedAuth = false;

        while (running)
        {
            try
            {
                if (!cfg.get().telegramEnabled || !tg.hasToken()) { Thread.sleep(5000); continue; }

                JsonObject p = new JsonObject();
                p.addProperty("offset", offset);
                p.addProperty("timeout", pollTimeoutSec);
                JsonArray allowed = new JsonArray();
                allowed.add("message");
                p.add("allowed_updates", allowed);
                JsonObject r = tg.call("getUpdates", p, pollTimeoutSec + 15);
                lastPollOkAt = System.currentTimeMillis();
                backoff = 2000;
                warnedAuth = false;
                pollOnce(r.getAsJsonArray("result"));
                Thread.sleep(pollWaitMs);
            }
            catch (InterruptedException e) { return; }
            catch (Llm.LlmException e)
            {
                if (e.kind == Llm.Failure.AUTH)
                {
                    if (!warnedAuth) { warnedAuth = true; Log.warn("تيليجرام: " + e.getMessage()); }
                    backoff = 60_000;
                }
                else backoff = Math.min(backoff * 2, 60_000);

                try { Thread.sleep(Math.max(backoff, e.retryAfterMs)); } catch (InterruptedException ie) { return; }
            }
            catch (Exception e) { Log.warn("خطأ في بوت تيليجرام: " + e.getClass().getSimpleName()); try { Thread.sleep(5000); } catch (InterruptedException ie) { return; } }
        }
    }

    /** Process one batch of updates (separate method so tests can drive it). */
    public void pollOnce(JsonArray updates)
    {
        if (updates == null) return;
        List<String> stale = new ArrayList<>();
        long staleCut = System.currentTimeMillis() / 1000 - cfg.get().staleCommandMinutes * 60L;

        for (JsonElement e : updates)
        {
            JsonObject u = e.getAsJsonObject();
            offset = Math.max(offset, u.get("update_id").getAsLong() + 1);

            if (!u.has("message") || !u.get("message").isJsonObject()) continue;
            JsonObject m = u.getAsJsonObject("message");
            JsonObject from = m.has("from") ? m.getAsJsonObject("from") : new JsonObject();
            JsonObject chat = m.has("chat") ? m.getAsJsonObject("chat") : new JsonObject();
            long fromId = from.has("id") ? from.get("id").getAsLong() : -1;
            long chatId = chat.has("id") ? chat.get("id").getAsLong() : -2;
            boolean isBot = Json.bool(from, "is_bot", false);
            String type = Json.str(chat, "type", "");
            long owner = cfg.get().telegramOwnerId;

            if (isBot || fromId != owner || chatId != owner || !type.equals("private"))
            {
                reject(fromId, chatId);
                continue;
            }

            String text = Json.str(m, "text", "").trim();
            if (text.isEmpty()) { reply(owner, "أستقبل النصوص فقط حاليًا (قصة أو أمر)."); continue; }

            long date = m.has("date") ? m.get("date").getAsLong() : System.currentTimeMillis() / 1000;
            if (firstBatch && date < staleCut) { stale.add(text.length() > 60 ? text.substring(0, 60) + "…" : text); continue; }

            try { handle(owner, text); }
            catch (Exception ex) { reply(owner, "❌ تعذر تنفيذ الأمر: " + ex.getClass().getSimpleName()); }
        }

        firstBatch = false;
        persistOffset();

        if (!stale.isEmpty())
        {
            reply(cfg.get().telegramOwnerId, "⚠️ وصلتني " + stale.size() + " رسالة أثناء إغلاق اللعبة (أقدم من " + cfg.get().staleCommandMinutes
                + " دقيقة) ولم أنفذها لأمانك:\n- " + String.join("\n- ", stale) + "\nأعد إرسال ما تريده الآن إن كان ما زال مطلوبًا.");
        }
    }

    private void persistOffset()
    {
        try { JsonObject o = new JsonObject(); o.addProperty("offset", offset); Json.writeAtomic(stateFile, Json.COMPACT.toJson(o)); }
        catch (Exception ignored) {}
    }

    private void reject(long fromId, long chatId)
    {
        rejectedCount++;
        Log.warn("رُفض أمر تيليجرام من معرّف غير مصرح: " + fromId + " (لم يُمرَّر أي نص منه إلى الذكاء الاصطناعي)");
        long now = System.currentTimeMillis();

        if (chatId > 0 && now - lastRejectReply.getOrDefault(fromId, 0L) > 3600_000L)
        {
            lastRejectReply.put(fromId, now);
            outbox.text(chatId, "⛔ هذا البوت خاص ولا يقبل أوامر إلا من صاحبه.");
        }
    }

    private void reply(long chatId, String text) { outbox.text(chatId, text); }

    private void handle(long owner, String text)
    {
        String lower = text.toLowerCase(Locale.ROOT);
        String cmd = lower.split("\\s+")[0].replaceAll("@.*$", "");
        String arg = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";

        String out;
        switch (cmd)
        {
            case "/start", "/help", "مساعدة" -> out = help();
            case "/status", "/حالة", "حالة" -> out = hooks.status();
            case "/stop", "/ايقاف", "/إيقاف", "ايقاف", "إيقاف", "توقف" -> out = hooks.stop();
            case "/resume", "/استكمال", "استكمال", "استئناف" -> out = hooks.resume();
            case "/providers", "/المزودين" -> out = hooks.providers();
            case "/probe", "/فحص" -> out = hooks.probe();
            case "/screenshot", "/صورة", "صورة", "لقطة" -> out = hooks.screenshot(arg);
            case "/export", "/تصدير", "تصدير" -> out = hooks.export();
            case "/story", "/قصة" -> out = arg.isEmpty() ? "اكتب القصة بعد الأمر، أو أرسلها كرسالة عادية." : hooks.story(arg);
            default -> out = cmd.startsWith("/") ? "أمر غير معروف. اكتب /help" : hooks.story(text);
        }

        if (out != null && !out.isBlank()) reply(owner, out);
    }

    public static String help()
    {
        return """
            🎬 استوديو الأنيميشن الذكي
            أرسل قصة أو أمرًا بالعربية كرسالة عادية فأبدأ التنفيذ داخل اللعبة.
            /status — حالة المهمة
            /stop — إيقاف وحفظ التقدم
            /resume — استكمال من آخر نقطة
            /screenshot [low|medium|high] — لقطة من اللعبة
            /export — تصدير مشروع الأنيميشن وإرساله
            /providers — المزودون والموديلات
            /probe — إعادة فحص الموديلات
            ⚠️ لا أستطيع الرد بلا مزود مضبوط: داخل اللعبة اضغط K ← ⚙ الإعدادات ← 🔌 المزودون ← ➕ Groq ← ضع المفتاح واحفظ، أو ضع المفتاح في config/abu-ai-studio.json.
            ⚠️ البوت يعمل داخل اللعبة؛ إن كانت اللعبة مغلقة فلا أستطيع الرد ولا التحكم ولا التقاط صور. الأوامر القديمة التي تصل أثناء الإغلاق لا تُنفَّذ تلقائيًا.""";
    }
}
