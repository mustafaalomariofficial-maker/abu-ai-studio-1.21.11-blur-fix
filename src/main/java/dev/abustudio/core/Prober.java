package dev.abustudio.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Lists a provider's models and tests what they can actually do (plain reply, tool calling, latency). */
public final class Prober
{
    private final Supplier<StudioConfig> cfg;
    private final SecretStore secrets;
    private final Runnable save;

    public Prober(Supplier<StudioConfig> cfg, SecretStore secrets, Runnable save)
    {
        this.cfg = cfg;
        this.secrets = secrets;
        this.save = save;
    }

    private static boolean chatLike(String id)
    {
        String l = id.toLowerCase();
        return !(l.contains("embed") || l.contains("whisper") || l.contains("tts") || l.contains("guard") || l.contains("moderation")
            || l.contains("image") || l.contains("dall") || l.contains("rerank") || l.contains("transcribe") || l.contains("imagen")
            || l.contains("veo") || l.contains("aqa") || l.contains("audio"));
    }

    /** Fetch the model list and store it. @return model ids found (throws on failure). */
    public List<String> discover(ProviderConfig p) throws Llm.LlmException
    {
        List<String> found = LlmClient.listModels(p, secrets.get("provider:" + p.id));
        found.removeIf(m -> !chatLike(m));

        for (String m : found) if (!p.models.contains(m)) p.models.add(m);
        save.run();
        return found;
    }

    public ProviderConfig.ModelProbe probe(ProviderConfig p, String model)
    {
        ProviderConfig.ModelProbe r = new ProviderConfig.ModelProbe();
        r.testedAt = System.currentTimeMillis();
        String key = secrets.get("provider:" + p.id);
        long total = 0;

        try
        {
            /* 1) plain Arabic reply */
            Llm.Result a = LlmClient.chat(p, key, model, List.of(Llm.Msg.user("أجب بكلمة واحدة فقط: ما لون السماء في النهار؟")), null);
            r.replied = a.text() != null && !a.text().isBlank();
            total += a.latencyMs();

            /* 2) tool calling: must call echo_number with n = 7 */
            JsonObject params = Json.obj("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"description\":\"الرقم\"}},\"required\":[\"n\"]}");
            List<Llm.ToolSpec> tools = List.of(new Llm.ToolSpec("echo_number", "يعيد الرقم الذي تمرره", params));
            Llm.Result b = LlmClient.chat(p, key, model, List.of(Llm.Msg.user("استدعِ الأداة echo_number مع n=7 ولا تكتب أي شيء آخر.")), tools);
            total += b.latencyMs();
            boolean called = false;

            for (Llm.ToolCall c : b.toolCalls())
                if (c.name.equals("echo_number") && Json.num(c.args, "n", -1) == 7) called = true;

            r.toolsOk = called;
            r.note = called ? "نجح اختبار الأدوات" : "لم يستدعِ الأداة بشكل صحيح";

            /* 3) round trip: send the tool result back and expect a final answer or a clean response */
            if (called)
            {
                Llm.ToolCall c = b.toolCalls().get(0);
                List<Llm.Msg> conv = new ArrayList<>();
                conv.add(Llm.Msg.user("استدعِ الأداة echo_number مع n=7 ثم أخبرني بالنتيجة."));
                conv.add(Llm.Msg.assistant(b.text(), b.toolCalls()));
                conv.add(Llm.Msg.tool(c.id, c.name, "{\"n\":7}"));
                Llm.Result d = LlmClient.chat(p, key, model, conv, tools);
                total += d.latencyMs();
                if ((d.text() == null || d.text().isBlank()) && d.toolCalls().isEmpty())
                {
                    r.toolsOk = false;
                    r.note = "فشل في إكمال دورة نتيجة الأداة";
                }
            }
        }
        catch (Llm.LlmException e)
        {
            r.note = e.kind.arabic + (e.status > 0 ? " (HTTP " + e.status + ")" : "");
            if (e.kind == Llm.Failure.NO_TOOLS) r.replied = true;
        }

        r.latencyMs = total / 3;
        p.probes.put(model, r);
        save.run();
        return r;
    }

    /** Probe up to {@code max} models (preferred first). */
    public List<String> probeAll(ProviderConfig p, int max)
    {
        List<String> order = new ArrayList<>(p.models);
        if (!p.preferredModel.isBlank()) { order.remove(p.preferredModel); order.add(0, p.preferredModel); }
        List<String> done = new ArrayList<>();

        for (String m : order)
        {
            if (done.size() >= max) break;
            ProviderConfig.ModelProbe r = probe(p, m);
            done.add(m + " ← " + (r.toolsOk ? "✅ أدوات" : r.replied ? "⚠️ يرد بلا أدوات" : "❌") + " " + r.latencyMs + "ms " + r.note);
        }

        return done;
    }
}
