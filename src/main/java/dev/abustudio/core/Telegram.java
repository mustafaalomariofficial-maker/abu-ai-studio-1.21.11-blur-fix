package dev.abustudio.core;

import com.google.gson.JsonObject;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Supplier;

/** Minimal Telegram Bot API client. The token is read from SecretStore on every call and never logged. */
public final class Telegram
{
    private final Supplier<StudioConfig> cfg;
    private final SecretStore secrets;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public Telegram(Supplier<StudioConfig> cfg, SecretStore secrets)
    {
        this.cfg = cfg;
        this.secrets = secrets;
    }

    public boolean hasToken() { return secrets.has("telegram_token"); }

    private String url(String method) throws Llm.LlmException
    {
        String t = secrets.get("telegram_token");
        if (t == null) throw new Llm.LlmException(Llm.Failure.AUTH, 0, "لم يُضف رمز بوت تيليجرام بعد");
        return cfg.get().telegramBaseUrl.replaceAll("/+$", "") + "/bot" + t + "/" + method;
    }

    private JsonObject finish(HttpResponse<String> r) throws Llm.LlmException
    {
        JsonObject o;
        try { o = Json.obj(r.body()); }
        catch (Exception e) { throw new Llm.LlmException(Llm.Failure.SERVER, r.statusCode(), "رد تيليجرام غير صالح"); }

        if (r.statusCode() == 429)
        {
            long ra = o.has("parameters") ? Json.num(o.getAsJsonObject("parameters"), "retry_after", 5) : 5;
            throw new Llm.LlmException(Llm.Failure.RATE_LIMIT, 429, "تيليجرام: انتظر " + ra + " ثانية", ra * 1000);
        }
        if (r.statusCode() == 401 || r.statusCode() == 403 || r.statusCode() == 404) throw new Llm.LlmException(Llm.Failure.AUTH, r.statusCode(), "رمز البوت مرفوض أو غير صحيح");
        if (r.statusCode() >= 500) throw new Llm.LlmException(Llm.Failure.SERVER, r.statusCode(), "خطأ خادم تيليجرام");
        if (!Json.bool(o, "ok", false)) throw new Llm.LlmException(Llm.Failure.BAD_REQUEST, r.statusCode(), Json.str(o, "description", "رفض تيليجرام الطلب"));
        return o;
    }

    private Llm.LlmException io(Exception e)
    {
        if (e instanceof HttpTimeoutException) return new Llm.LlmException(Llm.Failure.TIMEOUT, 0, "timeout");
        if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); return new Llm.LlmException(Llm.Failure.OTHER, 0, "interrupted"); }
        return new Llm.LlmException(Llm.Failure.NETWORK, 0, e.getClass().getSimpleName());
    }

    public JsonObject call(String method, JsonObject params, int timeoutSec) throws Llm.LlmException
    {
        try
        {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url(method))).timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.COMPACT.toJson(params), StandardCharsets.UTF_8)).build();
            return finish(client.send(rq, HttpResponse.BodyHandlers.ofString()));
        }
        catch (IOException | InterruptedException e) { throw io(e); }
    }

    public void sendText(long chatId, String text) throws Llm.LlmException
    {
        String t = text.length() > 4000 ? text.substring(0, 4000) + "…" : text;
        JsonObject p = new JsonObject();
        p.addProperty("chat_id", chatId);
        p.addProperty("text", Redactor.redact(t));
        call("sendMessage", p, 30);
    }

    /** kind: "photo" or "document". Streams the file (no full read into memory). */
    public void sendFile(String kind, long chatId, Path file, String caption) throws Llm.LlmException
    {
        String boundary = "----abu" + Long.toHexString(System.nanoTime());
        String field = kind.equals("photo") ? "photo" : "document";

        try
        {
            long size = Files.size(file);
            StringBuilder head = new StringBuilder();
            head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n").append(chatId).append("\r\n");

            if (caption != null && !caption.isBlank())
                head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n")
                    .append(Redactor.redact(caption.length() > 900 ? caption.substring(0, 900) : caption)).append("\r\n");

            head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(field).append("\"; filename=\"")
                .append(file.getFileName().toString().replaceAll("[\"\\r\\n]", "_")).append("\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            byte[] h = head.toString().getBytes(StandardCharsets.UTF_8);
            byte[] f = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

            HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofInputStream(() ->
            {
                try
                {
                    return new SequenceInputStream(new SequenceInputStream(new ByteArrayInputStream(h), Files.newInputStream(file)), new ByteArrayInputStream(f));
                }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });

            long timeout = Math.max(90, size / 8_000);       /* assume at least ~8 KB/s */
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url(kind.equals("photo") ? "sendPhoto" : "sendDocument")))
                .timeout(Duration.ofSeconds(Math.min(timeout, 1800)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build();
            finish(client.send(rq, HttpResponse.BodyHandlers.ofString()));
        }
        catch (UncheckedIOException e) { throw new Llm.LlmException(Llm.Failure.OTHER, 0, "تعذر قراءة الملف المراد إرساله"); }
        catch (IOException | InterruptedException e) { throw io(e); }
    }
}
