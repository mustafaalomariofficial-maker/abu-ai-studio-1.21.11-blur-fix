package dev.abustudio.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest
{
    @TempDir Path dir;
    final List<String> notes = new CopyOnWriteArrayList<>();
    StudioConfig cfg = new StudioConfig();
    SecretStore secrets;
    Router router;
    Tools tools = new Tools();
    Agent agent;
    final AtomicInteger bumps = new AtomicInteger();

    ProviderConfig prov(String id, String name, String url, String model)
    {
        ProviderConfig p = ProviderConfig.preset("openai", id);
        p.name = name; p.baseUrl = url; p.models.add(model); p.authMode = "bearer";
        p.local = true;
        cfg.providers.add(p);
        secrets.put("provider:" + id, "REDACTED_TEST_CREDENTIAL");
        return p;
    }

    @BeforeEach
    void setup()
    {
        NetMonitor.probe = () -> true;
        NetMonitor.reset();
        secrets = new SecretStore(dir.resolve("cfg"));
        router = new Router(() -> cfg, secrets, notes::add);
        router.backoffBaseMs = 5;
        tools.add("bump", "يزيد العداد", "{\"properties\":{}}", true, (a, c) -> { bumps.incrementAndGet(); return Tools.ok(); });
        agent = new Agent(() -> cfg, router, tools, notes::add, dir.resolve("runs"));
        agent.waitScale = 1;
    }

    @Test
    void failoverOnQuotaThenCompletesWithoutRepeatingActions() throws Exception
    {
        try (Mock a = new Mock(); Mock b = new Mock())
        {
            a.script = r -> new Mock.Reply(429, "{\"error\":{\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}");
            AtomicInteger n = new AtomicInteger();
            b.script = r -> switch (n.getAndIncrement())
            {
                case 0 -> new Mock.Reply(200, Mock.toolCall("bump", "{}"));
                default -> new Mock.Reply(200, Mock.toolCall("finish", "{\"summary\":\"تم\"}"));
            };

            ProviderConfig pa = prov("a", "المزود أ", a.url(), "m-a");
            pa.priority = 1;
            prov("b", "المزود ب", b.url(), "m-b").priority = 2;

            agent.submit("ابنِ بيتًا");
            assertTrue(agent.join(20000));

            assertEquals("DONE", agent.current().status);
            assertEquals(1, bumps.get(), "الفعل ينفذ مرة واحدة فقط");
            String all = String.join("\n", notes);
            assertTrue(all.contains("المزود أ") && all.contains("m-a") && all.contains("نفدت الحصة"), all);
            assertTrue(all.contains("المزود ب") && all.contains("m-b"), "إشعار التبديل يذكر البديل: " + all);
            assertFalse(all.contains("REDACTED_TEST_CREDENTIAL"), "لا مفاتيح في الإشعارات");
            System.out.println("NOTIFICATIONS:\n" + all);
        }
    }

    @Test
    void offlineWaitsThenResumesAutomatically() throws Exception
    {
        try (Mock b = new Mock())
        {
            AtomicInteger n = new AtomicInteger();
            b.script = r -> n.getAndIncrement() == 0 ? new Mock.Reply(200, Mock.toolCall("bump", "{}")) : new Mock.Reply(200, Mock.toolCall("finish", "{\"summary\":\"ok\"}"));
            ProviderConfig p = prov("cloud", "سحابي", b.url(), "m");
            p.local = false;                      /* behaves as a cloud provider */
            NetMonitor.probe = () -> false;       /* internet is down */
            NetMonitor.reset();
            agent.waitScale = 1;

            agent.submit("قصة");
            long end = System.currentTimeMillis() + 15000;
            while (!"WAITING_NETWORK".equals(agent.current().status) && System.currentTimeMillis() < end) Thread.sleep(50);
            assertEquals("WAITING_NETWORK", agent.current().status);
            assertEquals(0, b.seen.size(), "لا طلبات سحابية أثناء الانقطاع");
            assertTrue(String.join("\n", notes).contains("انقطع الإنترنت"));

            NetMonitor.probe = () -> true;        /* internet returns */
            assertTrue(agent.join(40000));
            assertEquals("DONE", agent.current().status);
            assertEquals(1, bumps.get());
            assertTrue(String.join("\n", notes).contains("عادت الواجهة"));
        }
    }

    @Test
    void localProviderIsUsedWhileOffline() throws Exception
    {
        try (Mock local = new Mock())
        {
            local.script = r -> new Mock.Reply(200, Mock.toolCall("finish", "{\"summary\":\"محلي\"}"));
            prov("loc", "LM Studio", local.url(), "qwen");
            NetMonitor.probe = () -> false;
            NetMonitor.reset();
            agent.submit("قصة");
            assertTrue(agent.join(20000));
            assertEquals("DONE", agent.current().status);
            assertEquals(1, local.seen.size());
        }
    }

    @Test
    void stopSavesCheckpointAndResumeContinues() throws Exception
    {
        try (Mock b = new Mock())
        {
            AtomicInteger n = new AtomicInteger();
            b.script = r ->
            {
                int i = n.getAndIncrement();
                if (i < 2) return new Mock.Reply(200, Mock.toolCall("wait_seconds", "{\"seconds\":2}"));
                return new Mock.Reply(200, Mock.toolCall("finish", "{\"summary\":\"x\"}"));
            };
            prov("p", "P", b.url(), "m");
            agent.submit("قصة");
            Thread.sleep(700);
            agent.stop();
            assertTrue(agent.join(20000));
            assertEquals("PAUSED", agent.current().status);
            assertTrue(Files.exists(dir.resolve("runs").resolve(agent.current().id).resolve("state.json")));

            /* new Agent instance = game restarted: loads the saved run */
            Agent a2 = new Agent(() -> cfg, router, tools, notes::add, dir.resolve("runs"));
            a2.resume();
            assertTrue(a2.join(30000));
            assertEquals("DONE", a2.current().status);
        }
    }

    @Test
    void interruptedMutatingCallIsNotBlindlyRepeated() throws Exception
    {
        /* simulate a crash between "start" and "done": journal has START only */
        RunState s = new RunState();
        s.id = "crash1"; s.title = "t"; s.request = "r"; s.createdAt = 1;
        s.messages.add(Llm.Msg.user("r"));
        Llm.ToolCall c = new Llm.ToolCall("call-X", "bump", Json.obj("{}"));
        s.messages.add(Llm.Msg.assistant(null, List.of(c)));
        Files.createDirectories(dir.resolve("runs/crash1"));
        Json.writeAtomic(dir.resolve("runs/crash1/state.json"), Json.GSON.toJson(s));
        new Journal(dir.resolve("runs/crash1/journal.jsonl")).start("call-X", "bump", Json.obj("{}"), null);

        try (Mock b = new Mock())
        {
            b.script = r -> new Mock.Reply(200, Mock.toolCall("finish", "{\"summary\":\"f\"}"));
            prov("p", "P", b.url(), "m");
            agent.resume();
            assertTrue(agent.join(20000));
            assertEquals(0, bumps.get(), "غير مؤكد ⇒ لا يُعاد تنفيذه تلقائيًا");
            String toolResult = agent.current().messages.stream().filter(m -> m.role.equals("tool")).findFirst().get().content;
            assertTrue(toolResult.contains("uncertain"), toolResult);
        }
    }

    @Test
    void verifiedAppliedIsNotRepeatedAndVerifiedMissingIsRun() throws Exception
    {
        for (boolean applied : new boolean[]{true, false})
        {
            bumps.set(0);
            tools.get("bump").verify(null, (a, pre, ctx) -> applied);
            String id = "crash-" + applied;
            RunState s = new RunState();
            s.id = id; s.title = "t"; s.request = "r"; s.createdAt = 1;
            s.messages.add(Llm.Msg.user("r"));
            s.messages.add(Llm.Msg.assistant(null, List.of(new Llm.ToolCall("call-" + id, "bump", Json.obj("{}")))));
            Files.createDirectories(dir.resolve("runs/" + id));
            Json.writeAtomic(dir.resolve("runs/" + id + "/state.json"), Json.GSON.toJson(s));
            new Journal(dir.resolve("runs/" + id + "/journal.jsonl")).start("call-" + id, "bump", Json.obj("{}"), null);

            try (Mock b = new Mock())
            {
                b.script = r -> new Mock.Reply(200, Mock.toolCall("finish", "{\"summary\":\"f\"}"));
                cfg.providers.clear();
                prov("p", "P", b.url(), "m");
                Agent a = new Agent(() -> cfg, router, tools, notes::add, dir.resolve("runs"));
                a.resume();
                assertTrue(a.join(20000));
                assertEquals(applied ? 0 : 1, bumps.get());
            }
            Files.delete(dir.resolve("runs/" + id + "/state.json"));
        }
    }

    @Test
    void allProvidersDownReportsAndRecovers() throws Exception
    {
        try (Mock b = new Mock())
        {
            AtomicInteger n = new AtomicInteger();
            b.script = r -> n.getAndIncrement() < 3 ? new Mock.Reply(503, "{\"error\":\"overloaded\"}") : new Mock.Reply(200, Mock.toolCall("finish", "{\"summary\":\"back\"}"));
            prov("p", "P", b.url(), "m");
            agent.waitScale = 0;     /* don't wait the real recovery delay in tests */
            agent.submit("قصة");
            assertTrue(agent.join(30000));
            assertEquals("DONE", agent.current().status);
            String all = String.join("\n", notes);
            assertTrue(all.contains("تعطلت جميع واجهات"), all);
        }
    }
}
