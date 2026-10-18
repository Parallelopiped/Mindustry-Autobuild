package autobuild;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.*;
import autobuild.plan.DefensePlanner.*;
import autobuild.plan.DrillLayoutPlanner.CancelledException;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.entities.bullet.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.consumers.*;
import mindustry.world.meta.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Defense: game-side wrapper around {@link DefensePlanner}.
 * <ul>
 * <li>Settings: turrets (multi-select), ammo items, a liquid (liquid ammo / coolant), wall
 *     material and thickness, menders / mend projectors / force projectors / shock mines,
 *     logistics blocks, route clearance, turret budget.</li>
 * <li>Threat: the coming waves (Rules.spawns) give the ground / air share by health and the
 *     typical ground enemy range.</li>
 * <li>Routes: the game's ground path cost of every map tile (Pathfinder.costTypes on the packed
 *     tile), the spawns, and the cores the wave team walks to - the planner rebuilds the same flow
 *     field. Flying units: a straight line from where they appear to the nearest core.</li>
 * <li>Ports: one input kind per ammo item, the liquid, power.</li>
 * </ul>
 */
public class DefenseJob extends BuildJob{
    // ---- settings ----
    public final Seq<Turret> turrets = new Seq<>();
    public final Seq<Item> ammo = new Seq<>();
    public Liquid liquid;
    /** feed the liquid to turrets as coolant where it pays */
    public boolean coolant = true;
    /** small wall of the chosen material (its large version is found by name), or null */
    public Wall wall;
    public int wallThickness = 1;
    public boolean mender = true, mendProjector, forceProjector, mines;
    public Block belt, conduit;
    public boolean bridges = true;
    public int clearance = 1;

    // ---- threat (updated with the region) ----
    public float groundShare = 1f, airShare = 0.3f, enemyRange = 7f;
    public String threatText = "";
    /** ground routes (map tile indices) and flying lines, for the overlay before planning */
    public final Seq<int[]> previewRoutes = new Seq<>();
    public final Seq<float[]> previewAir = new Seq<>();

    // ---- ports ----
    final ObjectMap<Item, PortKind> ammoKinds = new ObjectMap<>();
    final ObjectMap<Liquid, PortKind> liquidKinds = new ObjectMap<>();
    public final PortKind powerKind = new PortKind("Power input", "power inputs", Pal.powerLight, true,
        "a power node is placed there: link it to your grid", "a power node is placed there: link it to your grid (or click your node / battery / generator)", DefenseJob::powerSourceError).anywhere();

    volatile DefensePlanner.Ctl current;

    boolean initialized;

    /** defaults, once content is loaded (the mod is constructed before it) */
    void defaults(){
        if(initialized) return;
        initialized = true;
        turrets.add((Turret)Blocks.duo, (Turret)Blocks.hail);
        ammo.add(Items.graphite);
        liquid = Liquids.water;
        wall = (Wall)Blocks.titaniumWall;
        belt = Blocks.conveyor;
        conduit = Blocks.conduit;
    }

    @Override
    public String name(){ return "Defense"; }

    public PortKind ammoKind(Item item){
        return ammoKinds.get(item, () -> new PortKind(item.localizedName + " input", item.localizedName + " inputs", item.color, true,
            "your belt brings " + item.localizedName + " in there", "a tile your belt delivers into (or click your core / container / conveyor)", DefenseJob::itemSourceError).anywhere());
    }

    public PortKind liquidKind(){
        return liquidKinds.get(liquid, () -> new PortKind(liquid.localizedName + " input", liquid.localizedName + " inputs", liquid.color, true,
            "connect your pipe from outside", "a tile your pipe delivers into (or click your tank / conduit / pump)", BuildJob::liquidSourceError).anywhere());
    }

    /** ammo items actually used by a selected turret */
    public Seq<Item> usedAmmo(){
        return ammo.select(i -> turrets.contains(t -> t instanceof ItemTurret it && it.ammoTypes.containsKey(i)));
    }

    public boolean liquidUsed(){
        if(liquid == null) return false;
        for(Turret t : turrets){
            if(t instanceof LiquidTurret lt && lt.ammoTypes.containsKey(liquid)) return true;
            if(t instanceof LaserTurret && coolantOk(liquid)) return true;
            if(coolant && t.coolant != null && coolantOk(liquid)) return true;
        }
        return false;
    }

    public boolean powerUsed(){
        if(mender || mendProjector || forceProjector) return true;
        return turrets.contains(BuildJob::needsPower);
    }

    @Override
    public Seq<PortKind> portKinds(){
        Seq<PortKind> out = new Seq<>();
        for(Item i : usedAmmo()) out.add(ammoKind(i));
        if(liquidUsed()) out.add(liquidKind());
        if(powerUsed()) out.add(powerKind);
        return out;
    }

    /** why the selection can't be planned, or null */
    public String problem(){
        if(turrets.isEmpty()) return "select at least one turret";
        for(Turret t : turrets){
            if(t instanceof ItemTurret it && !ammo.contains(i -> it.ammoTypes.containsKey(i))) return t.localizedName + ": select an ammo item it takes";
            if(t instanceof LiquidTurret lt && (liquid == null || !lt.ammoTypes.containsKey(liquid))) return t.localizedName + ": choose a liquid it shoots";
            if(t instanceof LaserTurret && (liquid == null || !coolantOk(liquid))) return t.localizedName + " needs a coolant (water / cryofluid)";
        }
        if(belt == null || !available(belt)) return "no conveyor available";
        if(powerUsed() && powerNode() == null) return "power nodes aren't available here";
        int area = (bx - ax + 1) * (by - ay + 1);
        if(area > DefensePlanner.MAX_AREA) return "region too large (" + area + " tiles, at most " + DefensePlanner.MAX_AREA + "): it is filled completely - select a strip along the route";
        return null;
    }

    @Override
    public boolean ready(){ return problem() == null; }

    // ------------------------------------------------------------------ port checks

    /** existing building as an ammo source: it gives items out, or it's storage (an unloader takes them) */
    public static String itemSourceError(Building b){
        Block k = b.block;
        if(k instanceof StorageBlock) return available(Blocks.unloader) ? null : "unloaders aren't available to take items from " + k.localizedName;
        if(!k.hasItems || !k.outputsItems()) return k.localizedName + " doesn't give out items";
        if(k instanceof ItemBridge ib && bridgeLinked(ib, b)) return "a linked bridge passes its items on: click its end";
        return null;
    }

