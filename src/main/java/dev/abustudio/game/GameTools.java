package dev.abustudio.game;

import com.google.gson.*;
import dev.abustudio.core.*;
import mchorse.bbs_mod.entity.ActorEntity;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Real game-control tools. Each returns the real result (or the real error) to the model. */
public final class GameTools
{
    private GameTools() {}

    static final int MAX_BLOCKS = 40_000;
    static final Set<String> BLOCKED = Set.of("stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "whitelist", "save-off", "publish",
        "transfer", "setidletimeout", "debug", "perf", "kick", "reload", "banlist", "stopsound_all");
    private static final Deque<List<Object[]>> undo = new ArrayDeque<>();

    /* ------------------------------------------------------------ plumbing */

    public static <T> T onServer(Studio s, Supplier<T> job) throws Exception
    {
        MinecraftServer srv = s.server;
        if (srv == null) throw new IllegalStateException("لا يوجد عالم مفتوح الآن (افتح عالمًا أو شغّل الخادم أولًا)");
        CompletableFuture<T> f = new CompletableFuture<>();
        srv.execute(() -> { try { f.complete(job.get()); } catch (Throwable t) { f.completeExceptionally(t); } });

        try { return f.get(30, TimeUnit.SECONDS); }
        catch (TimeoutException e) { throw new IllegalStateException("انتهت مهلة انتظار خيط اللعبة (اللعبة متجمدة أو متوقفة مؤقتًا)"); }
        catch (ExecutionException e) { Throwable c = e.getCause(); throw c instanceof Exception ex ? ex : new RuntimeException(c); }
    }

    static ServerPlayerEntity player(Studio s)
    {
        MinecraftServer srv = s.server;
        if (srv == null) return null;
        List<ServerPlayerEntity> l = srv.getPlayerManager().getPlayerList();
        if (!s.cfg.playerName.isBlank()) for (ServerPlayerEntity p : l) if (p.getName().getString().equalsIgnoreCase(s.cfg.playerName)) return p;
        return l.isEmpty() ? null : l.get(0);
    }

    static ServerWorld world(Studio s, JsonObject a)
    {
        MinecraftServer srv = s.server;
        String dim = Json.str(a, "dimension", "");
        if (!dim.isBlank())
        {
            for (ServerWorld w : srv.getWorlds()) if (w.getRegistryKey().getValue().toString().equals(dim.contains(":") ? dim : "minecraft:" + dim)) return w;
            throw new IllegalArgumentException("بُعد غير معروف: " + dim);
        }
        ServerPlayerEntity p = player(s);
        return p != null ? (ServerWorld) p.getEntityWorld() : srv.getOverworld();
    }

    static BlockPos pos(JsonObject a, String prefix, ServerPlayerEntity fallback)
    {
        String kx = prefix + "x", ky = prefix + "y", kz = prefix + "z";
        if (a.has(kx) && a.has(ky) && a.has(kz)) return BlockPos.ofFloored(Json.dbl(a, kx, 0), Json.dbl(a, ky, 0), Json.dbl(a, kz, 0));
        if (fallback != null && prefix.isEmpty()) return fallback.getBlockPos();
        throw new IllegalArgumentException("الإحداثيات " + kx + "," + ky + "," + kz + " مطلوبة");
    }

    /** Numbered corner: x1,y1,z1 / x2,y2,z2 */
    static BlockPos posN(JsonObject a, String n)
    {
        if (a.has("x" + n) && a.has("y" + n) && a.has("z" + n)) return BlockPos.ofFloored(Json.dbl(a, "x" + n, 0), Json.dbl(a, "y" + n, 0), Json.dbl(a, "z" + n, 0));
        throw new IllegalArgumentException("الإحداثيات x" + n + ",y" + n + ",z" + n + " مطلوبة");
    }

