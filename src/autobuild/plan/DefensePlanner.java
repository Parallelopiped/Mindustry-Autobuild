package autobuild.plan;

import autobuild.plan.DrillLayoutPlanner.*;
import autobuild.plan.GridRouter.*;

import java.util.*;

/**
 * Defense layout (pure Java). Grid = a box of the map holding the selected region and the input
 * ports (local tiles u = x - ox, v = y - oy); the region is a rectangle inside it. The enemy route
 * model works on the whole map.
 *
 * <ol>
 * <li><b>Routes</b>: the ground route of every spawn ({@link EnemyRoutes}, the game's flow field)
 *     and the straight lines of flying units, weighted by the ground / air share of the waves.</li>
 * <li><b>One side</b>: tiles within {@code clearance} of the ground route stay empty (the route
 *     keeps its cost, so it can't move). The rest of the region splits into the parts on either
 *     side of the route; only the part with the most buildable ground is used. Its edge nearest
 *     the route is the <i>front</i>; the layout is a sequence of rows parallel to it.</li>
 * <li><b>Rows</b>, front to back: walls ({@code wallThickness}, following the route per column),
 *     a support row (menders, power-node slots), then turret rows with supply lanes between them:
 *     short-range turrets in front, long-range ones behind (several row orders are tried, the best
 *     scoring one wins). A lane is one row of conveyors / conduits; every lane tile next to a
 *     turret that uses it becomes a router (liquid router), so each turret row is fed from the
 *     lanes on its two sides (ammo on one, liquid on the other).</li>
 * <li><b>Trunks</b>: from each input port (anywhere on the map) to one end of every lane segment,
 *     {@link GridRouter} with a cost that grows near the route (trunks keep away from enemies) and
 *     bridges to save tiles. Nothing lands on walls, the corridor or the other side.</li>
 * <li><b>Projectors</b> (force / mend) replace turrets where they cover the most exposed turrets;
 *     <b>power</b>: {@link PowerNetwork} (support-row slots and leftover tiles; a turret no node can
 *     reach gives its slot to a node); <b>walls</b> in front of what was built; <b>mines</b> on the
 *     route. Finally the route is recomputed with every planned block.</li>
 * </ol>
 */
public class DefensePlanner{
    // building type kinds
    public static final int TURRET = 0, WALL = 1, MENDER = 2, SHIELD = 3, MINE = 4;
    // place kinds (logistics use GridRouter's kinds 0..4 with `type` = network index)
    public static final int BLOCK = 10, NODE = 11;
    // occupancy
    static final int FREE = -1, BLOCKED = -2, CORRIDOR = -3, BAND = -4, ROUTE = -5, OTHER = -6, WALLTILE = -7, SLOT = -8;
    // occupied: 1000 + network (logistics), 2000 + type (buildings), 3000 (node), 4000 + network (reserved port tile)
    static final int NET = 1000, BUILDING = 2000, POWER = 3000, PORT = 4000;
    /** the region is filled densely: larger ones are refused */
    public static final int MAX_AREA = 900;

    /** Receives intermediate states for the gray replay (planning thread). */
    public interface Listener{
        void snapshot(String stage, List<Place> places);
    }

    /** A kind of building the planner may place. */
    public static class Type{
        public int kind, size = 1;
        /** range in tiles; turrets: min range */
        public float range, minRange;
        public double dpsGround, dpsAir;
        /** item network that brings its ammo, or -1 */
        public int ammoNet = -1;
        /** its ammo is the liquid (wave / tsunami) */
        public boolean liquidAmmo;
        /** needs the liquid as coolant to fire at all (meltdown) */
        public boolean coolantRequired;
        /** damage multiplier with the liquid as coolant (1 = no coolant use) */
        public double coolantBoost = 1;
        public boolean powered;
        public double price;
        /** solid blocks change the enemy path cost of their tiles */
        public boolean solid = true;
        public int pathCost;
        /** protectors: relative strength per covered exposed tile */
        public double strength;
        public String name = "";
    }

    /** A planned block. Logistics: kind = GridRouter kind, type = network; blocks: kind = BLOCK, type = Type index. */
    public static class Place{
        public final int kind, type, u, v, dir, lu, lv;

        public Place(int kind, int type, int u, int v, int dir, int lu, int lv){
            this.kind = kind; this.type = type; this.u = u; this.v = v; this.dir = dir; this.lu = lu; this.lv = lv;
        }

        @Override
        public String toString(){ return kind + "/" + type + "@" + u + "," + v + ">" + dir + ":" + lu + "," + lv; }
    }

    /** A supply network: items (one ammo item) or the liquid. */
    public static class Net{
        public boolean liquid;
        /** where it starts: {u, v, from (-1 unknown), source (1 = place the source block there: an unloader)} */
        public int[][] starts = new int[0][];
        /** the planner chooses the port (starts empty) */
        public boolean auto;
        public int bridgeRange;
    }

    public static class Params{
        /** the grid: a box of the map */
        public int W, H, ox, oy;
        /** the region inside the grid */
        public int rx0, ry0, rw, rh;
        // ---- enemy routes, whole map ----
        public int mapW, mapH;
        public int[] pathCost;
        public boolean[] nearSolid;
        public int[] targets, groundSpawns;
        /** flying routes {x0, y0, x1, y1} in map tiles */
        public float[][] airLines = new float[0][];
        public double groundWeight = 1, airWeight = 0;
        /** typical weapon range of ground enemies, tiles */
        public float enemyRange = 7f;
        // ---- grid ----
        /** tile is empty and buildable for a 1x1 block, and no existing block pushes items into it */
        public boolean[][] free;
        /** a router-like block here would push into an existing block of the player's */
        public boolean[][] nearForeignItems, nearForeignLiquids;
        /** per type: placeable with its footprint's min corner here (inside the region) */
        public boolean[][][] fits;
        public Type[] types = new Type[0];
        public Net[] nets = new Net[0];
        public int clearance = 1, wallThickness = 1;
        public int maxTurrets = 1000;
        public boolean mines;
        // ---- power ----
        public boolean[][] nodeOk;
        public float nodeRange = 6f;
        public int nodeMaxLinks = 10;
        /** existing power buildings the network joins {u, v, size} (may lie outside the grid) */
        public int[][] powerAnchors = new int[0][];
        /** tiles where a port node is placed {u, v} */
        public int[][] powerPorts = new int[0][];
        /** pick a power port (no anchors / ports given) */
        public boolean powerAuto;
        // ---- prices, in conveyor units ----
        public double pipePrice = 1, bridgePrice = 6, routerPrice = 3, nodePrice = 2, unloaderPrice = 25;
        /** enemy path cost of a node / of each logistics kind ([liquid ? 1 : 0][GridRouter kind]) when built (0 = not solid) */
        public int nodePathCost;
        public int[][] logisticsPathCost = new int[2][5];
        public int variant;
        public long seed;
    }

    /** shared by the layouts tried for one plan: cancelling, gray replay */
    public static class Ctl{
        public volatile boolean cancelled;
        public Listener listener;
        public int snapshotInterval = 1;
        int count;
    }

    public final Params p;
    public final int W, H;
    int[][] occ;
    public final List<Place> places = new ArrayList<>();
    public String failure;
    public Ctl ctl = new Ctl();
    /** the row order this layout was built with (for the summary) */
    public String templateName = "";

    // ---- routes (shared between layouts) ----
    public final List<int[]> groundRoutes, airRoutes;
    public EnemyRoutes routes;
    /** route tiles that matter (in reach): grid coordinates, weight, air? */
    float[] rx = new float[0], ry = new float[0];
    double[] rw = new double[0];
    boolean[] rAir = new boolean[0];
    /** current DPS on each route tile */
    double[] dps = new double[0];
    /** Chebyshev distance to the ground route (capped) */
    int[][] pd;
    /** grid tiles on the side of the route the layout uses */
    boolean[][] ourSide;
    /** front edge of the region (0 = south, 1 = north, 2 = west, 3 = east), rows parallel to it */
    public int front;
    double K = 50;
    Random rand;

    // ---- networks ----
    int[][] netAt, kindAt, endBack;
    /** network tiles whose block is placed (lane tiles are only reserved until connected) */
    boolean[][] placedAt;
    boolean[][][] pushedBy;
    GridRouter[] routers;
    /** port starts chosen per network: {net, u, v} */
    public final List<int[]> chosenPorts = new ArrayList<>();
    /** each network's starts in this layout (the ones given, or the port chosen here) */
    public int[][][] netStarts;
    /** {net, u, v}: the tile kept empty next to each port for the player's belt / pipe */
    public final List<int[]> playerSide = new ArrayList<>();
    public int[] chosenPowerPort;
    public final List<int[]> extraPowerPorts = new ArrayList<>();

    // ---- placed buildings ----
    public static class Built{
        public final int type, u, v;
        public boolean cooled, powered = true;
        Built(int type, int u, int v){ this.type = type; this.u = u; this.v = v; }
    }
    public final List<Built> built = new ArrayList<>();
    List<int[]> nodeList = new ArrayList<>();
    /** lane rows: {net, b} and their segments */
    final List<Lane> lanes = new ArrayList<>();
    /** tiles just past a lane end: a trunk's way into that lane, so other trunks avoid landing there */
    boolean[][] access;
    /** lane segments no trunk could reach (diagnostics) */
    public final List<String> failedLanes = new ArrayList<>();

    // ---- results ----
    public double score, valueGround, valueAir;
    public int turrets, walls, protectors, mines, pipes, bridges, nodes, unpowered, powerGroups, unfed;
    public double meanGroundDps, meanAirDps, coveredGround, coveredAir;
    public boolean routeChanged;
    public final List<int[]> newGroundRoutes = new ArrayList<>();

