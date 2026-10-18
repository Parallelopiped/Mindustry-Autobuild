package autobuild.plan;

import autobuild.plan.DrillLayoutPlanner.*;
import autobuild.plan.GridRouter.*;

import java.util.*;

/**
 * Defense layout for a region the enemy route passes (pure Java; region-local tiles u = x - ox,
 * v = y - oy; the enemy route model works on the whole map).
 *
 * <ol>
 * <li><b>Routes</b>: the ground route of every spawn ({@link EnemyRoutes}, same flow field as the
 *     game) and the straight lines of flying units. Each route tile is one unit of "time enemies
 *     spend there", weighted by the ground / air share of the coming waves.</li>
 * <li><b>Corridor and wall band</b>: tiles within {@code clearance} (Chebyshev) of the ground
 *     route stay empty - no planned solid block touches a route tile or its neighbours, so the
 *     route's cost is unchanged and every other path only got more expensive: the route stays
 *     where it is. The next {@code wallThickness} rings are kept for walls.</li>
 * <li><b>Turrets</b>: greedy with lazy evaluation. A turret's value is its damage on the route
 *     tiles in range with diminishing returns per tile (F(D) = K ln(1 + D/K) of the DPS D already
 *     there), reduced when enemies can shoot it; minus its price and the cost of connecting its
 *     supply (ammo items / liquid) from the network built so far ({@link GridRouter} distance
 *     fields; a turret next to a router of its network is fed for free). The chosen turret is
 *     connected right away, so the next ones can branch off its line.</li>
 * <li><b>Coolant</b>: turrets whose fire rate a coolant boosts are connected to the liquid network
 *     when the extra damage pays for the pipes.</li>
 * <li><b>Protection</b>: menders / mend projectors / force projectors where they cover the most
 *     exposed structures.</li>
 * <li><b>Power</b>: {@link PowerNetwork} over every powered block, joined to the power ports.</li>
 * <li><b>Walls</b> fill the wall band in front of what was built; <b>shock mines</b> go on the
 *     route itself (not solid: the route doesn't change).</li>
 * <li>The route is recomputed with every planned block to confirm it didn't move.</li>
 * </ol>
 */