    static BlockState parseState(String spec) throws Exception
    {
        String sp = spec.contains(":") ? spec : "minecraft:" + spec;
        return BlockArgumentParser.block(Registries.BLOCK, sp, false).blockState();
    }

    static String id(BlockState st) { return Registries.BLOCK.getId(st.getBlock()).toString() + (st.getEntries().isEmpty() ? "" : st.toString().substring(st.toString().indexOf('['))).replace("Block{", ""); }

    public static String worldSummary(Studio s)
    {
        try
        {
            ServerPlayerEntity p = player(s);
            if (s.server == null) return "لا يوجد عالم مفتوح.";
            if (p == null) return "عالم مفتوح بلا لاعب متصل.";
            return "اللاعب " + p.getName().getString() + " عند " + p.getBlockX() + "," + p.getBlockY() + "," + p.getBlockZ() + " في " + p.getEntityWorld().getRegistryKey().getValue();
        }
        catch (Throwable t) { return ""; }
    }

    public static void chatToPlayers(Studio s, String text)
    {
        MinecraftServer srv = s.server;
        if (srv == null) return;
        try
        {
            srv.execute(() ->
            {
                try { for (String line : ArabicText.chatLines(text, 46)) srv.getPlayerManager().broadcast(Text.literal(line), false); }
                catch (Throwable t) { Log.warn("تعذر عرض رسالة في الشات: " + t.getClass().getSimpleName()); }
            });
        }
        catch (Throwable ignored) {}
    }

    static void backup(Studio s, Tools.Ctx ctx) throws Exception
    {
        if (!s.cfg.backupBeforeWorldEdit || ctx.state.backedUp) return;
        String res = WorldBackup.create(s, "before-run-" + ctx.state.id);
        ctx.state.backedUp = true;
        s.agent.save(ctx.state);
        s.say("💾 نسخة احتياطية للعالم: " + res);
    }

    /* ------------------------------------------------------------ registration */

