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

import static mindustry.Vars.*;

/**
 * Game-side wrapper around {@link DrillLayoutPlanner}: snapshots a world region into the
 * planner's local frame, runs it, and converts the result back into world BuildPlans.
 * Used by both the client UI and the server test command.
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
    /** 0=E 1=N 2=W 3=S */
    public int outDir;
    /** port position along the output edge, world order (left->right / bottom->top) */
    public float portFrac = 0.5f;
    public Mode mode = Mode.OUTPUT;
    /** allow item bridges in layout and routing */
    public boolean bridges = true;
    /** water boost: water rows + spine fed from a port on another edge */
    public boolean water;
    /** world side of the water port (0=E 1=N 2=W 3=S), position along it in world order */
    public int waterDir = 2;
    public float waterFrac = 0.5f;
    public Block conduit = Blocks.conduit;

    // ---- results ----
    public final Seq<BuildPlan> plans = new Seq<>();
    public DrillLayoutPlanner planner;
    public int portX, portY;
    public float itemsPerSecond, beltCapacity, powerPerSecond;
    public PowerNode node;
    public ItemBridge bridge;
    int W, H;
    public int waterPortX = -1, waterPortY = -1;
    public float waterPerSecond;
    boolean waterOn;
    public String error;

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
        return n.isVisible() && n.environmentBuildable() && (state.rules.infiniteResources || n.unlockedNow()) ? (PowerNode)n : null;
    }

    /** local (u,v) -> world, for a frame where +u points toward outDir. */
    int wx(int u, int v){
        switch(outDir){
            case 0: return ax + u;
            case 1: return bx - v;
            case 2: return bx - u;
            default: return ax + v;
        }
    }

    int wy(int u, int v){
        switch(outDir){
            case 0: return ay + v;
            case 1: return ay + u;
            case 2: return by - v;
            default: return by - u;
        }
    }

    /** world origin tile of an s x s block whose local footprint min corner is (u,v) */
    int originX(int u, int v, int s){ return Math.min(wx(u, v), wx(u + s - 1, v + s - 1)) + (s - 1) / 2; }

    int originY(int u, int v, int s){ return Math.min(wy(u, v), wy(u + s - 1, v + s - 1)) + (s - 1) / 2; }

    /** world -> local (inverse of wx/wy) */
    int lu(int x, int y){
        switch(outDir){
            case 0: return x - ax;
            case 1: return y - ay;
            case 2: return bx - x;
            default: return by - y;
        }
    }

    int lv(int x, int y){
        switch(outDir){
            case 0: return y - ay;
            case 1: return bx - x;
            case 2: return by - y;
            default: return x - ax;
        }
    }

    /** world tile on the region edge facing `side`, at fraction f in world order */
    int[] edgeTile(int side, float f){
        switch(side){
            case 0: return new int[]{bx, ay + Mathf.round(f * (by - ay))};
            case 1: return new int[]{ax + Mathf.round(f * (bx - ax)), by};
            case 2: return new int[]{ax, ay + Mathf.round(f * (by - ay))};
            default: return new int[]{ax + Mathf.round(f * (bx - ax)), ay};
        }
    }

    /** Synchronous prepare + compute + finish (server test). */
    public boolean run(){
        return prepare() && compute() && finish();
    }

    /** Main thread: snapshot the region into planner input. */
    public boolean prepare(){
        plans.clear();
        error = null;
        planner = null;
        boolean rot = outDir % 2 == 1;
        W = rot ? by - ay + 1 : bx - ax + 1;
        H = rot ? bx - ax + 1 : by - ay + 1;
        int s = drill.size;
        boolean power = needsPower(drill);
        node = power ? powerNode() : null;
        if(power && node == null){ error = drill.localizedName + " needs power, but power nodes aren't available here"; return false; }
        bridge = bridges && available(Blocks.itemBridge) ? (ItemBridge)Blocks.itemBridge : null;

        Params p = new Params();
        p.W = W; p.H = H; p.drillSize = s; p.target = target.id; p.mode = mode; p.power = power;
        p.bridges = bridge != null;
        if(bridge != null) p.bridgeRange = bridge.range;
        p.beltOk = new boolean[W][H]; p.nodeOk = new boolean[W][H]; p.drillOk = new boolean[W][H]; p.drop = new int[W][H];
        p.lowPriority = new boolean[content.items().size];
        for(Item i : content.items()) p.lowPriority[i.id] = i.lowPriority;
        if(node != null){ p.nodeRange = node.laserRange; p.nodeMaxLinks = node.maxNodes; }
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int x = wx(u, v), y = wy(u, v);
            p.beltOk[u][v] = Build.validPlace(belt, team, x, y, 0) && (bridge == null || Build.validPlace(bridge, team, x, y, 0));
            p.nodeOk[u][v] = node != null && Build.validPlace(node, team, x, y, 0);
            Tile t = world.tile(x, y);
            p.drop[u][v] = drill.canMine(t) ? drill.getDrop(t).id : -1;
        }
        for(int u = 0; u + s <= W; u++) for(int v = 0; v + s <= H; v++){
            p.drillOk[u][v] = Build.validPlace(drill, team, originX(u, v, s), originY(u, v, s), 0);
        }
        // slider is in world order; local v runs the other way for N and W
        float f = outDir == 1 || outDir == 2 ? 1f - portFrac : portFrac;
        p.portV = Mathf.round(f * (H - 1));

        ConsumeLiquid boost = waterBoost(drill);
        waterOn = water && boost != null && waterAvailable(conduit) && waterDir != outDir;
        if(water && !waterOn){
            error = boost == null ? drill.localizedName + " can't be boosted with water"
                : waterDir == outDir ? "the water port must be on a different side than the output"
                : "conduits / liquid routers aren't available here";
            return false;
        }
        if(waterOn){
            p.water = true;
            p.waterBoost = drill.liquidBoostIntensity * drill.liquidBoostIntensity;
            p.waterSide = (waterDir - outDir + 4) % 4; // 1 = top (+v), 2 = west (-u), 3 = bottom (-v)
            int[] wt = edgeTile(waterDir, waterFrac);
            p.waterPos = p.waterSide == 2 ? lv(wt[0], wt[1]) : lu(wt[0], wt[1]);
            if(available(Blocks.bridgeConduit)) p.liquidBridgeRange = ((ItemBridge)Blocks.bridgeConduit).range;
            else p.liquidBridgeRange = 0;
            waterPerSecond = boost.amount * 60f;
        }
        planner = new DrillLayoutPlanner(p);
        return true;
    }

    /** Any thread: run the planner. Throws CancelledException if cancelled. */
    public boolean compute(){
        long time = Time.millis();
        boolean ok = planner.plan();
        Log.info("[autobuild] @x@ out=@ @ @ layout=@ ok=@ drills=@ ore=@ belts=@ bridges=@ nodes=@ routed=@ droppedLanes=@ droppedForPower=@ powerGroups=@ in @ms @",
            W, H, dirNames[outDir], drill.name, mode, planner.layout, ok, planner.drills, planner.oreTiles, planner.belts, planner.bridges, planner.nodes,
            planner.segmentsRouted, planner.segmentsDropped, planner.drillsDroppedForPower, planner.powerGroups, Time.timeSinceMillis(time), ok ? "" : planner.failure);
        if(!ok) error = planner.failure;
        return ok;
    }

    /** Main thread: convert the result to world BuildPlans. */
    public boolean finish(){
        plans.clear();
        float perOre = 60f / drill.getDrillTime(target);
        float boostMul = drill.liquidBoostIntensity * drill.liquidBoostIntensity;
        itemsPerSecond = 0f;
        Seq<BuildPlan> nodes = new Seq<>(), logistics = new Seq<>(), drills = new Seq<>();
        for(Place pl : planner.places){
            BuildPlan bp = toPlan(pl);
            switch(pl.kind){
                case DrillLayoutPlanner.NODE: nodes.add(bp); break;
                case DrillLayoutPlanner.DRILL: drills.add(bp); itemsPerSecond += perOre * pl.ore * (pl.dir == 1 ? boostMul : 1f); break;
                default: logistics.add(bp);
            }
        }
        // nodes first (they link to each other as they're built, drills link to them when finished), then logistics, then drills
        plans.addAll(nodes).addAll(logistics).addAll(drills);
        beltCapacity = belt instanceof Conveyor c ? c.displayedSpeed : Float.MAX_VALUE;
        powerPerSecond = node != null ? drill.consPower.usage * 60f * planner.drills : 0f;
        portX = wx(W - 1, planner.port);
        portY = wy(W - 1, planner.port);
        if(waterOn && planner.waterU >= 0){
            waterPortX = wx(planner.waterU, planner.waterV);
            waterPortY = wy(planner.waterU, planner.waterV);
        }else waterPortX = waterPortY = -1;
        return true;
    }

    /** Planner place (local frame) -> world BuildPlan. Pure arithmetic: safe on the planning thread. */
    public BuildPlan toPlan(Place pl){
        int s = drill.size;
        switch(pl.kind){
            case DrillLayoutPlanner.NODE: return new BuildPlan(wx(pl.u, pl.v), wy(pl.u, pl.v), 0, node);
            case DrillLayoutPlanner.BELT: return new BuildPlan(wx(pl.u, pl.v), wy(pl.u, pl.v), (pl.dir + outDir) % 4, belt);
            case DrillLayoutPlanner.CONDUIT: return new BuildPlan(wx(pl.u, pl.v), wy(pl.u, pl.v), (pl.dir + outDir) % 4, conduit);
            case DrillLayoutPlanner.LROUTER: return new BuildPlan(wx(pl.u, pl.v), wy(pl.u, pl.v), 0, Blocks.liquidRouter);
            case DrillLayoutPlanner.LBRIDGE: {
                int x = wx(pl.u, pl.v), y = wy(pl.u, pl.v);
                return new BuildPlan(x, y, 0, Blocks.bridgeConduit, new Point2(wx(pl.lu, pl.lv) - x, wy(pl.lu, pl.lv) - y));
            }
            case DrillLayoutPlanner.BRIDGE: {
                int x = wx(pl.u, pl.v), y = wy(pl.u, pl.v);
                // relative link; (0,0) marks an end bridge and also stops the game's auto-linking
                // (ItemBridge.findLink only links from a previous bridge whose link is -1)
                return new BuildPlan(x, y, 0, bridge, new Point2(wx(pl.lu, pl.lv) - x, wy(pl.lu, pl.lv) - y));
            }
            default: return new BuildPlan(originX(pl.u, pl.v, s), originY(pl.u, pl.v, s), 0, drill);
        }
    }

    static boolean available(Block b){
        return b.isVisible() && b.environmentBuildable() && (state.rules.infiniteResources || b.unlockedNow());
    }

    public String summary(){
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.format("@ drills on @ @ ore tiles, ~@/s", planner.drills, planner.oreTiles, target.localizedName, Strings.autoFixed(itemsPerSecond, 1)));
        sb.append(Strings.format(" [lightgray](@)[]", planner.layout));
        if(waterOn && planner.waterSkipped){
            sb.append("\n[orange]Water skipped: the water network couldn't reach enough drills to beat a layout without water");
        }else if(waterOn){
            sb.append(Strings.format("\n[sky]@/@ drills water-boosted, needs ~@ water/s at the water port", planner.wetDrills, planner.drills,
                Strings.autoFixed(planner.wetDrills * waterPerSecond, 1)));
        }
        if(planner.nodes > 0) sb.append(Strings.format(", @ power nodes, needs @ power/s", planner.nodes, Strings.autoFixed(powerPerSecond, 1)));
        if(itemsPerSecond > beltCapacity) sb.append(Strings.format("\n[orange]Exceeds @ capacity (~@/s): output will back up", belt.localizedName, Strings.autoFixed(beltCapacity, 1)));
        if(planner.segmentsDropped > 0) sb.append("\n[orange]").append(planner.segmentsDropped).append(" lane(s) dropped: no route to the port");
        if(planner.drillsDroppedForPower > 0) sb.append("\n[orange]").append(planner.drillsDroppedForPower).append(" drill(s) dropped to fit power nodes");
        if(planner.powerGroups > 1) sb.append("\n[orange]Power nodes form ").append(planner.powerGroups).append(" separate groups: link them manually");
        if(needsLiquid(drill) && !waterOn) sb.append("\n[orange]").append(drill.localizedName).append(" needs a liquid supply (not planned yet)");
        return sb.toString();
    }
}
