package dev.abustudio.core;

import com.google.gson.JsonObject;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keys and tokens live ONLY here: config/abu_studio/secrets.enc (AES-256-GCM), with the random key in a separate
 * file (.keyfile, owner-only permissions where the OS supports it). This protects against casual exposure
 * (screenshots of folders, sharing the config folder, logs); it does NOT protect against someone who has both files
 * on your device. Environment variables (ABU_SECRET_<NAME>) take priority and are never written to disk.
 * Names: "telegram_token" and "provider:<id>".
 */
public final class SecretStore
{
    private final Path dir;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    public SecretStore(Path dir)
    {
        this.dir = dir;
        load();
    }

    public static String envName(String name)
    {
        return "ABU_SECRET_" + name.toUpperCase().replaceAll("[^A-Z0-9]", "_");
    }

    public String get(String name)
    {
        String env = System.getenv(envName(name));
        if (env == null && name.equals("telegram_token")) env = System.getenv("ABU_TELEGRAM_TOKEN");
        if (env != null && !env.isBlank()) { Redactor.register(env.trim()); return env.trim(); }
        return cache.get(name);
    }

    public boolean has(String name) { return get(name) != null; }

    public synchronized void put(String name, String value)
    {
        if (value == null || value.isBlank()) cache.remove(name);
        else { cache.put(name, value.trim()); Redactor.register(value.trim()); }
        save();
    }

    public synchronized void remove(String name) { cache.remove(name); save(); }

    /** Reads secrets.import.json once ({"telegram_token":"...","provider:groq":"..."}) then deletes it. */
    public synchronized int importFile()
    {
        Path f = dir.resolve("secrets.import.json");
        if (!Files.exists(f)) return 0;
        int n = 0;

        try
        {
            JsonObject o = Json.obj(Files.readString(f, StandardCharsets.UTF_8));
            for (String k : o.keySet())
            {
                String v = o.get(k).getAsString();
                if (!v.isBlank()) { cache.put(k, v.trim()); Redactor.register(v.trim()); n++; }
            }
            save();
            Files.delete(f);
            Log.info("تم استيراد " + n + " سرّ من ملف الاستيراد وحُذف الملف.");
        }
        catch (Exception e)
        {
            Log.warn("فشل استيراد ملف الأسرار: " + e.getClass().getSimpleName());
        }

        return n;
    }

    private byte[] key() throws Exception
    {
        Path kf = dir.resolve(".keyfile");

        if (!Files.exists(kf))
        {
            Files.createDirectories(dir);
            byte[] k = new byte[32];
            rnd.nextBytes(k);
            Files.writeString(kf, Base64.getEncoder().encodeToString(k));
            try { Files.setPosixFilePermissions(kf, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) {}
        }

        return Base64.getDecoder().decode(Files.readString(kf).trim());
    }

    private void load()
    {
        try
        {
            Path f = dir.resolve("secrets.enc");
            if (!Files.exists(f)) return;
            byte[] all = Base64.getDecoder().decode(Files.readString(f).trim());
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, all, 0, 12));
            String json = new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
            JsonObject o = Json.obj(json);

            for (String k : o.keySet())
            {
                cache.put(k, o.get(k).getAsString());
                Redactor.register(o.get(k).getAsString());
            }
        }
        catch (Exception e)
        {
            Log.warn("تعذر فك تشفير ملف الأسرار (هل حُذف ملف المفتاح؟). أعد إدخال المفاتيح.");
        }
    }

    private void save()
    {
        try
        {
            JsonObject o = new JsonObject();
            cache.forEach(o::addProperty);
            byte[] iv = new byte[12];
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(Json.COMPACT.toJson(o).getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            Json.writeAtomic(dir.resolve("secrets.enc"), Base64.getEncoder().encodeToString(out));
        }
        catch (Exception e)
        {
            Log.error("تعذر حفظ الأسرار: " + e.getClass().getSimpleName());
        }
    }
}
