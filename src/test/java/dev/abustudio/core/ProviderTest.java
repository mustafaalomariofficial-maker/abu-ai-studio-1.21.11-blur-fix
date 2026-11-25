package dev.abustudio.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProviderTest
{
    @TempDir Path dir;
    StudioConfig cfg = new StudioConfig();
    SecretStore secrets;

    @BeforeEach void setup() { secrets = new SecretStore(dir); NetMonitor.probe = () -> true; NetMonitor.reset(); }

    @Test
    void discoversModelsAndTestsToolSupportThenRanksTested()  throws Exception
    {
        try (Mock m = new Mock())
        {
            m.script = r ->
            {
                if (r.path().endsWith("/models")) return new Mock.Reply(200, "{\"data\":[{\"id\":\"good-tools\"},{\"id\":\"chat-only\"},{\"id\":\"whisper-large\"}]}");
                JsonObject b = Json.obj(r.body());
                boolean tools = b.has("tools");
                String model = b.get("model").getAsString();
                boolean hasToolResult = r.body().contains("\"role\":\"tool\"");
                if (!tools) return new Mock.Reply(200, Mock.text("زرقاء"));
                if (model.equals("chat-only")) return new Mock.Reply(400, "{\"error\":{\"message\":\"this model does not support tools\"}}");
                if (hasToolResult) return new Mock.Reply(200, Mock.text("النتيجة 7"));
                return new Mock.Reply(200, Mock.toolCall("echo_number", "{\"n\":7}"));
            };
            ProviderConfig p = ProviderConfig.preset("openai", "x");
            p.baseUrl = m.url();
            p.local = true;
            cfg.providers.add(p);
            secrets.put("provider:x", "REDACTED_TEST_CREDENTIAL");
            Prober pr = new Prober(() -> cfg, secrets, () -> {});
            List<String> found = pr.discover(p);
            assertEquals(List.of("good-tools", "chat-only"), found, "whisper مُستبعد");
            pr.probeAll(p, 5);
            assertTrue(p.probes.get("good-tools").toolsOk);
            assertFalse(p.probes.get("chat-only").toolsOk);
            assertTrue(p.probes.get("chat-only").replied);
            assertEquals("Bearer " + "REDACTED_TEST_CREDENTIAL", m.seen.get(0).auth());

            Router router = new Router(() -> cfg, secrets, t -> {});
            List<Router.Candidate> c = router.candidates(true);
            assertEquals(1, c.size(), "الموديل الذي فشل في اختبار الأدوات لا يُستخدم للوكيل");
            assertEquals("good-tools", c.get(0).model());
            assertTrue(c.get(0).verified());
        }
    }

    @Test
    void geminiNativeRequestAndFunctionCallParsing() throws Exception
    {
        try (Mock m = new Mock())
        {
            m.script = r ->
            {
                if (r.path().startsWith("/models?")) return new Mock.Reply(200, "{\"models\":[{\"name\":\"models/gemini-x\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/embed\",\"supportedGenerationMethods\":[\"embedContent\"]}]}");
                return new Mock.Reply(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"echo_number\",\"args\":{\"n\":7}},\"thoughtSignature\":\"SIG\"}]}}]}");
            };
            ProviderConfig p = ProviderConfig.preset("gemini", "g");
            p.baseUrl = m.url();
            List<String> ms = LlmClient.listModels(p, "REDACTED_TEST_CREDENTIAL");
            assertEquals(List.of("gemini-x"), ms);
            JsonObject params = Json.obj("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},\"additionalProperties\":false}");
            Llm.Result r = LlmClient.chat(p, "REDACTED_TEST_CREDENTIAL", "gemini-x", List.of(Llm.Msg.system("s"), Llm.Msg.user("hi")), List.of(new Llm.ToolSpec("echo_number", "d", params)));
            assertEquals("echo_number", r.toolCalls().get(0).name);
            assertEquals(7, Json.num(r.toolCalls().get(0).args, "n", 0));
            assertEquals("SIG", r.toolCalls().get(0).signature);
            Mock.Req last = m.seen.get(m.seen.size() - 1);
            assertEquals("REDACTED_TEST_CREDENTIAL", last.googKey());
            assertTrue(last.body().contains("functionDeclarations") && last.body().contains("systemInstruction") && !last.body().contains("additionalProperties"));
        }
    }

    @Test
    void classifiesFailures() throws Exception
    {
        try (Mock m = new Mock())
        {
            ProviderConfig p = ProviderConfig.preset("openai", "x");
            p.baseUrl = m.url();
            Object[][] cases = {{401, "{}", Llm.Failure.AUTH}, {429, "{\"error\":\"rate limit\"}", Llm.Failure.RATE_LIMIT},
                {429, "{\"error\":{\"code\":\"insufficient_quota\"}}", Llm.Failure.QUOTA}, {503, "{}", Llm.Failure.SERVER}, {404, "{\"error\":\"model_not_found\"}", Llm.Failure.MODEL_MISSING}};
            for (Object[] c : cases)
            {
                m.script = r -> new Mock.Reply((int) c[0], (String) c[1]);
                Llm.LlmException e = assertThrows(Llm.LlmException.class, () -> LlmClient.chat(p, "k", "m", List.of(Llm.Msg.user("x")), null));
                assertEquals(c[2], e.kind, "status " + c[0]);
            }
            p.baseUrl = "http://127.0.0.1:1";
            assertEquals(Llm.Failure.NETWORK, assertThrows(Llm.LlmException.class, () -> LlmClient.chat(p, "k", "m", List.of(Llm.Msg.user("x")), null)).kind);
        }
    }

    @Test
    void secretsAreEncryptedOnDiskAndImportFileIsConsumed() throws Exception
    {
        secrets.put("provider:groq", "REDACTED_TEST_CREDENTIAL");
        for (Path p : (Iterable<Path>) Files.list(dir)::iterator)
            if (Files.isRegularFile(p)) assertFalse(Files.readString(p).contains("REDACTED_TEST_CREDENTIAL"), p.getFileName() + " يحتوي المفتاح نصًا");
        Files.writeString(dir.resolve("secrets.import.json"), "{\"telegram_token\":\"REDACTED_TEST_CREDENTIAL\"}");
        assertEquals(1, secrets.importFile());
        assertFalse(Files.exists(dir.resolve("secrets.import.json")));
        assertEquals("REDACTED_TEST_CREDENTIAL", new SecretStore(dir).get("telegram_token"));
        assertFalse(Redactor.redact("failed with key REDACTED_TEST_CREDENTIAL").contains("REDACTED_TEST_CREDENTIAL"));
    }
}