    public DefensePlanner(Params p){
        this.p = p;
        W = p.W; H = p.H;
        groundRoutes = new ArrayList<>();
        airRoutes = new ArrayList<>();
        init();
    }

    /** a layout sharing the routes and zones of `base` */
    DefensePlanner(DefensePlanner base){
        this.p = base.p;
        W = p.W; H = p.H;
        ctl = base.ctl;
        groundRoutes = base.groundRoutes; airRoutes = base.airRoutes; routes = base.routes;
        rx = base.rx; ry = base.ry; rw = base.rw; rAir = base.rAir; dps = new double[rx.length];
        pd = base.pd; front = base.front; K = base.K; ourSide = base.ourSide;
        init();
        for(int u = 0; u < W; u++) occ[u] = base.occ[u].clone();
    }

    void init(){
        occ = new int[W][H];
        netAt = new int[W][H];
        kindAt = new int[W][H];
        endBack = new int[W][H];
        for(int[] r : netAt) Arrays.fill(r, -1);
        for(int[] r : kindAt) Arrays.fill(r, -1);
        for(int[] r : endBack) Arrays.fill(r, -1);
        placedAt = new boolean[W][H];
        access = new boolean[W][H];
        service = new boolean[W][H];
        pushedBy = new boolean[p.nets.length][W][H];
        netStarts = new int[p.nets.length][][];
        for(int k = 0; k < p.nets.length; k++) netStarts[k] = p.nets[k].starts;
        rand = new Random(p.seed * 31 + p.variant);
    }

    boolean in(int u, int v){ return u >= 0 && v >= 0 && u < W && v < H; }

    boolean inRegion(int u, int v){ return u >= p.rx0 && v >= p.ry0 && u < p.rx0 + p.rw && v < p.ry0 + p.rh; }

    void checkCancel(){
        if(ctl.cancelled) throw new CancelledException();
    }

    void snapshot(String stage, boolean force){
        if(ctl.listener == null) return;
        if(!force && (ctl.count++ % ctl.snapshotInterval) != 0) return;
        ctl.listener.snapshot(stage, currentPlaces());
    }

    /** current placements (for the gray replay) */
    public List<Place> currentPlaces(){
        List<Place> out = new ArrayList<>(places);
        for(int[] n : nodeList) out.add(new Place(NODE, 0, n[0], n[1], 0, n[0], n[1]));
        return out;
    }

    // ================================================================== entry

    /**
     * Plans every row order and returns the `variant`-th best distinct layout (0 = best); on
     * failure a planner whose {@link #failure} says why.
     */
    public static DefensePlanner planBest(Params p, Ctl ctl){
        DefensePlanner base = new DefensePlanner(p);
        base.ctl = ctl;
        if(!base.prepare()) return base;
        List<DefensePlanner> done = new ArrayList<>();
        String firstFail = null;
        for(int[] order : base.orders()){
            base.checkCancel();
            DefensePlanner l = new DefensePlanner(base);
            if(l.layout(order)) done.add(l);
            else if(firstFail == null) firstFail = l.failure;
        }
        if(done.isEmpty()){
            base.failure = firstFail != null ? firstFail : "no turret could be placed and supplied";
            return base;
        }
        done.sort((a, b) -> Double.compare(b.score, a.score));
        // distinct layouts only
        List<DefensePlanner> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for(DefensePlanner l : done) if(seen.add(l.places.toString())) distinct.add(l);
        DefensePlanner chosen = distinct.get(p.variant % distinct.size());
        chosen.snapshot("done", true);
        return chosen;
    }

