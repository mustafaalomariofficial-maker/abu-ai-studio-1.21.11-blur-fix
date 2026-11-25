package dev.abustudio.core;

import com.google.gson.*;

import java.util.*;

/** Talks to one provider. Stateless; the key is passed in per call and never stored in the client. */
public final class LlmClient
{
    private LlmClient() {}

    private static boolean isGemini(ProviderConfig p) { return p.type.equals("gemini"); }

    /** Ollama's OpenAI-compatible endpoint lives under /v1; users usually type the bare host. */
    private static String chatBase(ProviderConfig p)
    {
        String b = p.baseUrl.replaceAll("/+$", "");
        if (p.type.equals("ollama") && !b.endsWith("/v1")) b += "/v1";
        return b;
    }

    private static Map<String, String> headers(ProviderConfig p, String key)
    {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        if (key != null && !key.isBlank())
        {
            switch (p.authMode)
            {
                case "bearer" -> h.put("Authorization", "Bearer " + key);
                case "header" -> h.put(p.authName.isBlank() ? "x-api-key" : p.authName, key);
                default -> {}
            }
        }
        return h;
    }

    private static String withQuery(ProviderConfig p, String key, String url)
    {
        if (p.authMode.equals("query") && key != null && !key.isBlank())
        {
            String name = p.authName.isBlank() ? "key" : p.authName;
            return url + (url.contains("?") ? "&" : "?") + name + "=" + java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
        }
        return url;
    }

    private static void needKey(ProviderConfig p, String key) throws Llm.LlmException
    {
        if (!p.authMode.equals("none") && (key == null || key.isBlank()))
            throw new Llm.LlmException(Llm.Failure.AUTH, 0, "لا يوجد مفتاح محفوظ للمزود " + p.name);
    }

    /* ------------------------------------------------------------------ model listing */

    public static List<String> listModels(ProviderConfig p, String key) throws Llm.LlmException
    {
        needKey(p, key);
        List<String> out = new ArrayList<>();

        if (isGemini(p))
        {
            String url = withQuery(p, key, p.baseUrl.replaceAll("/+$", "") + "/models?pageSize=200");
            Http.Response r = Http.send("GET", url, headers(p, key), null);
            if (r.status() >= 400) throw Http.classify(r);
            JsonObject o = Json.obj(r.body());

            if (o.has("models"))
            {
                for (JsonElement e : o.getAsJsonArray("models"))
                {
                    JsonObject m = e.getAsJsonObject();
                    boolean gen = false;
                    if (m.has("supportedGenerationMethods"))
                        for (JsonElement g : m.getAsJsonArray("supportedGenerationMethods")) if (g.getAsString().equals("generateContent")) gen = true;
                    if (gen) out.add(Json.str(m, "name", "").replaceFirst("^models/", ""));
                }
            }
            return out;
        }

        String url;
        if (p.type.equals("ollama"))
            url = p.baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "") + "/api/tags";
        else
            url = withQuery(p, key, chatBase(p) + "/models");

        Http.Response r = Http.send("GET", url, headers(p, key), null);
        if (r.status() >= 400) throw Http.classify(r);
        JsonObject o = Json.obj(r.body());

        if (o.has("data"))
            for (JsonElement e : o.getAsJsonArray("data")) out.add(Json.str(e.getAsJsonObject(), "id", ""));
        else if (o.has("models"))
            for (JsonElement e : o.getAsJsonArray("models")) out.add(Json.str(e.getAsJsonObject(), "name", Json.str(e.getAsJsonObject(), "model", "")));

