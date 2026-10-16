package autobuild;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.*;
import autobuild.plan.DrillLayoutPlanner.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.consumers.*;

import java.util.*;
import java.util.Arrays;

import static mindustry.Vars.*;

/**
 * Game-side wrapper around {@link DrillLayoutPlanner}.
 * <ol>
 * <li>{@link #prepare()} (main thread) snapshots the region once, in world coordinates;</li>
 * <li>{@link #search} (any thread) plans one or more candidates: every output side / water side
 *     that wasn't picked by the player, and a few variants, keeping the best by score;</li>
 * <li>a {@link Candidate} holds the world BuildPlans and stats of one plan.</li>
 * </ol>
 * Used by the client UI and by the server test command.
 */
public class DrillFieldJob{
    public static final String[] dirNames = {"East", "North", "West", "South"};

    // ---- settings ----
    /** region, world tiles, inclusive */
    public int ax, ay, bx, by;
    public Team team = Team.sharded;
    public Drill drill;
    public Block belt = Blocks.conveyor;
    public Item target;
    public Mode mode = Mode.OUTPUT;
    /** allow item bridges in layout and routing */
    public boolean bridges = true;
    /** water boost: water rows fed from a water port */
    public boolean water;
    public Block conduit = Blocks.conduit;
    /** also use smaller, lower-tier drills where the chosen one doesn't fit */
    public boolean mixDrills = true;
    /** ports picked by the player; empty = let the planner choose */
    public final Seq<Port> outs = new Seq<>(), waters = new Seq<>();
    /** tiles kept free around an interior output (server test: 1 for a 3x3 vault) */
    public int sinkReserve;

    /**
     * A port: on a region edge (side 0=E 1=N 2=W 3=S, position along it in world order) or an
     * interior tile. An edge output's belt leaves the region there; an interior output delivers into
     * that tile (the player's own block). A water port is a liquid router fed from outside / by the player.
     */
    public static class Port{
        public boolean edge;
        public int side;
        public float frac;
        public int x, y;

        public static Port edge(int side, float frac){ Port p = new Port(); p.edge = true; p.side = side; p.frac = frac; return p; }

        public static Port at(int x, int y){ Port p = new Port(); p.x = x; p.y = y; return p; }

        public String name(){ return edge ? dirNames[side] : "(" + x + "," + y + ")"; }
    }

    /** a click: inside the region (a tile away from its border) = interior port, otherwise snapped to the nearest edge */
    public Port classify(int tx, int ty){
        if(tx > ax && tx < bx && ty > ay && ty < by) return Port.at(tx, ty);
        float[] s = snap(tx, ty);
        return Port.edge((int)s[0], s[1]);
    }

    /** world tile the planner delivers items into for an output port */
    public int[] sinkTile(Port p){
        if(!p.edge) return new int[]{p.x, p.y};
        int[] t = edgeTile(p.side, p.frac);
        return new int[]{t[0] + Geometry.d4x(p.side), t[1] + Geometry.d4y(p.side)};
    }

    /** world tile of the liquid router for a water port */
    public int[] waterTile(Port p){
        return p.edge ? edgeTile(p.side, p.frac) : new int[]{p.x, p.y};
    }

    public String error;
    public volatile boolean cancelled;
    volatile DrillLayoutPlanner current;

    // ------------------------------------------------------------------ block helpers

    /** Optional water consumer that boosts this drill, or null. */
    public static ConsumeLiquid waterBoost(Block b){
        for(Consume c : b.optionalConsumers) if(c instanceof ConsumeLiquid cl && cl.liquid == Liquids.water) return cl;
        return null;
    }

    public static boolean waterAvailable(Block conduit){
        return available(conduit) && available(Blocks.liquidRouter);
    }

    public static boolean needsPower(Block b){
        return b.consPower != null;
    }

    /** Required (non-optional) liquid input: not supplied by this planner yet. */
    public static boolean needsLiquid(Block b){
        for(Consume c : b.nonOptionalConsumers) if(c instanceof ConsumeLiquidBase) return true;
        return false;
    }