    public static String powerSourceError(Building b){
        Block k = b.block;
        if(!k.hasPower || !k.connectedPower) return k.localizedName + " isn't part of a power grid";
        if(!(k instanceof PowerNode || k instanceof Battery || k.outputsPower)) return k.localizedName + " only uses power: click a node, battery or generator";
        return null;
    }

    static boolean coolantOk(Liquid l){
        return l != null && l.coolant && !l.gas && l.temperature <= 0.5f && l.flammability < 0.1f;
    }

    // ------------------------------------------------------------------ turret stats

    /** the bullet a turret fires with this ammo (null = it doesn't take it) */
    static BulletType bullet(Turret t, Item item){
        if(t instanceof ItemTurret it) return item == null ? null : it.ammoTypes.get(item);
        if(t instanceof PowerTurret pt) return pt.shootType;
        if(t instanceof ContinuousTurret ct) return ct.shootType;
        return null;
    }

    static float reload(Turret t){
        return Math.max(1f, t.reload);
    }

    /** estimated DPS (the game's own estimate: shots / reload x bullet damage) */
    static float dps(Turret t, BulletType b){
        if(b == null) return 0f;
        if(t instanceof ContinuousTurret) return b.damage * 60f / (b instanceof ContinuousBulletType c ? c.damageInterval : 5f);
        if(t instanceof LaserTurret lt){
            float interval = b instanceof ContinuousBulletType c ? c.damageInterval : 5f;
            // fires for shootDuration, then reloads
            return b.damage * 60f / interval * lt.shootDuration / (lt.shootDuration + lt.reload);
        }
        return t.shoot.shots / reload(t) * 60f * b.estimateDPS() * b.reloadMultiplier;
    }

    static float coolantBoost(Turret t, Liquid l){
        if(t.coolant == null || !coolantOk(l) || t instanceof ContinuousTurret || t instanceof LaserTurret) return 1f;
        return 1f + t.coolant.amount * l.heatCapacity * t.coolantMultiplier;
    }

    static BulletType liquidBullet(Turret t, Liquid l){
        return t instanceof LiquidTurret lt && l != null ? lt.ammoTypes.get(l) : null;
    }

    /** a turret's description for the dialog */
    static String tags(Turret t){
        StringBuilder sb = new StringBuilder();
        sb.append(t.size).append("x").append(t.size).append(" r").append(Strings.autoFixed(t.range / tilesize, 0));
        if(!t.targetAir) sb.append(" [lightgray]ground");
        else if(!t.targetGround) sb.append(" [sky]air");
        if(needsPower(t)) sb.append(" [accent]⚡");
        if(t instanceof LiquidTurret || t instanceof LaserTurret) sb.append(" [sky]liquid");
        return sb.toString();
    }

    public static Seq<Turret> availableTurrets(){
        Seq<Turret> out = content.blocks().select(b -> (b instanceof ItemTurret || b instanceof PowerTurret || b instanceof LiquidTurret || b instanceof ContinuousTurret)
            && available(b)).map(b -> (Turret)b);
        out.sort(t -> t.size * 1000 + t.buildTime / 100f);
        return out;
    }

    /** the large version of a wall ("copper-wall" -> "copper-wall-large") */
    static Wall largeWall(Wall w){
        Block b = content.block(w.name + "-large");
        return b instanceof Wall lw && available(lw) ? lw : null;
    }

    // ------------------------------------------------------------------ threat and routes

    @Override
    public void regionChanged(){
        defaults();
        analyzeWaves();
        previewRoutes.clear();
        previewAir.clear();
        if(!state.isGame()) return;
        MapData m = mapData();
        EnemyRoutes r = new EnemyRoutes(world.width(), world.height(), m.cost, m.nearSolid);
        r.flow(m.targets);
        for(int s : m.spawns){
            int[] t = r.trace(s);
            if(t.length > 1) previewRoutes.add(t);
        }
        previewAir.addAll(m.air);
    }

    /** fraction along the route (20%..85%) with the most buildable ground in a size x size window around it (tests) */
    public float openStretch(int[] route, int size){
        int w = world.width(), best = -1;
        float at = 0.5f;
        for(float f = 0.2f; f <= 0.85f; f += 0.025f){
            int m = route[(int)(route.length * f)], open = 0;
            for(int x = m % w - size / 2; x < m % w + size / 2; x++) for(int y = m / w - size / 2; y < m / w + size / 2; y++){
                Tile t = world.tile(x, y);
                if(t != null && !t.solid() && !t.floor().isDeep() && t.build == null) open++;
            }
            if(open > best){ best = open; at = f; }
        }
        return at;
    }

    /** selects a size x size region centred on the route at fraction `at` (tests) */
    public void regionOnRoute(int[] route, float at, int size){
        int w = world.width(), mid = route[(int)(route.length * at)];
        int x0 = Math.max(0, Math.min(w - size, mid % w - size / 2)), y0 = Math.max(0, Math.min(world.height() - size, mid / w - size / 2));
        setRegion(x0, y0, x0 + size - 1, y0 + size - 1);
    }

    /** ground / air share of the coming waves by health, typical ground weapon range */
    void analyzeWaves(){
        double ground = 0, air = 0, rangeSum = 0;
        int from = Math.max(0, state.wave - 1), to = from + 20;
        for(SpawnGroup g : state.rules.spawns){
            if(g.type == null) continue;
            for(int w = from; w < to; w++){
                int n = g.getSpawned(w);
                if(n <= 0) continue;
                double hp = n * (g.type.health + g.getShield(w));
                if(g.type.flying) air += hp;
                else{ ground += hp; rangeSum += hp * g.type.maxRange / tilesize; }
            }
        }
        if(ground + air <= 0){
            groundShare = 1f; airShare = 0.3f; enemyRange = 7f;
            threatText = "[lightgray]No wave data: assuming mostly ground enemies";
            return;
        }
        groundShare = (float)(ground / (ground + air));
        airShare = (float)(air / (ground + air));
        enemyRange = ground > 0 ? (float)(rangeSum / ground) : 7f;
        threatText = Strings.format("[lightgray]Next 20 waves: [white]@%[] ground, [sky]@%[] air (by health); ground enemies shoot ~@ tiles",
            Mathf.round(groundShare * 100), Mathf.round(airShare * 100), Strings.autoFixed(enemyRange, 1));
    }

