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
    /** ports picked by the player: region side (0=E 1=N 2=W 3=S) or -1 = let the planner choose; position along the side in world order */
    public int outPick = -1, waterPick = -1;
    public float outFrac = 0.5f, waterFrac = 0.5f;

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
        /** drill placeable with its footprint's min corner at (rx, ry) */
        boolean[][] drillMin;
        int[][] drop;
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
        sn.power = needsPower(drill);
        sn.node0 = sn.power ? powerNode() : null;
        if(sn.power && sn.node0 == null){ error = drill.localizedName + " needs power, but power nodes aren't available here"; return null; }
        sn.bridge = bridges && available(Blocks.itemBridge) ? (ItemBridge)Blocks.itemBridge : null;
        ConsumeLiquid boost = waterBoost(drill);
        if(water && (boost == null || !waterAvailable(conduit))){
            error = boost == null ? drill.localizedName + " can't be boosted with water" : "conduits / liquid routers aren't available here";
            return null;
        }
        if(water && outPick >= 0 && outPick == waterPick){ error = "the water port must be on a different side than the output"; return null; }
        sn.waterOn = water;
        if(water){
            sn.boost = drill.liquidBoostIntensity * drill.liquidBoostIntensity;
            sn.waterRate = boost.amount * 60f;
            sn.liquidRange = available(Blocks.bridgeConduit) ? ((ItemBridge)Blocks.bridgeConduit).range : 0;
        }
        sn.belt = new boolean[sn.rw][sn.rh]; sn.node = new boolean[sn.rw][sn.rh]; sn.drillMin = new boolean[sn.rw][sn.rh]; sn.drop = new int[sn.rw][sn.rh];
        sn.low = new boolean[content.items().size];
        for(Item i : content.items()) sn.low[i.id] = i.lowPriority;
        int s = sn.s, off = (s - 1) / 2;
        for(int rx = 0; rx < sn.rw; rx++) for(int ry = 0; ry < sn.rh; ry++){
            int x = ax + rx, y = ay + ry;
            sn.belt[rx][ry] = Build.validPlace(belt, team, x, y, 0) && (sn.bridge == null || Build.validPlace(sn.bridge, team, x, y, 0));
            sn.node[rx][ry] = sn.node0 != null && Build.validPlace(sn.node0, team, x, y, 0);
            Tile t = world.tile(x, y);
            sn.drop[rx][ry] = drill.canMine(t) ? drill.getDrop(t).id : -1;
            sn.drillMin[rx][ry] = rx + s <= sn.rw && ry + s <= sn.rh && Build.validPlace(drill, team, x + off, y + off, 0);
        }
        return sn;
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
        public int outDir, waterDir = -1, variant;
        public float outFrac, waterFrac;
        public boolean outAuto, waterAuto;
        public final Seq<BuildPlan> plans = new Seq<>();
        public float itemsPerSecond, beltCapacity, powerPerSecond, waterPerSecond;
        public int portX, portY, waterPortX = -1, waterPortY = -1;
        public PowerNode node;
        public ItemBridge bridge;
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
                default: return new BuildPlan(f.minX(pl.u, pl.v, s) + (s - 1) / 2, f.minY(pl.u, pl.v, s) + (s - 1) / 2, 0, drill);
            }
        }

        public Seq<BuildPlan> toPlans(List<Place> places){
            Seq<BuildPlan> out = new Seq<>(places.size());
            for(Place pl : places) out.add(toPlan(pl));
            return out;
        }

        public String portText(){
            String t = "Output: " + dirNames[outDir] + (outAuto ? " (auto)" : "");
            if(waterDir >= 0) t += ", water from: " + dirNames[waterDir] + (waterAuto ? " (auto)" : "");
            return t;
        }

        public String summary(){
            if(!ok()) return "[scarlet]" + error;
            StringBuilder sb = new StringBuilder();
            sb.append(Strings.format("@ drills on @ @ ore tiles, ~@/s", planner.drills, planner.oreTiles, target.localizedName, Strings.autoFixed(itemsPerSecond, 1)));
            sb.append(Strings.format(" [lightgray](@)[]", planner.layout));
            if(waterDir >= 0 && planner.waterSkipped){
                sb.append("\n[orange]Water skipped: the water network couldn't reach enough drills to beat a layout without water");
            }else if(waterDir >= 0){
                sb.append(Strings.format("\n[sky]@/@ drills water-boosted, needs ~@ water/s at the water port", planner.wetDrills, planner.drills,
                    Strings.autoFixed(planner.wetDrills * waterPerSecond, 1)));
            }
            if(planner.nodes > 0) sb.append(Strings.format("\n@ power nodes, needs @ power/s", planner.nodes, Strings.autoFixed(powerPerSecond, 1)));
            if(itemsPerSecond > beltCapacity) sb.append(Strings.format("\n[orange]Exceeds @ capacity (~@/s): output will back up", belt.localizedName, Strings.autoFixed(beltCapacity, 1)));
            if(planner.segmentsDropped > 0) sb.append("\n[orange]").append(planner.segmentsDropped).append(" lane(s) dropped: no route to the port");
            if(planner.drillsDroppedForPower > 0) sb.append("\n[orange]").append(planner.drillsDroppedForPower).append(" drill(s) dropped to fit power nodes");
            if(planner.powerGroups > 1) sb.append("\n[orange]Power nodes form ").append(planner.powerGroups).append(" separate groups: link them manually");
            if(needsLiquid(drill) && waterDir < 0) sb.append("\n[orange]").append(drill.localizedName).append(" needs a liquid supply (not planned yet)");
            return sb.toString();
        }
    }

    /** Plans one candidate with fixed ports and variant. Any thread. */
    public Candidate plan(Snapshot sn, int outDir, float outFrac, int waterDir, float waterFrac, int variant, Progress progress){
        Candidate c = new Candidate();
        c.outDir = outDir; c.outFrac = outFrac; c.waterDir = waterDir; c.waterFrac = waterFrac; c.variant = variant;
        c.node = sn.node0; c.bridge = sn.bridge;
        Frame f = c.frame = new Frame(ax, ay, bx, by, outDir);
        int W = f.W, H = f.H, s = sn.s;

        Params p = new Params();
        p.W = W; p.H = H; p.drillSize = s; p.target = target.id; p.mode = mode; p.power = sn.power; p.variant = variant;
        p.bridges = sn.bridge != null;
        if(sn.bridge != null) p.bridgeRange = sn.bridge.range;
        if(sn.node0 != null){ p.nodeRange = sn.node0.laserRange; p.nodeMaxLinks = sn.node0.maxNodes; }
        p.lowPriority = sn.low;
        p.beltOk = new boolean[W][H]; p.nodeOk = new boolean[W][H]; p.drillOk = new boolean[W][H]; p.drop = new int[W][H];
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int rx = f.wx(u, v) - ax, ry = f.wy(u, v) - ay;
            p.beltOk[u][v] = sn.belt[rx][ry];
            p.nodeOk[u][v] = sn.node[rx][ry];
            p.drop[u][v] = sn.drop[rx][ry];
        }
        for(int u = 0; u + s <= W; u++) for(int v = 0; v + s <= H; v++){
            p.drillOk[u][v] = sn.drillMin[f.minX(u, v, s) - ax][f.minY(u, v, s) - ay];
        }
        // port position: fraction in world order -> local v (the local v axis runs backwards for N and W)
        float lf = outDir == 1 || outDir == 2 ? 1f - outFrac : outFrac;
        p.portV = Mathf.round(lf * (H - 1));
        if(waterDir >= 0){
            p.water = true;
            p.waterBoost = sn.boost;
            p.waterSide = (waterDir - outDir + 4) % 4; // 1 = top (+v), 2 = west (-u), 3 = bottom (-v)
            int[] wt = edgeTile(waterDir, waterFrac);
            p.waterPos = p.waterSide == 2 ? f.lv(wt[0], wt[1]) : f.lu(wt[0], wt[1]);
            p.liquidBridgeRange = sn.liquidRange;
            c.waterPerSecond = sn.waterRate;
        }

        DrillLayoutPlanner planner = c.planner = current = new DrillLayoutPlanner(p);
        if(cancelled) throw new CancelledException();
        if(progress != null){
            planner.listener = (st, snap) -> progress.frame(c, st, snap);
            planner.snapshotInterval = 4;
        }
        long time = Time.millis();
        boolean ok = planner.plan();
        Log.info("[autobuild] @x@ out=@ water=@ var=@ @ @ layout=@ ok=@ score=@ drills=@ wet=@ ore=@ belts=@ bridges=@ nodes=@ routed=@ dropped=@ in @ms @",
            W, H, dirNames[outDir], waterDir < 0 ? "-" : dirNames[waterDir], variant, drill.name, mode, planner.layout, ok, Strings.autoFixed((float)planner.score, 1),
            planner.drills, planner.wetDrills, planner.oreTiles, planner.belts, planner.bridges, planner.nodes, planner.segmentsRouted, planner.segmentsDropped,
            Time.timeSinceMillis(time), ok ? "" : planner.failure);
        if(!ok){ c.error = planner.failure; return c; }

        float perOre = 60f / drill.getDrillTime(target);
        Seq<BuildPlan> nodes = new Seq<>(), logistics = new Seq<>(), drills = new Seq<>();
        for(Place pl : planner.places){
            BuildPlan bp = c.toPlan(pl);
            c.signature.add(pl.toString());
            switch(pl.kind){
                case DrillLayoutPlanner.NODE: nodes.add(bp); break;
                case DrillLayoutPlanner.DRILL: drills.add(bp); c.itemsPerSecond += perOre * pl.ore * (pl.dir == 1 ? sn.boost : 1f); break;
                default: logistics.add(bp);
            }
        }
        // nodes first (they link to each other as they're built, drills link to them when finished), then logistics, then drills
        c.plans.addAll(nodes).addAll(logistics).addAll(drills);
        c.beltCapacity = belt instanceof Conveyor cv ? cv.displayedSpeed : Float.MAX_VALUE;
        c.powerPerSecond = sn.power ? drill.consPower.usage * 60f * planner.drills : 0f;
        c.portX = f.wx(W - 1, planner.port);
        c.portY = f.wy(W - 1, planner.port);
        if(waterDir >= 0 && planner.waterU >= 0 && !planner.waterSkipped){
            c.waterPortX = f.wx(planner.waterU, planner.waterV);
            c.waterPortY = f.wy(planner.waterU, planner.waterV);
        }
        return c;
    }

    /**
     * Plans every side combination the player left on auto (output: all four sides; water: the
     * side opposite the output first, then the others for the best output side), then
     * `variants` variants starting at `variantBase` with the best ports. Returns the best by score,
     * or the first failure if nothing works.
     */
    public Candidate search(Snapshot sn, int variantBase, int variants, Progress progress){
        Candidate best = null, firstFail = null;
        IntSeq outs = new IntSeq();
        if(outPick >= 0) outs.add(outPick);
        else for(int d = 0; d < 4; d++) if(!(sn.waterOn && d == waterPick)) outs.add(d);
        for(int i = 0; i < outs.size; i++){
            int o = outs.get(i);
            int wd = !sn.waterOn ? -1 : waterPick >= 0 ? waterPick : (o + 2) % 4;
            Candidate c = plan(sn, o, outPick >= 0 ? outFrac : 0.5f, wd, waterPick >= 0 ? waterFrac : 0.5f, variantBase, progress);
            c.outAuto = outPick < 0; c.waterAuto = sn.waterOn && waterPick < 0;
            if(c.ok() && (best == null || c.score() > best.score())) best = c;
            if(!c.ok() && firstFail == null) firstFail = c;
        }
        if(best != null && sn.waterOn && waterPick < 0){
            for(int wd = 0; wd < 4; wd++){
                if(wd == best.outDir || wd == best.waterDir) continue;
                Candidate c = plan(sn, best.outDir, best.outFrac, wd, 0.5f, variantBase, progress);
                c.outAuto = best.outAuto; c.waterAuto = true;
                if(c.ok() && c.score() > best.score()) best = c;
            }
        }
        if(best != null){
            Candidate b = best;
            for(int v = variantBase + 1; v < variantBase + variants; v++){
                Candidate c = plan(sn, b.outDir, b.outFrac, b.waterDir, b.waterFrac, v, progress);
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