    /** Power node usable in the current environment, or null. */
    public static PowerNode powerNode(){
        Block n = Blocks.powerNode;
        return available(n) ? (PowerNode)n : null;
    }

    static boolean available(Block b){
        return b.isVisible() && b.environmentBuildable() && (state.rules.infiniteResources || b.unlockedNow());
    }

    // ------------------------------------------------------------------ geometry

    /** Region seen in the planner's local frame, where items leave toward +u (= world side `dir`). */
    public static class Frame{
        public final int ax, ay, bx, by, dir, W, H;

        public Frame(int ax, int ay, int bx, int by, int dir){
            this.ax = ax; this.ay = ay; this.bx = bx; this.by = by; this.dir = dir;
            boolean rot = dir % 2 == 1;
            W = rot ? by - ay + 1 : bx - ax + 1;
            H = rot ? bx - ax + 1 : by - ay + 1;
        }

        /** local (u,v) -> world */
        public int wx(int u, int v){
            switch(dir){
                case 0: return ax + u;
                case 1: return bx - v;
                case 2: return bx - u;
                default: return ax + v;
            }
        }

        public int wy(int u, int v){
            switch(dir){
                case 0: return ay + v;
                case 1: return ay + u;
                case 2: return by - v;
                default: return by - u;
            }
        }

        /** world -> local */
        public int lu(int x, int y){
            switch(dir){
                case 0: return x - ax;
                case 1: return y - ay;
                case 2: return bx - x;
                default: return by - y;
            }
        }

        public int lv(int x, int y){
            switch(dir){
                case 0: return y - ay;
                case 1: return bx - x;
                case 2: return by - y;
                default: return x - ax;
            }
        }

        /** min corner (world) of the s x s footprint whose local min corner is (u,v) */
        public int minX(int u, int v, int s){ return Math.min(wx(u, v), wx(u + s - 1, v + s - 1)); }

        public int minY(int u, int v, int s){ return Math.min(wy(u, v), wy(u + s - 1, v + s - 1)); }
    }

    /** world tile on the region edge facing `side`, at fraction f in world order */
    public int[] edgeTile(int side, float f){
        switch(side){
            case 0: return new int[]{bx, ay + Mathf.round(f * (by - ay))};
            case 1: return new int[]{ax + Mathf.round(f * (bx - ax)), by};
            case 2: return new int[]{ax, ay + Mathf.round(f * (by - ay))};
            default: return new int[]{ax + Mathf.round(f * (bx - ax)), ay};
        }
    }

    /** Any tile (clamped into the region) -> nearest side and position along it: {side, frac}. */
    public float[] snap(int x, int y){
        x = Mathf.clamp(x, ax, bx); y = Mathf.clamp(y, ay, by);
        int[] d = {bx - x, by - y, x - ax, y - ay};
        int side = 0;
        for(int i = 1; i < 4; i++) if(d[i] < d[side]) side = i;
        float f = side % 2 == 0 ? (by == ay ? 0.5f : (y - ay) / (float)(by - ay)) : (bx == ax ? 0.5f : (x - ax) / (float)(bx - ax));
        return new float[]{side, f};
    }

    // ------------------------------------------------------------------ snapshot

    /** The region, captured once (main thread) in world-relative coordinates. */
    public static class Snapshot{
        int rw, rh, s;
        boolean[][] belt, node;
        /** drill types: 0 = the chosen drill, then smaller fillers (one per size) */
        Drill[] types;
        double[] rate;
        /** per type: placeable with its footprint's min corner at (rx, ry); mineable item per tile */
        boolean[][][] drillMin;
        int[][][] drop;
        boolean[] low;
        PowerNode node0;
        ItemBridge bridge;
        int liquidRange;
        boolean power, waterOn;
        float boost, waterRate;
    }

