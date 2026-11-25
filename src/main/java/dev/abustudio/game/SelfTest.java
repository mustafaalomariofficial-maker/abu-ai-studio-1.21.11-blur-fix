package dev.abustudio.game;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.abustudio.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Developer self-test (java -Dabu.selftest=true on a dedicated server): a scripted "AI" (local mock HTTP provider)
 * drives the REAL tools against a REAL world. Prints SELFTEST lines. Not used in normal play.
 */
final class SelfTest
{
    private SelfTest() {}

    static String call(String name, JsonObject args)
    {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant"); m.add("content", JsonNull.INSTANCE);
        JsonArray tcs = new JsonArray();
        JsonObject tc = new JsonObject();
        tc.addProperty("id", "call_" + System.nanoTime()); tc.addProperty("type", "function");
        JsonObject fn = new JsonObject(); fn.addProperty("name", name); fn.addProperty("arguments", args.toString());
        tc.add("function", fn); tcs.add(tc); m.add("tool_calls", tcs);
        JsonObject ch = new JsonObject(); ch.add("message", m);
        JsonArray chs = new JsonArray(); chs.add(ch);
        JsonObject o = new JsonObject(); o.add("choices", chs);
        return o.toString();
    }

    static JsonObject o(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    static void run(Studio s, MinecraftServer server)
    {
        new Thread(() ->
        {
            int failures = 0;
            try
            {
                Thread.sleep(3000);
                ServerWorld w = server.getOverworld();
                int bx = 40, bz = 40;
                int by = w.getTopY(Heightmap.Type.WORLD_SURFACE, bx, bz) + 1;
                String film = "selftest_film";
                server.submit(() -> { w.setChunkForced(2, 2, true); w.setChunkForced(2, 3, true); }).get();
                Thread.sleep(1500);

                List<String> script = new ArrayList<>();
                script.add(call("plan_set", o("{\"steps\":[\"بناء منصة\",\"استدعاء شخصية\",\"إنشاء فيلم\"]}")));
                script.add(call("get_world_state", o("{}")));
                script.add(call("fill_blocks", o("{\"x1\":" + bx + ",\"y1\":" + by + ",\"z1\":" + bz + ",\"x2\":" + (bx + 6) + ",\"y2\":" + by + ",\"z2\":" + (bz + 6) + ",\"block\":\"minecraft:stone_bricks\"}")));
                script.add(call("get_block", o("{\"x\":" + (bx + 3) + ",\"y\":" + by + ",\"z\":" + (bz + 3) + "}")));
                script.add(call("npc_spawn", o("{\"name\":\"سعد\",\"x\":" + (bx + 1) + ",\"y\":" + (by + 1) + ",\"z\":" + (bz + 1) + "}")));
                script.add(call("npc_goto", o("{\"name\":\"سعد\",\"x\":" + (bx + 5) + ",\"y\":" + (by + 1) + ",\"z\":" + (bz + 5) + "}")));
                script.add(call("npc_place_block", o("{\"name\":\"سعد\",\"x\":" + (bx + 5) + ",\"y\":" + (by + 1) + ",\"z\":" + (bz + 6) + ",\"block\":\"minecraft:oak_planks\"}")));
                script.add(call("npc_say", o("{\"name\":\"سعد\",\"text\":\"مرحبًا يا أصدقائي، هذه بداية القصة!\"}")));
                script.add(call("bbs_form_template", o("{\"type\":\"model\"}")));
                script.add(call("bbs_clip_template", o("{\"type\":\"idle\"}")));
                script.add(call("bbs_clip_template", o("{\"type\":\"subtitle\"}")));
                script.add(call("bbs_film_create", o("{\"film\":\"" + film + "\",\"description\":\"فيلم اختبار ذاتي\"}")));
                script.add(call("bbs_film_add_replay", o("{\"film\":\"" + film + "\",\"label\":\"البطل\",\"form_json\":\"{\\\"type\\\":\\\"bbs:model\\\",\\\"model\\\":\\\"emoticons/steve\\\"}\",\"x\":" + bx + ",\"y\":" + (by + 1) + ",\"z\":" + bz + "}")));
                script.add(call("bbs_film_add_keyframes", o("{\"film\":\"" + film + "\",\"replay\":\"البطل\",\"channel\":\"x\",\"keys\":[{\"tick\":0,\"value\":" + bx + "},{\"tick\":60,\"value\":" + (bx + 5) + "}]}")));
                script.add(call("bbs_film_add_clip", o("{\"film\":\"" + film + "\",\"type\":\"idle\",\"tick\":0,\"duration\":80,\"title\":\"لقطة افتتاحية\"}")));
                script.add(call("bbs_film_add_subtitle", o("{\"film\":\"" + film + "\",\"text\":\"في يوم من الأيام...\",\"tick\":10,\"duration\":50}")));
                script.add(call("bbs_film_get", o("{\"film\":\"" + film + "\"}")));
                script.add(call("run_command", o("{\"command\":\"time set day\"}")));
                script.add(call("run_command", o("{\"command\":\"stop\"}")));
                script.add(call("export_project", o("{\"send\":false}")));
                script.add(call("take_screenshot", o("{}")));
                script.add(call("finish", o("{\"summary\":\"انتهى الاختبار الذاتي\"}")));

                AtomicInteger n = new AtomicInteger();
                HttpServer bad = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                bad.createContext("/", ex -> { byte[] b = "{\"error\":{\"message\":\"quota exceeded\",\"code\":\"insufficient_quota\"}}".getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(429, b.length); ex.getResponseBody().write(b); ex.close(); });
                bad.start();
                HttpServer good = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                good.createContext("/", ex ->
                {
                    ex.getRequestBody().readAllBytes();
                    int i = n.getAndIncrement();
                    byte[] b = script.get(Math.min(i, script.size() - 1)).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
                });
                good.start();

                s.cfg.providers.clear();
                ProviderConfig a = ProviderConfig.preset("openai", "bad"); a.name = "مزود معطل"; a.baseUrl = "http://127.0.0.1:" + bad.getAddress().getPort(); a.models.add("m1"); a.priority = 1; a.local = true;
                ProviderConfig b = ProviderConfig.preset("openai", "good"); b.name = "مزود سليم"; b.baseUrl = "http://127.0.0.1:" + good.getAddress().getPort(); b.models.add("m2"); b.priority = 2; b.local = true;
                s.cfg.providers.add(a); s.cfg.providers.add(b);
                s.secrets.put("provider:bad", "REDACTED_TEST_CREDENTIAL");
                s.secrets.put("provider:good", "REDACTED_TEST_CREDENTIAL");
                s.cfg.telegramEnabled = false;

                List<String> notes = new ArrayList<>();
                s.listeners.add(notes::add);
                try
                {
                    var base = mchorse.bbs_mod.BBSMod.getForms().create(mchorse.bbs_mod.resources.Link.bbs("model"));
                    System.out.println("SELFTEST form-template keys: " + mchorse.bbs_mod.forms.FormUtils.toData(base).keys());
                    var clip = mchorse.bbs_mod.BBSMod.getFactoryCameraClips().create(mchorse.bbs_mod.resources.Link.bbs("subtitle"));
                    System.out.println("SELFTEST subtitle keys: " + clip.toData().asMap().keys());
                }
                catch (Throwable t) { System.out.println("SELFTEST template diag failed " + t); }
                System.out.println("SELFTEST start (story → agent)");
                s.agent.submit("ابنِ منصة صغيرة واستدعِ شخصية اسمها سعد وأنشئ فيلمًا قصيرًا");
                boolean finished = s.agent.join(180_000);
                RunState st = s.agent.current();
                System.out.println("SELFTEST agent status=" + st.status + " steps=" + st.step + " finished=" + finished);

                Map<String, String> results = new LinkedHashMap<>();
                Map<String, String> names = new HashMap<>();
                for (Llm.Msg m : st.messages)
                {
                    if (m.role.equals("assistant") && m.toolCalls != null) for (Llm.ToolCall tc : m.toolCalls) names.put(tc.id, tc.name + " " + tc.args);
                    if (m.role.equals("tool"))
                    {
                        String line = names.get(m.toolCallId) + "\n      → " + (m.content.length() > 420 ? m.content.substring(0, 420) + "…" : m.content);
                        System.out.println("SELFTEST tool: " + line);
                        results.put(m.toolCallId, m.content);
                    }
                }

                for (String nline : notes) if (nline.contains("تعطل")) System.out.println("SELFTEST notification: " + nline.replace("\n", " | "));

                BlockPos chk = new BlockPos(bx + 3, by, bz + 3);
                boolean built = w.getBlockState(chk).getBlock() == net.minecraft.block.Blocks.STONE_BRICKS;
                var npc = s.npcs.find(server, "سعد");
                boolean npcThere = npc != null && npc.getBlockX() >= bx + 4;
                boolean planks = w.getBlockState(new BlockPos(bx + 5, by + 1, bz + 6)).getBlock() == net.minecraft.block.Blocks.OAK_PLANKS;
                boolean filmSaved = mchorse.bbs_mod.BBSMod.getFilms().exists(film);
                System.out.println("SELFTEST check platform_built=" + built + " npc_exists=" + (npc != null) + " npc_at=" + (npc == null ? "-" : npc.getBlockX() + "," + npc.getBlockY() + "," + npc.getBlockZ()) + " npc_reached=" + npcThere + " npc_placed_block=" + planks + " film_saved=" + filmSaved);
                boolean blockedStop = results.values().stream().anyMatch(r -> r.contains("محظور"));
                System.out.println("SELFTEST check dangerous_command_blocked=" + blockedStop + " failover_notified=" + notes.stream().anyMatch(x -> x.contains("مزود سليم")));
                System.out.println("SELFTEST check screenshot_honest_on_server=" + results.values().stream().anyMatch(r -> r.contains("لا يمكن التقاط صورة")));
                bad.stop(0); good.stop(0);
                System.out.println("SELFTEST RESULT " + (st.status.equals("DONE") && built && npcThere && planks && filmSaved && blockedStop ? "PASS" : "FAIL"));
            }
            catch (Throwable t) { System.out.println("SELFTEST EXCEPTION " + t); t.printStackTrace(System.out); }
            finally { server.stop(false); }
        }, "abu-selftest").start();
    }
}