    /** routes, zones; false (and failure) if this region can't hold a defense */
    boolean prepare(){
        if(p.rw < 3 || p.rh < 3){ failure = "region too small"; return false; }
        if(p.rw * p.rh > MAX_AREA){ failure = "region too large (" + p.rw * p.rh + " tiles, at most " + MAX_AREA + "): select a strip along the route - it is filled completely"; return false; }
        computeRoutes();
        if(groundRoutes.isEmpty() && airRoutes.isEmpty()){
            failure = "no enemy route: the map needs an enemy spawn (or enemy core) and a core of yours";
            return false;
        }
        reach();
        if(rx.length == 0){ failure = "no enemy route passes within turret range of this region"; return false; }
        zones();
        int free = 0;
        for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++) if(occ[u][v] == FREE) free++;
        if(free < 4){ failure = "nothing in the region can be built on beside the enemy route"; return false; }
        double sum = 0;
        int cnt = 0;
        for(Type t : p.types) if(t.kind == TURRET){ sum += Math.max(t.dpsGround, t.dpsAir); cnt++; }
        K = Math.max(5, 2 * sum / Math.max(1, cnt));
        return true;
    }

    // ------------------------------------------------------------------ routes

    void computeRoutes(){
        routes = new EnemyRoutes(p.mapW, p.mapH, p.pathCost, p.nearSolid);
        if(p.targets != null && p.targets.length > 0 && p.groundSpawns != null){
            routes.flow(p.targets);
            for(int s : p.groundSpawns){
                int[] r = routes.trace(s);
                if(r.length > 1) groundRoutes.add(r);
            }
        }
        for(float[] l : p.airLines) airRoutes.add(EnemyRoutes.line(p.mapW, p.mapH, l[0], l[1], l[2], l[3]));
    }

    float maxRange(){
        float r = 0;
        for(Type t : p.types) if(t.kind == TURRET) r = Math.max(r, t.range + t.size);
        return r;
    }

    /** route tiles within reach of the region, with their weights (grid coordinates) */
    void reach(){
        float reach = maxRange() + 1;
        int x0 = p.rx0, y0 = p.ry0, x1 = p.rx0 + p.rw, y1 = p.ry0 + p.rh;
        Map<Integer, double[]> g = new LinkedHashMap<>(), a = new LinkedHashMap<>();
        int gn = Math.max(1, groundRoutes.size()), an = Math.max(1, airRoutes.size());
        for(int[] r : groundRoutes) for(int t : r){
            int x = t % p.mapW - p.ox, y = t / p.mapW - p.oy;
            if(x < x0 - reach || y < y0 - reach || x > x1 + reach || y > y1 + reach) continue;
            g.computeIfAbsent(t, k -> new double[1])[0] += p.groundWeight / gn;
        }
        if(p.airWeight > 0) for(int[] r : airRoutes) for(int t : r){
            int x = t % p.mapW - p.ox, y = t / p.mapW - p.oy;
            if(x < x0 - reach || y < y0 - reach || x > x1 + reach || y > y1 + reach) continue;
            a.computeIfAbsent(t, k -> new double[1])[0] += p.airWeight / an;
        }
        int n = g.size() + a.size(), i = 0;
        rx = new float[n]; ry = new float[n]; rw = new double[n]; rAir = new boolean[n]; dps = new double[n];
        for(Map.Entry<Integer, double[]> e : g.entrySet()){
            rx[i] = e.getKey() % p.mapW - p.ox; ry[i] = e.getKey() / p.mapW - p.oy; rw[i] = e.getValue()[0]; i++;
        }
        for(Map.Entry<Integer, double[]> e : a.entrySet()){
            rx[i] = e.getKey() % p.mapW - p.ox; ry[i] = e.getKey() / p.mapW - p.oy; rw[i] = e.getValue()[0]; rAir[i] = true; i++;
        }
    }

    /** distance to the route, corridor, the side used, the front edge */
    void zones(){
        pd = new int[W][H];
        int cap = 1 << 20;
        for(int[] r : pd) Arrays.fill(r, cap);
        // multi-source BFS (8-neighbourhood = Chebyshev distance) from the route tiles in the grid
        // (ground; flying lines when there is no ground route: they only orient the layout)
        List<int[]> seeds = groundRoutes.isEmpty() ? airRoutes : groundRoutes;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        int m = 64;
        for(int[] r : seeds) for(int t : r){
            int x = t % p.mapW - p.ox, y = t / p.mapW - p.oy;
            if(x < -m || y < -m || x >= W + m || y >= H + m) continue;
            if(in(x, y)){ if(pd[x][y] > 0){ pd[x][y] = 0; q.add(new int[]{x, y}); } continue; }
            // outside the grid: seed the nearest border tiles with their distance
            int cx = Math.max(0, Math.min(W - 1, x)), cy = Math.max(0, Math.min(H - 1, y));
            int d = Math.max(Math.abs(cx - x), Math.abs(cy - y));
            if(d < pd[cx][cy]){ pd[cx][cy] = d; q.add(new int[]{cx, cy}); }
        }
        while(!q.isEmpty()){
            int[] c = q.poll();
            for(int du = -1; du <= 1; du++) for(int dv = -1; dv <= 1; dv++){
                int nu = c[0] + du, nv = c[1] + dv;
                if(!in(nu, nv) || pd[nu][nv] <= pd[c[0]][c[1]] + 1) continue;
                pd[nu][nv] = pd[c[0]][c[1]] + 1;
                q.add(new int[]{nu, nv});
            }
        }
        boolean ground = !groundRoutes.isEmpty();
        int c = ground ? p.clearance : -1;
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            occ[u][v] = ground && pd[u][v] == 0 ? ROUTE : pd[u][v] <= c ? CORRIDOR : p.free[u][v] ? FREE : BLOCKED;
        }
        // the parts of the region on either side of the route: keep the one with the most free ground
        int[][] comp = new int[W][H];
        for(int[] r : comp) Arrays.fill(r, -1);
        int best = -1, bestFree = -1, n = 0;
        for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++){
            if(comp[u][v] >= 0 || pd[u][v] <= c) continue;
            int free = 0;
            ArrayDeque<int[]> f = new ArrayDeque<>();
            f.add(new int[]{u, v});
            comp[u][v] = n;
            while(!f.isEmpty()){
                int[] t = f.poll();
                if(occ[t[0]][t[1]] == FREE) free++;
                for(int d = 0; d < 4; d++){
                    int nu = t[0] + GridRouter.DX[d], nv = t[1] + GridRouter.DY[d];
                    if(!inRegion(nu, nv) || comp[nu][nv] >= 0 || pd[nu][nv] <= c) continue;
                    comp[nu][nv] = n;
                    f.add(new int[]{nu, nv});
                }
            }
            if(free > bestFree){ bestFree = free; best = n; }
            n++;
        }
        for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++){
            if(comp[u][v] >= 0 && comp[u][v] != best) occ[u][v] = OTHER;
        }
        // the grid tiles reachable from the side used without crossing the corridor
        ourSide = new boolean[W][H];
        ArrayDeque<int[]> f2 = new ArrayDeque<>();
        for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++){
            if(comp[u][v] == best && best >= 0){ ourSide[u][v] = true; f2.add(new int[]{u, v}); }
        }
        while(!f2.isEmpty()){
            int[] t = f2.poll();
            for(int d = 0; d < 4; d++){
                int nu = t[0] + GridRouter.DX[d], nv = t[1] + GridRouter.DY[d];
                if(!in(nu, nv) || ourSide[nu][nv] || pd[nu][nv] <= c || inRegion(nu, nv)) continue;
                ourSide[nu][nv] = true;
                f2.add(new int[]{nu, nv});
            }
        }
        // front: the edge the route lies beyond - the axis along which the distance to the route
        // changes most over the side used (least-squares slopes), toward smaller distances
        double mx = 0, my = 0, md = 0;
        int cnt = 0;
        for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++){
            if(comp[u][v] != best) continue;
            mx += u; my += v; md += Math.min(pd[u][v], 1000); cnt++;
        }
        front = 0;
        if(cnt > 0){
            mx /= cnt; my /= cnt; md /= cnt;
            double sxd = 0, sxx = 0, syd = 0, syy = 0;
            for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++){
                if(comp[u][v] != best) continue;
                double d = Math.min(pd[u][v], 1000) - md;
                sxd += (u - mx) * d; sxx += (u - mx) * (u - mx);
                syd += (v - my) * d; syy += (v - my) * (v - my);
            }
            double sx = sxx > 0 ? sxd / sxx : 0, sy = syy > 0 ? syd / syy : 0;
            // total change across the region along each axis
            double dx = sx * p.rw, dy = sy * p.rh;
            if(Math.abs(dy) >= Math.abs(dx)) front = dy >= 0 ? 0 : 1; // distance grows northward: the route is south
            else front = dx >= 0 ? 2 : 3;
        }
    }

    // ------------------------------------------------------------------ frame: a = along the front, b = depth from it

    int A(){ return front < 2 ? p.rw : p.rh; }

    int B(){ return front < 2 ? p.rh : p.rw; }

    int fu(int a, int b){
        switch(front){
            case 0: case 1: return p.rx0 + a;
            case 2: return p.rx0 + b;
            default: return p.rx0 + p.rw - 1 - b;
        }
    }

    int fv(int a, int b){
        switch(front){
            case 0: return p.ry0 + b;
            case 1: return p.ry0 + p.rh - 1 - b;
            default: return p.ry0 + a;
        }
    }

    /** grid min corner of the s x s footprint whose frame min corner is (a, b) */
    int[] corner(int a, int b, int s){
        int u0 = fu(a, b), v0 = fv(a, b), u1 = fu(a + s - 1, b + s - 1), v1 = fv(a + s - 1, b + s - 1);
        return new int[]{Math.min(u0, u1), Math.min(v0, v1)};
    }

    /** depth from the front (frame b) and position along it (frame a) of a region tile */
    public int[] frameOf(int u, int v){
        int x = u - p.rx0, y = v - p.ry0;
        switch(front){
            case 0: return new int[]{x, y};
            case 1: return new int[]{x, p.rh - 1 - y};
            case 2: return new int[]{y, x};
            default: return new int[]{y, p.rw - 1 - x};
        }
    }

    // ------------------------------------------------------------------ row orders

    /** row orders to try: turret type indices front to back (the last one repeats) */
    List<int[]> orders(){
        List<Integer> tur = new ArrayList<>();
        for(int i = 0; i < p.types.length; i++) if(p.types[i].kind == TURRET && suppliable(p.types[i])) tur.add(i);
        tur.sort(Comparator.comparingDouble(i -> p.types[i].range));
        List<int[]> out = new ArrayList<>();
        if(tur.isEmpty()) return out;
        int[] asc = new int[tur.size()];
        for(int i = 0; i < asc.length; i++) asc[i] = tur.get(i);
        // short range in front, long range behind, the longest repeats
        out.add(asc);
        // the whole sequence repeated
        if(asc.length > 1){
            int[] cyc = new int[asc.length * 4];
            for(int i = 0; i < cyc.length; i++) cyc[i] = asc[i % asc.length];
            out.add(cyc);
            // long range first (when they all reach anyway)
            int[] desc = new int[asc.length];
            for(int i = 0; i < asc.length; i++) desc[i] = asc[asc.length - 1 - i];
            out.add(desc);
        }
        // each type alone
        if(asc.length > 1) for(int t : asc) out.add(new int[]{t});
        // every pair: front type, then back type repeated
        if(asc.length > 2) for(int i = 0; i < asc.length; i++) for(int j = i + 1; j < asc.length; j++) out.add(new int[]{asc[i], asc[j]});
        return out;
    }

    String orderName(int[] order){
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < Math.min(order.length, 4); i++) sb.append(i > 0 ? " > " : "").append(p.types[order[i]].name);
        return sb.toString();
    }

    boolean suppliable(Type t){
        if((t.liquidAmmo || t.coolantRequired) && liquidNet() < 0) return false;
        return t.ammoNet < p.nets.length;
    }

    int liquidNet(){
        for(int k = 0; k < p.nets.length; k++) if(p.nets[k].liquid) return k;
        return -1;
    }

    /** networks a type can't fire without */
    List<Integer> required(Type t){
        List<Integer> n = new ArrayList<>();
        if(t.ammoNet >= 0) n.add(t.ammoNet);
        if((t.liquidAmmo || t.coolantRequired) && liquidNet() >= 0) n.add(liquidNet());
        return n;
    }

    /** networks a building takes from a lane next to it (required, or coolant / shield cooling) */
    boolean uses(Type t, int k){
        if(t.kind == TURRET) return t.ammoNet == k || p.nets[k].liquid && (t.liquidAmmo || t.coolantRequired || t.coolantBoost > 1.001);
        return false;
    }

    // ================================================================== one layout

    /** a lane: a row of one network; segments of free tiles {a0, a1}, head / tail chosen when connected */
    static class Lane{
        final int net, b;
        final List<int[]> segs = new ArrayList<>();
        Lane(int net, int b){ this.net = net; this.b = b; }
    }

    boolean layout(int[] order){
        templateName = orderName(order);
        try{
            ports();
            int[] wl = walls0();
            boolean support = hasSupport();
            for(int[] st : strips(wl)){
                int b = st[2];
                if(support) b = supportRow(b, st[0], st[1]);
                rows(order, b, st[0], st[1]);
            }
            if(turrets() == 0){ failure = "no turret fits beside the route within range"; return false; }
            int before = turrets();
            connectLanes();
            gapFill();
            if(System.getProperty("defdebug") != null) System.out.println("layout " + templateName + " front=" + front + " turrets " + before + " -> " + turrets() + " lanes " + lanes.size() + " unfed " + unfed + " failed " + failedLanes);
            if(turrets() == 0){ failure = failure != null ? failure : "the turrets' supply lanes couldn't be connected to the inputs"; return false; }
            projectors();
            power();
            prune();
            walls();
            mines();
            validate();
            finish();
            return true;
        }catch(CancelledException e){
            throw e;
        }
    }

    // ------------------------------------------------------------------ ports

    void ports(){
        // tiles the player picked stay reserved for their network
        for(int k = 0; k < p.nets.length; k++){
            for(int[] st : netStarts[k]) if(in(st[0], st[1]) && occ[st[0]][st[1]] == FREE) occ[st[0]][st[1]] = PORT + k;
        }
        for(int[] pp : p.powerPorts) if(in(pp[0], pp[1])) occ[pp[0]][pp[1]] = POWER;
        List<int[]> taken = new ArrayList<>();
        for(int k = 0; k < p.nets.length; k++){
            Net net = p.nets[k];
            if(!net.auto && net.starts.length > 0) continue;
            int[] t = rearTile(taken);
            if(t == null) continue;
            taken.add(t);
            netStarts[k] = new int[][]{{t[0], t[1], -1, 0}};
            occ[t[0]][t[1]] = PORT + k;
            chosenPorts.add(new int[]{k, t[0], t[1]});
        }
        // a router on every single-tile port: a hub the trunks branch from (it takes the player's belt from any side)
        for(int k = 0; k < p.nets.length; k++){
            int[][] st = netStarts[k];
            if(st.length != 1 || st[0][3] == 1) continue;
            int u = st[0][0], v = st[0][1];
            if(!in(u, v) || occ[u][v] != PORT + k || !pushOk(k, u, v)) continue;
            setNet(u, v, k, GridRouter.ROUTER, -1);
            places.add(new Place(GridRouter.ROUTER, k, u, v, 0, u, v));
            // one side stays empty: the player's belt / pipe comes in there (the side farthest from the region)
            int bd = -1, bu = -1, bv = -1;
            for(int d = 0; d < 4; d++){
                int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
                if(!in(nu, nv) || occ[nu][nv] != FREE) continue;
                int dist = Math.max(Math.max(p.rx0 - nu, nu - (p.rx0 + p.rw - 1)), Math.max(p.ry0 - nv, nv - (p.ry0 + p.rh - 1)));
                if(dist > bd){ bd = dist; bu = nu; bv = nv; }
            }
            if(bu >= 0){ occ[bu][bv] = BLOCKED; playerSide.add(new int[]{k, bu, bv}); }
        }
        if(p.powerAuto){
            int[] t = rearTile(taken);
            if(t != null && p.nodeOk[t[0]][t[1]]){ chosenPowerPort = t; occ[t[0]][t[1]] = POWER; }
        }
    }

    /** a free tile just outside the region behind it (far from the route), 2 apart from the taken ones */
    int[] rearTile(List<int[]> taken){
        int[] best = null;
        double bs = Double.NEGATIVE_INFINITY;
        int cu = p.rx0 + p.rw / 2, cv = p.ry0 + p.rh / 2;
        for(int u = Math.max(0, p.rx0 - 2); u < Math.min(W, p.rx0 + p.rw + 2); u++) for(int v = Math.max(0, p.ry0 - 2); v < Math.min(H, p.ry0 + p.rh + 2); v++){
            if(inRegion(u, v) || occ[u][v] != FREE || !ourSide[u][v]) continue;
            // room to branch from it
            int open = 0;
            for(int d = 0; d < 4; d++){
                int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
                if(in(nu, nv) && occ[nu][nv] == FREE) open++;
            }
            if(open < 2) continue;
            boolean near = false;
            for(int[] t : taken) if(Math.abs(t[0] - u) + Math.abs(t[1] - v) < 2) near = true;
            if(near) continue;
            // behind the region, near the middle of the back edge
            double sc = Math.min(pd[u][v], 30) - 0.2 * (Math.abs(u - cu) + Math.abs(v - cv));
            if(sc > bs){ bs = sc; best = new int[]{u, v}; }
        }
        return best;
    }

    // ------------------------------------------------------------------ walls, support row

    /**
     * Reserves the wall tiles: in every column, the first {@code wallThickness} usable tiles behind
     * the corridor (following the route). Returns the first depth for the rows behind them.
     */
    int[] walls0(){
        int T = p.wallThickness;
        int[] wl = new int[A()];
        for(int a = 0; a < A(); a++){
            int f = -1;
            for(int b = 0; b < B(); b++){
                int o = occ[fu(a, b)][fv(a, b)];
                if(o == CORRIDOR || o == ROUTE || o == OTHER) continue;
                f = b;
                break;
            }
            wl[a] = f < 0 ? -1 : f + T;
            if(f < 0) continue;
            for(int b = f; b < Math.min(B(), f + T); b++){
                int u = fu(a, b), v = fv(a, b);
                if(occ[u][v] == FREE) occ[u][v] = WALLTILE;
            }
        }
        return wl;
    }

    /** strips can't vary more than this in wall-line depth (tiles lost in front of the rows) */
    static final int STRIP_TOLERANCE = 3;

    /**
     * Columns grouped into strips where the wall line stays within {@link #STRIP_TOLERANCE}: each
     * strip gets its own rows, starting right behind its deepest wall tile, so the layout follows a
     * route that runs diagonally. Between strips one column stays free: trunks reach the lane ends
     * through it. Returns {a0, a1, b0} per strip.
     */
    List<int[]> strips(int[] wl){
        List<int[]> out = new ArrayList<>();
        int a0 = -1, lo = 0, hi = 0;
        for(int a = 0; a <= A(); a++){
            boolean end = a == A() || wl[a] < 0;
            if(!end && a0 >= 0 && (Math.max(hi, wl[a]) - Math.min(lo, wl[a]) > STRIP_TOLERANCE)){
                // close the strip; this column serves the trunks
                if(a - 1 >= a0) out.add(new int[]{a0, a - 1, hi});
                serviceColumn(a);
                a0 = -1;
                continue;
            }
            if(end){
                if(a0 >= 0) out.add(new int[]{a0, a - 1, hi});
                a0 = -1;
                continue;
            }
            if(a0 < 0){ a0 = a; lo = hi = wl[a]; }
            else{ lo = Math.min(lo, wl[a]); hi = Math.max(hi, wl[a]); }
        }
        return out;
    }

    /** grid tiles kept for trunks between strips */
    boolean[][] service;

    void serviceColumn(int a){
        for(int b = 0; b < B(); b++) service[fu(a, b)][fv(a, b)] = true;
    }

    boolean hasSupport(){
        for(Type t : p.types) if(t.kind == MENDER && t.size == 1) return true;
        return false;
    }

    /** support row: a node slot every 5 tiles, menders between */
    int supportRow(int b, int a0, int a1){
        if(b >= B()) return b;
        int mender = -1;
        for(int i = 0; i < p.types.length; i++) if(p.types[i].kind == MENDER && p.types[i].size == 1) mender = i;
        for(int a = a0; a <= a1; a++){
            int u = fu(a, b), v = fv(a, b);
            if(occ[u][v] != FREE) continue;
            if(a % 5 == 2 && p.nodeOk[u][v]){ occ[u][v] = SLOT; continue; }
            if(mender >= 0 && p.fits[mender][u][v]) addBuilt(mender, u, v);
        }
        return b + 1;
    }

    // ------------------------------------------------------------------ turret rows and lanes

    /** turret rows (from `order`, the last type repeating) with lanes between them, from depth b */
    void rows(int[] order, int b, int a0, int a1){
        int first = lanes.size();
        int idx = 0, emptyRows = 0;
        int prevType = -1;
        boolean started = false;
        Set<Integer> prevGot = new HashSet<>();
        while(b < B() && turrets() < p.maxTurrets){
            checkCancel();
            int ti = order[Math.min(idx, order.length - 1)];
            Type t = p.types[ti];
            // lane between the previous row and this one
            if(prevType >= 0){
                int net = laneNet(p.types[prevType], prevGot, t);
                if(net >= 0){
                    lane(net, b, a0, a1);
                    prevGot.add(net);
                    b++;
                    if(b >= B()) break;
                }
            }
            if(b + t.size > B()) break;
            int placed = turretRow(ti, b, a0, a1);
            if(placed == 0){
                // nothing fits here (rocks, the corridor, out of range): skip a row and start afresh
                // (a lane just laid stays: it still feeds the row in front of it)
                if(prevType >= 0 && (lanes.size() == first || lanes.get(lanes.size() - 1).b != b - 1)){
                    int net = laneNet(p.types[prevType], prevGot, null);
                    if(net >= 0) lane(net, b, a0, a1);
                }
                prevType = -1;
                prevGot = new HashSet<>();
                b++;
                if(started && ++emptyRows >= 3) break;
                continue;
            }
            started = true;
            emptyRows = 0;
            // what this row gets from the lane in front of it
            Set<Integer> got = new HashSet<>();
            if(lanes.size() > first && lanes.get(lanes.size() - 1).b == b - 1) got.add(lanes.get(lanes.size() - 1).net);
            prevType = ti;
            prevGot = got;
            b += t.size;
            idx++;
        }
        // the last row still needs its lane behind it
        if(prevType >= 0 && b < B()){
            int net = laneNet(p.types[prevType], prevGot, null);
            if(net >= 0) lane(net, b, a0, a1);
        }
    }

    /** network of the lane between a row of `prev` (already getting `got`) and a row of `next` */
    int laneNet(Type prev, Set<Integer> got, Type next){
        // what the row in front still needs
        for(int k : required(prev)) if(!got.contains(k)) return k;
        // coolant for both rows (the next row gets its ammo from the lane behind it): T I T Q T I ...
        int ln = liquidNet();
        if(ln >= 0 && !got.contains(ln) && (prev.coolantBoost > 1.001 || next != null && next.coolantBoost > 1.001)) return ln;
        if(next != null) for(int k : required(next)) return k;
        return -1;
    }

    /** fills a turret row; returns how many were placed */
    int turretRow(int ti, int b, int a0, int a1){
        Type t = p.types[ti];
        int n = 0;
        for(int a = a0; a + t.size - 1 <= a1; ){
            int[] c = corner(a, b, t.size);
            if(footprintFree(c[0], c[1], t.size) && p.fits[ti][c[0]][c[1]] && value(t, c[0], c[1], 1) > 0 && turrets() < p.maxTurrets){
                addBuilt(ti, c[0], c[1]);
                addDps(t, c[0], c[1], 1);
                n++;
                a += t.size;
            }else a++;
        }
        snapshot("turrets", false);
        return n;
    }

    void lane(int net, int b, int from, int to){
        Lane l = new Lane(net, b);
        int a0 = -1;
        for(int a = from; a <= to + 1; a++){
            boolean ok = a <= to && occ[fu(a, b)][fv(a, b)] == FREE
                && !(p.nets[net].liquid ? p.nearForeignLiquids : p.nearForeignItems)[fu(a, b)][fv(a, b)];
            if(ok && a0 < 0) a0 = a;
            if(!ok && a0 >= 0){ l.segs.add(new int[]{a0, a - 1}); a0 = -1; }
        }
        for(int[] s : l.segs) for(int a = s[0]; a <= s[1]; a++){
            int u = fu(a, b), v = fv(a, b);
            occ[u][v] = NET + net;
            netAt[u][v] = net;
            kindAt[u][v] = GridRouter.PIPE;
        }
        lanes.add(l);
    }

    /** a lane tile at (a, b) is next to a building that uses its network */
    boolean feedsHere(Lane l, int a){
        for(int db = -1; db <= 1; db += 2){
            int b = l.b + db;
            if(b < 0 || b >= B()) continue;
            int o = occ[fu(a, b)][fv(a, b)];
            if(o >= BUILDING && o < POWER && uses(p.types[o - BUILDING], l.net)) return true;
        }
        return false;
    }

    void freeTile(int u, int v){
        occ[u][v] = FREE;
        placedAt[u][v] = false;
        netAt[u][v] = -1;
        kindAt[u][v] = -1;
    }

    /**
     * Trims each lane segment to the span next to its users, connects one end of it to the network
     * by a trunk, and sets its blocks: routers next to users and at both ends, belts / pipes
     * between, flowing away from the connected end. Turrets left without a required supply go.
     */
    void connectLanes(){
        for(int k = 0; k < p.nets.length; k++){
            Net net = p.nets[k];
            int kk = k;
            routers = routers == null ? new GridRouter[p.nets.length] : routers;
            routers[k] = new GridRouter(W, H, new GridRouter.Grid(){
                public double tileCost(int u, int v){ return trunkCost(kk, u, v); }
                public boolean pushOk(int u, int v){ return DefensePlanner.this.pushOk(kk, u, v); }
            });
            routers[k].bridgeRange = net.bridgeRange;
            // a jump covers several tiles but occupies two: cheap in footprint
            routers[k].bridgeCost = 1.2;
        }
        // trim
        for(Lane l : lanes){
            List<int[]> keep = new ArrayList<>();
            for(int[] s : l.segs){
                int first = -1, last = -1;
                for(int a = s[0]; a <= s[1]; a++) if(feedsHere(l, a)){ if(first < 0) first = a; last = a; }
                for(int a = s[0]; a <= s[1]; a++){
                    if(first >= 0 && a >= first && a <= last) continue;
                    // keep the ends reachable: one extra tile toward a free side is not needed; drop the rest
                    freeTile(fu(a, l.b), fv(a, l.b));
                }
                if(first >= 0) keep.add(new int[]{first, last});
            }
            l.segs.clear();
            l.segs.addAll(keep);
        }
        // the way into every lane end
        for(Lane l : lanes) for(int[] sg : l.segs){
            for(int a : new int[]{sg[0] - 1, sg[1] + 1}){
                int u = fu(a, l.b), v = fv(a, l.b);
                if(in(u, v)) access[u][v] = true;
            }
        }
        // connect, the network's nearest segments first
        List<Object[]> work = new ArrayList<>();
        for(Lane l : lanes) for(int[] s : l.segs) work.add(new Object[]{l, s});
        for(int round = 0; round < 2 && !work.isEmpty(); round++){
            List<Object[]> failed = new ArrayList<>();
            for(Object[] w : work){
                checkCancel();
                Lane l = (Lane)w[0];
                int[] s = (int[])w[1];
                if(!connectSegment(l, s)) failed.add(w);
                snapshot("supply", false);
            }
            work = failed;
        }
        for(Object[] w : work){
            Lane l = (Lane)w[0];
            int[] s = (int[])w[1];
            failedLanes.add("net " + l.net + " b=" + l.b + " a=" + s[0] + ".." + s[1]);
            for(int a = s[0]; a <= s[1]; a++) freeTile(fu(a, l.b), fv(a, l.b));
            l.segs.remove(s);
        }
        dropUnfed();
    }

    /** the trunk to one end of a lane segment (the cheaper one), then the segment's blocks */
    boolean connectSegment(Lane l, int[] s){
        int k = l.net;
        List<Start> st = starts(k);
        if(st.isEmpty()) return false;
        List<Step> best = null;
        double bc = Double.MAX_VALUE;
        Start bs = null;
        int head = -1;
        for(int end = 0; end < 2; end++){
            int a = end == 0 ? s[0] : s[1];
            int u = fu(a, l.b), v = fv(a, l.b);
            // the end tile is ours (a lane tile): route to it like to a 1x1 consumer
            int save = occ[u][v];
            occ[u][v] = BUILDING - 1;
            List<Step> path = routers[k].route(st, u, v, 1);
            occ[u][v] = save;
            if(path != null && routers[k].lastCost < bc){ bc = routers[k].lastCost; best = path; bs = routers[k].lastStart; head = a; }
            if(s[0] == s[1]) break;
        }
        if(best == null){
            if(System.getProperty("defdebug") != null){
                StringBuilder sb = new StringBuilder("connect fail net " + k + " lane b=" + l.b + " ends " + fu(s[0], l.b) + "," + fv(s[0], l.b) + " / " + fu(s[1], l.b) + "," + fv(s[1], l.b) + " starts:");
                for(Start x : st) sb.append(" ").append(x.u).append(",").append(x.v);
                System.out.println(sb);
                for(int v = H - 1; v >= 0; v--){
                    StringBuilder r = new StringBuilder("   ");
                    for(int u = 0; u < W; u++){ double c = trunkCost(k, u, v); r.append(c < 0 ? (occ[u][v] >= NET && occ[u][v] < BUILDING ? 'n' : occ[u][v] >= BUILDING ? 'X' : '#') : '.'); }
                    System.out.println(r);
                }
            }
            return false;
        }
        if(bs != null && bs.cu >= 0){
            setNet(bs.cu, bs.cv, k, GridRouter.ROUTER, -1);
            replace(bs.cu, bs.cv, new Place(GridRouter.ROUTER, k, bs.cu, bs.cv, 0, bs.cu, bs.cv));
        }
        for(Step step : best){
            setNet(step.u, step.v, k, step.kind, step.kind == GridRouter.END ? endIncoming(best, step) : -1);
            places.add(new Place(step.kind, k, step.u, step.v, step.dir, step.lu, step.lv));
        }
        // the lane itself, flowing from the head
        int tail = head == s[0] ? s[1] : s[0], step = tail >= head ? 1 : -1;
        // routers next to users not fed yet; at most 3 in a row: a router pushes back into the router
        // before it, so long router chains move items by random walk, while a belt after them doesn't
        // take anything back (it never takes from the tile it points to)
        Set<Integer> fed = new HashSet<>();
        int run = 0;
        for(int a = head; ; a += step){
            int u = fu(a, l.b), v = fv(a, l.b);
            boolean router;
            if(a == head || a == tail) router = true;
            else if(run >= 3) router = false;
            else{
                router = false;
                for(int db = -1; db <= 1; db += 2){
                    int b = l.b + db;
                    if(b < 0 || b >= B()) continue;
                    int o = occ[fu(a, b)][fv(a, b)];
                    if(o >= BUILDING && o < POWER && uses(p.types[o - BUILDING], l.net)){
                        Built bt = builtAt(fu(a, b), fv(a, b));
                        if(bt != null && !fed.contains(System.identityHashCode(bt))) router = true;
                    }
                }
            }
            if(router){
                run++;
                for(int db = -1; db <= 1; db += 2){
                    int b = l.b + db;
                    if(b < 0 || b >= B()) continue;
                    Built bt = builtAt(fu(a, b), fv(a, b));
                    if(bt != null) fed.add(System.identityHashCode(bt));
                }
            }else run = 0;
            int dir = laneDir(step);
            setNet(u, v, k, router ? GridRouter.ROUTER : GridRouter.PIPE, -1);
            places.add(new Place(router ? GridRouter.ROUTER : GridRouter.PIPE, k, u, v, dir, u, v));
            if(a == tail) break;
        }
        return true;
    }

    // ------------------------------------------------------------------ gap filling

    /**
     * Free ground left on our side (in front of the rows where the route runs diagonally, around
     * rocks): turrets there, greedy by value, each fed by a lane router next to it or by a short
     * branch off the network (at most ~8 tiles).
     */
    void gapFill(){
        int added = 0;
        while(turrets() < p.maxTurrets && added < 200){
            checkCancel();
            double[][][] fields = new double[p.nets.length][][];
            int bt = -1, bu = -1, bv = -1;
            double best = 0;
            for(int ti = 0; ti < p.types.length; ti++){
                Type t = p.types[ti];
                if(t.kind != TURRET || !suppliable(t)) continue;
                for(int u = p.rx0; u + t.size <= p.rx0 + p.rw; u++) for(int v = p.ry0; v + t.size <= p.ry0 + p.rh; v++){
                    if(!p.fits[ti][u][v] || !footprintFree(u, v, t.size) || !ourSide[u][v] || onService(u, v, t.size)) continue;
                    double conn = 0;
                    for(int k : required(t)){
                        if(fedBy(k, u, v, t.size)) continue;
                        if(fields[k] == null) fields[k] = routers[k].field(starts(k));
                        double e = Double.MAX_VALUE;
                        for(int[] a : perimeter(u, v, t.size)) if(in(a[0], a[1])) e = Math.min(e, fields[k][a[0]][a[1]]);
                        conn += e;
                    }
                    if(conn > 8) continue;
                    double val = value(t, u, v, 1) / (1 + 0.15 * conn);
                    if(val > best){ best = val; bt = ti; bu = u; bv = v; }
                }
            }
            if(bt < 0) break;
            Type t = p.types[bt];
            int mark = places.size();
            addBuilt(bt, bu, bv);
            boolean ok = true;
            for(int k : required(t)){
                if(fedBy(k, bu, bv, t.size)) continue;
                if(!connectFootprint(k, bu, bv, t.size)){ ok = false; break; }
            }
            if(!ok){
                undoTo(mark);
                // don't try this spot again
                for(int i = 0; i < t.size; i++) for(int j = 0; j < t.size; j++) occ[bu + i][bv + j] = BLOCKED;
                continue;
            }
            addDps(t, bu, bv, 1);
            added++;
            snapshot("filling gaps", false);
        }
        // spots given up are free again
        for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++){
            if(occ[u][v] == BLOCKED && p.free[u][v] && pd[u][v] > (groundRoutes.isEmpty() ? -1 : p.clearance) && ourSide[u][v]) occ[u][v] = FREE;
        }
    }

    /** a branch of network k ending next to the footprint */
    boolean connectFootprint(int k, int u, int v, int size){
        List<Start> st = starts(k);
        if(st.isEmpty()) return false;
        List<Step> path = routers[k].route(st, u, v, size);
        if(path == null || routers[k].lastCost > 12) return false;
        Start s = routers[k].lastStart;
        if(s != null && s.cu >= 0){
            setNet(s.cu, s.cv, k, GridRouter.ROUTER, -1);
            replace(s.cu, s.cv, new Place(GridRouter.ROUTER, k, s.cu, s.cv, 0, s.cu, s.cv));
        }
        for(Step step : path){
            setNet(step.u, step.v, k, step.kind, step.kind == GridRouter.END ? endIncoming(path, step) : -1);
            places.add(new Place(step.kind, k, step.u, step.v, step.dir, step.lu, step.lv));
        }
        return true;
    }

    /** removes what was placed since `mark` (a turret and its branches) */
    void undoTo(int mark){
        while(places.size() > mark){
            Place pl = places.remove(places.size() - 1);
            if(pl.kind == BLOCK){
                for(int i = built.size() - 1; i >= 0; i--){
                    Built b = built.get(i);
                    if(b.u == pl.u && b.v == pl.v && b.type == pl.type){ built.remove(i); break; }
                }
                int s = p.types[pl.type].size;
                for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) occ[pl.u + i][pl.v + j] = FREE;
                continue;
            }
            freeTile(pl.u, pl.v);
            endBack[pl.u][pl.v] = -1;
        }
        // pushed marks: recompute from what is placed
        for(boolean[][] a : pushedBy) for(boolean[] r : a) Arrays.fill(r, false);
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int k = netAt[u][v], kind = kindAt[u][v];
            if(k < 0 || !placedAt[u][v] || !(kind == GridRouter.ROUTER || kind == GridRouter.END || kind == GridRouter.SOURCE)) continue;
            for(int d = 0; d < 4; d++){
                int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
                if(in(nu, nv)) pushedBy[k][nu][nv] = true;
            }
        }
    }

    /** grid direction of increasing a (step 1) or decreasing a (step -1) */
    int laneDir(int step){
        int d = front < 2 ? 0 : 1; // a runs along x for south / north fronts, along y otherwise
        return step > 0 ? d : (d + 2) % 4;
    }

    /** turrets without a required supply go; their lane tiles stay (routers just pass items on) */
    void dropUnfed(){
        for(int i = built.size() - 1; i >= 0; i--){
            Built bt = built.get(i);
            Type t = p.types[bt.type];
            if(t.kind != TURRET) continue;
            boolean ok = true;
            for(int k : required(t)) if(!fedBy(k, bt.u, bt.v, t.size) && !pointedAt(k, bt.u, bt.v, t.size)){ ok = false; break; }
            if(ok){
                if(liquidNet() >= 0 && fedBy(liquidNet(), bt.u, bt.v, t.size) && (t.coolantBoost > 1.001 || t.liquidAmmo || t.coolantRequired)) bt.cooled = true;
                continue;
            }
            removeBuilt(i);
            unfed++;
        }
        // cooled turrets fire faster
        Arrays.fill(dps, 0);
        for(Built bt : built){
            Type t = p.types[bt.type];
            if(t.kind == TURRET) addDps(t, bt.u, bt.v, bt.cooled && !t.liquidAmmo && !t.coolantRequired ? t.coolantBoost : 1);
        }
    }

    void removeBuilt(int i){
        Built bt = built.remove(i);
        Type t = p.types[bt.type];
        for(int a = 0; a < t.size; a++) for(int b = 0; b < t.size; b++) occ[bt.u + a][bt.v + b] = FREE;
        for(int j = places.size() - 1; j >= 0; j--){
            Place q = places.get(j);
            if(q.kind == BLOCK && q.u == bt.u && q.v == bt.v){ places.remove(j); break; }
        }
    }

    // ------------------------------------------------------------------ trunk routing

    /** cost of a trunk tile of network k: free tiles outside the rows, dearer near the route */
    double trunkCost(int k, int u, int v){
        if(!in(u, v)) return -1;
        int o = occ[u][v];
        if(o != FREE && o != PORT + k) return -1;
        boolean liquid = p.nets[k].liquid;
        for(int j = 0; j < p.nets.length; j++){
            if(j != k && p.nets[j].liquid == liquid && pushedBy[j][u][v]) return -1;
        }
        for(int d = 0; d < 4; d++){
            int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
            if(!in(nu, nv)) continue;
            int j = netAt[nu][nv];
            if(j >= 0 && j != k && p.nets[j].liquid == liquid && !placedAt[nu][nv]) return -1;
        }
        // enemies shoot within their range of the route: keep the trunk out of it
        double er = p.enemyRange + 4;
        double danger = groundRoutes.isEmpty() ? 0 : 4 * Math.max(0, (er - pd[u][v]) / er);
        return 1 + danger + (inRegion(u, v) ? 1 : 0) + (access[u][v] ? 30 : 0);
    }

    /** a router / end bridge / unloader of network k here pushes into nothing that isn't its own */
    boolean pushOk(int k, int u, int v){
        boolean liquid = p.nets[k].liquid;
        if(liquid ? p.nearForeignLiquids[u][v] : p.nearForeignItems[u][v]) return false;
        for(int d = 0; d < 4; d++){
            int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
            if(!in(nu, nv)) continue;
            int j = netAt[nu][nv];
            if(j >= 0 && j != k && p.nets[j].liquid == liquid) return false;
        }
        return true;
    }

    /** Branch points of network k: its ports, free neighbours of its pushing blocks, pipes it may turn into routers. */
    List<Start> starts(int k){
        List<Start> out = new ArrayList<>();
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            if(netAt[u][v] != k || kindAt[u][v] < 0 || !placedNet(u, v)) continue;
            int kind = kindAt[u][v];
            boolean pusher = kind == GridRouter.ROUTER || kind == GridRouter.END || kind == GridRouter.SOURCE;
            boolean pipe = kind == GridRouter.PIPE;
            if(!pusher && !(pipe && pushOk(k, u, v))) continue;
            for(int d = 0; d < 4; d++){
                int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
                if(!in(nu, nv) || occ[nu][nv] != FREE && occ[nu][nv] != PORT + k) continue;
                if(kind == GridRouter.END && d == endBack[u][v]) continue;
                Start s = new Start(nu, nv, (d + 2) % 4, pusher ? 0 : p.routerPrice / p.pipePrice, false);
                if(pipe){ s.cu = u; s.cv = v; }
                out.add(s);
            }
        }
        for(int[] s : netStarts[k]){
            if(in(s[0], s[1]) && occ[s[0]][s[1]] == PORT + k) out.add(new Start(s[0], s[1], s[2], s[3] == 1 ? p.unloaderPrice / p.pipePrice : 0, s[3] == 1));
        }
        return out;
    }

    /** this network tile already has its block placed (lanes are only reserved until connected) */
    boolean placedNet(int u, int v){ return placedAt[u][v]; }

    int endIncoming(List<Step> path, Step end){
        for(Step s : path){
            if(s.kind == GridRouter.BRIDGE && s.lu == end.u && s.lv == end.v) return (s.dir + 2) % 4;
        }
        return -1;
    }

    void replace(int u, int v, Place pl){
        for(int i = 0; i < places.size(); i++){
            Place o = places.get(i);
            if(o.kind != BLOCK && o.kind != NODE && o.u == u && o.v == v){ places.set(i, pl); return; }
        }
        places.add(pl);
    }

    void setNet(int u, int v, int k, int kind, int back){
        netAt[u][v] = k;
        kindAt[u][v] = kind;
        occ[u][v] = NET + k;
        endBack[u][v] = back;
        placedAt[u][v] = true;
        if(kind == GridRouter.ROUTER || kind == GridRouter.END || kind == GridRouter.SOURCE){
            for(int d = 0; d < 4; d++){
                int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
                if(in(nu, nv)) pushedBy[k][nu][nv] = true;
            }
        }
    }

    boolean fedBy(int k, int u, int v, int size){
        for(int[] a : perimeter(u, v, size)){
            if(!in(a[0], a[1]) || netAt[a[0]][a[1]] != k || !placedNet(a[0], a[1])) continue;
            int kind = kindAt[a[0]][a[1]];
            if(kind == GridRouter.ROUTER || kind == GridRouter.SOURCE) return true;
            if(kind == GridRouter.END && endBack[a[0]][a[1]] != a[2]) return true;
        }
        return false;
    }

    /** tiles around a footprint with the direction from them into it: {u, v, dir} */
    static List<int[]> perimeter(int u, int v, int size){
        List<int[]> out = new ArrayList<>(size * 4);
        for(int i = 0; i < size; i++){
            out.add(new int[]{u - 1, v + i, 0});
            out.add(new int[]{u + size, v + i, 2});
            out.add(new int[]{u + i, v - 1, 1});
            out.add(new int[]{u + i, v + size, 3});
        }
        return out;
    }

    // ------------------------------------------------------------------ value

    double F(double d){ return K * Math.log(1 + d / K); }

    /** damage value a turret of type t at (u,v) adds, with `boost` x its DPS */
    public double value(Type t, int u, int v, double boost){
        if(t.kind != TURRET) return 0;
        double cx = u + t.size / 2.0 - 0.5, cy = v + t.size / 2.0 - 0.5;
        double r2 = (t.range + 0.5) * (t.range + 0.5), m2 = t.minRange * t.minRange;
        double sum = 0;
        for(int i = 0; i < rx.length; i++){
            double d = rAir[i] ? t.dpsAir : t.dpsGround;
            if(d <= 0) continue;
            double dx = rx[i] - cx, dy = ry[i] - cy, dd = dx * dx + dy * dy;
            if(dd > r2 || dd < m2) continue;
            sum += rw[i] * (F(dps[i] + d * boost) - F(dps[i]));
        }
        return sum;
    }

    void addDps(Type t, int u, int v, double factor){
        double cx = u + t.size / 2.0 - 0.5, cy = v + t.size / 2.0 - 0.5;
        double r2 = (t.range + 0.5) * (t.range + 0.5), m2 = t.minRange * t.minRange;
        for(int i = 0; i < rx.length; i++){
            double d = rAir[i] ? t.dpsAir : t.dpsGround;
            if(d <= 0) continue;
            double dx = rx[i] - cx, dy = ry[i] - cy, dd = dx * dx + dy * dy;
            if(dd > r2 || dd < m2) continue;
            dps[i] += d * factor;
        }
    }

    boolean footprintFree(int u, int v, int s){
        if(u < 0 || v < 0 || u + s > W || v + s > H) return false;
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) if(occ[u + i][v + j] != FREE || !inRegion(u + i, v + j)) return false;
        return true;
    }

    int turrets(){
        int n = 0;
        for(Built b : built) if(p.types[b.type].kind == TURRET) n++;
        return n;
    }

    void addBuilt(int type, int u, int v){
        built.add(new Built(type, u, v));
        places.add(new Place(BLOCK, type, u, v, 0, u, v));
        int s = p.types[type].size;
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) occ[u + i][v + j] = BUILDING + type;
    }

    /** enemy fire a tile can receive: route weight within enemy range */
    double exposure(int u, int v){
        double er = p.enemyRange + 1, sum = 0;
        for(int i = 0; i < rx.length; i++){
            if(rAir[i]) continue;
            double dx = rx[i] - u, dy = ry[i] - v;
            if(dx * dx + dy * dy <= er * er) sum += rw[i];
        }
        return sum;
    }

    // ------------------------------------------------------------------ projectors

    /**
     * Force / mend projectors replace turrets (and use free row tiles) where they cover the most
     * exposed turrets; at most one per ~8 turrets each. Never on lanes, walls or the support row.
     */
    void projectors(){
        for(int ti = 0; ti < p.types.length; ti++){
            Type t = p.types[ti];
            if(!(t.kind == SHIELD || t.kind == MENDER && t.size > 1)) continue;
            int count = Math.max(1, turrets() / 8);
            for(int c = 0; c < count; c++){
                checkCancel();
                int bu = -1, bv = -1;
                double best = 0;
                for(int u = p.rx0; u + t.size <= p.rx0 + p.rw; u++) for(int v = p.ry0; v + t.size <= p.ry0 + p.rh; v++){
                    if(!p.fits[ti][u][v] || onService(u, v, t.size)) continue;
                    // only free tiles and turrets under it; the turrets it replaces count against it
                    boolean ok = true;
                    double lost = 0;
                    Set<Built> under = new HashSet<>();
                    for(int i = 0; i < t.size && ok; i++) for(int j = 0; j < t.size; j++){
                        int o = occ[u + i][v + j];
                        if(o == FREE) continue;
                        if(o >= BUILDING && o < POWER && p.types[o - BUILDING].kind == TURRET){ under.add(builtAt(u + i, v + j)); continue; }
                        ok = false; break;
                    }
                    if(!ok) continue;
                    for(Built b : under) lost += 1;
                    double cx = u + t.size / 2.0 - 0.5, cy = v + t.size / 2.0 - 0.5, r2 = (t.range + 0.5) * (t.range + 0.5);
                    double val = 0;
                    for(Built b : built){
                        Type bt = p.types[b.type];
                        if(bt.kind != TURRET || under.contains(b)) continue;
                        double dx = b.u + bt.size / 2.0 - 0.5 - cx, dy = b.v + bt.size / 2.0 - 0.5 - cy;
                        if(dx * dx + dy * dy <= r2) val += exposure(b.u, b.v) + 0.1;
                    }
                    val = val / (1 + 0.5 * lost);
                    if(val > best){ best = val; bu = u; bv = v; }
                }
                if(bu < 0) break;
                for(int i = built.size() - 1; i >= 0; i--){
                    Built b = built.get(i);
                    Type bt = p.types[b.type];
                    if(b.u < bu + t.size && b.u + bt.size > bu && b.v < bv + t.size && b.v + bt.size > bv) removeBuilt(i);
                }
                addBuilt(ti, bu, bv);
                protectors++;
                snapshot("projectors", false);
            }
        }
        // the remaining turrets' damage
        Arrays.fill(dps, 0);
        for(Built bt : built){
            Type t = p.types[bt.type];
            if(t.kind == TURRET) addDps(t, bt.u, bt.v, bt.cooled && !t.liquidAmmo && !t.coolantRequired ? t.coolantBoost : 1);
        }
        for(Built b : built) if(p.types[b.type].kind == MENDER && p.types[b.type].size == 1) protectors++;
    }

    Built builtAt(int u, int v){
        for(Built b : built){
            int s = p.types[b.type].size;
            if(u >= b.u && u < b.u + s && v >= b.v && v < b.v + s) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ power

    void power(){
        List<int[]> consumers = new ArrayList<>();
        Map<int[], Built> owner = new HashMap<>();
        for(Built b : built){
            Type t = p.types[b.type];
            if(!t.powered) continue;
            int[] c = {b.u, b.v, t.size};
            consumers.add(c);
            owner.put(c, b);
        }
        if(consumers.isEmpty()){ chosenPowerPort = null; return; }
        double er = p.enemyRange + 4;
        PowerNetwork net = new PowerNetwork(W, H, p.nodeRange, p.nodeMaxLinks, new PowerNetwork.Host(){
            public boolean nodeFree(int u, int v){ return (occ[u][v] == FREE || occ[u][v] == SLOT) && p.nodeOk[u][v]; }
            public boolean nodeOk(int u, int v){ return p.nodeOk[u][v] && (occ[u][v] == FREE || occ[u][v] == SLOT || occ[u][v] >= BUILDING); }
            public double penalty(int u, int v){ return (groundRoutes.isEmpty() ? 0 : 300 * Math.max(0, (er - pd[u][v]) / er)) + (occ[u][v] == SLOT ? -50 : 0); }
            public int size(int[] c){ return c[2]; }
            public void occupy(int u, int v){ occ[u][v] = POWER; }
            public void drop(int[] c){
                // no node reaches it: its slot is freed (a node may stand there for the others)
                Built b = owner.get(c);
                int i = built.indexOf(b);
                if(i >= 0) removeBuilt(i);
                unpowered++;
            }
            public Object save(){ int[][] s = new int[W][]; for(int i = 0; i < W; i++) s[i] = occ[i].clone(); return new Object[]{s, new ArrayList<>(built), new ArrayList<>(places)}; }
            @SuppressWarnings("unchecked")
            public void restore(Object s){
                Object[] o = (Object[])s;
                int[][] a = (int[][])o[0];
                for(int i = 0; i < W; i++) occ[i] = a[i].clone();
                built.clear(); built.addAll((List<Built>)o[1]);
                places.clear(); places.addAll((List<Place>)o[2]);
            }
            public void progress(List<int[]> n, String stage){ nodeList = n; snapshot(stage, false); }
            public void checkCancel(){ DefensePlanner.this.checkCancel(); }
        });
        for(int[] a : p.powerAnchors) net.anchors.add(a);
        List<int[]> ports = new ArrayList<>();
        for(int[] pp : p.powerPorts) ports.add(pp);
        if(chosenPowerPort != null) ports.add(chosenPowerPort);
        for(int[] pp : ports){
            if(!in(pp[0], pp[1])) continue;
            occ[pp[0]][pp[1]] = POWER;
            net.nodes.add(new int[]{pp[0], pp[1]});
            net.anchors.add(new int[]{pp[0], pp[1], 1});
        }
        nodeList = net.nodes;
        net.place(consumers);
        nodeList = net.nodes;
        powerGroups = net.groups;
        if(built.stream().noneMatch(b -> p.types[b.type].powered)){
            // nothing left to power
            for(int[] n : nodeList) occ[n[0]][n[1]] = FREE;
            nodeList = new ArrayList<>();
            chosenPowerPort = null;
            powerGroups = 0;
            return;
        }
        // a turret given up for a node: the damage it would have done is gone
        Arrays.fill(dps, 0);
        for(Built bt : built){
            Type t = p.types[bt.type];
            if(t.kind == TURRET) addDps(t, bt.u, bt.v, bt.cooled && !t.liquidAmmo && !t.coolantRequired ? t.coolantBoost : 1);
        }
        int[] comp = net.components();
        Set<Integer> done = new HashSet<>();
        if(!net.anchors.isEmpty()) done.add(0);
        for(int i = 0; i < nodeList.size(); i++){
            if(!done.add(comp[i])) continue;
            extraPowerPorts.add(new int[]{nodeList.get(i)[0], nodeList.get(i)[1]});
        }
        // unused slots of the support row: menders if there are any
        int mender = -1;
        for(int i = 0; i < p.types.length; i++) if(p.types[i].kind == MENDER && p.types[i].size == 1) mender = i;
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            if(occ[u][v] != SLOT) continue;
            occ[u][v] = FREE;
            if(mender >= 0 && p.fits[mender][u][v] && reachedByNode(u, v)) addBuilt(mender, u, v);
        }
    }

    boolean reachedByNode(int u, int v){
        PowerNetwork pn = new PowerNetwork(W, H, p.nodeRange, p.nodeMaxLinks, null);
        for(int[] n : nodeList) if(pn.reaches(n[0], n[1], u, v, 1)) return true;
        return false;
    }

    /**
     * Belts / pipes whose output went away (the turret they fed gave way to a projector or a
     * node) are removed, back along their line: a conduit pointing at nothing leaks.
     */
    void prune(){
        boolean changed = true;
        while(changed){
            changed = false;
            for(int i = places.size() - 1; i >= 0; i--){
                Place q = places.get(i);
                if(q.kind != GridRouter.PIPE) continue;
                int tu = q.u + GridRouter.DX[q.dir], tv = q.v + GridRouter.DY[q.dir];
                boolean alive = false;
                if(in(tu, tv)){
                    if(netAt[tu][tv] == q.type && placedAt[tu][tv]) alive = true;
                    int o = occ[tu][tv];
                    if(o >= BUILDING && o < POWER){
                        Type t = p.types[o - BUILDING];
                        if(uses(t, q.type)) alive = true;
                    }
                }
                if(alive) continue;
                places.remove(i);
                freeTile(q.u, q.v);
                changed = true;
            }
        }
        // turrets that lost their supply that way
        Arrays.fill(dps, 0);
        for(int i = built.size() - 1; i >= 0; i--){
            Built bt = built.get(i);
            Type t = p.types[bt.type];
            if(t.kind != TURRET) continue;
            boolean ok = true;
            for(int k : required(t)) if(!fedBy(k, bt.u, bt.v, t.size) && !pointedAt(k, bt.u, bt.v, t.size)){ ok = false; break; }
            if(!ok){ removeBuilt(i); unfed++; continue; }
            int ln = liquidNet();
            bt.cooled = ln >= 0 && (fedBy(ln, bt.u, bt.v, t.size) || pointedAt(ln, bt.u, bt.v, t.size));
        }
        for(Built bt : built){
            Type t = p.types[bt.type];
            if(t.kind == TURRET) addDps(t, bt.u, bt.v, bt.cooled && !t.liquidAmmo && !t.coolantRequired ? t.coolantBoost : 1);
        }
    }

    boolean onService(int u, int v, int s){
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) if(service[u + i][v + j]) return true;
        return false;
    }

    /** a belt / pipe of network k points into the footprint */
    boolean pointedAt(int k, int u, int v, int size){
        for(int[] a : perimeter(u, v, size)){
            if(!in(a[0], a[1]) || netAt[a[0]][a[1]] != k || !placedAt[a[0]][a[1]] || kindAt[a[0]][a[1]] != GridRouter.PIPE) continue;
            for(Place q : places) if(q.kind == GridRouter.PIPE && q.u == a[0] && q.v == a[1] && q.dir == a[2]) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ walls, mines

    void walls(){
        int large = -1, small = -1;
        for(int ti = 0; ti < p.types.length; ti++){
            Type t = p.types[ti];
            if(t.kind != WALL) continue;
            if(t.size == 2) large = ti; else if(t.size == 1) small = ti;
        }
        boolean[][] want = new boolean[W][H];
        if(large >= 0 || small >= 0){
            // wall tiles in front of something we built (within enemy range of it)
            int reach = (int)Math.ceil(Math.max(6, p.enemyRange));
            List<int[]> structures = new ArrayList<>();
            for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++) if(occ[u][v] >= NET && occ[u][v] < PORT) structures.add(new int[]{u, v});
            for(int u = p.rx0; u < p.rx0 + p.rw; u++) for(int v = p.ry0; v < p.ry0 + p.rh; v++){
                if(occ[u][v] != WALLTILE) continue;
                for(int[] s : structures){
                    int dx = s[0] - u, dy = s[1] - v;
                    if(dx * dx + dy * dy <= reach * reach){ want[u][v] = true; break; }
                }
            }
            if(large >= 0){
                for(int u = 0; u + 1 < W; u++) for(int v = 0; v + 1 < H; v++){
                    if(want[u][v] && want[u + 1][v] && want[u][v + 1] && want[u + 1][v + 1] && p.fits[large][u][v]){
                        addWall(large, u, v);
                        want[u][v] = want[u + 1][v] = want[u][v + 1] = want[u + 1][v + 1] = false;
                    }
                }
            }
            if(small >= 0){
                for(int u = 0; u < W; u++) for(int v = 0; v < H; v++) if(want[u][v] && p.fits[small][u][v]) addWall(small, u, v);
            }
        }
        // wall tiles not used stay empty
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++) if(occ[u][v] == WALLTILE) occ[u][v] = FREE;
    }

    void addWall(int type, int u, int v){
        built.add(new Built(type, u, v));
        places.add(new Place(BLOCK, type, u, v, 0, u, v));
        int s = p.types[type].size;
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) occ[u + i][v + j] = BUILDING + type;
        walls++;
        if(walls % 8 == 0) snapshot("walls", false);
    }

    void mines(){
        if(!p.mines) return;
        int mt = -1;
        for(int ti = 0; ti < p.types.length; ti++) if(p.types[ti].kind == MINE) mt = ti;
        if(mt < 0) return;
        for(int i = 0; i < rx.length; i++){
            if(rAir[i] || dps[i] <= 0) continue;
            int u = (int)rx[i], v = (int)ry[i];
            if(!inRegion(u, v) || occ[u][v] != ROUTE || !p.fits[mt][u][v]) continue;
            built.add(new Built(mt, u, v));
            places.add(new Place(BLOCK, mt, u, v, 0, u, v));
            occ[u][v] = BUILDING + mt;
            mines++;
        }
    }

    // ------------------------------------------------------------------ check, score

    /** the ground route recomputed with every planned solid block */
    void validate(){
        if(groundRoutes.isEmpty() || p.targets == null) return;
        EnemyRoutes after = routes.copy();
        for(Place pl : places){
            if(pl.kind != BLOCK){
                int c = p.logisticsPathCost[p.nets[pl.type].liquid ? 1 : 0][pl.kind];
                if(c > 0) after.addSolid(pl.u + p.ox, pl.v + p.oy, c);
                continue;
            }
            Type t = p.types[pl.type];
            if(!t.solid) continue;
            for(int i = 0; i < t.size; i++) for(int j = 0; j < t.size; j++) after.addSolid(pl.u + i + p.ox, pl.v + j + p.oy, t.pathCost);
        }
        if(p.nodePathCost > 0) for(int[] n : nodeList) after.addSolid(n[0] + p.ox, n[1] + p.oy, p.nodePathCost);
        after.flow(p.targets);
        for(int s : p.groundSpawns){
            int[] r = after.trace(s);
            if(r.length > 1) newGroundRoutes.add(r);
        }
        routeChanged = !inReach(groundRoutes).equals(inReach(newGroundRoutes));
    }

    Set<Integer> inReach(List<int[]> rs){
        Set<Integer> out = new HashSet<>();
        int m = 4;
        for(int[] r : rs) for(int t : r){
            int x = t % p.mapW - p.ox, y = t / p.mapW - p.oy;
            if(x >= p.rx0 - m && y >= p.ry0 - m && x < p.rx0 + p.rw + m && y < p.ry0 + p.rh + m) out.add(t);
        }
        return out;
    }

    void finish(){
        places.removeIf(pl -> pl.kind == NODE);
        // a port hub of a network nothing else uses goes
        for(int k = 0; k < p.nets.length; k++){
            int kk = k;
            long n = places.stream().filter(q -> q.kind < BLOCK && q.type == kk).count();
            if(n != 1) continue;
            Place hub = places.stream().filter(q -> q.kind < BLOCK && q.type == kk).findFirst().get();
            // unless something next to it takes from it
            boolean feeds = false;
            for(int d = 0; d < 4; d++){
                int nu = hub.u + GridRouter.DX[d], nv = hub.v + GridRouter.DY[d];
                if(in(nu, nv) && occ[nu][nv] >= BUILDING && occ[nu][nv] < POWER && uses(p.types[occ[nu][nv] - BUILDING], kk)) feeds = true;
            }
            if(!feeds) places.remove(hub);
        }
        for(int[] n : nodeList) places.add(new Place(NODE, 0, n[0], n[1], 0, n[0], n[1]));
        // ports nothing ended up using need no input
        chosenPorts.removeIf(cp -> places.stream().noneMatch(q -> q.kind < BLOCK && q.type == cp[0]));
        turrets = 0;
        int logistics = 0;
        for(Place pl : places){
            switch(pl.kind){
                case BLOCK: if(p.types[pl.type].kind == TURRET) turrets++; break;
                case NODE: nodes++; logistics++; break;
                case GridRouter.PIPE: case GridRouter.ROUTER: case GridRouter.SOURCE: pipes++; logistics++; break;
                case GridRouter.BRIDGE: case GridRouter.END: bridges++; logistics++; break;
            }
        }
        valueGround = valueAir = 0;
        double gw = 0, aw = 0, gd = 0, ad = 0, gc = 0, ac = 0;
        for(int i = 0; i < rx.length; i++){
            if(rAir[i]){ valueAir += rw[i] * F(dps[i]); aw += rw[i]; ad += rw[i] * dps[i]; if(dps[i] > 0) ac += rw[i]; }
            else{ valueGround += rw[i] * F(dps[i]); gw += rw[i]; gd += rw[i] * dps[i]; if(dps[i] > 0) gc += rw[i]; }
        }
        meanGroundDps = gw > 0 ? gd / gw : 0;
        meanAirDps = aw > 0 ? ad / aw : 0;
        coveredGround = gw > 0 ? gc / gw : 0;
        coveredAir = aw > 0 ? ac / aw : 0;
        // damage on the route, a little less for every supply tile
        score = (valueGround + valueAir) * (1 - 0.002 * logistics) * (routeChanged ? 0.8 : 1);
    }
}