    /** Main thread. Returns null (and sets error) if the settings can't work here. */
    public Snapshot prepare(){
        error = null;
        cancelled = false;
        Snapshot sn = new Snapshot();
        sn.rw = bx - ax + 1; sn.rh = by - ay + 1; sn.s = drill.size;
        sn.types = drillTypes();
        sn.power = false;
        for(Drill d : sn.types) sn.power |= needsPower(d);
        sn.node0 = sn.power ? powerNode() : null;
        if(sn.power && sn.node0 == null){ error = drill.localizedName + " needs power, but power nodes aren't available here"; return null; }
        sn.rate = new double[sn.types.length];
        for(int i = 0; i < sn.types.length; i++) sn.rate[i] = drill.getDrillTime(target) / sn.types[i].getDrillTime(target);
        sn.bridge = bridges && available(Blocks.itemBridge) ? (ItemBridge)Blocks.itemBridge : null;
        ConsumeLiquid boost = waterBoost(drill);
        if(water && (boost == null || !waterAvailable(conduit))){
            error = boost == null ? drill.localizedName + " can't be boosted with water" : "conduits / liquid routers aren't available here";
            return null;
        }
        sn.waterOn = water;
        if(water){
            sn.boost = drill.liquidBoostIntensity * drill.liquidBoostIntensity;
            sn.waterRate = boost.amount * 60f;
            sn.liquidRange = available(Blocks.bridgeConduit) ? ((ItemBridge)Blocks.bridgeConduit).range : 0;
        }
        int T = sn.types.length;
        sn.belt = new boolean[sn.rw][sn.rh]; sn.node = new boolean[sn.rw][sn.rh]; sn.drillMin = new boolean[T][sn.rw][sn.rh]; sn.drop = new int[T][sn.rw][sn.rh];
        sn.low = new boolean[content.items().size];
        for(Item i : content.items()) sn.low[i.id] = i.lowPriority;
        for(int rx = 0; rx < sn.rw; rx++) for(int ry = 0; ry < sn.rh; ry++){
            int x = ax + rx, y = ay + ry;
            sn.belt[rx][ry] = Build.validPlace(belt, team, x, y, 0) && (sn.bridge == null || Build.validPlace(sn.bridge, team, x, y, 0));
            sn.node[rx][ry] = sn.node0 != null && Build.validPlace(sn.node0, team, x, y, 0);
            Tile t = world.tile(x, y);
            for(int i = 0; i < T; i++){
                Drill d = sn.types[i];
                int s = d.size, off = (s - 1) / 2;
                sn.drop[i][rx][ry] = d.canMine(t) ? d.getDrop(t).id : -1;
                sn.drillMin[i][rx][ry] = rx + s <= sn.rw && ry + s <= sn.rh && Build.validPlace(d, team, x + off, y + off, 0);
            }
        }
        return sn;
    }

    /**
     * The chosen drill, plus (if mixing) for every smaller size the fastest available drill of a
     * tier no higher than the chosen one that can mine the target and needs no liquid.
     */
    public Drill[] drillTypes(){
        Seq<Drill> out = Seq.with(drill);
        if(!mixDrills) return out.toArray(Drill.class);
        IntMap<Drill> best = new IntMap<>();
        for(Block b : content.blocks()){
            if(!(b instanceof Drill d) || d == drill || !available(d)) continue;
            if(d.size >= drill.size || d.tier > drill.tier || d.tier < target.hardness || needsLiquid(d)) continue;
            if(needsPower(d) && powerNode() == null) continue;
            Drill cur = best.get(d.size);
            if(cur == null || d.getDrillTime(target) < cur.getDrillTime(target)) best.put(d.size, d);
        }
        for(Drill d : best.values()) out.add(d);
        out.sort(d -> -d.size);
        return out.toArray(Drill.class);
    }

    // ------------------------------------------------------------------ planning

    /** Receives intermediate planner states (planning thread). */
    public interface Progress{
        void frame(Candidate candidate, String stage, List<Place> places);
    }

