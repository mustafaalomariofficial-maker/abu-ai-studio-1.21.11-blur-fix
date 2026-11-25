package dev.abustudio.core;

import com.google.gson.JsonObject;

import java.util.*;

/** Registry of real, result-returning tools the model may call. */
public final class Tools
{
    @FunctionalInterface
    public interface Handler { JsonObject run(JsonObject args, Ctx ctx) throws Exception; }

    @FunctionalInterface
    public interface Verifier { Boolean check(JsonObject args, JsonObject pre, Ctx ctx) throws Exception; }

    @FunctionalInterface
    public interface PreState { JsonObject capture(JsonObject args, Ctx ctx) throws Exception; }

    public static final class Ctx
    {
        public final Agent agent;
        public final RunState state;
        public final Control control;

        public Ctx(Agent agent, RunState state, Control control) { this.agent = agent; this.state = state; this.control = control; }
    }

    public static final class Tool
    {
        public final String name, description;
        public final JsonObject schema;
        /** Mutating tools are never blindly re-executed after an interruption. */
        public final boolean mutating;
        public final Handler handler;
        public Verifier verifier;
        public PreState preState;

        Tool(String name, String description, JsonObject schema, boolean mutating, Handler handler)
        {
            this.name = name; this.description = description; this.schema = schema; this.mutating = mutating; this.handler = handler;
        }

        public Tool verify(PreState pre, Verifier v) { this.preState = pre; this.verifier = v; return this; }
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public Tool add(String name, String description, String paramsJson, boolean mutating, Handler h)
    {
        JsonObject schema = Json.obj(paramsJson);
        if (!schema.has("type")) schema.addProperty("type", "object");
        Tool t = new Tool(name, description, schema, mutating, h);
        tools.put(name, t);
        return t;
    }

    public Tool get(String name) { return tools.get(name); }
    public Collection<Tool> all() { return tools.values(); }

    public List<Llm.ToolSpec> specs()
    {
        List<Llm.ToolSpec> l = new ArrayList<>();
        for (Tool t : tools.values()) l.add(new Llm.ToolSpec(t.name, t.description, t.schema));
        return l;
    }

    public static JsonObject ok() { JsonObject o = new JsonObject(); o.addProperty("ok", true); return o; }

    public static JsonObject err(String msg) { JsonObject o = new JsonObject(); o.addProperty("ok", false); o.addProperty("error", msg); return o; }
}
