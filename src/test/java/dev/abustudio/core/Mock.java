package dev.abustudio.core;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny scriptable HTTP server used to stand in for providers and Telegram. */
final class Mock implements AutoCloseable
{
    record Reply(int status, String body) {}
    record Req(String method, String path, String auth, String googKey, String body) {}

    interface Script { Reply handle(Req r); }

    final HttpServer server;
    final List<Req> seen = new CopyOnWriteArrayList<>();
    volatile Script script = r -> new Reply(404, "{}");

    Mock() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex ->
        {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Req r = new Req(ex.getRequestMethod(), ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("x-goog-api-key"), body);
            seen.add(r);
            Reply rep = script.handle(r);
            byte[] b = rep.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(rep.status(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); }

    static String toolCall(String name, String argsJson)
    {
        JsonObject args = Json.obj("{}");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c" + System.nanoTime()
            + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":" + Json.COMPACT.toJson(argsJson) + "}}]}}]}";
    }

    static String text(String t) { return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + Json.COMPACT.toJson(t) + "}}]}"; }
}