public class DefensePlanner{
    // building type kinds
    public static final int TURRET = 0, WALL = 1, MENDER = 2, SHIELD = 3, MINE = 4;
    // place kinds (logistics use GridRouter's kinds 0..4 with `type` = network index)
    public static final int BLOCK = 10, NODE = 11;
    // occupancy
    static final int FREE = -1, BLOCKED = -2, CORRIDOR = -3, BAND = -4, ROUTE = -5;
    // occupied: 1000 + network (logistics), 2000 + type (buildings), 3000 (node), 4000 + network (reserved port tile)
    static final int NET = 1000, BUILDING = 2000, POWER = 3000, PORT = 4000;

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
        /** the planner chooses the port on the region edge (starts empty) */
        public boolean auto;
        public int bridgeRange;
    }

    public static class Params{
        public int W, H;
        // ---- enemy routes, whole map ----
        public int mapW, mapH, ox, oy;
        public int[] pathCost;
        public boolean[] nearSolid;
        public int[] targets, groundSpawns;
        /** flying routes {x0, y0, x1, y1} in map tiles */
        public float[][] airLines = new float[0][];
        public double groundWeight = 1, airWeight = 0;
        /** typical weapon range of ground enemies, tiles */
        public float enemyRange = 7f;
        // ---- region ----
        /** tile is empty and buildable for a 1x1 block, and no existing block pushes items into it */
        public boolean[][] free;
        /** border tiles: the side (0=E 1=N 2=W 3=S) whose outside neighbour is free, so a belt / pipe / laser can come in there; -1 none */
        public int[][] edgeSide;
        /** a router-like block here would push into an existing block of the player's */
        public boolean[][] nearForeignItems, nearForeignLiquids;
        /** per type: placeable with its footprint's min corner here */
        public boolean[][][] fits;
        public Type[] types = new Type[0];
        public Net[] nets = new Net[0];
        public int clearance = 1, wallThickness = 1;
        public int maxTurrets = 12;
        public boolean mines;
        // ---- power ----
        public boolean[][] nodeOk;
        public float nodeRange = 6f;
        public int nodeMaxLinks = 10;
        /** existing power buildings the network joins {u, v, size} (may lie outside the region) */
        public int[][] powerAnchors = new int[0][];
        /** tiles where a port node is placed {u, v} */
        public int[][] powerPorts = new int[0][];
        /** pick a power port on the edge (no anchors / ports given) */
        public boolean powerAuto;
        // ---- prices (in turret-price units, see score) ----
        public double pipePrice = 1, bridgePrice = 6, routerPrice = 3, nodePrice = 2, unloaderPrice = 25;
        /** enemy path cost of a node / of each logistics kind ([liquid ? 1 : 0][GridRouter kind]) when built (0 = not solid) */
        public int nodePathCost;
        public int[][] logisticsPathCost = new int[2][5];
        public int variant;
        public long seed;
    }

    public final Params p;
    public final int W, H;
    final int[][] occ;
    public final List<Place> places = new ArrayList<>();
    public String failure;
    public volatile boolean cancelled;
    public Listener listener;
    public int snapshotInterval = 1;
    int snapCount;

    // ---- routes ----
    public EnemyRoutes routes;
    /** map tiles of each spawn's ground route, and of each air line */
    public final List<int[]> groundRoutes = new ArrayList<>(), airRoutes = new ArrayList<>();
    /** route tiles that matter (in reach): region-local coordinates, weight, air? */
    float[] rx = new float[0], ry = new float[0];
    double[] rw = new double[0];
    boolean[] rAir = new boolean[0];
    /** current DPS on each route tile */
    double[] dps = new double[0];
    /** Chebyshev distance to the ground route (capped) */
    int[][] pd;
    public double K = 50, mu = 1, tileMu = 1;
    Random rand;

    // ---- networks ----
    /** per tile: network index of the logistics block there, else -1 */
    int[][] netAt;
    int[][] kindAt;
    /** per network: tiles next to its pushing blocks */
    boolean[][][] pushedBy;
    /** port starts chosen per network (region-local) */
    public final List<int[]> chosenPorts = new ArrayList<>();
    public int[] chosenPowerPort;

    // ---- placed buildings ----
    public static class Built{
        public final int type, u, v;
        public boolean cooled, powered = true;
        Built(int type, int u, int v){ this.type = type; this.u = u; this.v = v; }
    }
    public final List<Built> built = new ArrayList<>();
    List<int[]> nodeList = new ArrayList<>();

    // ---- results ----
    public double score, valueGround, valueAir;
    public int turrets, walls, protectors, mines, pipes, bridges, nodes, unpowered, powerGroups;
    /** mean DPS over the ground / air route tiles in reach of the region, and the share of them covered */
    public double meanGroundDps, meanAirDps, coveredGround, coveredAir;
    public boolean routeChanged;
    /** ground route tiles after building (map indices), for display */
    public final List<int[]> newGroundRoutes = new ArrayList<>();

    public DefensePlanner(Params p){
        this.p = p;
        W = p.W; H = p.H;
        occ = new int[W][H];
        netAt = new int[W][H];
        kindAt = new int[W][H];
        for(int[] r : netAt) Arrays.fill(r, -1);
        for(int[] r : kindAt) Arrays.fill(r, -1);
        rand = new Random(p.seed * 31 + p.variant);
    }

    boolean in(int u, int v){ return u >= 0 && v >= 0 && u < W && v < H; }

    void checkCancel(){
        if(cancelled) throw new CancelledException();
    }

    void snapshot(String stage, boolean force){
        if(listener == null) return;
        if(!force && (snapCount++ % snapshotInterval) != 0) return;
        listener.snapshot(stage, currentPlaces());
    }

    /** current placements (for the gray replay; builds the list from the state) */
    public List<Place> currentPlaces(){
        List<Place> out = new ArrayList<>(places);
        for(int[] n : nodeList) out.add(new Place(NODE, 0, n[0], n[1], 0, n[0], n[1]));
        return out;
    }

    // ================================================================== plan

    public boolean plan(){
        if(W < 3 || H < 3){ failure = "region too small"; return false; }
        computeRoutes();
        if(groundRoutes.isEmpty() && airRoutes.isEmpty()){
            failure = "no enemy route: the map needs an enemy spawn (or enemy core) and a core of yours";
            return false;
        }
        reach();
        if(rx.length == 0){
            failure = "no enemy route passes within turret range of this region";
            return false;
        }
        zones();
        boolean any = false;
        for(int u = 0; u < W && !any; u++) for(int v = 0; v < H; v++) if(occ[u][v] == FREE){ any = true; break; }
        if(!any){ failure = "nothing in the region can be built on outside the enemy route"; return false; }
        if(!setupNets()) return false;
        placeTurrets();
        if(built.isEmpty()){
            failure = failure != null ? failure : "no turret can be placed and supplied here (check the inputs and the region)";
            return false;
        }
        coolant();
        protect();
        power();
        walls();
        mines();
        validate();
        finish();
        snapshot("done", true);
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

    /** route tiles within reach of the region, with their weights */
    void reach(){
        float reach = maxRange() + 1;
        Map<Integer, double[]> g = new LinkedHashMap<>(), a = new LinkedHashMap<>();
        int gn = Math.max(1, groundRoutes.size()), an = Math.max(1, airRoutes.size());
        for(int[] r : groundRoutes) for(int t : r){
            int x = t % p.mapW - p.ox, y = t / p.mapW - p.oy;
            if(x < -reach || y < -reach || x > W + reach || y > H + reach) continue;
            g.computeIfAbsent(t, k -> new double[1])[0] += p.groundWeight / gn;
        }
        if(p.airWeight > 0) for(int[] r : airRoutes) for(int t : r){
            int x = t % p.mapW - p.ox, y = t / p.mapW - p.oy;
            if(x < -reach || y < -reach || x > W + reach || y > H + reach) continue;
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

    /** corridor (route + clearance), wall band, and what is buildable */
    void zones(){
        pd = new int[W][H];
        int cap = p.clearance + p.wallThickness + 64;
        for(int[] r : pd) Arrays.fill(r, cap);
        // multi-source BFS (8-neighbourhood = Chebyshev) from ground route tiles near the region
        ArrayDeque<int[]> q = new ArrayDeque<>();
        int m = p.clearance + p.wallThickness + 2;
        for(int[] r : groundRoutes) for(int t : r){
            int x = t % p.mapW - p.ox, y = t / p.mapW - p.oy;
            if(x < -m || y < -m || x >= W + m || y >= H + m) continue;
            // seed every region tile within the margin by its exact Chebyshev distance
            for(int u = Math.max(0, x - m); u <= Math.min(W - 1, x + m); u++) for(int v = Math.max(0, y - m); v <= Math.min(H - 1, y + m); v++){
                int d = Math.max(Math.abs(u - x), Math.abs(v - y));
                if(d < pd[u][v]){ pd[u][v] = d; }
            }
        }
        // farther tiles: BFS outward
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++) if(pd[u][v] <= m) q.add(new int[]{u, v});
        while(!q.isEmpty()){
            int[] c = q.poll();
            for(int du = -1; du <= 1; du++) for(int dv = -1; dv <= 1; dv++){
                int nu = c[0] + du, nv = c[1] + dv;
                if(!in(nu, nv) || pd[nu][nv] <= pd[c[0]][c[1]] + 1) continue;
                pd[nu][nv] = pd[c[0]][c[1]] + 1;
                q.add(new int[]{nu, nv});
            }
        }
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            occ[u][v] = pd[u][v] == 0 ? ROUTE : pd[u][v] <= p.clearance ? CORRIDOR
                : pd[u][v] <= p.clearance + p.wallThickness ? (p.free[u][v] ? BAND : BLOCKED) : p.free[u][v] ? FREE : BLOCKED;
        }
    }

    boolean freeTile(int u, int v){ return in(u, v) && occ[u][v] == FREE; }

    // ------------------------------------------------------------------ networks

    GridRouter[] routers;

    boolean setupNets(){
        int n = p.nets.length;
        pushedBy = new boolean[n][W][H];
        endBack = new int[W][H];
        for(int[] r : endBack) Arrays.fill(r, -1);
        routers = new GridRouter[n];
        int[] rear = rearTile();
        List<int[]> taken = new ArrayList<>();
        for(int k = 0; k < n; k++){
            Net net = p.nets[k];
            int kk = k;
            routers[k] = new GridRouter(W, H, new GridRouter.Grid(){
                public double tileCost(int u, int v){ return pipeCost(kk, u, v); }
                public boolean pushOk(int u, int v){ return DefensePlanner.this.pushOk(kk, u, v); }
            });
            routers[k].bridgeRange = net.bridgeRange;
            routers[k].bridgeCost = p.bridgePrice / p.pipePrice;
            if(net.auto || net.starts.length == 0){
                int[] t = rear == null ? null : pickEdge(rear, taken);
                if(t == null){ failure = "no free tile on the region edge for an input"; return false; }
                taken.add(t);
                net.starts = new int[][]{{t[0], t[1], t[2], 0}};
                chosenPorts.add(new int[]{k, t[0], t[1], t[2]});
            }
        }
        // port tiles are kept for their network
        for(int k = 0; k < n; k++){
            for(int[] st : p.nets[k].starts) if(in(st[0], st[1]) && occ[st[0]][st[1]] == FREE) occ[st[0]][st[1]] = PORT + k;
        }
        if(p.powerAuto && rear != null){
            int[] t = pickEdge(rear, taken);
            if(t != null && p.nodeOk[t[0]][t[1]]) chosenPowerPort = t;
        }
        if(chosenPowerPort != null) occ[chosenPowerPort[0]][chosenPowerPort[1]] = POWER;
        for(int[] pp : p.powerPorts) if(in(pp[0], pp[1])) occ[pp[0]][pp[1]] = POWER;
        return true;
    }

    /** Edge tile farthest from the ground route (behind the defense), free: {u, v, side} */
    int[] rearTile(){
        int[] best = null;
        double bd = -1;
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            if(u != 0 && v != 0 && u != W - 1 && v != H - 1) continue;
            if(occ[u][v] != FREE || side(u, v) < 0) continue;
            // prefer the middle of a side a little
            double d = pd[u][v] - 0.01 * Math.min(Math.abs(u - W / 2), Math.abs(v - H / 2));
            if(d > bd){ bd = d; best = new int[]{u, v, side(u, v)}; }
        }
        return best;
    }

    /** the side a port on this border tile is fed from (its outside neighbour must be free), -1 none */
    int side(int u, int v){
        if(p.edgeSide != null) return p.edgeSide[u][v];
        return u == W - 1 ? 0 : v == H - 1 ? 1 : u == 0 ? 2 : v == 0 ? 3 : -1;
    }

    /** a free border tile near `rear` and as far from the route, at least 2 tiles from the ones taken: {u, v, side} */
    int[] pickEdge(int[] rear, List<int[]> taken){
        int[] best = null;
        double bs = Double.NEGATIVE_INFINITY;
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            if(u != 0 && v != 0 && u != W - 1 && v != H - 1 || occ[u][v] != FREE || side(u, v) < 0) continue;
            boolean near = false;
            for(int[] t : taken) if(Math.abs(t[0] - u) + Math.abs(t[1] - v) < 2) near = true;
            if(near) continue;
            double sc = Math.min(pd[u][v], 12) - 0.3 * (Math.abs(u - rear[0]) + Math.abs(v - rear[1]));
            if(sc > bs){ bs = sc; best = new int[]{u, v, side(u, v)}; }
        }
        return best;
    }

    /** a pipe of network k on this tile: free, not next to another network's pushing blocks */
    double pipeCost(int k, int u, int v){
        if(!in(u, v)) return -1;
        int o = occ[u][v];
        if(o != FREE && o != BAND && o != PORT + k) return -1;
        boolean liquid = p.nets[k].liquid;
        for(int j = 0; j < p.nets.length; j++){
            if(j != k && p.nets[j].liquid == liquid && pushedBy[j][u][v]) return -1;
        }
        // in pipe-price units; the wall band is the front line
        return o == BAND ? 3 : 1;
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
        boolean any = false;
        for(int u = 0; u < W && !any; u++) for(int v = 0; v < H; v++) if(netAt[u][v] == k){ any = true; break; }
        if(!any){
            for(int[] s : p.nets[k].starts) out.add(new Start(s[0], s[1], s[2], s[3] == 1 ? p.unloaderPrice / p.pipePrice : 0, s[3] == 1));
            return out;
        }
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            if(netAt[u][v] != k) continue;
            int kind = kindAt[u][v];
            boolean pusher = kind == GridRouter.ROUTER || kind == GridRouter.END || kind == GridRouter.SOURCE;
            boolean pipe = kind == GridRouter.PIPE;
            if(!pusher && !(pipe && pushOk(k, u, v))) continue;
            for(int d = 0; d < 4; d++){
                int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
                if(!in(nu, nv) || occ[nu][nv] != FREE && occ[nu][nv] != BAND && occ[nu][nv] != PORT + k) continue;
                // an end bridge doesn't output toward its feeding bridge
                if(kind == GridRouter.END && d == endBack[u][v]) continue;
                Start s = new Start(nu, nv, (d + 2) % 4, pusher ? 0 : p.routerPrice / p.pipePrice, false);
                if(pipe){ s.cu = u; s.cv = v; }
                out.add(s);
            }
        }
        // the ports stay usable for a second line
        for(int[] s : p.nets[k].starts){
            if(in(s[0], s[1]) && occ[s[0]][s[1]] == PORT + k) out.add(new Start(s[0], s[1], s[2], s[3] == 1 ? p.unloaderPrice / p.pipePrice : 0, s[3] == 1));
        }
        return out;
    }

    int[][] endBack;

    /** Connects network k to the footprint; returns the route cost or -1. */
    double connect(int k, int u, int v, int size){
        List<Start> st = starts(k);
        if(st.isEmpty()) return -1;
        // fed already: a pushing block of this network touches the footprint
        if(fedBy(k, u, v, size)) return 0;
        List<Step> path = routers[k].route(st, u, v, size);
        if(path == null) return -1;
        Start s = routers[k].lastStart;
        if(s != null && s.cu >= 0){
            setNet(s.cu, s.cv, k, GridRouter.ROUTER, -1);
            replace(s.cu, s.cv, new Place(GridRouter.ROUTER, k, s.cu, s.cv, 0, s.cu, s.cv));
        }
        for(Step step : path){
            setNet(step.u, step.v, k, step.kind, step.kind == GridRouter.END ? endIncoming(path, step) : -1);
            places.add(new Place(step.kind, k, step.u, step.v, step.dir, step.lu, step.lv));
        }
        return routers[k].lastCost;
    }

    /** direction toward the bridge feeding this end bridge */
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
        if(kind == GridRouter.ROUTER || kind == GridRouter.END || kind == GridRouter.SOURCE){
            for(int d = 0; d < 4; d++){
                int nu = u + GridRouter.DX[d], nv = v + GridRouter.DY[d];
                if(in(nu, nv)) pushedBy[k][nu][nv] = true;
            }
        }
    }

    boolean fedBy(int k, int u, int v, int size){
        for(int[] a : perimeter(u, v, size)){
            if(!in(a[0], a[1]) || netAt[a[0]][a[1]] != k) continue;
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

    /** damage value a building of type t at (u,v) adds, with `boost` x its DPS */
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
        return sum * (1 - risk(t, u, v));
    }

    /** share of value lost because ground enemies can shoot it from the route */
    double risk(Type t, int u, int v){
        int dmin = Integer.MAX_VALUE;
        for(int i = 0; i < t.size; i++) for(int j = 0; j < t.size; j++) dmin = Math.min(dmin, pd[u + i][v + j]);
        double er = Math.max(1, p.enemyRange);
        double w = p.wallThickness > 0 ? 0.2 : 0.35;
        return w * Math.max(0, Math.min(1, (er + 1 - dmin) / er));
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
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) if(occ[u + i][v + j] != FREE) return false;
        return true;
    }

    void occupy(int u, int v, int s, int code){
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) occ[u + i][v + j] = code;
    }

    /** the networks a type needs (ammo items, liquid) */
    int[] needs(Type t){
        int liquidNet = liquidNet();
        List<Integer> n = new ArrayList<>();
        if(t.ammoNet >= 0) n.add(t.ammoNet);
        if((t.liquidAmmo || t.coolantRequired) && liquidNet >= 0) n.add(liquidNet);
        int[] out = new int[n.size()];
        for(int i = 0; i < out.length; i++) out[i] = n.get(i);
        return out;
    }

    int liquidNet(){
        for(int k = 0; k < p.nets.length; k++) if(p.nets[k].liquid) return k;
        return -1;
    }

    /** a type that can't be supplied at all (needs a liquid nobody brings) */
    boolean suppliable(Type t){
        if((t.liquidAmmo || t.coolantRequired) && liquidNet() < 0) return false;
        return t.ammoNet < p.nets.length;
    }

    /** connection cost estimate of network k to a footprint, from a distance field */
    double connEstimate(int k, double[][] field, int u, int v, int size){
        double best = Double.MAX_VALUE;
        for(int[] a : perimeter(u, v, size)){
            if(!in(a[0], a[1])) continue;
            if(netAt[a[0]][a[1]] == k){
                int kind = kindAt[a[0]][a[1]];
                if(kind == GridRouter.ROUTER || kind == GridRouter.SOURCE || kind == GridRouter.END && endBack[a[0]][a[1]] != a[2]) return 0;
                if(kind == GridRouter.PIPE && pushOk(k, a[0], a[1])) best = Math.min(best, p.routerPrice / p.pipePrice);
                continue;
            }
            best = Math.min(best, field[a[0]][a[1]]);
        }
        return best;
    }

    // ------------------------------------------------------------------ turrets

    void placeTurrets(){
        List<int[]> cands = new ArrayList<>(); // {type, u, v}
        for(int ti = 0; ti < p.types.length; ti++){
            Type t = p.types[ti];
            if(t.kind != TURRET || !suppliable(t)) continue;
            for(int u = 0; u + t.size <= W; u++) for(int v = 0; v + t.size <= H; v++){
                if(p.fits[ti][u][v] && footprintFree(u, v, t.size)) cands.add(new int[]{ti, u, v});
            }
        }
        if(cands.isEmpty()){ failure = "no room for the selected turrets outside the enemy route and wall band"; return; }
        // diminishing-returns scale: about two average turrets on a tile
        double sum = 0;
        int cnt = 0;
        for(Type t : p.types) if(t.kind == TURRET){ sum += Math.max(t.dpsGround, t.dpsAir); cnt++; }
        K = Math.max(5, 2 * sum / Math.max(1, cnt)) * (p.variant == 0 ? 1 : 0.7 + 0.6 * rand.nextDouble());
        double[] ub = new double[cands.size()];
        double best0 = 0;
        for(int i = 0; i < ub.length; i++){
            int[] c = cands.get(i);
            Type t = p.types[c[0]];
            ub[i] = value(t, c[1], c[2], 1) * noise(c);
            best0 = Math.max(best0, ub[i] / Math.max(1, t.price));
        }
        // price -> value: a turret's price is worth ~15% of what the best one does per price unit
        mu = 0.15 * best0;
        tileMu = mu * p.pipePrice;
        boolean[] dead = new boolean[cands.size()];

        while(turrets() < p.maxTurrets){
            checkCancel();
            // connection fields per network
            double[][][] fields = new double[p.nets.length][][];
            for(int k = 0; k < p.nets.length; k++) fields[k] = routers[k].field(starts(k));
            // bounds
            Integer[] order = new Integer[cands.size()];
            double[] bound = new double[cands.size()];
            double[] conn = new double[cands.size()];
            int live = 0;
            for(int i = 0; i < cands.size(); i++){
                int[] c = cands.get(i);
                Type t = p.types[c[0]];
                if(dead[i] || !footprintFree(c[1], c[2], t.size)){ dead[i] = true; continue; }
                double cc = 0;
                for(int k : needs(t)){
                    double e = connEstimate(k, fields[k], c[1], c[2], t.size);
                    if(e == Double.MAX_VALUE){ cc = -1; break; }
                    cc += e;
                }
                if(cc < 0){ bound[i] = Double.NEGATIVE_INFINITY; }
                else{ conn[i] = cc; bound[i] = ub[i] - mu * t.price - tileMu * cc; }
                order[live++] = i;
            }
            if(live == 0) break;
            Integer[] ord = Arrays.copyOf(order, live);
            Arrays.sort(ord, (a, b) -> Double.compare(bound[b], bound[a]));
            int bi = -1;
            double bs = 0;
            for(int idx = 0; idx < ord.length; idx++){
                int i = ord[idx];
                if(bound[i] <= bs || bound[i] == Double.NEGATIVE_INFINITY) break;
                int[] c = cands.get(i);
                Type t = p.types[c[0]];
                ub[i] = value(t, c[1], c[2], 1) * noise(c);
                double s = ub[i] - mu * t.price - tileMu * conn[i];
                if(s > bs){ bs = s; bi = i; }
            }
            if(bi < 0) break;
            int[] c = cands.get(bi);
            Type t = p.types[c[0]];
            occupy(c[1], c[2], t.size, BUILDING + c[0]);
            int mark = places.size();
            boolean ok = true;
            for(int k : needs(t)){
                if(connect(k, c[1], c[2], t.size) < 0){ ok = false; break; }
            }
            if(!ok){
                // the estimate was too optimistic: undo and never try this one again
                undoPlaces(mark);
                occupy(c[1], c[2], t.size, FREE);
                dead[bi] = true;
                continue;
            }
            addBuilt(c[0], c[1], c[2]);
            addDps(t, c[1], c[2], 1);
            dead[bi] = true;
            snapshot("turrets", false);
        }
    }

    double noise(int[] c){
        if(p.variant == 0) return 1;
        long h = (c[0] * 73856093L) ^ (c[1] * 19349663L) ^ (c[2] * 83492791L) ^ (p.variant * 2654435761L);
        return 0.85 + 0.3 * ((h & 0xffff) / 65535.0);
    }

    int turrets(){
        int n = 0;
        for(Built b : built) if(p.types[b.type].kind == TURRET) n++;
        return n;
    }

    void addBuilt(int type, int u, int v){
        built.add(new Built(type, u, v));
        places.add(new Place(BLOCK, type, u, v, 0, u, v));
        occupy(u, v, p.types[type].size, BUILDING + type);
    }

    /** removes the logistics placed since `mark` (a failed connection) */
    void undoPlaces(int mark){
        while(places.size() > mark){
            Place pl = places.remove(places.size() - 1);
            if(pl.kind >= BLOCK) continue;
            netAt[pl.u][pl.v] = -1;
            kindAt[pl.u][pl.v] = -1;
            occ[pl.u][pl.v] = portOf(pl.u, pl.v) >= 0 ? PORT + portOf(pl.u, pl.v) : pd[pl.u][pl.v] <= p.clearance + p.wallThickness ? BAND : FREE;
        }
        // pushed marks are conservative; recompute
        for(boolean[][] a : pushedBy) for(boolean[] r : a) Arrays.fill(r, false);
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int k = netAt[u][v];
            if(k >= 0) setNet(u, v, k, kindAt[u][v], endBack[u][v]);
        }
    }

    /** network whose port reserves this tile, or -1 */
    int portOf(int u, int v){
        for(int k = 0; k < p.nets.length; k++) for(int[] st : p.nets[k].starts) if(st[0] == u && st[1] == v) return k;
        return -1;
    }

    // ------------------------------------------------------------------ coolant

    void coolant(){
        int ln = liquidNet();
        if(ln < 0) return;
        List<Built> order = new ArrayList<>();
        for(Built b : built){
            Type t = p.types[b.type];
            if(t.kind == TURRET && t.coolantBoost > 1.001 && !t.liquidAmmo && !t.coolantRequired) order.add(b);
            if(t.liquidAmmo || t.coolantRequired) b.cooled = true;
        }
        // biggest gain first
        Map<Built, Double> gain = new HashMap<>();
        for(Built b : order){
            Type t = p.types[b.type];
            addDps(t, b.u, b.v, -1);
            gain.put(b, value(t, b.u, b.v, t.coolantBoost) - value(t, b.u, b.v, 1));
            addDps(t, b.u, b.v, 1);
        }
        order.sort((a, b) -> Double.compare(gain.get(b), gain.get(a)));
        for(Built b : order){
            checkCancel();
            Type t = p.types[b.type];
            addDps(t, b.u, b.v, -1);
            double g = value(t, b.u, b.v, t.coolantBoost) - value(t, b.u, b.v, 1);
            addDps(t, b.u, b.v, 1);
            if(g <= 0) continue;
            double est = connEstimate(ln, routers[ln].field(starts(ln)), b.u, b.v, t.size);
            if(est == Double.MAX_VALUE || g - tileMu * est <= 0) continue;
            int mark = places.size();
            double c = connect(ln, b.u, b.v, t.size);
            if(c < 0 || g - tileMu * c <= 0){ undoPlaces(mark); continue; }
            b.cooled = true;
            addDps(t, b.u, b.v, t.coolantBoost - 1);
            snapshot("coolant", false);
        }
    }

    // ------------------------------------------------------------------ protection

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

    void protect(){
        boolean anyProt = false;
        for(Type t : p.types) anyProt |= t.kind == MENDER || t.kind == SHIELD;
        if(!anyProt) return;
        // exposed structures: turret tiles (x2) and the wall band (future walls)
        List<double[]> exposed = new ArrayList<>(); // {u, v, weight}
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            double w = occ[u][v] >= BUILDING && occ[u][v] < POWER ? 2 : occ[u][v] == BAND && p.wallThickness > 0 ? 0.5 : 0;
            if(w <= 0) continue;
            double e = exposure(u, v);
            if(e > 0) exposed.add(new double[]{u, v, w * e});
        }
        if(exposed.isEmpty()) return;
        int[] covered = new int[exposed.size()];
        int nTurrets = turrets();
        int menders = 0, shields = 0;
        int maxMenders = Math.max(1, (nTurrets + 1) / 2), maxShields = Math.max(1, nTurrets / 5);
        double total = 0;
        for(double[] e : exposed) total += e[2];
        while(true){
            checkCancel();
            int bt = -1, bu = -1, bv = -1;
            double best = 0;
            for(int ti = 0; ti < p.types.length; ti++){
                Type t = p.types[ti];
                if(t.kind != MENDER && t.kind != SHIELD) continue;
                if(t.kind == MENDER && menders >= maxMenders || t.kind == SHIELD && shields >= maxShields) continue;
                for(int u = 0; u + t.size <= W; u++) for(int v = 0; v + t.size <= H; v++){
                    if(!p.fits[ti][u][v] || !footprintFree(u, v, t.size)) continue;
                    double cx = u + t.size / 2.0 - 0.5, cy = v + t.size / 2.0 - 0.5, r2 = (t.range + 0.5) * (t.range + 0.5);
                    double val = 0;
                    for(int i = 0; i < exposed.size(); i++){
                        double[] e = exposed.get(i);
                        double dx = e[0] - cx, dy = e[1] - cy;
                        if(dx * dx + dy * dy <= r2) val += e[2] * Math.pow(0.5, covered[i]);
                    }
                    // protectors themselves should not stand in the line of fire
                    val = val * t.strength * (1 - risk(t, u, v));
                    if(val > best){ best = val; bt = ti; bu = u; bv = v; }
                }
            }
            // worth it: covers a fair share of what's exposed
            if(bt < 0 || best < 0.08 * total * p.types[bt].strength) break;
            Type t = p.types[bt];
            addBuilt(bt, bu, bv);
            if(t.kind == MENDER) menders++; else shields++;
            double cx = bu + t.size / 2.0 - 0.5, cy = bv + t.size / 2.0 - 0.5, r2 = (t.range + 0.5) * (t.range + 0.5);
            for(int i = 0; i < exposed.size(); i++){
                double[] e = exposed.get(i);
                double dx = e[0] - cx, dy = e[1] - cy;
                if(dx * dx + dy * dy <= r2) covered[i]++;
            }
            protectors++;
            snapshot("menders / shields", false);
        }
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
        if(consumers.isEmpty()) return;
        PowerNetwork net = new PowerNetwork(W, H, p.nodeRange, p.nodeMaxLinks, new PowerNetwork.Host(){
            // nodes may stand in the wall band (a gap in the wall) so lasers can cross the corridor
            public boolean nodeFree(int u, int v){ return (occ[u][v] == FREE || occ[u][v] == BAND) && p.nodeOk[u][v]; }
            public boolean nodeOk(int u, int v){ return p.nodeOk[u][v] && occ[u][v] != CORRIDOR && occ[u][v] != ROUTE; }
            public double penalty(int u, int v){ return 300 * risk1(u, v) + (occ[u][v] == BAND ? 200 : 0); }
            public int size(int[] c){ return c[2]; }
            public void occupy(int u, int v){ occ[u][v] = POWER; }
            public void drop(int[] c){ owner.get(c).powered = false; unpowered++; }
            public Object save(){ int[][] s = new int[W][]; for(int i = 0; i < W; i++) s[i] = occ[i].clone(); return s; }
            public void restore(Object s){ int[][] a = (int[][])s; for(int i = 0; i < W; i++) occ[i] = a[i].clone(); }
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
        // every group that isn't linked to a port gets one: the player feeds it there
        // (two sides of the route too far apart for a laser, or no port picked)
        int[] comp = net.components();
        boolean mainHasAnchor = !net.anchors.isEmpty();
        Set<Integer> done = new HashSet<>();
        if(mainHasAnchor) done.add(0);
        for(int i = 0; i < nodeList.size(); i++){
            if(!done.add(comp[i])) continue;
            // the group's node nearest the region border
            int bi = i;
            for(int j = 0; j < nodeList.size(); j++){
                if(comp[j] == comp[i] && border(nodeList.get(j)) < border(nodeList.get(bi))) bi = j;
            }
            extraPowerPorts.add(new int[]{nodeList.get(bi)[0], nodeList.get(bi)[1]});
        }
    }

    /** nodes the player must connect power to (besides the ports): one per unlinked group */
    public final List<int[]> extraPowerPorts = new ArrayList<>();

    int border(int[] n){ return Math.min(Math.min(n[0], W - 1 - n[0]), Math.min(n[1], H - 1 - n[1])); }

    double risk1(int u, int v){
        double er = Math.max(1, p.enemyRange);
        return Math.max(0, Math.min(1, (er + 1 - pd[u][v]) / er));
    }

    // ------------------------------------------------------------------ walls, mines

    void walls(){
        if(p.wallThickness <= 0) return;
        int large = -1, small = -1;
        for(int ti = 0; ti < p.types.length; ti++){
            Type t = p.types[ti];
            if(t.kind != WALL) continue;
            if(t.size == 2) large = ti; else if(t.size == 1) small = ti;
        }
        if(large < 0 && small < 0) return;
        // band tiles in front of something we built
        boolean[][] want = new boolean[W][H];
        int reach = (int)Math.ceil(Math.max(6, p.enemyRange));
        List<int[]> structures = new ArrayList<>();
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++) if(occ[u][v] >= NET && occ[u][v] < PORT) structures.add(new int[]{u, v});
        // a wall helps where it stands between the route and something of ours: same side of the corridor
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            if(occ[u][v] != BAND) continue;
            for(int[] s : structures){
                int dx = s[0] - u, dy = s[1] - v;
                if(dx * dx + dy * dy <= reach * reach && sameSide(u, v, s[0], s[1])){ want[u][v] = true; break; }
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
            for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
                if(want[u][v] && p.fits[small][u][v]) addWall(small, u, v);
            }
        }
    }

    /** the straight line between two tiles doesn't cross the corridor */
    boolean sameSide(int u0, int v0, int u1, int v1){
        int n = Math.max(Math.abs(u1 - u0), Math.abs(v1 - v0));
        for(int i = 1; i < n; i++){
            int u = Math.round(u0 + (u1 - u0) * i / (float)n), v = Math.round(v0 + (v1 - v0) * i / (float)n);
            if(pd[u][v] <= p.clearance) return false;
        }
        return true;
    }

    void addWall(int type, int u, int v){
        built.add(new Built(type, u, v));
        places.add(new Place(BLOCK, type, u, v, 0, u, v));
        occupy(u, v, p.types[type].size, BUILDING + type);
        walls++;
        if(walls % 8 == 0) snapshot("walls", false);
    }

    void mines(){
        if(!p.mines) return;
        int mt = -1;
        for(int ti = 0; ti < p.types.length; ti++) if(p.types[ti].kind == MINE) mt = ti;
        if(mt < 0) return;
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            if(occ[u][v] != ROUTE || !p.fits[mt][u][v]) continue;
            // only where turrets shoot
            boolean covered = false;
            for(int i = 0; i < rx.length && !covered; i++) if(!rAir[i] && Math.abs(rx[i] - u) < 0.5f && Math.abs(ry[i] - v) < 0.5f && dps[i] > 0) covered = true;
            if(!covered) continue;
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
        routeChanged = false;
        for(int s : p.groundSpawns){
            int[] r = after.trace(s);
            if(r.length > 1) newGroundRoutes.add(r);
        }
        // compare inside the region and its reach
        Set<Integer> before = inReach(groundRoutes), now = inReach(newGroundRoutes);
        routeChanged = !before.equals(now);
    }

    Set<Integer> inReach(List<int[]> rs){
        Set<Integer> out = new HashSet<>();
        int m = 4;
        for(int[] r : rs) for(int t : r){
            int x = t % p.mapW - p.ox, y = t / p.mapW - p.oy;
            if(x >= -m && y >= -m && x < W + m && y < H + m) out.add(t);
        }
        return out;
    }

    void finish(){
        places.removeIf(pl -> pl.kind == NODE);
        for(int[] n : nodeList) places.add(new Place(NODE, 0, n[0], n[1], 0, n[0], n[1]));
        turrets = 0;
        double price = 0;
        for(Place pl : places){
            switch(pl.kind){
                case BLOCK: if(p.types[pl.type].kind == TURRET){ turrets++; price += p.types[pl.type].price; } break;
                case NODE: nodes++; price += p.nodePrice; break;
                case GridRouter.PIPE: pipes++; price += p.pipePrice; break;
                case GridRouter.BRIDGE: case GridRouter.END: bridges++; price += p.bridgePrice / 2; break;
                case GridRouter.ROUTER: pipes++; price += p.routerPrice; break;
                case GridRouter.SOURCE: pipes++; price += p.unloaderPrice; break;
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
        score = valueGround + valueAir - mu * price - (routeChanged ? 0.2 * (valueGround + valueAir) : 0);
    }
}
