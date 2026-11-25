package dev.abustudio.core;

/**
 * Live state of the AI, shared by the in-game screen, the chat log and Telegram /status.
 * Written by the agent thread, read by the render thread: only volatile fields here.
 */
public final class Activity
{
    private static final int IDLE = 0, THINKING = 1, TOOL = 2, WAITING = 3, ERROR = 4;

    private static volatile int state = IDLE;
    private static volatile String detail = "";
    private static volatile String tool = "";
    private static volatile String model = "";
    private static volatile String lastError = "";
    private static volatile long since;

    private Activity() {}

    public static void thinking(String modelName)
    {
        state = THINKING; tool = ""; detail = ""; model = modelName == null ? "" : modelName; since = System.currentTimeMillis();
    }

    public static void tool(String name)
    {
        state = TOOL; tool = name == null ? "" : name; since = System.currentTimeMillis();
    }

    public static void answered(String modelName)
    {
        state = IDLE; tool = ""; detail = ""; lastError = "";
        if (modelName != null && !modelName.isBlank()) model = modelName;
        since = System.currentTimeMillis();
    }

    public static void waiting(String why) { state = WAITING; detail = why == null ? "" : why; since = System.currentTimeMillis(); }
    public static void error(String why) { state = ERROR; lastError = why == null ? "" : why; since = System.currentTimeMillis(); }
    public static void idle() { state = IDLE; tool = ""; since = System.currentTimeMillis(); }

    public static String model() { return model; }
    public static String lastError() { return lastError; }

    public static long elapsedMs() { return since <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - since); }

    /** One Arabic line for the screen and for /status. */
    public static String describe()
    {
        String secs = String.format(java.util.Locale.ROOT, "%.0f", elapsedMs() / 1000.0);

        return switch (state)
        {
            case THINKING -> "يفكر الآن" + (model.isBlank() ? "" : " عبر " + model) + " — " + secs + " ثانية";
            case TOOL -> "ينفّذ أداة " + (tool.isBlank() ? "غير معروفة" : tool) + " — " + secs + " ثانية";
            case WAITING -> "بانتظار المزود: " + (detail.isBlank() ? "لا يوجد مزود جاهز" : detail);
            case ERROR -> "خطأ: " + (lastError.isBlank() ? "غير معروف" : lastError);
            default -> model.isBlank() ? "خامل" : "خامل — آخر رد من " + model;
        };
    }
}