    /** One planned layout, in world terms. */
    public class Candidate{
        public Frame frame;
        public DrillLayoutPlanner planner;
        public int outDir, variant;
        public Seq<Port> outPorts = new Seq<>(), waterPorts = new Seq<>();
        public boolean outAuto, waterAuto;
        /** per output: world tile the items go into, items/s delivered */
        public int[][] sinkWorld = new int[0][];
        public double[] loads = new double[0];
        public final Seq<BuildPlan> plans = new Seq<>();
        public float itemsPerSecond, beltCapacity, powerPerSecond, waterPerSecond;
        public float excessFlow;
        public PowerNode node;
        public ItemBridge bridge;
        public Drill[] types;
        /** drills placed per type */
        public int[] perType;
        public String error;
        /** for "another plan": same placements = same plan */
        public final Set<String> signature = new HashSet<>();

        public boolean ok(){ return error == null; }

        public double score(){ return ok() ? planner.score : Double.NEGATIVE_INFINITY; }

        /** Planner place (local frame) -> world BuildPlan. Pure arithmetic: safe on the planning thread. */
        public BuildPlan toPlan(Place pl){
            int s = drill.size;
            Frame f = frame;
            switch(pl.kind){
                case DrillLayoutPlanner.NODE: return new BuildPlan(f.wx(pl.u, pl.v), f.wy(pl.u, pl.v), 0, node);
                case DrillLayoutPlanner.BELT: return new BuildPlan(f.wx(pl.u, pl.v), f.wy(pl.u, pl.v), (pl.dir + f.dir) % 4, belt);
                case DrillLayoutPlanner.CONDUIT: return new BuildPlan(f.wx(pl.u, pl.v), f.wy(pl.u, pl.v), (pl.dir + f.dir) % 4, conduit);
                case DrillLayoutPlanner.LROUTER: return new BuildPlan(f.wx(pl.u, pl.v), f.wy(pl.u, pl.v), 0, Blocks.liquidRouter);
                case DrillLayoutPlanner.BRIDGE: case DrillLayoutPlanner.LBRIDGE: {
                    int x = f.wx(pl.u, pl.v), y = f.wy(pl.u, pl.v);
                    // relative link; (0,0) marks an end bridge and also stops the game's auto-linking
                    // (ItemBridge.findLink only links from a previous bridge whose link is -1)
                    Block b = pl.kind == DrillLayoutPlanner.BRIDGE ? bridge : Blocks.bridgeConduit;
                    return new BuildPlan(x, y, 0, b, new Point2(f.wx(pl.lu, pl.lv) - x, f.wy(pl.lu, pl.lv) - y));
                }
                default: {
                    Drill d = types[pl.type];
                    int ds = d.size;
                    return new BuildPlan(f.minX(pl.u, pl.v, ds) + (ds - 1) / 2, f.minY(pl.u, pl.v, ds) + (ds - 1) / 2, 0, d);
                }
            }
        }

        public Seq<BuildPlan> toPlans(List<Place> places){
            Seq<BuildPlan> out = new Seq<>(places.size());
            for(Place pl : places) out.add(toPlan(pl));
            return out;
        }

        public boolean water(){ return waterPorts.size > 0; }

        public String portText(){
            StringBuilder t = new StringBuilder(outPorts.size == 1 ? "Output: " : "Outputs: ");
            for(int i = 0; i < outPorts.size; i++) t.append(i > 0 ? ", " : "").append(outPorts.get(i).name());
            if(outAuto) t.append(" (auto)");
            if(water()){
                t.append(waterPorts.size == 1 ? ", water from: " : ", water from: ");
                for(int i = 0; i < waterPorts.size; i++) t.append(i > 0 ? ", " : "").append(waterPorts.get(i).name());
                if(waterAuto) t.append(" (auto)");
            }
            return t.toString();
        }

        public boolean overloaded(int k){ return loads[k] > beltCapacity + 0.05f; }

