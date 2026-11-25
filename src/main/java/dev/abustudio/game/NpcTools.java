package dev.abustudio.game;

import com.google.gson.*;
import dev.abustudio.core.*;
import mchorse.bbs_mod.entity.ActorEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/** NPC / helper-bot tools. name defaults to the bot's name. */
final class NpcTools
{
    private NpcTools() {}

    static String formFor(Studio s, JsonObject a)
    {
        String f = Json.str(a, "form_json", "");
        if (!f.isBlank()) return f;
        JsonObject o = new JsonObject();
        o.addProperty("type", "bbs:model");
        o.addProperty("model", "emoticons/steve");
        if (!s.cfg.botSkinLink.isBlank() && Json.bool(a, "use_bot_skin", false)) o.addProperty("texture", s.cfg.botSkinLink);
        return Json.COMPACT.toJson(o);
    }

    static ActorEntity need(Studio s, JsonObject a) throws Exception
    {
        String n = Json.str(a, "name", s.cfg.botName);
        ActorEntity e = s.npcs.find(s.server, n);
        if (e == null) throw new IllegalStateException("الشخصية «" + n + "» غير موجودة. أنشئها بـ npc_spawn أو bot_summon. الموجود: " + s.npcs.names(s.server));
        return e;
    }

    static void register(Studio s)
    {
        Tools t = s.tools;
        final String NAME = "\"name\":{\"type\":\"string\",\"description\":\"اسم الشخصية (الافتراضي: اسم البوت المساعد)\"}";

        t.add("bot_summon", "استدعِ الشخصية المساعدة (البوت) بجانب اللاعب أو عند إحداثيات. هي كيان NPC من BBS وليست حساب لاعب حقيقي.",
            "{\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}}}", true, (a, c) -> spawn(s, a, s.cfg.botName, true))
            .verify(null, (a, pre, c) -> GameTools.onServer(s, () -> s.npcs.find(s.server, s.cfg.botName) != null));

        t.add("npc_spawn", "أنشئ شخصية NPC مسماة (كيان BBS) بنموذج. form_json اختياري بصيغة BBS (استخدم bbs_form_template). لا يُنشئ نسخة ثانية بنفس الاسم.",
            "{\"properties\":{" + NAME + ",\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"yaw\":{\"type\":\"number\"},\"form_json\":{\"type\":\"string\"},\"use_bot_skin\":{\"type\":\"boolean\"}},\"required\":[\"name\"]}", true,
            (a, c) -> spawn(s, a, Json.str(a, "name", "NPC"), false))
            .verify(null, (a, pre, c) -> GameTools.onServer(s, () -> s.npcs.find(s.server, Json.str(a, "name", "NPC")) != null));

        t.add("npc_goto", "حرّك الشخصية إلى إحداثيات بحساب مسار حقيقي (مشي وصعود بلوك وهبوط ≤3). ينتظر الوصول (حتى 40 ثانية) ويعيد النتيجة.",
            "{\"properties\":{" + NAME + ",\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"z\"]}", true, (a, c) ->
            {
                Npcs.Task task = GameTools.onServer(s, () -> { try { return s.npcs.goTo(need(s, a), new Vec3d(Json.dbl(a, "x", 0), Json.dbl(a, "y", 0), Json.dbl(a, "z", 0))); } catch (Exception e) { Npcs.Task f = new Npcs.Task(); f.state = "failed"; f.message = e.getMessage(); return f; } });
                long end = System.currentTimeMillis() + 40_000;
                while (task.state.equals("moving") && System.currentTimeMillis() < end) c.control.sleep(250);
                JsonObject o = task.state.equals("arrived") ? Tools.ok() : Tools.err(task.state.equals("moving") ? "لم تصل بعد خلال 40 ثانية (ما زالت تمشي)" : task.message);
                o.addProperty("state", task.state);
                o.addProperty("message", task.message);
                return o;
            });

        t.add("npc_look_at", "اجعل الشخصية تنظر إلى نقطة.",
            "{\"properties\":{" + NAME + ",\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"z\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try { s.npcs.lookAt(need(s, a), new Vec3d(Json.dbl(a, "x", 0), Json.dbl(a, "y", 0), Json.dbl(a, "z", 0))); return Tools.ok(); } catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("npc_say", "اجعل الشخصية تتكلم في شات اللعبة (عربي مدعوم).",
            "{\"properties\":{" + NAME + ",\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                String n = Json.str(a, "name", s.cfg.botName);
                s.npcs.say(s.server, n, Json.str(a, "text", ""));
                return Tools.ok();
            }));

