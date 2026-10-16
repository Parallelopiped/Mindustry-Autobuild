package autobuild;

import arc.math.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.*;
import autobuild.plan.DrillLayoutPlanner.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
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

    // ---- results ----
    public final Seq<BuildPlan> plans = new Seq<>();
    public DrillLayoutPlanner planner;
    public int portX, portY;
    public float itemsPerSecond, beltCapacity, powerPerSecond;
    public PowerNode node;
    public String error;

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

    public boolean run(){
        plans.clear();
        error = null;
        boolean rot = outDir % 2 == 1;
        int W = rot ? by - ay + 1 : bx - ax + 1, H = rot ? bx - ax + 1 : by - ay + 1;
        int s = drill.size;
        boolean power = needsPower(drill);
        node = power ? powerNode() : null;
        if(power && node == null){ error = drill.localizedName + " needs power, but power nodes aren't available here"; return false; }

        Params p = new Params();
        p.W = W; p.H = H; p.drillSize = s; p.target = target.id; p.mode = mode; p.power = power;
        p.beltOk = new boolean[W][H]; p.nodeOk = new boolean[W][H]; p.drillOk = new boolean[W][H]; p.drop = new int[W][H];
        p.lowPriority = new boolean[content.items().size];
        for(Item i : content.items()) p.lowPriority[i.id] = i.lowPriority;
        if(node != null){ p.nodeRange = node.laserRange; p.nodeMaxLinks = node.maxNodes; }
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int x = wx(u, v), y = wy(u, v);
            p.beltOk[u][v] = Build.validPlace(belt, team, x, y, 0);
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

        long time = Time.millis();
        planner = new DrillLayoutPlanner(p);
        boolean ok = planner.plan();
        Log.info("[autobuild] @x@ out=@ @ @ ok=@ drills=@ ore=@ nodes=@ routed=@ droppedLanes=@ droppedForPower=@ powerGroups=@ in @ms @",
            W, H, dirNames[outDir], drill.name, mode, ok, planner.drills, planner.oreTiles, planner.nodes, planner.segmentsRouted,
            planner.segmentsDropped, planner.drillsDroppedForPower, planner.powerGroups, Time.timeSinceMillis(time), ok ? "" : planner.failure);
        if(!ok){ error = planner.failure; return false; }

        float perOre = 60f / drill.getDrillTime(target);
        itemsPerSecond = 0f;
        // nodes first (they link to each other as they're built, drills link to them when finished), then belts, then drills
        for(Place pl : planner.places){
            switch(pl.kind){
                case DrillLayoutPlanner.NODE: plans.add(new BuildPlan(wx(pl.u, pl.v), wy(pl.u, pl.v), 0, node)); break;
                case DrillLayoutPlanner.BELT: plans.add(new BuildPlan(wx(pl.u, pl.v), wy(pl.u, pl.v), (pl.dir + outDir) % 4, belt)); break;
                default:
                    plans.add(new BuildPlan(originX(pl.u, pl.v, s), originY(pl.u, pl.v, s), 0, drill));
                    itemsPerSecond += perOre * pl.ore;
            }
        }
        beltCapacity = belt instanceof mindustry.world.blocks.distribution.Conveyor c ? c.displayedSpeed : Float.MAX_VALUE;
        powerPerSecond = power ? drill.consPower.usage * 60f * planner.drills : 0f;
        portX = wx(W - 1, planner.port);
        portY = wy(W - 1, planner.port);
        return true;
    }

    public String summary(){
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.format("@ drills on @ @ ore tiles, ~@/s", planner.drills, planner.oreTiles, target.localizedName, Strings.autoFixed(itemsPerSecond, 1)));
        if(planner.nodes > 0) sb.append(Strings.format(", @ power nodes, needs @ power/s", planner.nodes, Strings.autoFixed(powerPerSecond, 1)));
        if(itemsPerSecond > beltCapacity) sb.append(Strings.format("\n[orange]Exceeds @ capacity (~@/s): output will back up", belt.localizedName, Strings.autoFixed(beltCapacity, 1)));
        if(planner.segmentsDropped > 0) sb.append("\n[orange]").append(planner.segmentsDropped).append(" lane(s) dropped: no route to the port");
        if(planner.drillsDroppedForPower > 0) sb.append("\n[orange]").append(planner.drillsDroppedForPower).append(" drill(s) dropped to fit power nodes");
        if(planner.powerGroups > 1) sb.append("\n[orange]Power nodes form ").append(planner.powerGroups).append(" separate groups: link them manually");
        if(needsLiquid(drill)) sb.append("\n[orange]").append(drill.localizedName).append(" needs a liquid supply (not planned yet)");
        return sb.toString();
    }
}