        public String summary(){
            if(!ok()) return "[scarlet]" + error;
            StringBuilder sb = new StringBuilder();
            StringBuilder kinds = new StringBuilder();
            for(int i = 0; i < types.length; i++){
                if(perType[i] == 0) continue;
                if(kinds.length() > 0) kinds.append(" + ");
                kinds.append(perType[i]).append(' ').append(types[i].localizedName);
            }
            sb.append(Strings.format("@ on @ @ ore tiles, ~@/s", kinds, planner.oreTiles, target.localizedName, Strings.autoFixed(itemsPerSecond, 1)));
            sb.append(Strings.format(" [lightgray](@)[]", planner.layout));
            if(water() && planner.waterSkipped){
                sb.append("\n[orange]Water skipped: the water network couldn't reach enough drills to beat a layout without water");
            }else if(water()){
                sb.append(Strings.format("\n[sky]@/@ drills water-boosted, needs ~@ water/s at the water port", planner.wetDrills, planner.drills,
                    Strings.autoFixed(waterPerSecond, 1)));
            }
            if(planner.nodes > 0) sb.append(Strings.format("\n@ power nodes, needs @ power/s", planner.nodes, Strings.autoFixed(powerPerSecond, 1)));
            // per output line: load / capacity
            sb.append("\n").append(loads.length == 1 ? "Output line: " : "Output lines: ");
            for(int k = 0; k < loads.length; k++){
                sb.append(k > 0 ? ", " : "").append(overloaded(k) ? "[scarlet]" : "[lightgray]")
                    .append(Strings.autoFixed((float)loads[k], 1)).append('/').append(Strings.autoFixed(beltCapacity, 1)).append("[]");
            }
            sb.append(" items/s");
            for(int k = 0; k < loads.length; k++) if(overloaded(k)){ sb.append("\n[scarlet]Output ").append(k + 1).append(" exceeds ").append(belt.localizedName).append(" capacity: it will back up"); break; }
            if(excessFlow > 0.1f) sb.append(Strings.format("\n[orange]The field could add ~@/s with more outputs: use \"Add output\"", Strings.autoFixed(excessFlow, 1)));
            if(planner.segmentsDropped > 0) sb.append("\n[orange]").append(planner.segmentsDropped).append(" lane(s) dropped: no route to an output");
            if(planner.drillsDroppedForPower > 0) sb.append("\n[orange]").append(planner.drillsDroppedForPower).append(" drill(s) dropped to fit power nodes");
            if(planner.powerGroups > 1) sb.append("\n[orange]Power nodes form ").append(planner.powerGroups).append(" separate groups: link them manually");
            if(needsLiquid(drill) && !water()) sb.append("\n[orange]").append(drill.localizedName).append(" needs a liquid supply (not planned yet)");
            return sb.toString();
        }
    }

    /** Plans one candidate: frame direction `dir` (lane flow), given outputs and water ports, variant. Any thread. */
    public Candidate plan(Snapshot sn, int dir, Seq<Port> outPorts, Seq<Port> waterPorts, int variant, Progress progress){
        Candidate c = new Candidate();
        c.outDir = dir; c.variant = variant; c.outPorts = outPorts; c.waterPorts = waterPorts;
        c.node = sn.node0; c.bridge = sn.bridge; c.types = sn.types; c.perType = new int[sn.types.length];
        c.beltCapacity = belt instanceof Conveyor cv ? cv.displayedSpeed : Float.MAX_VALUE;
        Frame f = c.frame = new Frame(ax, ay, bx, by, dir);
        int W = f.W, H = f.H;

        Params p = new Params();
        p.W = W; p.H = H; p.drillSize = sn.s; p.target = target.id; p.mode = mode; p.power = sn.power; p.variant = variant;
        p.bridges = sn.bridge != null;
        if(sn.bridge != null) p.bridgeRange = sn.bridge.range;
        if(sn.node0 != null){ p.nodeRange = sn.node0.laserRange; p.nodeMaxLinks = sn.node0.maxNodes; }
        p.lowPriority = sn.low;
        int T = sn.types.length;
        p.typeSize = new int[T]; p.typeRate = sn.rate; p.typePowered = new boolean[T];
        p.typeDrop = new int[T][W][H]; p.typeDrillOk = new boolean[T][W][H];
        p.beltOk = new boolean[W][H]; p.nodeOk = new boolean[W][H];
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int rx = f.wx(u, v) - ax, ry = f.wy(u, v) - ay;
            p.beltOk[u][v] = sn.belt[rx][ry];
            p.nodeOk[u][v] = sn.node[rx][ry];
            for(int i = 0; i < T; i++) p.typeDrop[i][u][v] = sn.drop[i][rx][ry];
        }
        for(int i = 0; i < T; i++){
            int ds = sn.types[i].size;
            p.typeSize[i] = ds;
            p.typePowered[i] = needsPower(sn.types[i]);
            for(int u = 0; u + ds <= W; u++) for(int v = 0; v + ds <= H; v++){
                p.typeDrillOk[i][u][v] = sn.drillMin[i][f.minX(u, v, ds) - ax][f.minY(u, v, ds) - ay];
            }
        }
        p.drop = p.typeDrop[0]; p.drillOk = p.typeDrillOk[0];

