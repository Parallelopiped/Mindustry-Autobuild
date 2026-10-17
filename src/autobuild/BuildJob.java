package autobuild;

import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.consumers.*;

import static mindustry.Vars.*;

/**
 * A kind of facility the player can auto-build in a selected region (drill field, defense...).
 * The UI ({@link AutoBuildMod}) drives every job the same way:
 * <ol>
 * <li>{@link #settings} fills the settings dialog;</li>
 * <li>the player clicks the ports of each {@link #portKinds() kind} (or leaves them to the planner);</li>
 * <li>{@link #prepare()} (main thread) snapshots the region; {@link #search} (planning thread)
 *     returns the best {@link PlanResult}, reporting intermediate states for the gray replay;</li>
 * <li>the result is previewed and committed as build plans.</li>
 * </ol>
 * Also holds the geometry of ports and the rules shared by every job about the player's existing
 * buildings (never plan over them, never mix items with their networks).
 */
public abstract class BuildJob{
    /** region, world tiles, inclusive */
    public int ax, ay, bx, by;
    public Team team = Team.sharded;
    public String error;
    public volatile boolean cancelled;

    /** Receives intermediate planner states (planning thread). */
    public interface Progress{
        void frame(String stage, Seq<BuildPlan> plans);
    }

    public abstract String name();

    /** the port kinds to pick, in order (only the ones the current settings need) */
    public abstract Seq<PortKind> portKinds();

    /** the kind "Add ..." in the preview adds more of, or null (no such button) */
    public PortKind extraKind(){ return null; }

    /** the settings can be planned (else the dialog disables "Pick ports") */
    public abstract boolean ready();

    /** fills the settings dialog; `rebuild` redraws it (after a change that shows / hides options) */
    public abstract void settings(Table t, Runnable rebuild);

    /** main thread: snapshot of the region, or null (and {@link #error} set) */
    public abstract Object prepare();

    /** any thread: plans `variants` variants starting at `variantBase` (and the ports left to the planner); best result */
    public abstract PlanResult search(Object snapshot, int variantBase, int variants, Progress progress);

    public void cancel(){
        cancelled = true;
    }

    /** a new region was selected */
    public void regionChanged(){}

    /** extra world overlay while picking ports / planning / previewing (`shown` may be null) */
    public void drawOverlay(PlanResult shown){}

    public void setRegion(int ax, int ay, int bx, int by){
        this.ax = ax; this.ay = ay; this.bx = bx; this.by = by;
        regionChanged();
    }

    public boolean inRegion(int x, int y){ return x >= ax && x <= bx && y >= ay && y <= by; }

    // ------------------------------------------------------------------ ports

    /**
     * A click: one of our buildings (if it can serve as the port, see {@link PortKind#check}).
     * Inside the region (a tile away from its border): an interior port. Otherwise snapped to the
     * nearest edge.
     */
    public Port classify(int tx, int ty){
        Tile t = world.tile(tx, ty);
        if(t != null && t.build != null && t.build.team == team){
            Port p = Port.at(t.build.tile.x, t.build.tile.y);
            p.existing = t.build;
            return p;
        }
        if(tx > ax && tx < bx && ty > ay && ty < by) return Port.at(tx, ty);
        float[] s = snap(tx, ty);
        return Port.edge((int)s[0], s[1]);
    }

