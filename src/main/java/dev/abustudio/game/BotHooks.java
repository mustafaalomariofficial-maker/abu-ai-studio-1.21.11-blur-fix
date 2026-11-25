package dev.abustudio.game;

import dev.abustudio.core.*;

import java.util.List;

/** What each Telegram command really does. */
final class BotHooks implements TelegramBot.Hooks
{
    private final Studio s;

    BotHooks(Studio s) { this.s = s; }

    /** Concrete Arabic setup instructions used whenever no usable provider exists (Telegram or in game). */
    static String setupHelp(String problem)
    {
        if (problem != null && !problem.isBlank()) return SetupMessages.help(problem);
        return "⚠️ " + problem + "\n\n"
            + "الخطوات: داخل اللعبة اضغط K ← ⚙ الإعدادات ← 🔌 المزودون ← ➕ Groq ← الصق المفتاح (من console.groq.com/keys) ← حفظ ← فحص القدرات.\n"
            + "أو ضع المفتاح في ملف config/abu-ai-studio.json داخل مجلد اللعبة:\n"
            + "{\"groq_api_key\":\"مفتاحك\"}\n"
            + "ثم أعد تشغيل اللعبة؛ وتظهر حالة الاتصال في شاشة الاستوديو والإعدادات.\n"
            + "ملاحظة: البوت لا يعمل واللعبة مغلقة، ولا يوجد مفتاح جاهز داخل المود.";
    }

    private String withModel(String out)
    {
        String used = s.router.lastUsed();
        if (used == null || used.isBlank()) return out;
        return out + "\n\n🤖 آخر رد من: " + used;
    }

    public String story(String text)
    {
        String problem = s.router.setupProblem();
        if (!problem.isEmpty()) return setupHelp(problem);

        s.say("📨 (تيليجرام) " + text);
        return withModel(s.agent.submit(text));
    }

    public String stop() { return s.agent.stop(); }
    public String resume() { return withModel(s.agent.resume()); }

    public String status()
    {
        String st = "🧠 حالة الذكاء: " + Activity.describe();
        st += "\n" + s.agent.status();
        st += "\n🌐 الإنترنت: " + (NetMonitor.online(true) ? "متصل" : "مقطوع");
        st += "\n📤 عناصر بانتظار الإرسال: " + s.outbox.pending();
        st += "\n🎮 اللعبة: " + (s.server != null ? "عالم مفتوح" : "لا يوجد عالم مفتوح") + (s.client != null && s.client.available() ? " | التقاط الصور متاح" : " | التقاط الصور غير متاح");
        String problem = s.router.setupProblem();
        st += "\n🔑 المزود: " + (problem.isEmpty() ? "مضبوط" : problem);
        return withModel(st);
    }

    public String providers()
    {
        if (s.cfg.providers.isEmpty()) return setupHelp("لا يوجد أي مزود مضاف.");
        StringBuilder b = new StringBuilder("🔌 المزودون:");
        for (ProviderConfig p : s.cfg.providers)
        {
            b.append("\n• ").append(p.name).append(p.enabled ? "" : " (معطل)").append(s.secrets.has("provider:" + p.id) || p.authMode.equals("none") ? "" : " ⚠️ بلا مفتاح");
            for (String m : p.models)
            {
                ProviderConfig.ModelProbe pr = p.probes.get(m);
                b.append("\n   - ").append(m).append(pr == null ? " (لم يُفحص)" : pr.toolsOk ? " ✅ أدوات " + pr.latencyMs + "ms" : pr.replied ? " ⚠️ بلا أدوات" : " ❌ " + pr.note);
            }
        }
        b.append("\nيُستخدم حاليًا: ").append(s.router.lastUsed().isEmpty() ? "—" : s.router.lastUsed());
        return b.toString();
    }

    public String probe()
    {
        String problem = s.router.setupProblem();
        if (!problem.isEmpty()) return setupHelp(problem);

        new Thread(() ->
        {
            for (ProviderConfig p : s.cfg.providers)
            {
                if (!p.enabled) continue;
                try { s.prober.discover(p); } catch (Llm.LlmException e) { s.sendTelegram("تعذر جلب موديلات " + p.name + ": " + e.kind.arabic); }
                List<String> r = s.prober.probeAll(p, 6);
                s.sendTelegram("🧪 نتيجة فحص " + p.name + ":\n" + String.join("\n", r));
            }
        }, "abu-probe").start();
        return "🧪 بدأ فحص الموديلات (قد يستغرق دقائق). سأرسل النتيجة.";
    }

    public String screenshot(String quality) { return ExtraTools.takeAndSendScreenshot(s, quality); }
    public String export() { return ExtraTools.exportAndSend(s); }
}
