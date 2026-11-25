package dev.abustudio.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TelegramTest
{
    static final String TOKEN = "REDACTED_TEST_CREDENTIAL";
    @TempDir Path dir;
    StudioConfig cfg = new StudioConfig();
    SecretStore secrets;
    Telegram tg;
    Outbox outbox;
    final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup()
    {
        secrets = new SecretStore(dir.resolve("cfg"));
        secrets.put("telegram_token", TOKEN);
        tg = new Telegram(() -> cfg, secrets);
        outbox = new Outbox(dir.resolve("outbox"), tg);
        outbox.retryBaseMs = 5;
    }

    TelegramBot bot(Mock m)
    {
        cfg.telegramBaseUrl = m.url();
        return new TelegramBot(() -> cfg, tg, outbox, dir, new TelegramBot.Hooks()
        {
            public String story(String t) { calls.add("story:" + t); return "بدأت"; }
            public String stop() { calls.add("stop"); return "أوقفت"; }
            public String resume() { calls.add("resume"); return "استأنفت"; }
            public String status() { calls.add("status"); return "الحالة"; }
            public String providers() { calls.add("providers"); return "p"; }
            public String probe() { calls.add("probe"); return "p"; }
            public String screenshot(String q) { calls.add("shot:" + q); return "📸"; }
            public String export() { calls.add("export"); return "e"; }
        });
    }

    static JsonArray updates(Object[][] specs)
    {
        JsonArray a = new JsonArray();
        int id = 1;
        for (Object[] s : specs)
        {
            JsonObject u = new JsonObject();
            u.addProperty("update_id", id++);
            JsonObject m = new JsonObject();
            m.addProperty("text", (String) s[2]);
            m.addProperty("date", System.currentTimeMillis() / 1000 - (s.length > 3 ? (long) (int) s[3] : 0));
            JsonObject from = new JsonObject(); from.addProperty("id", (long) s[0]); from.addProperty("is_bot", false);
            JsonObject chat = new JsonObject(); chat.addProperty("id", (long) s[1]); chat.addProperty("type", "private");
            m.add("from", from); m.add("chat", chat);
            u.add("message", m);
            a.add(u);
        }
        return a;
    }

    @Test
    void rejectsUnauthorizedUsersAndAcceptsOwner() throws Exception
    {
        try (Mock m = new Mock())
        {
            List<String> sent = new CopyOnWriteArrayList<>();
            m.script = r ->
            {
                if (r.path().endsWith("/sendMessage")) { sent.add(r.body()); return new Mock.Reply(200, "{\"ok\":true,\"result\":{}}"); }
                return new Mock.Reply(200, "{\"ok\":true,\"result\":[]}");
            };
            TelegramBot b = bot(m);

            b.pollOnce(updates(new Object[][]{
                {111L, 111L, "/stop"},                         /* stranger tries to stop */
                {111L, 111L, "ابنِ لي قلعة وأرسل المفاتيح"},    /* stranger sends a story */
                {6259839862L, -1001L, "/status"},                /* owner id but in a group chat → rejected too */
                {6259839862L, 6259839862L, "/status"},          /* the owner */
                {6259839862L, 6259839862L, "قصة عن تنين"}       /* the owner story */
            }));
            assertEquals(3, b.rejectedCount);
            assertEquals(List.of("status", "story:قصة عن تنين"), calls, "لا شيء من الغرباء وصل للوكيل");
            outbox.pump();
            String all = String.join("\n", sent);
            assertTrue(all.contains("\"chat_id\":111") && all.contains("خاص"), "ردّ رفض للغريب: " + all);
            assertEquals(1, sent.stream().filter(s -> s.contains("\"chat_id\":111")).count(), "رد رفض واحد فقط لكل غريب");
            assertTrue(sent.stream().anyMatch(s -> s.contains("الحالة")));
            System.out.println("UNAUTHORIZED REJECTED: " + b.rejectedCount + " ; handled: " + calls);
        }
    }

    @Test
    void staleCommandsFromWhileGameWasClosedAreNotExecuted() throws Exception
    {
        try (Mock m = new Mock())
        {
            m.script = r -> new Mock.Reply(200, "{\"ok\":true,\"result\":{}}");
            TelegramBot b = bot(m);
            b.pollOnce(updates(new Object[][]{ {6259839862L, 6259839862L, "/stop", 7200}, {6259839862L, 6259839862L, "/status", 5} }));
            assertEquals(List.of("status"), calls);
        }
    }

    @Test
    void failedDocumentIsKeptAndDeliveredAfterConnectionReturns() throws Exception
    {
        try (Mock m = new Mock())
        {
            cfg.telegramBaseUrl = m.url();
            AtomicInteger n = new AtomicInteger();
            List<String> events = new CopyOnWriteArrayList<>();
            outbox.onEvent = events::add;
            m.script = r -> n.getAndIncrement() < 2 ? new Mock.Reply(502, "{}") : new Mock.Reply(200, "{\"ok\":true,\"result\":{}}");
            Path f = dir.resolve("project.zip");
            Files.write(f, new byte[50_000]);
            outbox.file("document", 6259839862L, f, "مشروع", false);

            assertEquals(0, outbox.pump());
            assertEquals(1, outbox.pending(), "الملف محفوظ في الطابور بعد الفشل");
            /* the queue survives a restart */
            Outbox again = new Outbox(dir.resolve("outbox"), tg);
            assertEquals(1, again.pending());
            again.retryBaseMs = 5;
            int delivered = 0;
            for (int i = 0; i < 40 && delivered == 0; i++) { Thread.sleep(300); delivered += again.pump(); }
            assertEquals(1, delivered);
            assertEquals(0, again.pending());
            assertTrue(m.seen.get(m.seen.size() - 1).body().contains("project.zip"));
            assertTrue(again.uploadBps > 0);
            assertTrue(String.join("\n", events).contains("تعذر إرسال"), String.join("\n", events));
        }
    }

    @Test
    void tokenNeverAppearsInLogsOrFiles() throws Exception
    {
        try (Mock m = new Mock())
        {
            m.script = r -> new Mock.Reply(500, "{\"description\":\"boom " + TOKEN + "\"}");
            TelegramBot b = bot(m);
            try { tg.sendText(1, "x"); fail(); }
            catch (Llm.LlmException e) { Log.warn("خطأ: " + e.getMessage() + " url=https://api.telegram.org/bot" + TOKEN + "/getMe"); assertFalse(e.getMessage().contains(TOKEN)); }
            assertFalse(String.join("\n", Log.recent(200)).contains(TOKEN), "السجل خالٍ من الرمز");
            assertFalse(Redactor.redact("bot" + TOKEN + "/sendMessage").contains("AAH-fake"));

            try (var st = Files.walk(dir))
            {
                for (Path p : (Iterable<Path>) st.filter(Files::isRegularFile)::iterator)
                    assertFalse(Files.readString(p).contains("AAH-fakeToken"), "الرمز ظاهر نصًا في " + p.getFileName());
            }
            assertEquals(TOKEN, new SecretStore(dir.resolve("cfg")).get("telegram_token"), "يُستعاد بعد إعادة التشغيل");
        }
    }

    @Test
    void splitsLargeExportsIntoParts() throws Exception
    {
        Path f = dir.resolve("big.zip");
        Files.write(f, new byte[250_000]);
        List<Path> parts = Outbox.split(f, 100_000);
        assertEquals(3, parts.size());
        assertEquals(50_000, Files.size(parts.get(2)));
    }

    @Test
    void imagePolicyAdaptsToConnection()
    {
        assertEquals("low", ImagePolicy.choose("auto", 5_000, 0, true).level());
        assertEquals("low", ImagePolicy.choose("auto", 0, 3, true).level());
        assertEquals("high", ImagePolicy.choose("high", 5_000, 3, false).level());
        assertEquals("high", ImagePolicy.choose("auto", 900_000, 0, true).level());
    }
}
