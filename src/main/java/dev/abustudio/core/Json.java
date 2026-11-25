package dev.abustudio.core;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Small JSON helpers + atomic file writes (write temp, then rename) so a crash never leaves a half file. */
public final class Json
{
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    public static JsonObject obj(String s)
    {
        JsonElement e = JsonParser.parseString(s);
        return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    public static String str(JsonObject o, String k, String def)
    {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    public static int num(JsonObject o, String k, int def)
    {
        try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? (int) Math.round(o.get(k).getAsDouble()) : def; }
        catch (Exception e) { return def; }
    }

    public static double dbl(JsonObject o, String k, double def)
    {
        try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def; }
        catch (Exception e) { return def; }
    }

    public static boolean bool(JsonObject o, String k, boolean def)
    {
        try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def; }
        catch (Exception e) { return def; }
    }

    public static void writeAtomic(Path file, String content) throws IOException
    {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    public static <T> T read(Path file, Class<T> type)
    {
        try
        {
            if (!Files.exists(file)) return null;
            return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
        }
        catch (Exception e)
        {
            Log.warn("تعذر قراءة الملف " + file.getFileName() + ": " + e.getClass().getSimpleName());
            return null;
        }
    }
}
