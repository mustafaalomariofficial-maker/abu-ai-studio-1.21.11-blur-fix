package dev.abustudio.game;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/** Small A* over block positions an NPC can stand on: walk, step up 1 block, drop up to 3. */
final class Pathfinder
{
    private Pathfinder() {}

    private static boolean passable(ServerWorld w, BlockPos p)
    {
        BlockState s = w.getBlockState(p);
        return s.getCollisionShape(w, p).isEmpty() && s.getFluidState().isEmpty();
    }

    private static boolean standable(ServerWorld w, BlockPos p)
    {
        return passable(w, p) && passable(w, p.up()) && !passable(w, p.down()) && w.getFluidState(p.down()).isEmpty();
    }

    static List<BlockPos> find(ServerWorld w, BlockPos start, BlockPos goal, int maxNodes)
    {
        /* snap start/goal to a standable position vertically */
        BlockPos s = snap(w, start), g = snap(w, goal);
        if (s == null || g == null) return null;

        record N(BlockPos p, double f) {}
        PriorityQueue<N> open = new PriorityQueue<>(Comparator.comparingDouble(N::f));
        Map<BlockPos, BlockPos> from = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        open.add(new N(s, 0));
        cost.put(s, 0.0);
        int visited = 0;

        while (!open.isEmpty() && visited++ < maxNodes)
        {
            BlockPos cur = open.poll().p;

            if (cur.equals(g) || (Math.abs(cur.getX() - g.getX()) + Math.abs(cur.getZ() - g.getZ()) == 0 && Math.abs(cur.getY() - g.getY()) <= 1))
            {
                LinkedList<BlockPos> path = new LinkedList<>();
                for (BlockPos p = cur; p != null; p = from.get(p)) path.addFirst(p);
                if (!path.isEmpty()) path.removeFirst();
                return new ArrayList<>(path);
            }

            for (Direction d : Direction.Type.HORIZONTAL)
            {
                BlockPos n = cur.offset(d);
                BlockPos target = null;

                if (standable(w, n)) target = n;
                else if (passable(w, cur.up(2)) && standable(w, n.up())) target = n.up();      /* step up one block */
                else
                {
                    for (int drop = 1; drop <= 3; drop++)
                    {
                        BlockPos dn = n.down(drop);
                        if (!passable(w, n.down(drop - 1))) break;
                        if (standable(w, dn)) { target = dn; break; }
                    }
                }

                if (target == null) continue;
                double c = cost.get(cur) + 1 + Math.abs(target.getY() - cur.getY()) * 0.5;

                if (c < cost.getOrDefault(target, Double.MAX_VALUE))
                {
                    cost.put(target, c);
                    from.put(target, cur);
                    double h = Math.abs(target.getX() - g.getX()) + Math.abs(target.getZ() - g.getZ()) + Math.abs(target.getY() - g.getY());
                    open.add(new N(target, c + h));
                }
            }
        }

        return null;
    }

    private static BlockPos snap(ServerWorld w, BlockPos p)
    {
        for (int dy = 0; dy >= -4; dy--) if (standable(w, p.up(dy))) return p.up(dy);
        for (int dy = 1; dy <= 3; dy++) if (standable(w, p.up(dy))) return p.up(dy);
        return null;
    }
}
