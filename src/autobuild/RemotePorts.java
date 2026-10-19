package autobuild;

import arc.math.geom.*;
import arc.struct.*;
import autobuild.plan.*;
import autobuild.plan.GridRouter.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.storage.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Drill-field ports picked away from the region, turned into what {@link DrillLayoutPlanner}
 * understands, plus the trunk lines that join them:
 * <ul>
 * <li>an item output away from the region (a tile, or an existing core / container / belt / router)
 *     becomes edge outputs on the side of the region facing it - several for storage, one belt's
 *     worth each, as many as the building has free sides (at most 4); the planner spreads the field
 *     over them and lines without load are dropped;</li>
 * <li>storage right next to the region becomes one output per tile facing the region (several
 *     lines into it the same way);</li>
 * <li>a water input away from the region becomes an edge water port facing it;</li>
 * <li>after planning, {@link GridRouter} lays a belt (pipe) line from each loaded edge output to
 *     its target (from the water source to the water port) over free ground outside the region,
 *     with bridges; each line into a building reaches a different tile of it.</li>
 * </ul>
 * Main thread: {@link #expand}; planning thread: {@link #route}.
 */
public class RemotePorts{
    /** what the planner gets */
    public final Seq<Port> planOuts = new Seq<>(), planWaters = new Seq<>();
    /** per planner port: index of the player's port it stands for */
    public final IntSeq outOwner = new IntSeq(), waterOwner = new IntSeq();
    /** per planner port: its line must be routed to the player's port */
    public final BoolSeq outRemote = new BoolSeq(), waterRemote = new BoolSeq();
    /** per player port: lines it may take */
    public final IntSeq outLines = new IntSeq();
    /** anything to route */
    public boolean any;

    // ---- trunk grid (world box gx..gx+gw-1, gy..gy+gh-1) ----
    int gx, gy, gw, gh;
    boolean[][] free, nearItems, nearLiquids;
    /** per player port: target footprint {x, y, size} (world min corner), or for water: source starts {x, y, from} */
    final Seq<int[]> outTarget = new Seq<>();
    final Seq<int[][]> waterStart = new Seq<>();
    Block belt, bridge, conduit, liquidBridge;
    int bridgeRange, liquidBridgeRange;
    int ax, ay, bx, by;

    // ---- results ----
    /** player ports a line couldn't reach (names) */
    public final Seq<String> unreached = new Seq<>();
    public int trunkTiles;

    boolean inRegion(int x, int y){ return x >= ax && x <= bx && y >= ay && y <= by; }

    /** a building's footprint touches the region or the ring around it */
    boolean near(Building b){
        int off = (b.block.size - 1) / 2, x0 = b.tile.x - off, y0 = b.tile.y - off, x1 = x0 + b.block.size - 1, y1 = y0 + b.block.size - 1;
        return x1 >= ax - 1 && x0 <= bx + 1 && y1 >= ay - 1 && y0 <= by + 1;
    }

    /** a port the planner can't reach by itself */
    boolean remote(Port p){
        if(p.edge) return false;
        if(p.existing != null) return !near(p.existing);
        return !inRegion(p.x, p.y);
    }

    /** Main thread. */
    public static RemotePorts expand(DrillFieldJob job, Seq<Port> outs, Seq<Port> waters, boolean liquidOn){
        RemotePorts r = new RemotePorts();
        r.ax = job.ax; r.ay = job.ay; r.bx = job.bx; r.by = job.by;
        r.belt = job.belt;
        r.bridge = job.bridges && BuildJob.available(Blocks.itemBridge) ? Blocks.itemBridge : null;
        r.bridgeRange = r.bridge == null ? 0 : ((ItemBridge)r.bridge).range;
        r.conduit = job.conduit;
        r.liquidBridge = BuildJob.available(Blocks.bridgeConduit) ? Blocks.bridgeConduit : null;
        r.liquidBridgeRange = r.liquidBridge == null ? 0 : ((ItemBridge)r.liquidBridge).range;
        // the trunk grid: the region and every remote port, with room around
        // the whole map: a line may have to follow corridors far away from the region and the port
        // (only when a port is away from the region: it is computed on the main thread)
        boolean anyRemote = outs.contains(r::remote) || liquidOn && waters.contains(r::remote);
        long t0 = arc.util.Time.millis();
        r.gx = 0; r.gy = 0;
        r.gw = anyRemote ? world.width() : 1; r.gh = anyRemote ? world.height() : 1;
        r.free = new boolean[r.gw][r.gh];
        r.nearItems = new boolean[r.gw][r.gh];
        r.nearLiquids = new boolean[r.gw][r.gh];
        for(int u = 0; u < r.gw; u++) for(int v = 0; v < r.gh; v++){
            int x = r.gx + u, y = r.gy + v;
            r.free[u][v] = !r.inRegion(x, y) && BuildJob.empty(x, y) && !job.fedByExisting(x, y) && Build.validPlace(job.belt, job.team, x, y, 0);
            r.nearItems[u][v] = job.touchesExistingLogistics(x, y, 1);
            for(int d = 0; d < 4; d++){
                Tile n = world.tile(x + Geometry.d4x(d), y + Geometry.d4y(d));
                if(n != null && n.build != null && n.block().hasLiquids && !job.portBuildings.contains(n.build)) r.nearLiquids[u][v] = true;
            }
        }

        if(anyRemote) arc.util.Log.info("[autobuild] line grid @x@ in @ms", r.gw, r.gh, arc.util.Time.timeSinceMillis(t0));
        for(int i = 0; i < outs.size; i++){
            Port p = outs.get(i);
            Building b = p.existing;
            r.outTarget.add((int[])null);
            r.outLines.add(1);
            if(b != null && b.block instanceof StorageBlock && r.near(b)){
                // storage at the region: one output per tile of it facing the region
                Seq<Port> subs = r.facing(b);
                if(!subs.isEmpty()){
                    for(Port s : subs){ r.planOuts.add(s); r.outOwner.add(i); r.outRemote.add(false); }
                    r.outLines.set(i, subs.size);
                    continue;
                }
            }
            if(!r.remote(p)){ r.planOuts.add(p); r.outOwner.add(i); r.outRemote.add(false); continue; }
            r.any = true;
            int[] target;
            int lines = 1;
            if(b != null){
                int off = (b.block.size - 1) / 2;
                target = new int[]{b.tile.x - off, b.tile.y - off, b.block.size};
                if(b.block instanceof StorageBlock) lines = Math.max(1, Math.min(4, r.approaches(target)));
            }else{
                target = new int[]{p.x, p.y, 1};
                // the target tile itself stays empty: the player's block goes there
                if(r.inGrid(p.x, p.y)) r.free[p.x - r.gx][p.y - r.gy] = false;
            }
            r.outTarget.set(i, target);
            r.outLines.set(i, lines);
            r.reach = r.flood(r.around(target), r.bridgeRange);
            for(Port e : r.edgePorts(target, lines)){ r.planOuts.add(e); r.outOwner.add(i); r.outRemote.add(true); }
        }
        if(liquidOn) for(int i = 0; i < waters.size; i++){
            Port p = waters.get(i);
            r.waterStart.add((int[][])null);
            if(!r.remote(p)){ r.planWaters.add(p); r.waterOwner.add(i); r.waterRemote.add(false); continue; }
            r.any = true;
            r.waterStart.set(i, r.sourceStarts(p));
            int[] t = job.portTile(p);
            int s = p.existing == null ? 1 : p.existing.block.size;
            Seq<int[]> from = new Seq<>();
            for(int[] a : r.waterStart.get(i)) from.add(new int[]{a[0], a[1]});
            r.reach = r.flood(from, r.liquidBridgeRange);
            // never on an edge the output lines leave from: that side of the field is their collector
            int avoid = 0;
            for(Port o : r.planOuts) if(o.edge) avoid |= 1 << o.side;
            for(Port e : r.edgePorts(new int[]{t[0], t[1], s}, 1, avoid)){ r.planWaters.add(e); r.waterOwner.add(i); r.waterRemote.add(true); }
        }
        return r;
    }

    /** the same ports with at most `max[i]` lines for the player's i-th output (the preferred ones kept) */
    public RemotePorts limit(int[] max){
        RemotePorts r = new RemotePorts();
        r.gx = gx; r.gy = gy; r.gw = gw; r.gh = gh; r.free = free; r.nearItems = nearItems; r.nearLiquids = nearLiquids;
        r.belt = belt; r.bridge = bridge; r.conduit = conduit; r.liquidBridge = liquidBridge;
        r.bridgeRange = bridgeRange; r.liquidBridgeRange = liquidBridgeRange;
        r.ax = ax; r.ay = ay; r.bx = bx; r.by = by;
        r.any = any;
        r.outTarget.addAll(outTarget);
        r.waterStart.addAll(waterStart);
        r.planWaters.addAll(planWaters); r.waterOwner.addAll(waterOwner); r.waterRemote.addAll(waterRemote);
        int[] count = new int[max.length];
        for(int k = 0; k < planOuts.size; k++){
            int o = outOwner.get(k);
            if(count[o]++ >= max[o]) continue;
            r.planOuts.add(planOuts.get(k)); r.outOwner.add(o); r.outRemote.add(outRemote.get(k));
        }
        for(int i = 0; i < max.length; i++) r.outLines.add(Math.min(max[i], outLines.get(i)));
        return r;
    }

    boolean inGrid(int x, int y){ return x >= gx && y >= gy && x < gx + gw && y < gy + gh; }

    /** free tiles a line could reach from these world tiles (steps, and bridge jumps over anything): grid flags; null = no limit */
    boolean[][] reach;

    boolean[][] flood(Seq<int[]> from, int range){
        boolean[][] seen = new boolean[gw][gh];
        IntQueue q = new IntQueue();
        for(int[] t : from){
            if(!freeAt(t[0], t[1])) continue;
            int u = t[0] - gx, v = t[1] - gy;
            if(!seen[u][v]){ seen[u][v] = true; q.addLast(u * gh + v); }
        }
        while(q.size > 0){
            int id = q.removeFirst(), u = id / gh, v = id % gh;
            for(int d = 0; d < 4; d++){
                for(int k = 1; k <= Math.max(1, range); k++){
                    int nu = u + Geometry.d4x(d) * k, nv = v + Geometry.d4y(d) * k;
                    if(nu < 0 || nv < 0 || nu >= gw || nv >= gh) break;
                    if(!free[nu][nv] || seen[nu][nv]) continue;
                    seen[nu][nv] = true;
                    q.addLast(nu * gh + nv);
                }
            }
        }
        return seen;
    }

    /** free tiles next to a footprint {x, y, size} */
    Seq<int[]> around(int[] t){
        Seq<int[]> out = new Seq<>();
        for(int i = -1; i <= t[2]; i++) for(int j = -1; j <= t[2]; j++){
            boolean edge = (i == -1 || i == t[2]) != (j == -1 || j == t[2]);
            if(edge) out.add(new int[]{t[0] + i, t[1] + j});
        }
        if(t[2] == 1 && freeAt(t[0], t[1])) out.add(new int[]{t[0], t[1]});
        return out;
    }

    /** a line from this edge-output tile could reach the target */
    boolean reachable(int x, int y){
        return reach == null || (inGrid(x, y) && reach[x - gx][y - gy]);
    }

    boolean freeAt(int x, int y){ return inGrid(x, y) && free[x - gx][y - gy]; }

    /** tiles of a storage building facing the region (spread out, at most 4) as sub-ports */
    Seq<Port> facing(Building b){
        int off = (b.block.size - 1) / 2, x0 = b.tile.x - off, y0 = b.tile.y - off, s = b.block.size;
        Seq<int[]> tiles = new Seq<>();
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++){
            int x = x0 + i, y = y0 + j;
            for(int d = 0; d < 4; d++){
                int nx = x + Geometry.d4x(d), ny = y + Geometry.d4y(d);
                boolean outside = nx < x0 || ny < y0 || nx >= x0 + s || ny >= y0 + s;
                if(outside && inRegion(nx, ny) && BuildJob.empty(nx, ny)){ tiles.add(new int[]{x, y}); break; }
            }
        }
        Seq<Port> out = new Seq<>();
        int n = Math.min(4, tiles.size);
        for(int k = 0; k < n; k++){
            int[] t = tiles.get(tiles.size == 1 ? 0 : k * (tiles.size - 1) / Math.max(1, n - 1));
            Port p = Port.at(t[0], t[1]);
            p.existing = b;
            p.sub = true;
            if(!out.contains(q -> q.x == p.x && q.y == p.y)) out.add(p);
        }
        return out;
    }

    /** free tiles next to a footprint (where lines can end) */
    int approaches(int[] t){
        int n = 0;
        for(int i = -1; i <= t[2]; i++) for(int j = -1; j <= t[2]; j++){
            boolean edge = (i == -1 || i == t[2]) != (j == -1 || j == t[2]);
            if(edge && freeAt(t[0] + i, t[1] + j)) n++;
        }
        return n;
    }

    /**
     * `lines` edge outputs facing the target: on the side of the region nearest it, around its
     * projection, 3 apart; if that side has too few free spots, on the next sides.
     */
    Seq<Port> edgePorts(int[] t, int lines){ return edgePorts(t, lines, 0); }

    /** as above, never on the sides in the `avoid` bit mask */
    Seq<Port> edgePorts(int[] t, int lines, int avoid){
        float cx = t[0] + (t[2] - 1) / 2f, cy = t[1] + (t[2] - 1) / 2f;
        float[] beyond = {cx - bx, cy - by, ax - cx, ay - cy};
        Integer[] sides = {0, 1, 2, 3};
        Arrays.sort(sides, (a, b) -> Float.compare(beyond[b], beyond[a]));
        if(avoid != 0 && avoid != 15){
            // avoided sides last (never used unless nothing else exists)
            Integer[] ok = Arrays.stream(sides).filter(x -> (avoid & (1 << x)) == 0).toArray(Integer[]::new);
            sides = ok;
        }
        Seq<Port> out = new Seq<>();
        for(int si = 0; si < 4 && out.size < lines; si++){
            int side = sides[si];
            // the far side only when nothing nearer connects (lines would wrap around the field)
            if(si > 0 && beyond[side] < -Math.max(bx - ax, by - ay) / 2f && !out.isEmpty()) break;
            boolean vertical = side % 2 == 0; // east / west edges run along y
            int lo = vertical ? ay : ax, hi = vertical ? by : bx;
            int center = Math.max(lo, Math.min(hi, Math.round(vertical ? cy : cx)));
            IntSeq taken = new IntSeq();
            IntSeq order = new IntSeq();
            for(int k = 0; k <= hi - lo; k += 3){ order.add(center + k); if(k > 0) order.add(center - k); }
            for(int k = 1; k <= hi - lo; k++){ order.add(center + k); order.add(center - k); }
            for(int i = 0; i < order.size && out.size < lines; i++){
                int pos = order.get(i);
                if(pos < lo || pos > hi) continue;
                boolean close = false;
                for(int j = 0; j < taken.size; j++) if(Math.abs(taken.get(j) - pos) < 2) close = true;
                if(close) continue;
                int ex = vertical ? (side == 0 ? bx : ax) : pos, ey = vertical ? pos : (side == 1 ? by : ay);
                if(!freeAt(ex + Geometry.d4x(side), ey + Geometry.d4y(side)) || !reachable(ex + Geometry.d4x(side), ey + Geometry.d4y(side))) continue;
                // not in the corner of an avoided side either (the corner tile belongs to both edges)
                if(nearAvoided(ex, ey, avoid)) continue;
                taken.add(pos);
                out.add(Port.edge(side, hi == lo ? 0.5f : (pos - lo) / (float)(hi - lo)));
            }
        }
        if(out.isEmpty()){
            int side = sides[0];
            out.add(Port.edge(side, 0.5f));
        }
        return out;
    }

    /** within 2 tiles of an edge in the `avoid` mask */
    boolean nearAvoided(int x, int y, int avoid){
        return (avoid & 1) != 0 && bx - x < 2 || (avoid & 2) != 0 && by - y < 2 || (avoid & 4) != 0 && x - ax < 2 || (avoid & 8) != 0 && y - ay < 2;
    }

    /** where a water line starts at a remote source: {x, y, from} */
    int[][] sourceStarts(Port p){
        Seq<int[]> out = new Seq<>();
        Building b = p.existing;
        if(b == null){
            out.add(new int[]{p.x, p.y, -1});
        }else if(b.block instanceof Conduit){
            out.add(new int[]{b.tile.x + Geometry.d4x(b.rotation), b.tile.y + Geometry.d4y(b.rotation), (b.rotation + 2) % 4});
        }else{
            int off = (b.block.size - 1) / 2, x0 = b.tile.x - off, y0 = b.tile.y - off, s = b.block.size;
            for(int i = -1; i <= s; i++) for(int j = -1; j <= s; j++){
                boolean edge = (i == -1 || i == s) != (j == -1 || j == s);
                if(edge) out.add(new int[]{x0 + i, y0 + j, i == -1 ? 0 : i == s ? 2 : j == -1 ? 1 : 3});
            }
        }
        return out.toArray(int[].class);
    }

    // ------------------------------------------------------------------ planning thread

    /**
     * Lines for a planned field: from every loaded edge output standing for a remote port to its
     * target, and from every remote water source to its water port. Returns the build plans.
     */
    public Seq<BuildPlan> route(DrillFieldJob.Candidate c, float minLoad){
        unreached.clear();
        trunkTiles = 0;
        Seq<BuildPlan> out = new Seq<>();
        boolean[][] used = new boolean[gw][gh];
        // water
        for(int k = 0; k < c.waterPorts.size && k < waterRemote.size; k++){
            if(!waterRemote.get(k)) continue;
            int owner = waterOwner.get(k);
            int[] w = c.waterPortTile(k);
            List<Start> st = new ArrayList<>();
            for(int[] a : waterStart.get(owner)) if(inGrid(a[0], a[1])) st.add(new Start(a[0] - gx, a[1] - gy, a[2], 0, false));
            if(!line(st, new int[]{w[0], w[1], 1}, used, true, out)) unreached.add(c.userWaters.get(owner).name());
        }
        // items
        for(int k = 0; k < c.outPorts.size && k < outRemote.size; k++){
            if(!outRemote.get(k) || k >= c.loads.length || c.loads[k] < minLoad) continue;
            int owner = outOwner.get(k);
            int[] t = outTarget.get(owner);
            int[] s = c.sinkWorld[k];
            Port e = c.outPorts.get(k);
            List<Start> st = new ArrayList<>();
            if(inGrid(s[0], s[1])) st.add(new Start(s[0] - gx, s[1] - gy, (e.side + 2) % 4, 0, false));
            if(!line(st, t, used, false, out)) unreached.add(c.userOuts.get(owner).name());
        }
        return out;
    }

    /** one line from the starts to the footprint t {x, y, size}; marks its tiles used */
    boolean line(List<Start> starts, int[] t, boolean[][] used, boolean liquid, Seq<BuildPlan> out){
        if(starts.isEmpty()) return false;
        GridRouter r = new GridRouter(gw, gh, new GridRouter.Grid(){
            public double tileCost(int u, int v){ return free[u][v] && !used[u][v] ? 1 : -1; }
            public boolean pushOk(int u, int v){ return !(liquid ? nearLiquids[u][v] : nearItems[u][v]); }
        });
        r.bridgeRange = liquid ? liquidBridgeRange : bridgeRange;
        r.bridgeCost = 1.5;
        List<Step> path = r.route(starts, t[0] - gx, t[1] - gy, t[2]);
        if(path == null) return false;
        for(Step s : path){
            used[s.u][s.v] = true;
            trunkTiles++;
            int x = gx + s.u, y = gy + s.v;
            switch(s.kind){
                case GridRouter.PIPE: out.add(new BuildPlan(x, y, s.dir, liquid ? conduit : belt)); break;
                case GridRouter.BRIDGE: out.add(new BuildPlan(x, y, 0, liquid ? liquidBridge : bridge, new Point2(s.lu - s.u, s.lv - s.v))); break;
                case GridRouter.END: out.add(new BuildPlan(x, y, 0, liquid ? liquidBridge : bridge, new Point2(0, 0))); break;
                default: out.add(new BuildPlan(x, y, 0, liquid ? Blocks.liquidRouter : Blocks.router)); break;
            }
        }
        return true;
    }
}
