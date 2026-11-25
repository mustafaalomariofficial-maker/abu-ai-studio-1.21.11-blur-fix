package dev.abustudio.core;

/** Stop switch shared between the UI/Telegram and the running agent. */
public final class Control
{
    private volatile boolean stopped;

    public boolean isStopped() { return stopped; }
    public void stop() { stopped = true; }
    public void reset() { stopped = false; }

    /** Sleep that wakes up early when stop is requested. */
    public void sleep(long ms) throws StopException
    {
        long end = System.currentTimeMillis() + ms;

        while (System.currentTimeMillis() < end)
        {
            if (stopped) throw new StopException();
            try { Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis()))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new StopException(); }
        }

        if (stopped) throw new StopException();
    }

    public static class StopException extends Exception
    {
        public StopException() { super("stopped", null, false, false); }
    }
}
