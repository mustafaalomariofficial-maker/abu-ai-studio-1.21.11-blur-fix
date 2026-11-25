package dev.abustudio.core;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.BooleanSupplier;

/** Cheap connectivity check (TCP connect), cached, so the agent can pause safely instead of burning retries. */
public final class NetMonitor
{
    /** Replaceable in tests. */
    public static volatile BooleanSupplier probe = NetMonitor::realProbe;

    private static volatile long checkedAt;
    private static volatile boolean last = true;
    private static volatile boolean changedNotified = true;

    private NetMonitor() {}

    private static boolean realProbe()
    {
        String[][] targets = {{"1.1.1.1", "443"}, {"8.8.8.8", "53"}, {"9.9.9.9", "443"}};

        for (String[] t : targets)
        {
            try (Socket s = new Socket())
            {
                s.connect(new InetSocketAddress(t[0], Integer.parseInt(t[1])), 3000);
                return true;
            }
            catch (Exception ignored) {}
        }

        return false;
    }

    public static synchronized void reset() { checkedAt = 0; }

    public static boolean online() { return online(false); }

    public static synchronized boolean online(boolean force)
    {
        long now = System.currentTimeMillis();

        if (force || now - checkedAt > 4000)
        {
            last = probe.getAsBoolean();
            checkedAt = now;
        }

        return last;
    }

    /** Blocks until online, stop requested or timeout. @return true if online. */
    public static boolean awaitOnline(Control c, long maxMs) throws InterruptedException
    {
        long end = System.currentTimeMillis() + maxMs;
        long wait = 2000;

        while (System.currentTimeMillis() < end)
        {
            if (c != null && c.isStopped()) return false;
            if (online(true)) return true;
            Thread.sleep(wait);
            wait = Math.min(wait * 2, 15000);
        }

        return online(true);
    }
}