        // outputs: tiles to deliver into; line capacity from the belt
        p.sinks = new int[outPorts.size][];
        for(int i = 0; i < outPorts.size; i++){
            int[] w = sinkTile(outPorts.get(i));
            p.sinks[i] = new int[]{f.lu(w[0], w[1]), f.lv(w[0], w[1])};
        }
        p.sinkReserve = sinkReserve;
        p.portV = Mathf.clamp(p.sinks[0][1], 0, H - 1);
        if(c.beltCapacity < Float.MAX_VALUE){
            p.sinkCapacity = c.beltCapacity;
            p.itemsPerOre = 60f / drill.getDrillTime(target);
        }
        if(waterPorts.size > 0){
            p.water = true;
            p.waterBoost = sn.boost;
            p.waterPorts = new int[waterPorts.size][];
            for(int i = 0; i < waterPorts.size; i++){
                int[] w = waterTile(waterPorts.get(i));
                p.waterPorts[i] = new int[]{f.lu(w[0], w[1]), f.lv(w[0], w[1])};
            }
            p.liquidBridgeRange = sn.liquidRange;
        }

        DrillLayoutPlanner planner = c.planner = current = new DrillLayoutPlanner(p);
        if(cancelled) throw new CancelledException();
        if(progress != null){
            planner.listener = (st, snap) -> progress.frame(c, st, snap);
            planner.snapshotInterval = 4;
        }
        long time = Time.millis();
        boolean ok = planner.plan();
        Log.info("[autobuild] @x@ dir=@ @ var=@ @ @ layout=@ ok=@ score=@ drills=@ wet=@ ore=@ belts=@ bridges=@ nodes=@ loads=@ excess=@ in @ms @",
            W, H, dirNames[dir], c.portText(), variant, drill.name, mode, planner.layout, ok, Strings.autoFixed((float)planner.score, 1),
            planner.drills, planner.wetDrills, planner.oreTiles, planner.belts, planner.bridges, planner.nodes, Arrays.toString(planner.sinkLoad),
            Strings.autoFixed((float)planner.excessFlow, 1), Time.timeSinceMillis(time), ok ? "" : planner.failure);
        if(!ok){ c.error = planner.failure; return c; }