    public static void register(Studio s)
    {
        Tools t = s.tools;

        t.add("get_world_state", "حالة اللعبة الآن: اللاعب وموقعه وصحته وجرده المختصر، الوقت والطقس، ما حوله من كيانات، وهل الأوامر مسموحة، وقائمة الشخصيات.",
            "{\"properties\":{\"radius\":{\"type\":\"integer\",\"description\":\"نصف قطر البحث عن الكيانات (افتراضي 16)\"}}}", false, (a, c) -> onServer(s, () ->
            {
                JsonObject o = Tools.ok();
                MinecraftServer srv = s.server;
                ServerPlayerEntity p = player(s);
                o.addProperty("players_online", srv.getPlayerManager().getPlayerList().size());
                o.addProperty("dedicated_server", srv.isDedicated());
                o.addProperty("commands_allowed", commandsAllowed(s));

                if (p == null) { o.addProperty("note", "لا يوجد لاعب متصل؛ مرّر الإحداثيات صراحةً."); }
                else
                {
                    ServerWorld w = (ServerWorld) p.getEntityWorld();
                    JsonObject pl = new JsonObject();
                    pl.addProperty("name", p.getName().getString());
                    pl.addProperty("x", Math.round(p.getX() * 100) / 100.0); pl.addProperty("y", Math.round(p.getY() * 100) / 100.0); pl.addProperty("z", Math.round(p.getZ() * 100) / 100.0);
                    pl.addProperty("yaw", Math.round(p.getYaw())); pl.addProperty("pitch", Math.round(p.getPitch()));
                    pl.addProperty("health", p.getHealth()); pl.addProperty("food", p.getHungerManager().getFoodLevel());
                    pl.addProperty("gamemode", p.interactionManager.getGameMode().getId());
                    pl.addProperty("dimension", w.getRegistryKey().getValue().toString());
                    o.add("player", pl);
                    JsonArray hot = new JsonArray();
                    for (int i = 0; i < 9; i++) { ItemStack st = p.getInventory().getStack(i); if (!st.isEmpty()) hot.add(i + ": " + Registries.ITEM.getId(st.getItem()) + " x" + st.getCount()); }
                    o.add("hotbar", hot);
                    o.addProperty("time_of_day", w.getTimeOfDay() % 24000);
                    o.addProperty("raining", w.isRaining());
                    int r = Math.min(64, Json.num(a, "radius", 16));
                    JsonArray ents = new JsonArray();
                    for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(r)))
                    {
                        if (ents.size() >= 25) break;
                        ents.add(Registries.ENTITY_TYPE.getId(e.getType()) + (e.hasCustomName() ? " «" + e.getCustomName().getString() + "»" : "") + " @" + e.getBlockX() + "," + e.getBlockY() + "," + e.getBlockZ() + " uuid=" + e.getUuid());
                    }
                    o.add("nearby_entities", ents);
                }

                JsonArray npcs = new JsonArray();
                for (String n : s.npcs.names(srv)) npcs.add(n);
                o.add("npcs", npcs);
                o.addProperty("bot_name", s.cfg.botName);
                return o;
            }));

        t.add("get_block", "اقرأ البلوك عند إحداثيات (وبعض ما حوله اختياريًا).",
            "{\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"dimension\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\"]}", false, (a, c) -> onServer(s, () ->
            {
                ServerWorld w = world(s, a);
                BlockPos p = pos(a, "", null);
                BlockState st = w.getBlockState(p);
                JsonObject o = Tools.ok();
                o.addProperty("block", id(st));
                o.addProperty("air", st.isAir());
                o.addProperty("loaded", w.isChunkLoaded(p));
                return o;
            }));

        t.add("find_blocks", "ابحث عن بلوكات من نوع معين حول نقطة (افتراضيًا حول اللاعب) حتى نصف قطر 32، وتعيد حتى 30 موضعًا.",
            "{\"properties\":{\"block\":{\"type\":\"string\",\"description\":\"مثل minecraft:oak_log\"},\"radius\":{\"type\":\"integer\"},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"block\"]}", false, (a, c) -> onServer(s, () ->
            {
                ServerWorld w = world(s, a);
                BlockPos center = pos(a, "", player(s));
                String want = Json.str(a, "block", "");
                String full = want.contains(":") ? want : "minecraft:" + want;
                int r = Math.min(32, Json.num(a, "radius", 16));
                JsonArray found = new JsonArray();
                int total = 0;
                for (BlockPos p : BlockPos.iterate(center.add(-r, -r, -r), center.add(r, r, r)))
                {
                    if (!w.isChunkLoaded(p)) continue;
                    if (Registries.BLOCK.getId(w.getBlockState(p).getBlock()).toString().equals(full)) { total++; if (found.size() < 30) found.add(p.getX() + "," + p.getY() + "," + p.getZ()); }
                }
                JsonObject o = Tools.ok();
                o.addProperty("total", total);
                o.add("positions", found);
                return o;
            }));

        t.add("get_inventory", "جرد اللاعب كاملًا (اسم العنصر والعدد لكل خانة).",
            "{\"properties\":{}}", false, (a, c) -> onServer(s, () ->
            {
                ServerPlayerEntity p = player(s);
                if (p == null) return Tools.err("لا يوجد لاعب متصل");
                JsonArray arr = new JsonArray();
                for (int i = 0; i < p.getInventory().size(); i++)
                {
                    ItemStack st = p.getInventory().getStack(i);
                    if (!st.isEmpty()) arr.add(i + ": " + Registries.ITEM.getId(st.getItem()) + " x" + st.getCount());
                }
                JsonObject o = Tools.ok();
                o.add("slots", arr);
                return o;
            }));

        t.add("set_block", "ضع بلوكًا واحدًا (يقبل خصائص مثل minecraft:oak_door[facing=north,half=lower]). آمن للتكرار.",
            "{\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"block\":{\"type\":\"string\"},\"dimension\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\",\"block\"]}", true, (a, c) ->
            {
                backup(s, c);
                return onServer(s, () ->
                {
                    ServerWorld w = world(s, a);
                    BlockPos p = pos(a, "", null);
                    BlockState st;
                    try { st = parseState(Json.str(a, "block", "")); } catch (Exception e) { return Tools.err("بلوك غير صالح: " + e.getMessage()); }
                    BlockState old = w.getBlockState(p);
                    pushUndo(w, Collections.singletonList(new Object[]{p.toImmutable(), old}));
                    w.setBlockState(p, st, 3);
                    JsonObject o = Tools.ok();
                    o.addProperty("placed", id(w.getBlockState(p)));
                    o.addProperty("replaced", id(old));
                    return o;
                });
            });

        t.add("fill_blocks", "املأ منطقة مكعبة بين نقطتين ببلوك (حتى 40000 بلوك). mode: replace (افتراضي) | hollow (جدران فقط) | outline | keep (لا يستبدل غير الهواء). آمن للتكرار ويدعم التراجع.",
            "{\"properties\":{\"x1\":{\"type\":\"integer\"},\"y1\":{\"type\":\"integer\"},\"z1\":{\"type\":\"integer\"},\"x2\":{\"type\":\"integer\"},\"y2\":{\"type\":\"integer\"},\"z2\":{\"type\":\"integer\"},\"block\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\"},\"dimension\":{\"type\":\"string\"}},\"required\":[\"x1\",\"y1\",\"z1\",\"x2\",\"y2\",\"z2\",\"block\"]}", true, (a, c) ->
            {
                BlockPos p1 = posN(a, "1"), p2 = posN(a, "2");
                backup(s, c);
                return onServer(s, () ->
                {
                    ServerWorld w = world(s, a);
                    BlockState st;
                    try { st = parseState(Json.str(a, "block", "")); } catch (Exception e) { return Tools.err("بلوك غير صالح: " + e.getMessage()); }
                    int x0 = Math.min(p1.getX(), p2.getX()), x1 = Math.max(p1.getX(), p2.getX());
                    int y0 = Math.min(p1.getY(), p2.getY()), y1 = Math.max(p1.getY(), p2.getY());
                    int z0 = Math.min(p1.getZ(), p2.getZ()), z1 = Math.max(p1.getZ(), p2.getZ());
                    long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
                    if (vol > MAX_BLOCKS) return Tools.err("الحجم " + vol + " أكبر من الحد " + MAX_BLOCKS + ". قسّم العمل إلى مناطق أصغر.");
                    String mode = Json.str(a, "mode", "replace");
                    List<Object[]> changed = new ArrayList<>();
                    int placed = 0;

                    for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++)
                    {
                        BlockPos p = new BlockPos(x, y, z);
                        boolean edge = x == x0 || x == x1 || y == y0 || y == y1 || z == z0 || z == z1;
                        if (mode.equals("hollow") && !edge) { BlockState o = w.getBlockState(p); if (!o.isAir()) { changed.add(new Object[]{p, o}); w.setBlockState(p, net.minecraft.block.Blocks.AIR.getDefaultState(), 2); } continue; }
                        if (mode.equals("outline") && !edge) continue;
                        BlockState old = w.getBlockState(p);
                        if (mode.equals("keep") && !old.isAir()) continue;
                        if (old == st) continue;
                        changed.add(new Object[]{p, old});
                        w.setBlockState(p, st, 2);
                        placed++;
                    }

                    pushUndo(w, changed);
                    JsonObject o = Tools.ok();
                    o.addProperty("changed", placed);
                    o.addProperty("volume", vol);
                    o.addProperty("sample_check", id(w.getBlockState(new BlockPos(x0, y0, z0))));
                    return o;
                });
            });

        t.add("undo_last_build", "تراجع عن آخر عملية بناء (set_block/fill_blocks) في الذاكرة الحالية.",
            "{\"properties\":{}}", true, (a, c) -> onServer(s, () ->
            {
                List<Object[]> last = undo.pollFirst();
                if (last == null) return Tools.err("لا توجد عملية للتراجع عنها");
                ServerWorld w = s.server.getOverworld();
                String dim = (String) lastUndoDim;
                for (ServerWorld x : s.server.getWorlds()) if (x.getRegistryKey().getValue().toString().equals(dim)) w = x;
                for (int i = last.size() - 1; i >= 0; i--) w.setBlockState((BlockPos) last.get(i)[0], (BlockState) last.get(i)[1], 2);
                JsonObject o = Tools.ok();
                o.addProperty("restored", last.size());
                return o;
            }));

        t.add("break_block", "اكسر بلوكًا (مع إسقاط الغنائم اختياريًا).",
            "{\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"drop\":{\"type\":\"boolean\"}},\"required\":[\"x\",\"y\",\"z\"]}", true, (a, c) ->
            {
                backup(s, c);
                return onServer(s, () ->
                {
                    ServerWorld w = world(s, a);
                    BlockPos p = pos(a, "", null);
                    BlockState old = w.getBlockState(p);
                    if (old.isAir()) return Tools.ok();
                    if (old.getHardness(w, p) < 0) return Tools.err("بلوك لا يُكسر (" + id(old) + ")");
                    pushUndo(w, Collections.singletonList(new Object[]{p.toImmutable(), old}));
                    w.breakBlock(p, Json.bool(a, "drop", false));
                    JsonObject o = Tools.ok();
                    o.addProperty("broken", id(old));
                    return o;
                });
            });

        t.add("run_command", "نفّذ أمر ماينكرافت (بدون /) وأعد ناتجه الحقيقي. بعض الأوامر الخطرة محظورة (stop, op, ban…). قد تكون معطلة إن لم يسمح العالم بالأوامر.",
            "{\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}", true, (a, c) ->
            {
                String cmd = Json.str(a, "command", "").trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                final String command = cmd;
                String first = command.split("\\s+")[0].toLowerCase();
                if (!s.cfg.allowCommandTool) return Tools.err("أداة الأوامر معطلة من إعدادات المود.");
                if (BLOCKED.contains(first)) return Tools.err("الأمر «" + first + "» محظور لأمان العالم والخادم.");
                backup(s, c);
                return onServer(s, () ->
                {
                    if (!commandsAllowed(s)) return Tools.err("هذا العالم لا يسمح بالأوامر (الغش معطل). فعّل الغش/الأوامر أو أعطِ اللاعب صلاحية OP ثم أعد المحاولة.");
                    StringBuilder out = new StringBuilder();
                    net.minecraft.server.command.ServerCommandSource src = s.server.getCommandSource().withOutput(new net.minecraft.server.command.CommandOutput()
                    {
                        public void sendMessage(Text m) { out.append(m.getString()).append("\n"); }
                        public boolean shouldReceiveFeedback() { return true; }
                        public boolean shouldTrackOutput() { return true; }
                        public boolean shouldBroadcastConsoleToOps() { return false; }
                    });
                    ServerPlayerEntity p = player(s);
                    if (p != null) src = src.withPosition(p.getEntityPos()).withWorld((ServerWorld) p.getEntityWorld());
                    int[] result = {0};
                    src = src.withReturnValueConsumer((ok, v) -> result[0] = v);
                    s.server.getCommandManager().parseAndExecute(src, command);
                    JsonObject o = Tools.ok();
                    o.addProperty("output", out.length() == 0 ? "(لا ناتج نصي)" : out.toString().trim());
                    o.addProperty("result", result[0]);
                    return o;
                });
            });

        t.add("give_item", "أعطِ اللاعب عناصر (يتطلب أن تسمح صلاحيات العالم). يتحقق من الجرد ولا يكرر الإعطاء بعد انقطاع.",
            "{\"properties\":{\"item\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\"}},\"required\":[\"item\"]}", true, (a, c) ->
            {
                return onServer(s, () ->
                {
                    ServerPlayerEntity p = player(s);
                    if (p == null) return Tools.err("لا يوجد لاعب متصل");
                    if (!commandsAllowed(s)) return Tools.err("الغش معطل في هذا العالم؛ لا يمكنني استدعاء عناصر. يمكنني فقط نقل/استخدام ما يملكه اللاعب.");
                    String item = Json.str(a, "item", "");
                    Item it = Registries.ITEM.get(Identifier.of(item.contains(":") ? item : "minecraft:" + item));
                    if (it == net.minecraft.item.Items.AIR) return Tools.err("عنصر غير معروف: " + item);
                    int n = Math.max(1, Math.min(2304, Json.num(a, "count", 1)));
                    p.getInventory().offerOrDrop(new ItemStack(it, n));
                    JsonObject o = Tools.ok();
                    o.addProperty("gave", Registries.ITEM.getId(it) + " x" + n);
                    return o;
                });
            }).verify((a, c) -> countItem(s, Json.str(a, "item", "")), (a, pre, c) ->
            {
                if (pre == null) return null;
                JsonObject now = countItem(s, Json.str(a, "item", ""));
                int want = Math.max(1, Json.num(a, "count", 1));
                return Json.num(now, "n", 0) - Json.num(pre, "n", 0) >= want;
            });

        t.add("teleport_player", "انقل اللاعب إلى إحداثيات.",
            "{\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"yaw\":{\"type\":\"number\"},\"pitch\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"z\"]}", true, (a, c) -> onServer(s, () ->
            {
                ServerPlayerEntity p = player(s);
                if (p == null) return Tools.err("لا يوجد لاعب متصل");
                p.teleport((ServerWorld) p.getEntityWorld(), Json.dbl(a, "x", 0), Json.dbl(a, "y", 0), Json.dbl(a, "z", 0), java.util.Set.of(), (float) Json.dbl(a, "yaw", p.getYaw()), (float) Json.dbl(a, "pitch", p.getPitch()), false);
                JsonObject o = Tools.ok();
                o.addProperty("now", p.getBlockX() + "," + p.getBlockY() + "," + p.getBlockZ());
                return o;
            }));

        NpcTools.register(s);
    }

    /* ------------------------------------------------------------ helpers */

    static String lastUndoDim = "minecraft:overworld";

    static void pushUndo(ServerWorld w, List<Object[]> changes)
    {
        if (changes.isEmpty()) return;
        lastUndoDim = w.getRegistryKey().getValue().toString();
        undo.addFirst(changes);
        while (undo.size() > 8) undo.removeLast();
    }

    static JsonObject countItem(Studio s, String item) throws Exception
    {
        return onServer(s, () ->
        {
            JsonObject o = new JsonObject();
            ServerPlayerEntity p = player(s);
            int n = 0;
            if (p != null)
            {
                Identifier idv = Identifier.of(item.contains(":") ? item : "minecraft:" + item);
                for (int i = 0; i < p.getInventory().size(); i++) { ItemStack st = p.getInventory().getStack(i); if (Registries.ITEM.getId(st.getItem()).equals(idv)) n += st.getCount(); }
            }
            o.addProperty("n", n);
            return o;
        });
    }

    /** Dedicated server: the player must be an operator. Integrated server: the world must allow commands (cheats). */
    static boolean commandsAllowed(Studio s)
    {
        MinecraftServer srv = s.server;
        ServerPlayerEntity p = player(s);
        if (!srv.isDedicated()) return srv.getSaveProperties().areCommandsAllowed() || (p != null && srv.getPlayerManager().isOperator(p.getPlayerConfigEntry()));
        return p == null || srv.getPlayerManager().isOperator(p.getPlayerConfigEntry());
    }
}