    /** whole-map data for the route model */
    static class MapData{
        int[] cost, targets, spawns;
        boolean[] nearSolid;
        final Seq<float[]> air = new Seq<>();
    }

    MapData mapData(){
        MapData m = new MapData();
        int w = world.width(), h = world.height();
        Team enemy = state.rules.waveTeam;
        Pathfinder.PathCost cost = Pathfinder.costTypes.get(Pathfinder.costGround);
        m.cost = new int[w * h];
        m.nearSolid = new boolean[w * h];
        for(int i = 0; i < w * h; i++){
            Tile t = world.tiles.geti(i);
            int packed = pathfinder.packTile(t);
            m.cost[i] = cost.getCost(enemy.id, packed);
            m.nearSolid[i] = PathTile.nearSolid(packed);
        }
        IntSeq targets = new IntSeq(), spawns = new IntSeq();
        for(Building b : indexer.getEnemy(enemy, BlockFlag.core)) targets.add(b.tile.array());
        for(Tile t : spawner.getSpawns()) spawns.add(t.array());
        if(state.rules.attackMode && state.teams.isActive(enemy)) for(Building c : enemy.cores()) spawns.add(c.tile.array());
        m.targets = targets.toArray();
        m.spawns = spawns.toArray();
        // flying units: from where they appear (map edge beyond the spawn, unless airUseSpawns) to the nearest core
        for(int s : m.spawns){
            float sx = s % w, sy = s / w;
            if(!state.rules.airUseSpawns){
                float angle = Angles.angle(w / 2f, h / 2f, sx, sy), trns = Math.max(w, h) * Mathf.sqrt2;
                sx = Mathf.clamp(w / 2f + Angles.trnsx(angle, trns), 0, w - 1);
                sy = Mathf.clamp(h / 2f + Angles.trnsy(angle, trns), 0, h - 1);
            }
            float bx = -1, by = -1, bd = Float.MAX_VALUE;
            for(int t : m.targets){
                float d = Mathf.dst(sx, sy, t % w, t / w);
                if(d < bd){ bd = d; bx = t % w; by = t / w; }
            }
            if(bx >= 0) m.air.add(new float[]{sx, sy, bx, by});
        }
        return m;
    }

    // ------------------------------------------------------------------ settings dialog

    @Override
    public void settings(Table c, Runnable rebuild){
        Seq<Turret> all = availableTurrets();
        turrets.retainAll(all::contains);
        c.add(threatText).row();
        if(previewRoutes.isEmpty()) c.add("[orange]No ground route found (no enemy spawn, or no core of yours): only flying units are considered").row();

        c.add("Turrets:").row();
        c.table(t -> {
            int i = 0;
            for(Turret tu : all){
                t.button(b -> {
                    b.image(tu.uiIcon).size(24f).padRight(4f);
                    b.add(tu.localizedName + " [lightgray]" + tags(tu)).growX();
                }, Styles.flatTogglet, () -> {
                    if(turrets.contains(tu)) turrets.remove(tu);
                    else{
                        turrets.add(tu);
                        // give it an ammo it can use
                        if(tu instanceof ItemTurret it && !ammo.contains(a -> it.ammoTypes.containsKey(a))){
                            Item best = null;
                            float bd = -1;
                            for(var e : it.ammoTypes){
                                float d = dps(tu, e.value);
                                if(e.key.unlockedNow() && d > bd){ bd = d; best = e.key; }
                            }
                            if(best != null) ammo.add(best);
                        }
                    }
                    rebuild.run();
                }).checked(b -> turrets.contains(tu)).size(250f, 40f);
                if(++i % 3 == 0) t.row();
            }
        }).row();

        // ammo items any selected item turret takes
        ObjectSet<Item> takes = new ObjectSet<>();
        for(Turret tu : turrets) if(tu instanceof ItemTurret it) for(var e : it.ammoTypes) takes.add(e.key);
        if(!takes.isEmpty()){
            c.add("Ammo (each is brought in by its own belt):").row();
            c.table(t -> {
                Seq<Item> items = content.items().select(takes::contains);
                int i = 0;
                for(Item it : items){
                    t.button(b -> {
                        b.image(it.uiIcon).size(24f).padRight(4f);
                        b.add(it.localizedName + " [lightgray]" + ammoUsers(it));
                    }, Styles.flatTogglet, () -> {
                        if(ammo.contains(it)) ammo.remove(it); else ammo.add(it);
                        rebuild.run();
                    }).checked(b -> ammo.contains(it)).size(250f, 40f);
                    if(++i % 3 == 0) t.row();
                }
            }).row();
        }

        c.table(t -> {
            t.add("Liquid: ").padRight(6f);
            for(Liquid l : Seq.with(Liquids.water, Liquids.cryofluid, Liquids.slag, Liquids.oil)){
                if(!l.unlockedNow() && !state.rules.infiniteResources) continue;
                t.button(b -> b.image(l.uiIcon).size(24f), Styles.flatTogglet, () -> { liquid = l; rebuild.run(); })
                    .checked(b -> liquid == l).size(44f, 40f).tooltip(l.localizedName);
            }
            t.check("Coolant for turrets", coolant, b -> { coolant = b; rebuild.run(); }).padLeft(10f).disabled(b -> !coolantOk(liquid));
        }).row();

        c.table(t -> {
            t.add("Walls: ").padRight(6f);
            t.button("None", Styles.flatTogglet, () -> { wall = null; rebuild.run(); }).checked(b -> wall == null).size(80f, 40f);
            for(Block b : content.blocks()){
                if(!(b instanceof Wall w) || b instanceof Door || w.size != 1 || !available(w) || largeWall(w) == null || w.name.contains("scrap")) continue;
                t.button(bt -> bt.image(w.uiIcon).size(24f), Styles.flatTogglet, () -> { wall = w; rebuild.run(); })
                    .checked(bt -> wall == w).size(44f, 40f).tooltip(w.localizedName);
            }
            t.add("  thickness ");
            for(int k = 1; k <= 2; k++){
                int kk = k;
                t.button("" + k, Styles.flatTogglet, () -> wallThickness = kk).checked(b -> wallThickness == kk).disabled(b -> wall == null).size(40f, 40f);
            }
        }).row();

        c.table(t -> {
            t.add("Support: ").padRight(6f);
            t.check("Menders", mender, b -> { mender = b; rebuild.run(); }).disabled(b -> !available(Blocks.mender)).padRight(8f);
            t.check("Mend projectors", mendProjector, b -> { mendProjector = b; rebuild.run(); }).disabled(b -> !available(Blocks.mendProjector)).padRight(8f);
            t.check("Force projectors", forceProjector, b -> { forceProjector = b; rebuild.run(); }).disabled(b -> !available(Blocks.forceProjector)).padRight(8f);
            t.check("Shock mines on the route", mines, b -> mines = b).disabled(b -> !available(Blocks.shockMine));
        }).row();

        c.table(t -> {
            t.add("Belts: ").padRight(6f);
            for(Block b : Seq.with(Blocks.conveyor, Blocks.titaniumConveyor).select(BuildJob::available)){
                t.button(bt -> bt.image(b.uiIcon).size(24f), Styles.flatTogglet, () -> belt = b).checked(bt -> belt == b).size(44f, 40f).tooltip(b.localizedName);
            }
            t.check("Bridges", bridges, b -> bridges = b).padLeft(8f).disabled(b -> !available(Blocks.itemBridge));
            t.add("   Pipes: ").padRight(6f);
            for(Block b : Seq.with(Blocks.conduit, Blocks.pulseConduit).select(BuildJob::available)){
                t.button(bt -> bt.image(b.uiIcon).size(24f), Styles.flatTogglet, () -> conduit = b).checked(bt -> conduit == b).size(44f, 40f).tooltip(b.localizedName);
            }
        }).row();

        c.table(t -> {
            t.add("Keep free around the route: ");
            for(int k = 1; k <= 3; k++){
                int kk = k;
                t.button(k + (k == 1 ? " tile" : " tiles"), Styles.flatTogglet, () -> clearance = kk).checked(b -> clearance == kk).size(84f, 40f);
            }
        }).row();
        c.label(() -> problem() == null ? "[lightgray]The region is filled completely (rows of walls, turrets and supply lanes on the side of the route with most room). Next you click where each ammo / liquid / power comes in - anywhere on the map; right-click lets the planner choose (just behind the region)."
            : "[scarlet]" + problem()).row();
    }