        c.waterPerSecond = 0f;
        Seq<BuildPlan> nodes = new Seq<>(), logistics = new Seq<>(), drills = new Seq<>();
        for(Place pl : planner.places){
            BuildPlan bp = c.toPlan(pl);
            c.signature.add(pl.toString());
            switch(pl.kind){
                case DrillLayoutPlanner.NODE: nodes.add(bp); break;
                case DrillLayoutPlanner.DRILL: {
                    Drill d = sn.types[pl.type];
                    drills.add(bp);
                    c.perType[pl.type]++;
                    c.itemsPerSecond += 60f / d.getDrillTime(target) * pl.ore * (pl.dir == 1 ? d.liquidBoostIntensity * d.liquidBoostIntensity : 1f);
                    if(needsPower(d)) c.powerPerSecond += d.consPower.usage * 60f;
                    ConsumeLiquid wb = waterBoost(d);
                    if(pl.dir == 1 && wb != null) c.waterPerSecond += wb.amount * 60f;
                    break;
                }
                default: logistics.add(bp);
            }
        }
        // nodes first (they link to each other as they're built, drills link to them when finished), then logistics, then drills
        c.plans.addAll(nodes).addAll(logistics).addAll(drills);
        c.sinkWorld = new int[planner.sinkList.size()][];
        for(int k = 0; k < c.sinkWorld.length; k++){
            int[] s0 = planner.sinkList.get(k);
            c.sinkWorld[k] = new int[]{f.wx(s0[0], s0[1]), f.wy(s0[0], s0[1])};
        }
        c.loads = planner.sinkLoad.clone();
        c.excessFlow = (float)planner.excessFlow;
        if(waterPorts.size > 0 && planner.waterSkipped) c.waterPorts = new Seq<>();
        return c;
    }

    /**
     * Plans every option the player left to the planner (outputs: one on each side; water: the side
     * opposite the output, then the other sides), then `variants` variants starting at `variantBase`
     * with the best ports. Picked outputs fix the lane direction (the first edge output's side; all
     * four for interior-only outputs). Returns the best by score, or the first failure.
     */
    public Candidate search(Snapshot sn, int variantBase, int variants, Progress progress){
        Candidate best = null, firstFail = null;
        Seq<Object[]> options = new Seq<>(); // {dir, outs}
        if(outs.isEmpty()){
            for(int d = 0; d < 4; d++) options.add(new Object[]{d, Seq.with(Port.edge(d, 0.5f))});
        }else{
            Port firstEdge = outs.find(o -> o.edge);
            if(firstEdge != null) options.add(new Object[]{firstEdge.side, new Seq<>(outs)});
            else for(int d = 0; d < 4; d++) options.add(new Object[]{d, new Seq<>(outs)});
        }
        for(Object[] o : options){
            int dir = (int)o[0];
            @SuppressWarnings("unchecked") Seq<Port> os = (Seq<Port>)o[1];
            Seq<Port> ws = !sn.waterOn ? new Seq<>() : !waters.isEmpty() ? new Seq<>(waters) : Seq.with(Port.edge((dir + 2) % 4, 0.5f));
            Candidate c = plan(sn, dir, os, ws, variantBase, progress);
            c.outAuto = outs.isEmpty(); c.waterAuto = sn.waterOn && waters.isEmpty();
            if(c.ok() && (best == null || c.score() > best.score())) best = c;
            if(!c.ok() && firstFail == null) firstFail = c;
        }
        if(best != null && sn.waterOn && waters.isEmpty()){
            Candidate b = best;
            for(int wd = 0; wd < 4; wd++){
                if(wd == (b.outDir + 2) % 4) continue;
                Candidate c = plan(sn, b.outDir, b.outPorts, Seq.with(Port.edge(wd, 0.5f)), variantBase, progress);
                c.outAuto = b.outAuto; c.waterAuto = true;
                if(c.ok() && c.score() > best.score()) best = c;
            }
        }
        if(best != null){
            Candidate b = best;
            for(int v = variantBase + 1; v < variantBase + variants; v++){
                Candidate c = plan(sn, b.outDir, b.outPorts, b.waterPorts.isEmpty() && sn.waterOn ? Seq.with(Port.edge((b.outDir + 2) % 4, 0.5f)) : b.waterPorts, v, progress);
                c.outAuto = b.outAuto; c.waterAuto = b.waterAuto;
                if(c.ok() && c.score() > best.score()) best = c;
            }
        }
        return best != null ? best : firstFail;
    }

    public void cancel(){
        cancelled = true;
        DrillLayoutPlanner p = current;
        if(p != null) p.cancelled = true;
    }
}
