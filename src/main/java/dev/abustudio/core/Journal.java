package dev.abustudio.core;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Write-ahead log of tool executions. A START line is written (and flushed) BEFORE a tool runs, a DONE line AFTER.
 * After a crash / disconnect: DONE → reuse the result; START without DONE → the effect is uncertain and must be
 * verified before being repeated.
 */
public final class Journal
{
    public record Entry(String callId, String tool, JsonObject pre, JsonObject result) {}

    private final Path file;
    private final Map<String, Entry> entries = new HashMap<>();

    public Journal(Path file)
    {
        this.file = file;

        try
        {
            if (Files.exists(file))
            {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
                {
                    if (line.isBlank()) continue;
                    try
                    {
                        JsonObject o = Json.obj(line);
                        String id = Json.str(o, "callId", "");
                        Entry old = entries.get(id);
                        JsonObject pre = o.has("pre") && o.get("pre").isJsonObject() ? o.getAsJsonObject("pre") : old != null ? old.pre : null;
                        JsonObject res = o.has("result") && o.get("result").isJsonObject() ? o.getAsJsonObject("result") : null;
                        entries.put(id, new Entry(id, Json.str(o, "tool", ""), pre, res));
                    }
                    catch (Exception ignored) { /* a torn last line after a crash is expected */ }
                }
            }
        }
        catch (IOException e) { Log.warn("تعذر قراءة سجل الأفعال"); }
    }

    public synchronized Entry get(String callId) { return entries.get(callId); }

    public synchronized void start(String callId, String tool, JsonObject args, JsonObject pre)
    {
        JsonObject o = new JsonObject();
        o.addProperty("callId", callId); o.addProperty("tool", tool); o.addProperty("phase", "start");
        o.add("args", args);
        if (pre != null) o.add("pre", pre);
        append(o);
        entries.put(callId, new Entry(callId, tool, pre, null));
    }

    public synchronized void done(String callId, String tool, JsonObject result)
    {
        JsonObject o = new JsonObject();
        o.addProperty("callId", callId); o.addProperty("tool", tool); o.addProperty("phase", "done");
        o.add("result", result);
        append(o);
        Entry old = entries.get(callId);
        entries.put(callId, new Entry(callId, tool, old != null ? old.pre : null, result));
    }

    private void append(JsonObject o)
    {
        try
        {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.COMPACT.toJson(o) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        }
        catch (IOException e) { Log.warn("تعذر الكتابة في سجل الأفعال"); }
    }
}