    String ammoUsers(Item item){
        int n = 0;
        for(Turret t : turrets) if(t instanceof ItemTurret it && it.ammoTypes.containsKey(item)) n++;
        return n == 0 ? "" : "(" + n + ")";
    }

    // ------------------------------------------------------------------ snapshot

    /** Everything the planner needs, captured on the main thread. */
    public static class Snapshot{
        DefensePlanner.Params base;
        /** per planner type: the block, the ammo item (or null) */
        Block[] typeBlock;
        /** per network: its port kind, item (or null for the liquid) */
        Seq<PortKind> netKinds = new Seq<>();
        Seq<Item> netItems = new Seq<>();
        /** per network: ports the player picked (empty = auto) */
        Seq<Seq<Port>> netPorts = new Seq<>();
        Seq<Port> powerPorts = new Seq<>();
        MapData map;
        /** grid origin (map tiles) */
        int gx, gy;
        Block node, belt, bridge, conduit, liquidBridge;
        Liquid liquid;
    }

    @Override
    public Snapshot prepare(){
        error = problem();
        cancelled = false;
        if(error != null) return null;
        Snapshot sn = new Snapshot();
        sn.map = mapData();
        if(sn.map.targets.length == 0){ error = "your team has no core: enemies have nowhere to go"; return null; }
        sn.node = powerNode();
        sn.belt = belt;
        sn.bridge = bridges && available(Blocks.itemBridge) ? Blocks.itemBridge : null;
        sn.conduit = conduit;
        sn.liquidBridge = available(Blocks.bridgeConduit) ? Blocks.bridgeConduit : null;
        sn.liquid = liquid;
        // the grid: the region, the ports picked anywhere on the map, and a margin for the trunks
        int margin = 8, gx0 = ax - margin, gy0 = ay - margin, gx1 = bx + margin, gy1 = by + margin;
        for(PortKind k : portKinds()) for(Port pt : k.picks){
            int[] t = portTile(pt);
            gx0 = Math.min(gx0, t[0] - 3); gy0 = Math.min(gy0, t[1] - 3); gx1 = Math.max(gx1, t[0] + 3); gy1 = Math.max(gy1, t[1] + 3);
        }
        gx0 = Math.max(0, gx0); gy0 = Math.max(0, gy0); gx1 = Math.min(world.width() - 1, gx1); gy1 = Math.min(world.height() - 1, gy1);
        int W = gx1 - gx0 + 1, H = gy1 - gy0 + 1;
        sn.gx = gx0; sn.gy = gy0;
        Params p = sn.base = new Params();
        p.W = W; p.H = H;
        p.mapW = world.width(); p.mapH = world.height(); p.ox = gx0; p.oy = gy0;
        p.rx0 = ax - gx0; p.ry0 = ay - gy0; p.rw = bx - ax + 1; p.rh = by - ay + 1;
        p.pathCost = sn.map.cost; p.nearSolid = sn.map.nearSolid; p.targets = sn.map.targets; p.groundSpawns = sn.map.spawns;
        p.airLines = sn.map.air.toArray(float[].class);
        p.groundWeight = groundShare; p.airWeight = airShare; p.enemyRange = enemyRange;
        p.clearance = clearance; p.wallThickness = wall == null ? 0 : wallThickness; p.mines = mines && available(Blocks.shockMine);

        // ports: every existing building picked is part of the plan's networks
        portBuildings.clear();
        for(PortKind k : portKinds()) for(Port pt : k.picks) if(pt.existing != null) portBuildings.add(pt.existing);

        // networks: one per used ammo item, then the liquid
        Seq<Item> items = usedAmmo();
        for(Item i : items){ sn.netKinds.add(ammoKind(i)); sn.netItems.add(i); sn.netPorts.add(new Seq<>(ammoKind(i).picks)); }
        boolean liquidOn = liquidUsed();
        if(liquidOn){ sn.netKinds.add(liquidKind()); sn.netItems.add((Item)null); sn.netPorts.add(new Seq<>(liquidKind().picks)); }
        sn.powerPorts.set(powerKind.picks);

        // building types
        Seq<Type> types = new Seq<>();
        Seq<Block> blocks = new Seq<>();
        for(Turret t : turrets){
            if(t instanceof ItemTurret it){
                for(int k = 0; k < items.size; k++){
                    BulletType b = it.ammoTypes.get(items.get(k));
                    if(b == null) continue;
                    Type ty = turretType(t, b, liquidOn);
                    ty.ammoNet = k;
                    ty.name = t.localizedName + " (" + items.get(k).localizedName + ")";
                    types.add(ty); blocks.add(t);
                }
            }else if(t instanceof LiquidTurret){
                BulletType b = liquidBullet(t, liquid);
                if(b == null) continue;
                Type ty = turretType(t, b, false);
                ty.liquidAmmo = true;
                types.add(ty); blocks.add(t);
            }else{
                Type ty = turretType(t, bullet(t, null), liquidOn);
                if(t instanceof LaserTurret){ ty.coolantRequired = true; ty.coolantBoost = 1; }
                types.add(ty); blocks.add(t);
            }
        }
        if(wall != null){
            types.add(wallType(wall)); blocks.add(wall);
            Wall lw = largeWall(wall);
            if(lw != null){ types.add(wallType(lw)); blocks.add(lw); }
        }
        if(mender && available(Blocks.mender)){ types.add(protector((MendProjector)Blocks.mender, DefensePlanner.MENDER, 1)); blocks.add(Blocks.mender); }
        if(mendProjector && available(Blocks.mendProjector)){ types.add(protector((MendProjector)Blocks.mendProjector, DefensePlanner.MENDER, 1.3)); blocks.add(Blocks.mendProjector); }
        if(forceProjector && available(Blocks.forceProjector)){
            ForceProjector f = (ForceProjector)Blocks.forceProjector;
            Type ty = base(f, DefensePlanner.SHIELD);
            ty.range = f.radius / tilesize; ty.strength = 2;
            types.add(ty); blocks.add(f);
        }
        if(p.mines){
            Type ty = base(Blocks.shockMine, DefensePlanner.MINE);
            types.add(ty); blocks.add(Blocks.shockMine);
        }
        p.types = types.toArray(Type.class);
        sn.typeBlock = blocks.toArray(Block.class);

        // region
        int T = p.types.length;
        p.free = new boolean[W][H];
        p.nearForeignItems = new boolean[W][H];
        p.nearForeignLiquids = new boolean[W][H];
        p.nodeOk = new boolean[W][H];
        p.fits = new boolean[T][W][H];
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int x = gx0 + u, y = gy0 + v;
            boolean e = empty(x, y);
            p.free[u][v] = e && !fedByExisting(x, y) && Build.validPlace(belt, team, x, y, 0);
            p.nodeOk[u][v] = e && sn.node != null && Build.validPlace(sn.node, team, x, y, 0);
            p.nearForeignItems[u][v] = touchesExistingLogistics(x, y, 1);
            p.nearForeignLiquids[u][v] = nearForeignLiquid(x, y);
            if(!inRegion(x, y)) continue;
            for(int i = 0; i < T; i++){
                Block b = sn.typeBlock[i];
                int s = b.size, off = (s - 1) / 2;
                p.fits[i][u][v] = inRegion(x + s - 1, y + s - 1) && footprintEmpty(x, y, s) && !coversConveyorFront(x, y, s)
                    && Build.validPlace(b, team, x + off, y + off, 0);
            }
        }
        // networks' ports
        p.nets = new Net[sn.netKinds.size];
        for(int k = 0; k < p.nets.length; k++){
            Net n = p.nets[k] = new Net();
            n.liquid = sn.netItems.get(k) == null;
            n.bridgeRange = n.liquid ? (sn.liquidBridge == null ? 0 : ((ItemBridge)sn.liquidBridge).range) : (sn.bridge == null ? 0 : ((ItemBridge)sn.bridge).range);
            n.auto = sn.netPorts.get(k).isEmpty();
            Seq<int[]> starts = new Seq<>();
            for(Port pt : sn.netPorts.get(k)) addStarts(sn, pt, n.liquid, starts);
            n.starts = starts.toArray(int[].class);
        }
        // power
        if(powerUsed()){
            p.nodeRange = ((PowerNode)sn.node).laserRange;
            p.nodeMaxLinks = ((PowerNode)sn.node).maxNodes;
            Seq<int[]> anchors = new Seq<>(), ports = new Seq<>();
            for(Port pt : sn.powerPorts){
                if(pt.existing != null){
                    Building b = pt.existing;
                    int off = (b.block.size - 1) / 2;
                    anchors.add(new int[]{b.tile.x - off - gx0, b.tile.y - off - gy0, b.block.size});
                }else{
                    int[] t = portTile(pt);
                    ports.add(new int[]{t[0] - gx0, t[1] - gy0});
                }
            }
            p.powerAnchors = anchors.toArray(int[].class);
            p.powerPorts = ports.toArray(int[].class);
            p.powerAuto = sn.powerPorts.isEmpty();
            p.nodePathCost = pathCost(sn.node);
        }
        // prices: build cost, in conveyor units
        double unit = Math.max(1f, belt.buildTime);
        p.pipePrice = 1;
        p.bridgePrice = sn.bridge == null ? 6 : sn.bridge.buildTime / unit;
        p.routerPrice = Blocks.router.buildTime / unit;
        p.unloaderPrice = Blocks.unloader.buildTime / unit;
        p.nodePrice = sn.node == null ? 2 : sn.node.buildTime / unit;
        for(Type ty : p.types) ty.price /= unit;
        p.logisticsPathCost = new int[][]{
            {pathCost(belt), pathCost(Blocks.router), sn.bridge == null ? 0 : pathCost(sn.bridge), sn.bridge == null ? 0 : pathCost(sn.bridge), pathCost(Blocks.unloader)},
            {pathCost(conduit), pathCost(Blocks.liquidRouter), sn.liquidBridge == null ? 0 : pathCost(sn.liquidBridge), sn.liquidBridge == null ? 0 : pathCost(sn.liquidBridge), 0}};
        return sn;
    }

    static int pathCost(Block b){
        return b.solid ? EnemyRoutes.healthCost(b.health) : 0;
    }

    Type base(Block b, int kind){
        Type t = new Type();
        t.kind = kind; t.size = b.size; t.price = b.buildTime; t.solid = b.solid; t.pathCost = pathCost(b);
        t.powered = needsPower(b); t.name = b.localizedName;
        return t;
    }

    Type turretType(Turret t, BulletType b, boolean liquidOn){
        Type ty = base(t, DefensePlanner.TURRET);
        float d = dps(t, b);
        ty.range = (t.range + (b == null ? 0 : b.rangeChange)) / tilesize;
        ty.minRange = (t.minRange + (b == null ? 0 : b.minRangeChange)) / tilesize;
        ty.dpsGround = t.targetGround && (b == null || b.collidesGround) ? d : 0;
        ty.dpsAir = t.targetAir && (b == null || b.collidesAir) ? d : 0;
        ty.coolantBoost = liquidOn && coolant ? coolantBoost(t, liquid) : 1;
        return ty;
    }

    Type wallType(Wall w){
        return base(w, DefensePlanner.WALL);
    }

    Type protector(MendProjector m, int kind, double strength){
        Type ty = base(m, kind);
        ty.range = m.range / tilesize; ty.strength = strength;
        return ty;
    }

    /** an existing block of the player's that takes liquids next to (x,y): a liquid router here would push into it */
    boolean nearForeignLiquid(int x, int y){
        for(int d = 0; d < 4; d++){
            Tile n = world.tile(x + Geometry.d4x(d), y + Geometry.d4y(d));
            if(n != null && n.build != null && n.block().hasLiquids && !portBuildings.contains(n.build)) return true;
        }
        return false;
    }

    /** where a network's first blocks may go for a port (grid tiles): {u, v, from, source} */
    void addStarts(Snapshot sn, Port pt, boolean liquid, Seq<int[]> out){
        int gx = sn.gx, gy = sn.gy, gw = sn.base.W, gh = sn.base.H;
        if(pt.existing == null){
            int[] t = portTile(pt);
            out.add(new int[]{t[0] - gx, t[1] - gy, pt.edge ? pt.side : -1, 0});
            return;
        }
        Building b = pt.existing;
        Block k = b.block;
        // a conveyor / conduit: the tile it points to
        if(k instanceof Conveyor || k instanceof Duct || k instanceof Conduit){
            int fx = b.tile.x + Geometry.d4x(b.rotation) - gx, fy = b.tile.y + Geometry.d4y(b.rotation) - gy;
            if(fx >= 0 && fy >= 0 && fx < gw && fy < gh) out.add(new int[]{fx, fy, (b.rotation + 2) % 4, 0});
            return;
        }
        // storage gives items through an unloader next to it; anything else pushes into its neighbours
        boolean unloader = !liquid && k instanceof StorageBlock;
        int off = (k.size - 1) / 2, x0 = b.tile.x - off, y0 = b.tile.y - off;
        for(int i = -1; i <= k.size; i++) for(int j = -1; j <= k.size; j++){
            boolean edge = (i == -1 || i == k.size) != (j == -1 || j == k.size);
            if(!edge) continue;
            int x = x0 + i - gx, y = y0 + j - gy;
            if(x < 0 || y < 0 || x >= gw || y >= gh || inRegion(x + gx, y + gy)) continue;
            int from = i == -1 ? 0 : i == k.size ? 2 : j == -1 ? 1 : 3;
            out.add(new int[]{x, y, from, unloader ? 1 : 0});
        }
    }

    // ------------------------------------------------------------------ planning

    @Override
    public PlanResult search(Object snapshot, int variantBase, int variants, Progress progress){
        Snapshot sn = (Snapshot)snapshot;
        Result best = null, firstFail = null;
        for(int v = variantBase; v < variantBase + variants; v++){
            Result r = plan(sn, v, progress);
            if(r.ok() && (best == null || r.score() > best.score())) best = r;
            if(!r.ok() && firstFail == null) firstFail = r;
        }
        return best != null ? best : firstFail;
    }

    @Override
    public void cancel(){
        super.cancel();
        DefensePlanner.Ctl c = current;
        if(c != null) c.cancelled = true;
    }

    public Result plan(Snapshot sn, int variant, Progress progress){
        Params p = copy(sn.base);
        p.variant = variant;
        p.seed = variant * 7919L;
        Result r = new Result(sn);
        DefensePlanner.Ctl ctl = current = new DefensePlanner.Ctl();
        if(cancelled) throw new CancelledException();
        if(progress != null){
            ctl.listener = (st, places) -> progress.frame("#" + variant + ": " + st, r.toPlans(places));
            ctl.snapshotInterval = 2;
        }
        long time = Time.millis();
        DefensePlanner pl = r.planner = DefensePlanner.planBest(p, ctl);
        boolean ok = pl.failure == null;
        Log.info("[autobuild] defense @x@ [@] front=@ var=@ ok=@ score=@ turrets=@ walls=@ prot=@ pipes=@ bridges=@ nodes=@ mines=@ dps=@/@ cov=@ changed=@ in @ms @",
            p.rw, p.rh, pl.templateName, pl.front, variant, ok, Strings.autoFixed((float)pl.score, 1), pl.turrets, pl.walls, pl.protectors, pl.pipes, pl.bridges, pl.nodes, pl.mines,
            Strings.autoFixed((float)pl.meanGroundDps, 0), Strings.autoFixed((float)pl.meanAirDps, 0), Strings.autoFixed((float)pl.coveredGround, 2), pl.routeChanged,
            Time.timeSinceMillis(time), ok ? "" : pl.failure);
        if(!ok){ r.error = pl.failure; return r; }
        r.fill();
        return r;
    }

    /** fresh Params sharing the (read-only) arrays; networks copied (the planner fills auto ports in) */
    static Params copy(Params b){
        Params p = new Params();
        p.W = b.W; p.H = b.H; p.mapW = b.mapW; p.mapH = b.mapH; p.ox = b.ox; p.oy = b.oy;
        p.pathCost = b.pathCost; p.nearSolid = b.nearSolid; p.targets = b.targets; p.groundSpawns = b.groundSpawns; p.airLines = b.airLines;
        p.groundWeight = b.groundWeight; p.airWeight = b.airWeight; p.enemyRange = b.enemyRange;
        p.free = b.free; p.nearForeignItems = b.nearForeignItems; p.nearForeignLiquids = b.nearForeignLiquids; p.fits = b.fits; p.types = b.types;
        p.clearance = b.clearance; p.wallThickness = b.wallThickness; p.maxTurrets = b.maxTurrets; p.mines = b.mines;
        p.rx0 = b.rx0; p.ry0 = b.ry0; p.rw = b.rw; p.rh = b.rh;
        p.nodeOk = b.nodeOk; p.nodeRange = b.nodeRange; p.nodeMaxLinks = b.nodeMaxLinks; p.powerAnchors = b.powerAnchors; p.powerPorts = b.powerPorts; p.powerAuto = b.powerAuto;
        p.pipePrice = b.pipePrice; p.bridgePrice = b.bridgePrice; p.routerPrice = b.routerPrice; p.nodePrice = b.nodePrice; p.unloaderPrice = b.unloaderPrice;
        p.nodePathCost = b.nodePathCost; p.logisticsPathCost = b.logisticsPathCost;
        p.nets = new Net[b.nets.length];
        for(int k = 0; k < b.nets.length; k++){
            Net n = p.nets[k] = new Net();
            n.liquid = b.nets[k].liquid; n.starts = b.nets[k].starts.clone(); n.auto = b.nets[k].auto; n.bridgeRange = b.nets[k].bridgeRange;
        }
        return p;
    }

    /** One planned defense in world terms. */
    public class Result extends PlanResult{
        public final Snapshot sn;
        public DefensePlanner planner;
        /** ports per kind (auto ones included) */
        public final ObjectMap<PortKind, Seq<Port>> ports = new ObjectMap<>();
        public final ObjectSet<PortKind> autoKinds = new ObjectSet<>();
        public float powerUse;
        /** total build cost */
        public final ItemSeq cost = new ItemSeq();

        Result(Snapshot sn){ this.sn = sn; }

        @Override
        public double score(){ return ok() ? planner.score : Double.NEGATIVE_INFINITY; }

        BuildPlan toPlan(DefensePlanner.Place pl){
            int x = sn.gx + pl.u, y = sn.gy + pl.v;
            if(pl.kind == DefensePlanner.NODE) return new BuildPlan(x, y, 0, sn.node);
            if(pl.kind == DefensePlanner.BLOCK){
                Block b = sn.typeBlock[pl.type];
                return new BuildPlan(x + (b.size - 1) / 2, y + (b.size - 1) / 2, 0, b);
            }
            boolean liquid = sn.netItems.get(pl.type) == null;
            switch(pl.kind){
                case GridRouter.PIPE: return new BuildPlan(x, y, pl.dir, liquid ? sn.conduit : sn.belt);
                case GridRouter.ROUTER: return new BuildPlan(x, y, 0, liquid ? Blocks.liquidRouter : Blocks.router);
                case GridRouter.SOURCE: return new BuildPlan(x, y, 0, Blocks.unloader, sn.netItems.get(pl.type));
                default: {
                    // bridge: relative link; (0,0) marks an end bridge and stops the game's auto-linking
                    Block b = liquid ? sn.liquidBridge : sn.bridge;
                    return new BuildPlan(x, y, 0, b, pl.kind == GridRouter.BRIDGE ? new Point2(pl.lu - pl.u, pl.lv - pl.v) : new Point2(0, 0));
                }
            }
        }

        Seq<BuildPlan> toPlans(List<DefensePlanner.Place> places){
            Seq<BuildPlan> out = new Seq<>(places.size());
            for(DefensePlanner.Place pl : places) out.add(toPlan(pl));
            return out;
        }

        /** build plans (nodes first: blocks link to them when finished; then logistics, turrets, the rest, walls last), ports, stats */
        void fill(){
            Seq<BuildPlan> nodes = new Seq<>(), logistics = new Seq<>(), main = new Seq<>(), walls = new Seq<>();
            for(DefensePlanner.Place pl : planner.places){
                BuildPlan bp = toPlan(pl);
                signature.add(pl.toString());
                if(pl.kind == DefensePlanner.NODE) nodes.add(bp);
                else if(pl.kind != DefensePlanner.BLOCK) logistics.add(bp);
                else if(planner.p.types[pl.type].kind == DefensePlanner.WALL) walls.add(bp);
                else main.add(bp);
            }
            plans.addAll(nodes).addAll(logistics).addAll(main).addAll(walls);
            for(BuildPlan bp : plans){
                for(ItemStack st : bp.block.requirements) cost.add(st.item, st.amount);
                if(needsPower(bp.block)) powerUse += bp.block.consPower.usage * 60f;
            }
            // ports: picked ones, plus those the planner chose
            for(int k = 0; k < sn.netKinds.size; k++){
                PortKind kind = sn.netKinds.get(k);
                Seq<Port> ps = new Seq<>(sn.netPorts.get(k));
                ports.put(kind, ps);
            }
            for(int[] cp : planner.chosenPorts){
                PortKind kind = sn.netKinds.get(cp[0]);
                // a network nothing ended up using needs no port
                boolean used = false;
                for(DefensePlanner.Place q : planner.places) if(q.kind < DefensePlanner.BLOCK && q.type == cp[0]){ used = true; break; }
                if(!used) continue;
                ports.get(kind).add(Port.at(sn.gx + cp[1], sn.gy + cp[2]));
                autoKinds.add(kind);
            }
            if(powerUsed()){
                Seq<Port> ps = new Seq<>(sn.powerPorts);
                if(planner.chosenPowerPort != null){
                    int[] cp = planner.chosenPowerPort;
                    ps.add(Port.at(sn.gx + cp[0], sn.gy + cp[1]));
                    autoKinds.add(powerKind);
                }
                for(int[] e : planner.extraPowerPorts) ps.add(Port.at(sn.gx + e[0], sn.gy + e[1]));
                ports.put(powerKind, ps);
            }
        }

        @Override
        public Seq<Port> ports(PortKind kind){ return ports.get(kind, Seq::new); }

        @Override
        public boolean auto(PortKind kind){ return autoKinds.contains(kind); }

        @Override
        public String portLabel(PortKind kind, int i){
            if(kind == powerKind && i >= ports(kind).size - planner.extraPowerPorts.size()) return "(link this group too)";
            return null;
        }

        @Override
        public String portText(){
            StringBuilder t = new StringBuilder();
            for(PortKind k : ports.keys()){
                Seq<Port> ps = ports.get(k);
                if(ps.isEmpty()) continue;
                if(t.length() > 0) t.append(", ");
                t.append(k.name).append(": ");
                for(int i = 0; i < ps.size; i++) t.append(i > 0 ? "/" : "").append(ps.get(i).name());
                if(autoKinds.contains(k)) t.append(" (auto)");
            }
            return t.toString();
        }

        @Override
        public String summary(){
            if(!ok()) return "[scarlet]" + error;
            DefensePlanner pl = planner;
            StringBuilder sb = new StringBuilder();
            // what is built
            ObjectIntMap<String> counts = new ObjectIntMap<>();
            for(DefensePlanner.Place q : pl.places){
                if(q.kind != DefensePlanner.BLOCK) continue;
                Type t = pl.p.types[q.type];
                if(t.kind == DefensePlanner.TURRET || t.kind == DefensePlanner.MENDER || t.kind == DefensePlanner.SHIELD) counts.increment(sn.typeBlock[q.type].localizedName);
            }
            StringBuilder kinds = new StringBuilder();
            for(var e : counts){ if(kinds.length() > 0) kinds.append(", "); kinds.append(e.value).append(' ').append(e.key); }
            sb.append(kinds);
            if(pl.walls > 0) sb.append(", ").append(pl.walls).append(" walls");
            if(pl.mines > 0) sb.append(", ").append(pl.mines).append(" shock mines");
            sb.append(Strings.format("\nGround route: ~@ DPS, @% of it in range", Strings.autoFixed((float)pl.meanGroundDps, 0), Mathf.round((float)pl.coveredGround * 100)));
            if(pl.p.airWeight > 0 && !pl.airRoutes.isEmpty()) sb.append(Strings.format("   Air: ~@ DPS, @% in range", Strings.autoFixed((float)pl.meanAirDps, 0), Mathf.round((float)pl.coveredAir * 100)));
            sb.append(Strings.format("\n[lightgray]Supply: @ belts / pipes, @ bridges", pl.pipes, pl.bridges));
            int cooled = 0;
            for(Built b : pl.built) if(b.cooled && pl.p.types[b.type].kind == DefensePlanner.TURRET && pl.p.types[b.type].coolantBoost > 1.001) cooled++;
            if(cooled > 0) sb.append(", ").append(cooled).append(" turrets cooled with ").append(sn.liquid.localizedName);
            if(pl.nodes > 0) sb.append(Strings.format(", @ power nodes, needs @ power/s", pl.nodes, Strings.autoFixed(powerUse, 1)));
            sb.append("[]");
            if(!pl.extraPowerPorts.isEmpty()) sb.append("\n[orange]Power: link ").append(pl.extraPowerPorts.size()).append(" more node group(s) to your grid (marked)");
            if(pl.unpowered > 0) sb.append("\n[orange]").append(pl.unpowered).append(" block(s) out of reach of power nodes");
            sb.append(pl.routeChanged ? "\n[orange]The enemy route moves with this layout (shown in orange)" : "\n[green]Enemy route unchanged[] [lightgray](kept " + pl.p.clearance + " tile(s) free around it)");
            // cost against the core
            CoreBlock.CoreBuild core = team.core();
            if(core != null && !state.rules.infiniteResources){
                StringBuilder lack = new StringBuilder();
                for(ItemStack st : cost){
                    int have = core.items.get(st.item);
                    if(have < st.amount) lack.append(lack.length() > 0 ? ", " : "").append(st.amount - have).append(' ').append(st.item.localizedName);
                }
                if(lack.length() > 0) sb.append("\n[scarlet]Core lacks: ").append(lack);
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ overlay

    @Override
    public void drawOverlay(PlanResult shown){
        Draw.z(Layer.overlayUI - 1f);
        if(shown instanceof Result r && r.planner != null){
            for(int[] route : r.planner.groundRoutes) drawRoute(route, Pal.remove);
            if(r.planner.routeChanged) for(int[] route : r.planner.newGroundRoutes) drawRoute(route, Pal.accent);
            if(r.planner.p.airWeight > 0) for(float[] l : r.planner.p.airLines) drawAir(l);
        }else{
            for(int[] route : previewRoutes) drawRoute(route, Pal.remove);
            if(airShare > 0) for(float[] l : previewAir) drawAir(l);
        }
        Draw.reset();
    }

    void drawRoute(int[] route, Color color){
        int w = world.width();
        Lines.stroke(2f, color);
        Draw.alpha(0.8f);
        for(int i = 1; i < route.length; i++){
            Lines.line(route[i - 1] % w * tilesize, route[i - 1] / w * tilesize, route[i] % w * tilesize, route[i] / w * tilesize);
        }
        // direction marks
        for(int i = 6; i < route.length; i += 8){
            float x0 = route[i - 1] % w * tilesize, y0 = route[i - 1] / w * tilesize, x1 = route[i] % w * tilesize, y1 = route[i] / w * tilesize;
            float ang = Angles.angle(x0, y0, x1, y1);
            Fill.tri(x1 + Angles.trnsx(ang, 5f), y1 + Angles.trnsy(ang, 5f),
                x1 + Angles.trnsx(ang + 140f, 5f), y1 + Angles.trnsy(ang + 140f, 5f),
                x1 + Angles.trnsx(ang - 140f, 5f), y1 + Angles.trnsy(ang - 140f, 5f));
        }
    }

    void drawAir(float[] l){
        Lines.stroke(1.5f, Pal.sap);
        Draw.color(Color.sky, 0.6f);
        Lines.dashLine(l[0] * tilesize, l[1] * tilesize, l[2] * tilesize, l[3] * tilesize, (int)(Mathf.dst(l[0], l[1], l[2], l[3]) / 2));
    }
}