    /** the tile a port's marker sits on: the edge tile, or its own tile */
    public int[] portTile(Port p){
        return p.edge ? edgeTile(p.side, p.frac) : new int[]{p.x, p.y};
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

    /** existing building as an item output: it must take items */
    public static String itemSinkError(Building b){
        Block k = b.block;
        if(!k.acceptsItems || !k.hasItems) return k.localizedName + " can't take items";
        if(k instanceof ItemBridge ib && !(k instanceof LiquidBridge) && !bridgeLinked(ib, b))
            return "an unlinked " + k.localizedName + " only takes items from other bridges";
        return null;
    }

    /** existing building as a liquid input: it must give out liquid */
    public static String liquidSourceError(Building b){
        if(!b.block.hasLiquids || !b.block.outputsLiquid) return b.block.localizedName + " doesn't give out liquid";
        return null;
    }

    // ------------------------------------------------------------------ blocks

    public static boolean available(Block b){
        return b.isVisible() && b.environmentBuildable() && (state.rules.infiniteResources || b.unlockedNow());
    }

    public static boolean needsPower(Block b){
        return b.consPower != null;
    }

    /** Required (non-optional) liquid input. */
    public static boolean needsLiquid(Block b){
        for(Consume c : b.nonOptionalConsumers) if(c instanceof ConsumeLiquidBase) return true;
        return false;
    }

    /** Power node usable in the current environment, or null. */
    public static PowerNode powerNode(){
        Block n = Blocks.powerNode;
        return available(n) ? (PowerNode)n : null;
    }

    /**
     * Nothing built on the tile. Build.validPlace alone also accepts tiles whose block may be
     * *replaced* (Block.canReplace: a belt over a belt, a better drill over a drill, ...), which
     * would plan over the player's existing structures.
     */
    public static boolean empty(int x, int y){
        Tile t = world.tile(x, y);
        return t != null && t.build == null && t.block() == Blocks.air;
    }

    public static boolean footprintEmpty(int x, int y, int size){
        for(int i = 0; i < size; i++) for(int j = 0; j < size; j++) if(!empty(x + i, y + j)) return false;
        return true;
    }

    /** a block of this size can stand with its centre tile at (x,y): validPlace on empty tiles */
    public boolean fits(Block b, int x, int y){
        int off = (b.size - 1) / 2;
        return footprintEmpty(x - off, y - off, b.size) && Build.validPlace(b, team, x, y, 0);
    }

    /** a plan can be built as is: placeable and not replacing anything */
    public static boolean placeable(BuildPlan p, Team team){
        int off = (p.block.size - 1) / 2;
        return footprintEmpty(p.x - off, p.y - off, p.block.size) && Build.validPlace(p.block, team, p.x, p.y, p.rotation);
    }

    // ------------------------------------------------------------------ the player's existing networks

    /**
     * Existing buildings picked as ports: items may go into / come from them, so the rules below
     * treat them as part of the plan.
     */
    public final ObjectSet<Building> portBuildings = new ObjectSet<>();

    /**
     * Would an existing building push items into a block at (x,y)? Drills, routers, bridges,
     * crafters... dump into any neighbour; a conveyor only into the tile in front of it. Storage
     * (cores, vaults, containers) never pushes.
     */
    public boolean fedByExisting(int x, int y){
        for(int d = 0; d < 4; d++){
            Tile n = world.tile(x + Geometry.d4x(d), y + Geometry.d4y(d));
            if(n == null || n.build == null || !n.block().hasItems || n.block() instanceof StorageBlock) continue;
            // a port building's neighbours are handled as approach tiles (except a conveyor's front)
            if(portBuildings.contains(n.build) && !(n.block() instanceof Conveyor || n.block() instanceof Duct)){
                if(n.block() instanceof ItemBridge ib && bridgeLinked(ib, n.build) && n.build.relativeTo(x, y) == linkDir(n.build)) return true; // its link side refuses items
                continue;
            }
            if(n.block() instanceof Conveyor || n.block() instanceof Duct){
                Tile front = n.build.front() == null ? null : n.build.front().tile;
                if(front != null && front.x == x && front.y == y) return true;
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Would a block of ours at this footprint that gives items to its neighbours (a drill, a router)
     * give them to an existing logistics block (belt, bridge, router...) next to it? Then part of its
     * output would leak into the player's network. Storage is fine (delivering there is harmless).
     */
    public boolean touchesExistingLogistics(int x, int y, int size){
        for(int i = -1; i <= size; i++) for(int j = -1; j <= size; j++){
            boolean edge = (i == -1 || i == size) != (j == -1 || j == size);
            if(!edge) continue;
            Tile n = world.tile(x + i, y + j);
            if(n != null && n.build != null && n.block().hasItems && n.block().acceptsItems && !(n.block() instanceof StorageBlock)
                && !portBuildings.contains(n.build)) return true; // next to a port building is fine
        }
        return false;
    }

    /** a block must not sit in front of an existing conveyor (that conveyor would stall or feed it) */
    public static boolean coversConveyorFront(int x, int y, int size){
        for(int i = 0; i < size; i++) for(int j = 0; j < size; j++){
            for(int d = 0; d < 4; d++){
                Tile n = world.tile(x + i + Geometry.d4x(d), y + j + Geometry.d4y(d));
                if(n == null || n.build == null || !(n.block() instanceof Conveyor || n.block() instanceof Duct)) continue;
                Building f = n.build.front();
                if(f != null && f.tile.x == x + i && f.tile.y == y + j) return true;
                if(f == null && n.build.tile.x + Geometry.d4x(n.build.rotation) == x + i && n.build.tile.y + Geometry.d4y(n.build.rotation) == y + j) return true;
            }
        }
        return false;
    }

    /** next to a port building that pushes items into its neighbours (routers, sorters, end bridges...) */
    public boolean pushedByPort(int x, int y){
        for(int d = 0; d < 4; d++){
            Tile n = world.tile(x + Geometry.d4x(d), y + Geometry.d4y(d));
            if(n == null || n.build == null || !portBuildings.contains(n.build)) continue;
            Block b = n.block();
            if(b instanceof Conveyor || b instanceof Duct || b instanceof StorageBlock) continue;
            if(b instanceof ItemBridge ib && bridgeLinked(ib, n.build)) continue; // a linked bridge only sends along its link
            return true;
        }
        return false;
    }

    public static boolean bridgeLinked(ItemBridge ib, Building b){
        return ib.linkValid(b.tile, world.tile(((ItemBridge.ItemBridgeBuild)b).link));
    }

    public static int linkDir(Building b){
        Tile l = world.tile(((ItemBridge.ItemBridgeBuild)b).link);
        return l == null ? -1 : b.relativeTo(l.x, l.y);
    }
}
