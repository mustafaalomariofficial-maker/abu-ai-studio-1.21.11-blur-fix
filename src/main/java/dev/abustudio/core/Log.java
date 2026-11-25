package dev.abustudio.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** All logging goes through the Redactor; also keeps a short in-memory ring for the in-game status screen. */
public final class Log
{
    private static final Logger L = LoggerFactory.getLogger("abu_studio");
    private static final Deque<String> ring = new ArrayDeque<>();
    public static volatile Consumer<String> sink;

    private Log() {}

    public static void info(String m) { String r = Redactor.redact(m); L.info(r); keep(r); }
    public static void warn(String m) { String r = Redactor.redact(m); L.warn(r); keep(r); }
    public static void error(String m) { String r = Redactor.redact(m); L.error(r); keep(r); }

    private static synchronized void keep(String r)
    {
        ring.addLast(r);
        while (ring.size() > 200) ring.removeFirst();
        Consumer<String> s = sink;
        if (s != null) { try { s.accept(r); } catch (Exception ignored) {} }
    }

    public static synchronized List<String> recent(int n)
    {
        List<String> all = new ArrayList<>(ring);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }
}