        t.add("npc_equip", "ألبس/أمسك عنصرًا للشخصية (mainhand, offhand, head, chest, legs, feet). العنصر مظهر فقط للـNPC وليس من جرد لاعب.",
            "{\"properties\":{" + NAME + ",\"item\":{\"type\":\"string\"},\"slot\":{\"type\":\"string\"}},\"required\":[\"item\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    ActorEntity e = need(s, a);
                    String item = Json.str(a, "item", "");
                    Item it = Registries.ITEM.get(Identifier.of(item.contains(":") ? item : "minecraft:" + item));
                    if (it == net.minecraft.item.Items.AIR && !item.endsWith("air")) return Tools.err("عنصر غير معروف: " + item);
                    EquipmentSlot slot = switch (Json.str(a, "slot", "mainhand")) { case "offhand" -> EquipmentSlot.OFFHAND; case "head" -> EquipmentSlot.HEAD; case "chest" -> EquipmentSlot.CHEST; case "legs" -> EquipmentSlot.LEGS; case "feet" -> EquipmentSlot.FEET; default -> EquipmentSlot.MAINHAND; };
                    s.npcs.equip(e, new ItemStack(it), slot);
                    JsonObject o = Tools.ok();
                    o.addProperty("equipped", Registries.ITEM.getId(it) + " في " + slot.asString());
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("npc_place_block", "اجعل الشخصية تضع بلوكًا (يجب أن تكون على بعد ≤6 بلوكات؛ وإلا استخدم npc_goto أولًا). آمن للتكرار.",
            "{\"properties\":{" + NAME + ",\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"block\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\",\"block\"]}", true, (a, c) ->
            {
                GameTools.backup(s, c);
                return GameTools.onServer(s, () ->
                {
                    try
                    {
                        ActorEntity e = need(s, a);
                        BlockPos p = GameTools.pos(a, "", null);
                        if (e.getEntityPos().distanceTo(Vec3d.ofCenter(p)) > Npcs.REACH) return Tools.err("الهدف بعيد (" + Math.round(e.getEntityPos().distanceTo(Vec3d.ofCenter(p))) + " بلوك). اقترب بـ npc_goto أولًا.");
                        ServerWorld w = (ServerWorld) e.getEntityWorld();
                        BlockState st = GameTools.parseState(Json.str(a, "block", ""));
                        BlockState old = w.getBlockState(p);
                        GameTools.pushUndo(w, Collections.singletonList(new Object[]{p.toImmutable(), old}));
                        s.npcs.lookAt(e, Vec3d.ofCenter(p));
                        Npcs.swing(e);
                        w.setBlockState(p, st, 3);
                        JsonObject o = Tools.ok();
                        o.addProperty("placed", GameTools.id(w.getBlockState(p)));
                        return o;
                    }
                    catch (Exception e) { return Tools.err(e.getMessage()); }
                });
            });

        t.add("npc_break_block", "اجعل الشخصية تكسر بلوكًا في مدى 6 بلوكات (مع إسقاط الغنائم).",
            "{\"properties\":{" + NAME + ",\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}", true, (a, c) ->
            {
                GameTools.backup(s, c);
                return GameTools.onServer(s, () ->
                {
                    try
                    {
                        ActorEntity e = need(s, a);
                        BlockPos p = GameTools.pos(a, "", null);
                        if (e.getEntityPos().distanceTo(Vec3d.ofCenter(p)) > Npcs.REACH) return Tools.err("الهدف بعيد. اقترب بـ npc_goto أولًا.");
                        ServerWorld w = (ServerWorld) e.getEntityWorld();
                        BlockState old = w.getBlockState(p);
                        if (old.isAir()) return Tools.ok();
                        if (old.getHardness(w, p) < 0) return Tools.err("بلوك لا يُكسر");
                        GameTools.pushUndo(w, Collections.singletonList(new Object[]{p.toImmutable(), old}));
                        s.npcs.lookAt(e, Vec3d.ofCenter(p));
                        Npcs.swing(e);
                        w.breakBlock(p, true, e);
                        JsonObject o = Tools.ok();
                        o.addProperty("broken", GameTools.id(old));
                        return o;
                    }
                    catch (Exception e) { return Tools.err(e.getMessage()); }
                });
            });

        t.add("npc_interact_block", "افتح/أغلق باب أو بوابة أو عتبة، أو بدّل ذراعًا، بتغيير حالة البلوك (الشخصية ليست لاعبًا فلا تفتح الصناديق).",
            "{\"properties\":{" + NAME + ",\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    ActorEntity e = need(s, a);
                    BlockPos p = GameTools.pos(a, "", null);
                    ServerWorld w = (ServerWorld) e.getEntityWorld();
                    BlockState st = w.getBlockState(p);
                    if (e.getEntityPos().distanceTo(Vec3d.ofCenter(p)) > Npcs.REACH) return Tools.err("الهدف بعيد. اقترب أولًا.");
                    Npcs.swing(e);
                    JsonObject o = Tools.ok();

                    if (st.getBlock() instanceof DoorBlock door && st.contains(Properties.OPEN))
                    {
                        boolean open = !st.get(Properties.OPEN);
                        door.setOpen(e, w, st, p, open);
                        o.addProperty("open", open);
                    }
                    else if (st.contains(Properties.OPEN)) { BlockState n = st.cycle(Properties.OPEN); w.setBlockState(p, n, 3); o.addProperty("open", n.get(Properties.OPEN)); }
                    else if (st.contains(Properties.POWERED)) { BlockState n = st.cycle(Properties.POWERED); w.setBlockState(p, n, 3); w.updateNeighbors(p, n.getBlock()); o.addProperty("powered", n.get(Properties.POWERED)); }
                    else return Tools.err("البلوك " + GameTools.id(st) + " لا يدعم التبديل (لا باب/بوابة/ذراع). الشخصيات لا تستطيع استخدام الصناديق أو الأفران.");
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("npc_attack", "اجعل الشخصية تضرب كيانًا (uuid من get_world_state) إن كان في مدى 4 بلوكات.",
            "{\"properties\":{" + NAME + ",\"target_uuid\":{\"type\":\"string\"},\"damage\":{\"type\":\"number\"}},\"required\":[\"target_uuid\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    ActorEntity e = need(s, a);
                    ServerWorld w = (ServerWorld) e.getEntityWorld();
                    Entity target = w.getEntity(UUID.fromString(Json.str(a, "target_uuid", "")));
                    if (target == null) return Tools.err("الكيان الهدف غير موجود");
                    if (target.distanceTo(e) > 4.5) return Tools.err("الهدف بعيد؛ اقترب أولًا");
                    if (target instanceof ServerPlayerEntity) return Tools.err("لا أضرب اللاعبين.");
                    s.npcs.lookAt(e, target.getEntityPos());
                    Npcs.swing(e);
                    boolean hit = target.damage(w, w.getDamageSources().mobAttack(e), (float) Math.min(20, Json.dbl(a, "damage", 4)));
                    JsonObject o = Tools.ok();
                    o.addProperty("hit", hit);
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("npc_remove", "احذف شخصية NPC.", "{\"properties\":{" + NAME + "}}", true, (a, c) -> GameTools.onServer(s, () ->
            s.npcs.remove(s.server, Json.str(a, "name", s.cfg.botName)) ? Tools.ok() : Tools.err("غير موجودة (ربما حُذفت بالفعل)")));

        t.add("npc_list", "اذكر الشخصيات الموجودة ومواقعها.", "{\"properties\":{}}", false, (a, c) -> GameTools.onServer(s, () ->
        {
            JsonObject o = Tools.ok();
            JsonArray arr = new JsonArray();
            for (String n : s.npcs.names(s.server)) arr.add(n);
            o.add("npcs", arr);
            return o;
        }));
    }

    static JsonObject spawn(Studio s, JsonObject a, String name, boolean bot) throws Exception
    {
        return GameTools.onServer(s, () ->
        {
            try
            {
                ServerPlayerEntity p = GameTools.player(s);
                ServerWorld w = GameTools.world(s, a);
                Vec3d pos;
                if (a.has("x") && a.has("z")) pos = new Vec3d(Json.dbl(a, "x", 0), Json.dbl(a, "y", p != null ? p.getY() : 64), Json.dbl(a, "z", 0));
                else if (p != null) pos = p.getEntityPos().add(p.getRotationVector().multiply(2, 0, 2));
                else return Tools.err("لا يوجد لاعب؛ مرّر x,y,z");
                JsonObject b = a.deepCopy();
                if (bot) b.addProperty("use_bot_skin", true);
                ActorEntity e = s.npcs.spawn(w, name, pos, (float) Json.dbl(a, "yaw", p != null ? p.getYaw() + 180 : 0), formFor(s, b));
                JsonObject o = Tools.ok();
                o.addProperty("name", name);
                o.addProperty("at", e.getBlockX() + "," + e.getBlockY() + "," + e.getBlockZ());
                o.addProperty("uuid", e.getUuid().toString());
                o.addProperty("note", "كيان NPC من BBS وليس حساب لاعب حقيقي.");
                return o;
            }
            catch (Exception e) { return Tools.err(e.getMessage()); }
        });
    }
}
