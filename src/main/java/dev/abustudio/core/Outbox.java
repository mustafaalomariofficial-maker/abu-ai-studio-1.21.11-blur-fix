package dev.abustudio.core;

import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Persistent send queue for Telegram. Every message/photo/file is stored on disk first, then sent with retries and
 * exponential backoff, so a weak or dropped connection (or closing the game) never loses it. Large exports are split
 * into parts so a failure only repeats one part, not the whole upload. (A single HTTP upload cannot be resumed
 * mid-file by the Telegram Bot API — only whole parts are resumable.)
 */
public final class Outbox
{
    public static class Item
    {
        public String id;
        public String kind;          /* text | photo | document */
        public long chatId;
        public String text;
        public String file;
        public String caption;
        public int attempts;
        public long nextAt;
        public long createdAt;
        public boolean deleteAfter;
    }

    private final Path dir;
    private final Telegram tg;
    private final List<Item> items = new ArrayList<>();
    private Thread worker;
    private volatile boolean running;
    public volatile Consumer<String> onEvent = s -> {};
    /** Bytes/second moving average of real uploads; 0 = unknown. */
    public volatile double uploadBps;
    public volatile int recentFailures;
    public volatile long retryBaseMs = 5000;

    public Outbox(Path dir, Telegram tg)
    {
        this.dir = dir;
        this.tg = tg;
        load();
    }

    private Path index() { return dir.resolve("queue.json"); }

    private void load()
    {
        try
        {
            if (Files.exists(index()))
            {
                List<Item> l = Json.GSON.fromJson(Files.readString(index()), new TypeToken<List<Item>>() {}.getType());
                if (l != null) items.addAll(l);
            }
        }
        catch (Exception e) { Log.warn("تعذر قراءة صندوق الإرسال"); }
    }

    private synchronized void persist()
    {
        try { Json.writeAtomic(index(), Json.GSON.toJson(items)); }
        catch (IOException e) { Log.warn("تعذر حفظ صندوق الإرسال"); }
    }

    public synchronized int pending() { return items.size(); }

    public synchronized void text(long chatId, String text)
    {
        Item i = base("text", chatId);
        i.text = text;
        items.add(i);
        while (items.size() > 300) items.remove(0);
        persist();
    }

    /** Copies the file into the outbox folder so it survives even if the original is replaced. */
    public synchronized void file(String kind, long chatId, Path src, String caption, boolean deleteSrc)
    {
        try
        {
            Files.createDirectories(dir.resolve("files"));
            Item i = base(kind, chatId);
            Path copy = dir.resolve("files").resolve(i.id + "-" + src.getFileName());
            Files.copy(src, copy, StandardCopyOption.REPLACE_EXISTING);
            if (deleteSrc) Files.deleteIfExists(src);
            i.file = copy.toString();
            i.caption = caption;
            i.deleteAfter = true;
            items.add(i);
            persist();
        }
        catch (IOException e) { Log.warn("تعذر حفظ الملف في صندوق الإرسال: " + e.getMessage()); }
    }

    private Item base(String kind, long chatId)
    {
        Item i = new Item();
        i.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(1296), 36);
        i.kind = kind;
        i.chatId = chatId;
        i.createdAt = System.currentTimeMillis();
        return i;
    }

    public synchronized void start()
    {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "abu-outbox");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() { running = false; if (worker != null) worker.interrupt(); }

    /** Process everything that is due, once. Returns number delivered. (Used by the worker and by tests.) */
    public int pump()
    {
        int sent = 0;

        while (true)
        {
            Item next = null;
            synchronized (this)
            {
                long now = System.currentTimeMillis();
                for (Item i : items) if (i.nextAt <= now) { next = i; break; }
            }
            if (next == null) return sent;
            if (!tg.hasToken()) return sent;

            long t0 = System.currentTimeMillis();

            try
            {
                if (next.kind.equals("text"))
                {
                    if (System.currentTimeMillis() - next.createdAt > 6 * 3600_000L) { drop(next, false); continue; }
                    tg.sendText(next.chatId, next.text);
                }
                else
                {
                    Path f = Path.of(next.file);
                    if (!Files.exists(f)) { drop(next, false); continue; }
                    tg.sendFile(next.kind, next.chatId, f, next.caption);
                    double secs = Math.max(0.2, (System.currentTimeMillis() - t0) / 1000.0);
                    double bps = sizeOf(f) / secs;
                    uploadBps = uploadBps == 0 ? bps : uploadBps * 0.6 + bps * 0.4;
                }

                boolean wasRetried = next.attempts > 0;
                recentFailures = 0;
                drop(next, true);
                sent++;
                if (wasRetried && !next.kind.equals("text")) onEvent.accept("📬 تم إرسال «" + Path.of(next.file).getFileName() + "» المؤجل بعد استعادة الاتصال.");
            }
            catch (Llm.LlmException e)
            {
                recentFailures++;
                next.attempts++;
                long backoff = e.retryAfterMs > 0 ? e.retryAfterMs : Math.min(retryBaseMs * (1L << Math.min(next.attempts, 6)), 5 * 60_000L);

                if (e.kind == Llm.Failure.BAD_REQUEST && next.attempts >= 3) { drop(next, false); onEvent.accept("❌ رفض تيليجرام إرسال عنصر وحُذف من الطابور: " + e.getMessage()); continue; }
                if (e.kind == Llm.Failure.AUTH) backoff = 5 * 60_000L;

                synchronized (this) { next.nextAt = System.currentTimeMillis() + backoff; persist(); }
                if (next.attempts == 1 && !next.kind.equals("text"))
                    onEvent.accept("📭 تعذر إرسال «" + Path.of(next.file).getFileName() + "» (" + e.kind.arabic + "). حُفظ في الطابور وسأعيد المحاولة تلقائيًا.");
                return sent;
            }
        }
    }

    private static long sizeOf(Path f) { try { return Files.size(f); } catch (IOException e) { return 0; } }

    private synchronized void drop(Item i, boolean delivered)
    {
        items.remove(i);
        persist();
        if (i.file != null && i.deleteAfter) { try { Files.deleteIfExists(Path.of(i.file)); } catch (IOException ignored) {} }
    }

    private void runLoop()
    {
        while (running)
        {
            try
            {
                if (pending() > 0) pump();
                Thread.sleep(2000);
            }
            catch (InterruptedException e) { return; }
            catch (Exception e) { Log.warn("خطأ في صندوق الإرسال: " + e.getClass().getSimpleName()); }
        }
    }

    /** Splits a big file into numbered parts of at most partBytes. */
    public static List<Path> split(Path file, long partBytes) throws IOException
    {
        long size = Files.size(file);
        if (size <= partBytes) return List.of(file);
        List<Path> parts = new ArrayList<>();

        try (var in = Files.newInputStream(file))
        {
            byte[] buf = new byte[1 << 16];
            int n = 1;
            long remaining = size;

            while (remaining > 0)
            {
                Path p = file.resolveSibling(file.getFileName() + String.format(".%03d", n++));
                long toCopy = Math.min(partBytes, remaining);

                try (var out = Files.newOutputStream(p))
                {
                    long c = 0;
                    while (c < toCopy)
                    {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, toCopy - c));
                        if (r < 0) break;
                        out.write(buf, 0, r);
                        c += r;
                    }
                }

                parts.add(p);
                remaining -= toCopy;
            }
        }

        return parts;
    }
}