        out.removeIf(String::isBlank);
        return out;
    }

    /* ------------------------------------------------------------------ chat */

    public static Llm.Result chat(ProviderConfig p, String key, String model, List<Llm.Msg> msgs, List<Llm.ToolSpec> tools) throws Llm.LlmException
    {
        needKey(p, key);
        long t0 = System.currentTimeMillis();
        Llm.Result res = isGemini(p) ? chatGemini(p, key, model, msgs, tools) : chatOpenAi(p, key, model, msgs, tools);
        return new Llm.Result(res.text(), res.toolCalls(), System.currentTimeMillis() - t0, res.raw());
    }

    private static Llm.Result chatOpenAi(ProviderConfig p, String key, String model, List<Llm.Msg> msgs, List<Llm.ToolSpec> tools) throws Llm.LlmException
    {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.3);
        JsonArray ms = new JsonArray();

        for (Llm.Msg m : msgs)
        {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role);

            if (m.role.equals("tool"))
            {
                o.addProperty("tool_call_id", m.toolCallId);
                o.addProperty("content", m.content == null ? "" : m.content);
            }
            else
            {
                if (m.content != null) o.addProperty("content", m.content);
                else if (m.toolCalls == null) o.addProperty("content", "");
                else o.add("content", JsonNull.INSTANCE);

                if (m.toolCalls != null)
                {
                    JsonArray tcs = new JsonArray();
                    for (Llm.ToolCall c : m.toolCalls)
                    {
                        JsonObject tc = new JsonObject();
                        tc.addProperty("id", c.id);
                        tc.addProperty("type", "function");
                        JsonObject fn = new JsonObject();
                        fn.addProperty("name", c.name);
                        fn.addProperty("arguments", Json.COMPACT.toJson(c.args));
                        tc.add("function", fn);
                        tcs.add(tc);
                    }
                    o.add("tool_calls", tcs);
                }
            }
            ms.add(o);
        }

        body.add("messages", ms);

        if (tools != null && !tools.isEmpty())
        {
            JsonArray ts = new JsonArray();
            for (Llm.ToolSpec t : tools)
            {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", t.name());
                fn.addProperty("description", t.description());
                fn.add("parameters", t.parameters());
                JsonObject w = new JsonObject();
                w.addProperty("type", "function");
                w.add("function", fn);
                ts.add(w);
            }
            body.add("tools", ts);
            body.addProperty("tool_choice", "auto");
        }

        Http.Response r = Http.send("POST", withQuery(p, key, chatBase(p) + "/chat/completions"), headers(p, key), Json.COMPACT.toJson(body));
        if (r.status() >= 400) throw Http.classify(r);

        JsonObject o;
        try { o = Json.obj(r.body()); }
        catch (Exception e) { throw new Llm.LlmException(Llm.Failure.SERVER, r.status(), "رد غير صالح (ليس JSON)"); }

        if (!o.has("choices") || o.getAsJsonArray("choices").isEmpty())
        {
            String err = o.has("error") ? o.get("error").toString() : "رد بلا اختيارات";
            throw new Llm.LlmException(Llm.Failure.SERVER, r.status(), err);
        }

        JsonObject msg = o.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        String text = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
        List<Llm.ToolCall> calls = new ArrayList<>();

        if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull())
        {
            int i = 0;
            for (JsonElement e : msg.getAsJsonArray("tool_calls"))
            {
                JsonObject tc = e.getAsJsonObject();
                JsonObject fn = tc.getAsJsonObject("function");
                JsonObject args;
                try
                {
                    JsonElement a = fn.get("arguments");
                    args = a == null || a.isJsonNull() ? new JsonObject() : a.isJsonObject() ? a.getAsJsonObject() : Json.obj(a.getAsString().isBlank() ? "{}" : a.getAsString());
                }
                catch (Exception ex) { args = new JsonObject(); args.addProperty("_parse_error", "arguments ليست JSON صالحًا"); }
                calls.add(new Llm.ToolCall(Json.str(tc, "id", "call_" + (i++) + "_" + System.nanoTime()), Json.str(fn, "name", ""), args));
            }
        }

        return new Llm.Result(text, calls, 0, null);
    }

    private static JsonElement geminiSchema(JsonElement s)
    {
        /* Gemini accepts an OpenAPI subset; drop what it rejects. */
        if (s.isJsonObject())
        {
            JsonObject o = new JsonObject();
            for (Map.Entry<String, JsonElement> e : s.getAsJsonObject().entrySet())
            {
                if (e.getKey().equals("additionalProperties") || e.getKey().equals("$schema") || e.getKey().equals("default")) continue;
                o.add(e.getKey(), geminiSchema(e.getValue()));
            }
            return o;
        }
        if (s.isJsonArray())
        {
            JsonArray a = new JsonArray();
            for (JsonElement e : s.getAsJsonArray()) a.add(geminiSchema(e));
            return a;
        }
        return s;
    }

    private static Llm.Result chatGemini(ProviderConfig p, String key, String model, List<Llm.Msg> msgs, List<Llm.ToolSpec> tools) throws Llm.LlmException
    {
        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        StringBuilder sys = new StringBuilder();
        JsonObject pendingResponses = null;      /* consecutive tool results are merged into one user turn */

        for (Llm.Msg m : msgs)
        {
            if (m.role.equals("system")) { sys.append(m.content).append("\n"); continue; }

            if (m.role.equals("tool"))
            {
                if (pendingResponses == null)
                {
                    pendingResponses = new JsonObject();
                    pendingResponses.addProperty("role", "user");
                    pendingResponses.add("parts", new JsonArray());
                    contents.add(pendingResponses);
                }
                JsonObject fr = new JsonObject();
                fr.addProperty("name", m.name);
                JsonObject resp = new JsonObject();
                try { JsonElement parsed = JsonParser.parseString(m.content); resp.add("result", parsed); }
                catch (Exception e) { resp.addProperty("result", m.content); }
                fr.add("response", resp);
                JsonObject part = new JsonObject();
                part.add("functionResponse", fr);
                pendingResponses.getAsJsonArray("parts").add(part);
                continue;
            }

            pendingResponses = null;
            JsonObject c = new JsonObject();
            JsonArray parts = new JsonArray();

            if (m.role.equals("assistant"))
            {
                c.addProperty("role", "model");
                if (m.content != null && !m.content.isBlank()) { JsonObject t = new JsonObject(); t.addProperty("text", m.content); parts.add(t); }
                if (m.toolCalls != null)
                    for (Llm.ToolCall tc : m.toolCalls)
                    {
                        JsonObject fc = new JsonObject();
                        fc.addProperty("name", tc.name);
                        fc.add("args", tc.args);
                        JsonObject part = new JsonObject();
                        part.add("functionCall", fc);
                        if (tc.signature != null) part.addProperty("thoughtSignature", tc.signature);
                        parts.add(part);
                    }
                if (parts.isEmpty()) { JsonObject t = new JsonObject(); t.addProperty("text", "."); parts.add(t); }
            }
            else
            {
                c.addProperty("role", "user");
                JsonObject t = new JsonObject();
                t.addProperty("text", m.content == null || m.content.isBlank() ? "." : m.content);
                parts.add(t);
            }

            c.add("parts", parts);
            contents.add(c);
        }

        body.add("contents", contents);

        if (sys.length() > 0)
        {
            JsonObject si = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject t = new JsonObject();
            t.addProperty("text", sys.toString());
            parts.add(t);
            si.add("parts", parts);
            body.add("systemInstruction", si);
        }

        if (tools != null && !tools.isEmpty())
        {
            JsonArray decls = new JsonArray();
            for (Llm.ToolSpec t : tools)
            {
                JsonObject d = new JsonObject();
                d.addProperty("name", t.name());
                d.addProperty("description", t.description());
                if (t.parameters().has("properties") && !t.parameters().getAsJsonObject("properties").isEmpty())
                    d.add("parameters", geminiSchema(t.parameters()));
                decls.add(d);
            }
            JsonObject tool = new JsonObject();
            tool.add("functionDeclarations", decls);
            JsonArray ts = new JsonArray();
            ts.add(tool);
            body.add("tools", ts);
        }

        JsonObject gen = new JsonObject();
        gen.addProperty("temperature", 0.3);
        body.add("generationConfig", gen);

        String url = withQuery(p, key, p.baseUrl.replaceAll("/+$", "") + "/models/" + model + ":generateContent");
        Http.Response r = Http.send("POST", url, headers(p, key), Json.COMPACT.toJson(body));
        if (r.status() >= 400) throw Http.classify(r);

        JsonObject o = Json.obj(r.body());
        if (!o.has("candidates") || o.getAsJsonArray("candidates").isEmpty())
            throw new Llm.LlmException(Llm.Failure.SERVER, r.status(), o.has("promptFeedback") ? "تم حجب الطلب: " + o.get("promptFeedback") : "رد بلا مرشحين");

        JsonObject cand = o.getAsJsonArray("candidates").get(0).getAsJsonObject();
        StringBuilder text = new StringBuilder();
        List<Llm.ToolCall> calls = new ArrayList<>();

        if (cand.has("content") && cand.getAsJsonObject("content").has("parts"))
        {
            int i = 0;
            for (JsonElement e : cand.getAsJsonObject("content").getAsJsonArray("parts"))
            {
                JsonObject part = e.getAsJsonObject();
                if (part.has("text") && !(part.has("thought") && part.get("thought").getAsBoolean())) text.append(part.get("text").getAsString());
                if (part.has("functionCall"))
                {
                    JsonObject fc = part.getAsJsonObject("functionCall");
                    Llm.ToolCall tc = new Llm.ToolCall("gem_" + (i++) + "_" + System.nanoTime(), Json.str(fc, "name", ""),
                        fc.has("args") && fc.get("args").isJsonObject() ? fc.getAsJsonObject("args") : new JsonObject());
                    if (part.has("thoughtSignature")) tc.signature = part.get("thoughtSignature").getAsString();
                    calls.add(tc);
                }
            }
        }

        return new Llm.Result(text.toString(), calls, 0, null);
    }
}
