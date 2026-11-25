package dev.abustudio.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One API provider. The key itself is NOT stored here (see SecretStore, name "provider:<id>"). */
public class ProviderConfig
{
    public String id;
    public String name;
    /** openai | groq | gemini | ollama | lmstudio | omniroute */
    public String type = "openai";
    public String baseUrl = "";
    /** bearer | header | query | none */
    public String authMode = "bearer";
    /** header name or query parameter name when authMode is header/query */
    public String authName = "";
    public boolean enabled = true;
    /** null = detect from the address; true/false = user override (local models keep working offline). */
    public Boolean local;
    public int priority = 100;
    /** Models added manually or discovered. */
    public List<String> models = new ArrayList<>();
    /** Model the user prefers (tried first among equally tested ones). */
    public String preferredModel = "";
    public Map<String, ModelProbe> probes = new LinkedHashMap<>();

    public static class ModelProbe
    {
        public boolean replied;
        public boolean toolsOk;
        public long latencyMs;
        public long testedAt;
        public String note = "";
    }

    public static ProviderConfig preset(String type, String id)
    {
        ProviderConfig p = new ProviderConfig();
        p.id = id;
        p.type = type;
        switch (type)
        {
            case "groq" -> { p.name = "Groq"; p.baseUrl = "https://api.groq.com/openai/v1"; p.preferredModel = "openai/gpt-oss-20b"; p.models.add(p.preferredModel); }
            case "gemini" -> { p.name = "Google Gemini"; p.baseUrl = "https://generativelanguage.googleapis.com/v1beta"; p.authMode = "header"; p.authName = "x-goog-api-key"; }
            case "ollama" -> { p.name = "Ollama (محلي)"; p.baseUrl = "http://127.0.0.1:11434"; p.authMode = "none"; }
            case "lmstudio" -> { p.name = "LM Studio (محلي)"; p.baseUrl = "http://127.0.0.1:1234/v1"; p.authMode = "none"; }
            case "omniroute" -> { p.name = "OmniRoute"; p.baseUrl = "http://127.0.0.1:20128/v1"; p.authMode = "bearer"; }
            default -> { p.name = "OpenAI-متوافق"; p.baseUrl = "https://api.openai.com/v1"; }
        }
        return p;
    }

    public boolean isLocal()
    {
        if (local != null) return local;
        try
        {
            String host = java.net.URI.create(baseUrl).getHost();
            if (host == null) return false;
            return host.equals("localhost") || host.equals("::1") || host.equals("[::1]") || host.startsWith("127.")
                || host.startsWith("10.") || host.startsWith("192.168.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
        }
        catch (Exception e) { return false; }
    }
}
