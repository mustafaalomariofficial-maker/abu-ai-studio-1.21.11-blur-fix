package dev.abustudio.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional plain-text setup file read once at start: <game>/config/abu-ai-studio.json
 * (the abu_studio settings folder lives next to it). It lets a key be added without opening the game UI.
 * Nothing from this file is ever logged, printed, sent to chat or committed; only a count is reported.
 */
public final class ExternalConfig
{
    private ExternalConfig() {}

    public static Path file(Path configRoot) { return configRoot.resolve("abu-ai-studio.json"); }

    /** @return a short Arabic note for the log, or "" when there is nothing to read. */
    public static String apply(Path configRoot, StudioConfig cfg, SecretStore secrets)
    {
        if (configRoot == null) return "";
        Path f = file(configRoot);
        if (!Files.isRegularFile(f)) return "";

        try
        {
            JsonObject o = Json.obj(Files.readString(f));
            int applied = 0;

            if (o.has("providers") && o.get("providers").isJsonArray())
            {
                JsonArray arr = o.getAsJsonArray("providers");
                for (JsonElement el : arr)
                {
                    if (!el.isJsonObject()) continue;
                    JsonObject po = el.getAsJsonObject();
                    String type = Json.str(po, "type", "openai");
                    String id = Json.str(po, "id", type);
                    ProviderConfig p = cfg.provider(id);
                    if (p == null) { p = ProviderConfig.preset(type, id); cfg.providers.add(p); }

                    String name = Json.str(po, "name", "");
                    if (!name.isBlank()) p.name = name;
                    String url = Json.str(po, "baseUrl", "");
                    if (!url.isBlank()) p.baseUrl = url;

                    String model = Json.str(po, "model", "");
                    if (!model.isBlank()) { p.preferredModel = model; if (!p.models.contains(model)) p.models.add(model); }
                    if (po.has("models") && po.get("models").isJsonArray())
                        for (JsonElement m : po.getAsJsonArray("models"))
                            if (m.isJsonPrimitive() && !p.models.contains(m.getAsString())) p.models.add(m.getAsString());

                    String key = firstNonBlank(Json.str(po, "apiKey", ""), Json.str(po, "key", ""), Json.str(po, "token", ""));
                    if (!key.isBlank()) { secrets.put("provider:" + p.id, key); applied++; }
                }
            }

            String single = firstNonBlank(Json.str(o, "groq_api_key", ""), Json.str(o, "api_key", ""), Json.str(o, "openai_api_key", ""));
            if (!single.isBlank())
            {
                ProviderConfig p = cfg.provider("groq");
                if (p == null) { p = ProviderConfig.preset("groq", "groq"); cfg.providers.add(p); }
                secrets.put("provider:" + p.id, single);
                applied++;
            }

            String tg = Json.str(o, "telegram_token", "");
            if (!tg.isBlank()) { secrets.put("telegram_token", tg); applied++; }

            String owner = Json.str(o, "telegram_owner_id", "");
            if (!owner.isBlank()) { try { cfg.telegramOwnerId = Long.parseLong(owner.trim()); } catch (NumberFormatException ignored) {} }

            return applied > 0 ? "قرأت " + applied + " إعدادًا من " + f.getFileName() + " (تيليجرام يستخدم المزود نفسه)" : "";
        }
        catch (Exception e)
        {
            Log.warn("تعذر قراءة abu-ai-studio.json (تأكد أن صيغة JSON صحيحة). لم يُطبَّق أي إعداد منه.");
            return "";
        }
    }

    private static String firstNonBlank(String a, String b, String c)
    {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return c == null ? "" : c.trim();
    }
}
