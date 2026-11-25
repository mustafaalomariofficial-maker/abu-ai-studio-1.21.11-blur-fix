package dev.abustudio.game;

import dev.abustudio.core.ArabicText;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.data.DataToString;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Server-side NPC characters (BBS ActorEntity). The AI's helper "bot" is just one of them.
 * IMPORTANT: these are entities, NOT real player accounts: no profile, no entry in the player list, they cannot join
 * servers, own no real inventory/advancements, and cannot press buttons or open containers the way a player does —
 * they move along a path we compute, place/break blocks within reach and toggle doors/levers by block state.
 */
public final class Npcs
{
    public static final class Task
    {
        public volatile String state = "moving";   /* moving | arrived | failed */
        public volatile String message = "";
    }

    private static final class Mover
    {
        List<BlockPos> path;
        int index;
        Task task;
        Vec3d goal;
    }

    private final Map<String, UUID> byName = new HashMap<>();
    private final Map<UUID, Mover> movers = new HashMap<>();

    public ActorEntity find(MinecraftServer server, String name)
    {
        UUID id = byName.get(name);

        for (ServerWorld w : server.getWorlds())
        {
            if (id != null && w.getEntity(id) instanceof ActorEntity a && a.isAlive()) return a;
        }

        /* After a world reload the registry is empty: look by custom name. */
        for (ServerWorld w : server.getWorlds())
        {
            List<? extends ActorEntity> list = w.getEntitiesByType(BBSMod.ACTOR_ENTITY, e -> e.getCustomName() != null && e.getCustomName().getString().equals(name));
            if (!list.isEmpty()) { byName.put(name, list.get(0).getUuid()); return list.get(0); }
        }

        return null;
    }

    public List<String> names(MinecraftServer server)
    {
        List<String> out = new ArrayList<>();
        for (ServerWorld w : server.getWorlds())
            for (ActorEntity a : w.getEntitiesByType(BBSMod.ACTOR_ENTITY, e -> e.getCustomName() != null))
                out.add(a.getCustomName().getString() + " @ " + a.getBlockX() + "," + a.getBlockY() + "," + a.getBlockZ());
        return out;
    }

    public ActorEntity spawn(ServerWorld world, String name, Vec3d pos, float yaw, String formJson) throws Exception
    {
        ActorEntity old = find(world.getServer(), name);
        if (old != null) throw new IllegalStateException("الشخصية «" + name + "» موجودة بالفعل عند " + old.getBlockX() + "," + old.getBlockY() + "," + old.getBlockZ() + " (لن أنشئ نسخة ثانية)");

        if (!world.isChunkLoaded(BlockPos.ofFloored(pos))) throw new IllegalStateException("المنطقة غير محمّلة (لا لاعب قريب). اقترب بالاعب من الموقع أو اختر موقعًا قرب اللاعب.");
        ActorEntity e = new ActorEntity(BBSMod.ACTOR_ENTITY, world);
        e.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0);
        e.setCustomName(Text.literal(name));
        e.setCustomNameVisible(true);

        if (formJson != null && !formJson.isBlank())
        {
            e.setForm(BbsTools.makeForm(formJson));
        }

        if (!world.spawnEntity(e)) throw new IllegalStateException("رفضت اللعبة إنشاء الكيان");
        byName.put(name, e.getUuid());
        return e;
    }

    public boolean remove(MinecraftServer server, String name)
    {
        ActorEntity e = find(server, name);
        if (e == null) return false;
        e.discard();
        byName.remove(name);
        movers.remove(e.getUuid());
        return true;
    }

    public void say(MinecraftServer server, String name, String text)
    {
        for (String line : ArabicText.chatLines(text, 44))
            server.getPlayerManager().broadcast(Text.literal("<" + ArabicText.visual(name) + "> " + line), false);
    }

    public void lookAt(LivingEntity e, Vec3d t)
    {
        double dx = t.x - e.getX(), dz = t.z - e.getZ(), dy = t.y - e.getEyeY();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        e.setYaw(yaw); e.setBodyYaw(yaw); e.setHeadYaw(yaw); e.setPitch(pitch);
    }

    /* ------------------------------------------------------------ movement */

    public Task goTo(ActorEntity e, Vec3d target)
    {
        Task t = new Task();
        ServerWorld w = (ServerWorld) e.getEntityWorld();
        BlockPos start = e.getBlockPos();
        BlockPos goal = BlockPos.ofFloored(target);
        List<BlockPos> path = Pathfinder.find(w, start, goal, 4000);

        if (path == null)
        {
            t.state = "failed";
            t.message = "لم أجد مسارًا ممكنًا إلى " + goal.getX() + "," + goal.getY() + "," + goal.getZ() + " (عوائق أو بعيد جدًا أو يحتاج قفزًا أكثر من بلوك)";
            return t;
        }

        Mover m = new Mover();
        m.path = path; m.index = 0; m.task = t; m.goal = target;
        movers.put(e.getUuid(), m);
        return t;
    }

    public void tick(MinecraftServer server)
    {
        if (movers.isEmpty()) return;
        Iterator<Map.Entry<UUID, Mover>> it = movers.entrySet().iterator();

        while (it.hasNext())
        {
            var en = it.next();
            Mover m = en.getValue();
            Entity ent = null;
            for (ServerWorld w : server.getWorlds()) { ent = w.getEntity(en.getKey()); if (ent != null) break; }

            if (!(ent instanceof ActorEntity a) || !a.isAlive()) { m.task.state = "failed"; m.task.message = "اختفت الشخصية"; it.remove(); continue; }
            if (m.index >= m.path.size()) { m.task.state = "arrived"; m.task.message = "وصلت إلى " + a.getBlockX() + "," + a.getBlockY() + "," + a.getBlockZ(); it.remove(); continue; }

            BlockPos next = m.path.get(m.index);
            Vec3d to = new Vec3d(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
            Vec3d cur = a.getEntityPos();
            Vec3d d = to.subtract(cur);
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            double step = 0.22;

            if (horiz <= step && Math.abs(d.y) < 1.2)
            {
                a.setPosition(to.x, to.y, to.z);
                m.index++;
            }
            else
            {
                double k = Math.min(1.0, step / Math.max(horiz, 1e-6));
                double ny = d.y > 0 ? cur.y + Math.min(d.y, 0.42) : d.y < 0 ? cur.y + Math.max(d.y, -0.6) : cur.y;
                a.setPosition(cur.x + d.x * k, ny, cur.z + d.z * k);
                float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
                a.setYaw(yaw); a.setBodyYaw(yaw); a.setHeadYaw(yaw);
            }

            a.setVelocity(Vec3d.ZERO);
        }
    }

    /* ------------------------------------------------------------ interaction */

    public static final double REACH = 6.0;

    public static void swing(LivingEntity e) { e.swingHand(Hand.MAIN_HAND); }

    public void equip(ActorEntity e, ItemStack stack, EquipmentSlot slot) { e.equipStack(slot, stack); }
}
