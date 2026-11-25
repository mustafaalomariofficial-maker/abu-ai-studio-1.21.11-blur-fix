package dev.abustudio.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Provider-neutral chat types. */
public final class Llm
{
    private Llm() {}

    public record ToolSpec(String name, String description, JsonObject parameters) {}

    public static final class ToolCall
    {
        public String id;
        public String name;
        public JsonObject args = new JsonObject();
        /** Gemini "thoughtSignature" must be echoed back to the same model on the next turn. */
        public String signature;

        public ToolCall() {}
        public ToolCall(String id, String name, JsonObject args) { this.id = id; this.name = name; this.args = args; }
    }

    public static final class Msg
    {
        public String role;           /* system | user | assistant | tool */
        public String content;
        public List<ToolCall> toolCalls;
        public String toolCallId;
        public String name;

        public Msg() {}
        public static Msg system(String t) { Msg m = new Msg(); m.role = "system"; m.content = t; return m; }
        public static Msg user(String t) { Msg m = new Msg(); m.role = "user"; m.content = t; return m; }
        public static Msg assistant(String t, List<ToolCall> calls)
        {
            Msg m = new Msg(); m.role = "assistant"; m.content = t;
            m.toolCalls = calls == null || calls.isEmpty() ? null : new ArrayList<>(calls);
            return m;
        }
        public static Msg tool(String id, String name, String result)
        {
            Msg m = new Msg(); m.role = "tool"; m.toolCallId = id; m.name = name; m.content = result; return m;
        }
    }

    public record Result(String text, List<ToolCall> toolCalls, long latencyMs, String raw) {}

    public enum Failure
    {
        NETWORK("انقطاع الاتصال بالإنترنت", true),
        TIMEOUT("انتهت مهلة الاستجابة", true),
        RATE_LIMIT("تجاوز حد الطلبات", false),
        QUOTA("نفدت الحصة أو الرصيد", false),
        AUTH("مشكلة في المصادقة (المفتاح غير صالح أو ناقص)", false),
        SERVER("خطأ من خادم المزود", true),
        MODEL_MISSING("الموديل غير موجود لدى المزود", false),
        BAD_REQUEST("طلب مرفوض من المزود", false),
        NO_TOOLS("الموديل لا يدعم استدعاء الأدوات", false),
        OTHER("خطأ غير معروف", false);

        public final String arabic;
        /** Worth retrying on the same provider shortly (transient). */
        public final boolean transientError;

        Failure(String arabic, boolean transientError) { this.arabic = arabic; this.transientError = transientError; }
    }

    public static class LlmException extends Exception
    {
        public final Failure kind;
        public final int status;
        public final long retryAfterMs;

        public LlmException(Failure kind, int status, String message, long retryAfterMs)
        {
            super(Redactor.redact(message));
            this.kind = kind;
            this.status = status;
            this.retryAfterMs = retryAfterMs;
        }

        public LlmException(Failure kind, int status, String message) { this(kind, status, message, 0); }
    }
}
