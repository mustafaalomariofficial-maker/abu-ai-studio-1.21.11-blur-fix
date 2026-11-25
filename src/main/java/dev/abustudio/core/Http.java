package dev.abustudio.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;

/** Single place for HTTP + mapping every failure to a Llm.Failure, with secrets scrubbed. */
public final class Http
{
    public static volatile int connectSeconds = 15;
    public static volatile int readSeconds = 120;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private Http() {}

    public record Response(int status, String body, HttpHeaders headers) {}

    public static Response send(String method, String url, Map<String, String> headers, String body) throws Llm.LlmException
    {
        try
        {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(readSeconds));
            headers.forEach(b::header);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body(), r.headers());
        }
        catch (HttpTimeoutException e)
        {
            throw new Llm.LlmException(Llm.Failure.TIMEOUT, 0, "timeout");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new Llm.LlmException(Llm.Failure.OTHER, 0, "interrupted");
        }
        catch (IOException e)
        {
            /* connection refused / DNS failure / reset: the network (or local server) is unreachable */
            throw new Llm.LlmException(Llm.Failure.NETWORK, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        catch (IllegalArgumentException e)
        {
            throw new Llm.LlmException(Llm.Failure.BAD_REQUEST, 0, "عنوان غير صالح");
        }
    }

    public static Llm.LlmException classify(Response r)
    {
        String b = r.body() == null ? "" : r.body();
        String low = b.toLowerCase();
        long retry = 0;
        OptionalLong ra = r.headers().firstValueAsLong("retry-after");
        if (ra.isPresent()) retry = ra.getAsLong() * 1000;
        String snippet = Redactor.redact(b.length() > 300 ? b.substring(0, 300) : b);
        int s = r.status();

        Llm.Failure k;
        if (s == 401 || s == 403) k = Llm.Failure.AUTH;
        else if (s == 402 || low.contains("insufficient_quota") || low.contains("quota") && s != 404 && !low.contains("per minute")
            || low.contains("resource_exhausted") && low.contains("quota") || low.contains("billing") || low.contains("credit")) k = Llm.Failure.QUOTA;
        else if (s == 429) k = Llm.Failure.RATE_LIMIT;
        else if (s == 404 || low.contains("model_not_found") || low.contains("does not exist") || low.contains("not found")) k = Llm.Failure.MODEL_MISSING;
        else if (s >= 500) k = Llm.Failure.SERVER;
        else if (low.contains("tool") && (low.contains("not support") || low.contains("unsupported") || low.contains("does not support"))) k = Llm.Failure.NO_TOOLS;
        else if (s == 408) k = Llm.Failure.TIMEOUT;
        else if (s >= 400) k = Llm.Failure.BAD_REQUEST;
        else k = Llm.Failure.OTHER;

        return new Llm.LlmException(k, s, "HTTP " + s + ": " + snippet, retry);
    }
}
