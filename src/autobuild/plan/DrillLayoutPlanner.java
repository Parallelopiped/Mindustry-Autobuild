package autobuild.plan;

import java.util.*;

/**
 * Pure planner for a drill field, independent of game classes so it can be tested offline.
 *
 * Works in a canonical local frame: u in [0,W), v in [0,H), items leave the region toward +u
 * through the port tile (W-1, portV). Directions: 0=+u, 1=+v, 2=-u, 3=-v.
 *
 * 1. Layout, best of:
 *    - lanes: horizontal belt rows with drills on one or both sides; rows chosen by a DP over
 *      rows, drills along each lane by a DP over columns;
 *    - bridge lattice: rows of item-bridge chains, drills packed around them (randomized greedy
 *      over all spacings/phases). Bridges free most of the logistics row for drills.
 *    Column W-1 is reserved as the collector corridor.
 * 2. Routing: each lane / chain end is connected to the port, or into an already-routed belt, by
 *    A* over (tile, direction, landed-bridge) with conveyor steps and bridge jumps.
 * 3. Power (optional): nodes by greedy set cover, then relays (Dijkstra) into one linked group.
 */
public class DrillLayoutPlanner{
    public enum Mode{
        /** cover as much ore as possible */
        OUTPUT,
        /** only place drills that are mostly on ore; fewer drills/belts per item mined */
        EFFICIENCY
    }

    public static final int DRILL = 0, BELT = 1, NODE = 2, BRIDGE = 3, CONDUIT = 4, LROUTER = 5, LBRIDGE = 6;
    static final int[] DU = {1, 0, -1, 0}, DV = {0, 1, 0, -1};

    // occupancy codes; >= 0 is a routed belt with that direction
    static final int FREE = -1, ODRILL = -2, LANE = -3, ONODE = -4, OBRIDGE = -5, BUFFER = -6, OWATER = -7;

    public static class Place{
        public final int kind, u, v, dir, ore;
        /** BRIDGE/LBRIDGE: link target (equal to u,v for an end bridge). DRILL: dir = 1 if water-boosted */
        public final int lu, lv;
        Place(int kind, int u, int v, int dir, int ore){ this(kind, u, v, dir, ore, u, v); }
        Place(int kind, int u, int v, int dir, int ore, int lu, int lv){
            this.kind = kind; this.u = u; this.v = v; this.dir = dir; this.ore = ore; this.lu = lu; this.lv = lv;
        }
        @Override public String toString(){ return "DBNRCOL".charAt(kind) + "(" + u + "," + v + ")" + (kind == BELT ? ">" + dir : kind == BRIDGE ? "->(" + lu + "," + lv + ")" : ""); }
    }

    public static class Params{
        public int W, H, drillSize, target, portV;
        /** conveyor / power node placeable at (u,v); bridges use beltOk too */
        public boolean[][] beltOk, nodeOk;
        /** drill footprint with local min corner (u,v) placeable */
        public boolean[][] drillOk;
        /** mineable item id at (u,v) for this drill, or -1 */
        public int[][] drop;
        /** Item.lowPriority by item id (sand); null = none */
        public boolean[] lowPriority;
        public Mode mode = Mode.OUTPUT;
        public boolean power;
        /** laser range in tiles, max links per node */
        public float nodeRange = 6f;
        public int nodeMaxLinks = 10;
        /** allow item bridges (layout and routing); range in tiles */
        public boolean bridges = true;
        public int bridgeRange = 4;
        public long seed = 1;
        /** water boost: layout with water rows, spine from the water port */
        public boolean water;
        /** drill speed multiplier with full water (liquidBoostIntensity^2) */
        public float waterBoost = 2.56f;
        /** water port edge in the local frame: 1 = top (v=H-1), 2 = west (u=0), 3 = bottom (v=0); position along it */
        public int waterSide = 2, waterPos;
        public int liquidBridgeRange = 4;
    }

    /** Receives snapshots of the plan while it is being computed (called on the planning thread). */
    public interface Listener{
        void progress(String stage, List<Place> snapshot);
    }

    public static class CancelledException extends RuntimeException{
        public CancelledException(){ super("cancelled", null, false, false); }
    }

    // ---- inputs ----
    final Params p;
    final int W, H, s;
    public Listener listener;
    /** minimum milliseconds between intermediate snapshots */
    public long snapshotInterval = 16;
    public volatile boolean cancelled;

    // ---- outputs ----
    public final List<Place> places = new ArrayList<>();
    public int drills, wetDrills, waterTiles, oreTiles, nodes, belts, bridges, segmentsRouted, segmentsDropped, drillsDroppedForPower, powerGroups, port = -1;
    public String layout = "", failure;
    /** water was requested but a dry layout scored better (e.g. the water network couldn't reach the rows) */
    public boolean waterSkipped;

    // ---- work state ----
    int[][] occ;
    /** target ore under a drill at min corner (u,v) if it fits and would mine the target, else 0 */
    int[][] ore;
    final Random rand;
    long lastSnapshot;
    List<Feeder> feeders = new ArrayList<>();
    List<int[]> nodeList = new ArrayList<>();
    /** tiles reserved for the water spine / feed row (not usable by the layout) */
    boolean[][] mask;
    /** water rows chosen by the layout */
    List<Integer> waterRows = new ArrayList<>();
    /** water elements {u, v, kind, dir, lu, lv} */
    List<int[]> water = new ArrayList<>();
    public int waterU = -1, waterV = -1;

    public DrillLayoutPlanner(Params p){
        this.p = p; this.W = p.W; this.H = p.H; this.s = p.drillSize;
        this.rand = new Random(p.seed);
    }

    /** Something that must be routed to the port: a lane segment or a bridge chain. */
    static class Feeder{
        final List<int[]> drills = new ArrayList<>(); // min corners
        final List<int[]> belts = new ArrayList<>();  // lane belts before the start tile, flowing +u
        final List<int[]> bridges = new ArrayList<>(); // chain bridges before the start tile, each links to the next
        int su, sv;        // routing start (arrives moving +u)
        boolean landed;    // start tile is a bridge (chain end)
        double value;
        List<int[]> path;  // routed elements {u, v, kind, dir, lu, lv}, first = start tile
    }

    static class Lane{
        final List<Feeder> segs = new ArrayList<>();
        double value;
    }

    public boolean plan(){
        if(W < s + 2 || H < s + 1){ failure = "region too small for this drill"; return false; }
        boolean any = false;
        for(boolean[] col : p.beltOk) for(boolean b : col) any |= b;
        if(!any){ failure = "nothing in the region is buildable (walls, deep water or enemy territory)"; return false; }

        port = findPort();
        if(port < 0){ failure = "no buildable tile on the output edge"; return false; }

        mask = new boolean[W][H];
        if(p.water && !setupWater()){ failure = "no buildable tile for the water port"; return false; }
        computeOre();
        occ = new int[W][H];

        // dry layout: belt lanes, or a bridge lattice if better
        double dryValue = chooseLanes();
        List<Feeder> dry = feeders;
        String dryName = "belt lanes";
        snapshot("layout: belt lanes", true);
        if(p.bridges){
            double latticeValue = chooseLattice(dryValue);
            if(latticeValue > dryValue){ dryValue = latticeValue; dry = feeders; dryName = layout; }
        }
        feeders = dry; layout = dryName;

        if(p.water){
            // wet layout: lanes + water rows; keep it only if the built water network pays off
            boolean[][] keepMask = mask;
            chooseLanesWater();
            List<Feeder> wet = feeders;
            for(int[] row : occ) Arrays.fill(row, FREE);
            for(Feeder f : wet) mark(f, true);
            layout = "belt lanes + water rows";
            buildWater();
            double wetValue = 0;
            for(Feeder f : wet){
                for(int[] d : f.drills) wetValue += drillValue(d[0], d[1], d[2] == 1);
                wetValue -= beltCost() * (f.belts.size() + 1);
            }
            wetValue -= (p.mode == Mode.OUTPUT ? 0.02 : 0.3) * water.size();
            if(wetValue > dryValue){
                feeders = wet;
            }else{
                waterSkipped = true;
                water.clear(); waterAt.clear(); waterRows.clear();
                feeders = dry; layout = dryName;
                mask = keepMask;
            }
        }
        if(feeders.isEmpty()){
            failure = p.mode == Mode.EFFICIENCY ? "no drill position with enough target ore (try Max output)" : "no drill position on the target ore";
            return false;
        }

        for(int[] row : occ) Arrays.fill(row, FREE);
        for(Feeder f : feeders) mark(f, true);
        for(int[] el : water) occ[el[0]][el[1]] = OWATER;
        snapshot("layout: " + layout, true);

        // route, closest to the port first
        feeders.sort(Comparator.comparingInt(f -> Math.abs(f.sv - port) + (W - 1 - f.su)));
        boolean portUsed = false;
        List<Feeder> routed = new ArrayList<>(), pending = new ArrayList<>(feeders);
        for(Feeder f : pending){
            checkCancel();
            List<int[]> path = routeWithRetry(f, !portUsed);
            if(path == null){
                mark(f, false);
                feeders.remove(f);
                segmentsDropped++;
                continue;
            }
            commit(f, path);
            routed.add(f);
            if(!portUsed && occ[W - 1][port] >= 0) portUsed = true;
            segmentsRouted++;
            snapshot("routing", false);
        }
        feeders = routed;
        if(segmentsRouted == 0){ failure = "no lane could be routed to the output port"; return false; }

        if(p.power){
            List<int[]> drillList = new ArrayList<>();
            for(Feeder f : feeders) drillList.addAll(f.drills);
            nodeList = placePower(drillList);
        }

        places.clear();
        places.addAll(build());
        for(Place pl : places){
            switch(pl.kind){
                case DRILL: drills++; oreTiles += pl.ore; if(pl.dir == 1) wetDrills++; break;
                case CONDUIT: case LROUTER: case LBRIDGE: waterTiles++; break;
                case NODE: nodes++; break;
                case BELT: belts++; break;
                default: bridges++;
            }
        }
        snapshot("done", true);
        return true;
    }

    void checkCancel(){
        if(cancelled) throw new CancelledException();
    }

    void snapshot(String stage, boolean force){
        checkCancel();
        if(listener == null) return;
        long now = System.currentTimeMillis();
        if(!force && now - lastSnapshot < snapshotInterval) return;
        lastSnapshot = now;
        listener.progress(stage, build());
    }

    /** Current state of feeders (+ routes, nodes) as places. */
    List<Place> build(){
        List<Place> out = new ArrayList<>();
        for(int[] n : nodeList) out.add(new Place(NODE, n[0], n[1], 0, 0));
        for(int[] e : water) out.add(new Place(e[2], e[0], e[1], e[3], 0, e[4], e[5]));
        for(Feeder f : feeders){
            for(int[] b : f.belts) out.add(new Place(BELT, b[0], b[1], 0, 0));
            for(int i = 0; i < f.bridges.size(); i++){
                int[] b = f.bridges.get(i), n = i + 1 < f.bridges.size() ? f.bridges.get(i + 1) : new int[]{f.su, f.sv};
                out.add(new Place(BRIDGE, b[0], b[1], 0, 0, n[0], n[1]));
            }
            if(f.path != null){
                for(int[] e : f.path) out.add(new Place(e[2], e[0], e[1], e[3], 0, e[4], e[5]));
            }else if(f.landed){
                out.add(new Place(BRIDGE, f.su, f.sv, 0, 0));
            }else{
                out.add(new Place(BELT, f.su, f.sv, 0, 0));
            }
            for(int[] d : f.drills) out.add(new Place(DRILL, d[0], d[1], d.length > 2 ? d[2] : 0, ore[d[0]][d[1]]));
        }
        return out;
    }

    int findPort(){
        for(int d = 0; d < H; d++){
            for(int sgn = -1; sgn <= 1; sgn += 2){
                int v = p.portV + d * sgn;
                if(v >= 0 && v < H && p.beltOk[W - 1][v]) return v;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ ore

    void computeOre(){
        ore = new int[W][H];
        int[] counts = new int[4096];
        for(int u = 0; u + s <= W - 1; u++){ // column W-1 is the collector
            for(int v = 0; v + s <= H; v++){
                if(!p.drillOk[u][v]) continue;
                for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++) if(p.drop[x][y] >= 0) counts[p.drop[x][y]]++;
                int mined = -1;
                for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++){
                    int d = p.drop[x][y];
                    if(d >= 0 && (mined < 0 || minedBefore(d, counts[d], mined, counts[mined]))) mined = d;
                }
                ore[u][v] = mined == p.target ? counts[mined] : 0;
                for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++) if(p.drop[x][y] >= 0) counts[p.drop[x][y]] = 0;
            }
        }
    }

    /** Target ore a drill at min corner (u,v) would mine (0 if it would mine something else). */
    public int oreUnder(int u, int v){
        if(ore == null) computeOre();
        return ore[u][v];
    }

    /**
     * Same order as Drill.countOre: the drill mines the maximum by
     * (not low priority, count, item id). Returns true if item a beats item b.
     */
    boolean minedBefore(int a, int ca, int b, int cb){
        boolean la = p.lowPriority != null && p.lowPriority[a], lb = p.lowPriority != null && p.lowPriority[b];
        if(la != lb) return !la;
        if(ca != cb) return ca > cb;
        return a > b;
    }

    double drillValue(int u, int v){
        if(u < 0 || v < 0 || v + s > H || u + s > W - 1) return 0;
        int o = ore[u][v];
        if(o == 0 || masked(u, v, s)) return 0;
        // efficiency: a drill must sit mostly on ore to be worth its cost
        return p.mode == Mode.OUTPUT ? o - 0.01 : o - 0.5 * s * s;
    }

    boolean masked(int u, int v, int size){
        if(!p.water) return false;
        for(int x = u; x < u + size; x++) for(int y = v; y < v + size; y++) if(mask[x][y]) return true;
        return false;
    }

    /** Value of a drill that gets water (if `wet`), or the plain value. */
    double drillValue(int u, int v, boolean wet){
        double base = drillValue(u, v);
        if(!wet || base <= 0) return base;
        int o = ore[u][v];
        return p.mode == Mode.OUTPUT ? o * p.waterBoost - 0.01 : o * p.waterBoost - 0.5 * s * s - 0.5;
    }

    /** Water row tile can carry a conduit/router. */
    boolean waterTileOk(int x, int r){
        return r >= 0 && r < H && x >= 0 && x < W - 1 && p.beltOk[x][r] && !mask[x][r];
    }

    /** Lane belt tile usable by the layout. */
    boolean laneOk(int u, int v){
        return p.beltOk[u][v] && !mask[u][v];
    }

    double beltCost(){ return p.mode == Mode.OUTPUT ? 0.01 : 0.25; }

    /** A bridge costs ~12x a conveyor in materials. */
    double bridgeCost(){ return p.mode == Mode.OUTPUT ? 0.05 : 2.0; }

    // ------------------------------------------------------------------ layout: lanes

    /** Row DP: pick lane rows and sides so drill bands never overlap. Sets feeders, returns value. */
    double chooseLanes(){
        Lane[][] memo = new Lane[H][12];
        double[] f = new double[H + 2];
        int[] choice = new int[H + 1]; // 0 skip, 1 top-only at i, 2 bottom-only at i+s, 3 both at i+s
        for(int i = H - 1; i >= 0; i--){
            f[i] = f[i + 1]; choice[i] = 0;
            if(i + s < H){
                double a = lane(memo, i, 1).value + f[i + s + 1];
                if(a > f[i]){ f[i] = a; choice[i] = 1; }
                double b = lane(memo, i + s, 2).value + f[i + s + 1];
                if(b > f[i]){ f[i] = b; choice[i] = 2; }
            }
            if(i + 2 * s < H){
                double c = lane(memo, i + s, 3).value + f[i + 2 * s + 1];
                if(c > f[i]){ f[i] = c; choice[i] = 3; }
            }
        }
        feeders = new ArrayList<>();
        for(int i = 0; i < H; ){
            switch(choice[i]){
                case 0: i++; break;
                case 1: feeders.addAll(lane(memo, i, 1).segs); i += s + 1; break;
                case 2: feeders.addAll(lane(memo, i + s, 2).segs); i += s + 1; break;
                default: feeders.addAll(lane(memo, i + s, 3).segs); i += 2 * s + 1; break;
            }
        }
        return f[0];
    }

    /** sides: 1 = top (+v), 2 = bottom, 3 = both */
    Lane lane(Lane[][] memo, int v, int sides){ return lane(memo, v, sides, false, false); }

    /** wetTop / wetBot: a water row runs along the outer side of that drill band */
    Lane lane(Lane[][] memo, int v, int sides, boolean wetTop, boolean wetBot){
        int k = (sides - 1) * 4 + (wetTop ? 2 : 0) + (wetBot ? 1 : 0);
        if(memo[v][k] != null) return memo[v][k];
        Lane l = new Lane();
        int u = 0;
        while(u < W - 1){
            if(!laneOk(u, v)){ u++; continue; }
            int a = u;
            while(u < W - 1 && laneOk(u, v)) u++;
            Feeder g = segment(v, a, u - 1, sides, wetTop, wetBot);
            if(g != null){ l.segs.add(g); l.value += g.value; }
        }
        return memo[v][k] = l;
    }

    /** Best drills beside a straight belt run [a,b] in row v, via 1D DP per side. */
    Feeder segment(int v, int a, int b, int sides, boolean wetTop, boolean wetBot){
        Feeder g = new Feeder();
        int minU = Integer.MAX_VALUE, maxU = -1;
        int n = b - a + 1;
        for(int side = 0; side < 2; side++){
            if((sides & (1 << side)) == 0) continue;
            int dv = side == 0 ? v + 1 : v - s;
            boolean wetSide = side == 0 ? wetTop : wetBot;
            int wRow = side == 0 ? v + s + 1 : v - s - 1;
            double[] dp = new double[n + 1];
            boolean[] take = new boolean[n + 1];
            // dp[i] = best using columns a+i..b
            for(int i = n - 1; i >= 0; i--){
                dp[i] = dp[i + 1];
                if(i + s <= n){
                    double val = drillValue(a + i, dv, wetSide && wettable(a + i, wRow));
                    if(val > 0 && val + dp[i + s] > dp[i]){ dp[i] = val + dp[i + s]; take[i] = true; }
                }
            }
            for(int i = 0; i < n; ){
                if(take[i]){
                    int u = a + i;
                    boolean wet = wetSide && wettable(u, wRow);
                    g.drills.add(new int[]{u, dv, wet ? 1 : 0});
                    g.value += drillValue(u, dv, wet);
                    minU = Math.min(minU, u); maxU = Math.max(maxU, u + s - 1);
                    i += s;
                }else i++;
            }
        }
        if(g.drills.isEmpty()) return null;
        for(int u = minU; u < maxU; u++) g.belts.add(new int[]{u, v});
        g.su = maxU; g.sv = v; g.landed = false;
        g.value -= beltCost() * (maxU - minU + 1);
        return g.value > 0 ? g : null;
    }

    /** a drill at columns u..u+s-1 can get a router in water row r */
    boolean wettable(int u, int r){
        for(int x = u; x < u + s; x++) if(waterTileOk(x, r)) return true;
        return false;
    }

    /**
     * Row DP with water rows. State: whether the row just below is a water row. Structures:
     * skip, W, [D][I], [I][D](+W), [D][I][D](+W). A drill band is watered when a water row runs
     * along its outer side; watered drills are valued at the boosted rate.
     */
    double chooseLanesWater(){
        Lane[][] memo = new Lane[H][12];
        double rowCost = (p.mode == Mode.OUTPUT ? 0.02 : 0.3) * W;
        double[][] f = new double[H + 3][2];
        int[][] choice = new int[H + 1][2];
        for(int i = H - 1; i >= 0; i--){
            for(int w = 0; w < 2; w++){
                boolean pw = w == 1;
                double best = f[i + 1][0]; int ch = 0;
                if(rowFree(i)){ double c = f[i + 1][1] - rowCost; if(c > best){ best = c; ch = 1; } }
                if(i + s < H){
                    double a = lane(memo, i + s, 2, false, pw).value + f[i + s + 1][0];
                    if(a > best){ best = a; ch = 2; }
                    double b = lane(memo, i, 1, false, false).value + f[i + s + 1][0];
                    if(b > best){ best = b; ch = 3; }
                    if(i + s + 1 < H && rowFree(i + s + 1)){
                        double bw = lane(memo, i, 1, true, false).value - rowCost + f[i + s + 2][1];
                        if(bw > best){ best = bw; ch = 4; }
                    }
                }
                if(i + 2 * s < H){
                    double c = lane(memo, i + s, 3, false, pw).value + f[i + 2 * s + 1][0];
                    if(c > best){ best = c; ch = 5; }
                    if(i + 2 * s + 1 < H && rowFree(i + 2 * s + 1)){
                        double cw = lane(memo, i + s, 3, true, pw).value - rowCost + f[i + 2 * s + 2][1];
                        if(cw > best){ best = cw; ch = 6; }
                    }
                }
                f[i][w] = best; choice[i][w] = ch;
            }
        }
        feeders = new ArrayList<>();
        waterRows = new ArrayList<>();
        int w = 0;
        for(int i = 0; i < H; ){
            boolean pw = w == 1;
            switch(choice[i][w]){
                case 0: i++; w = 0; break;
                case 1: waterRows.add(i); i++; w = 1; break;
                case 2: feeders.addAll(lane(memo, i + s, 2, false, pw).segs); i += s + 1; w = 0; break;
                case 3: feeders.addAll(lane(memo, i, 1, false, false).segs); i += s + 1; w = 0; break;
                case 4: feeders.addAll(lane(memo, i, 1, true, false).segs); waterRows.add(i + s + 1); i += s + 2; w = 1; break;
                case 5: feeders.addAll(lane(memo, i + s, 3, false, pw).segs); i += 2 * s + 1; w = 0; break;
                default: feeders.addAll(lane(memo, i + s, 3, true, pw).segs); waterRows.add(i + 2 * s + 1); i += 2 * s + 2; w = 1; break;
            }
        }
        return f[0][0];
    }

    /** a water row may not cross the reserved feed row */
    boolean rowFree(int r){
        for(int x = 1; x < W - 1; x++) if(mask[x][r]) return false;
        return true;
    }

    // ------------------------------------------------------------------ water

    /** Port tile on the chosen edge, spine along column 0, feed row along the top/bottom edge. */
    boolean setupWater(){
        int side = p.waterSide;
        // port: nearest buildable tile on the edge to the requested position
        int best = -1, bestD = Integer.MAX_VALUE;
        int len = side == 2 ? H : W - 1;
        for(int i = 0; i < len; i++){
            int u = side == 2 ? 0 : i, v = side == 2 ? i : side == 1 ? H - 1 : 0;
            if(!p.beltOk[u][v]) continue;
            int d = Math.abs(i - p.waterPos);
            if(d < bestD){ bestD = d; best = i; }
        }
        if(best < 0) return false;
        waterU = side == 2 ? 0 : best;
        waterV = side == 2 ? best : side == 1 ? H - 1 : 0;
        for(int v = 0; v < H; v++) mask[0][v] = true;
        if(side != 2) for(int u = 0; u <= waterU; u++) mask[u][waterV] = true;
        return true;
    }

    /** water element by tile */
    Map<Long, int[]> waterAt = new HashMap<>();

    /**
     * Builds the water network: port router, feed row to column 0 (top/bottom ports), spine down
     * column 0 with a router at every water row, then each water row as a conduit line with routers
     * next to its drills (interval stabbing). Unbuildable gaps are jumped with bridge conduits.
     * Drills without a router (or bridge end) next to them lose their wet flag.
     */
    void buildWater(){
        water.clear();
        waterAt.clear();
        List<int[]> wetList = new ArrayList<>();
        for(Feeder f : feeders) for(int[] d : f.drills) if(d.length > 2 && d[2] == 1) wetList.add(d);

        // routers per water row: fewest points stabbing every adjacent wet drill's columns
        TreeMap<Integer, TreeSet<Integer>> routers = new TreeMap<>();
        for(int r : waterRows){
            List<int[]> iv = new ArrayList<>();
            for(int[] d : wetList) if(d[1] == r + 1 || d[1] + s == r) iv.add(d);
            iv.sort(Comparator.comparingInt(d -> d[0]));
            TreeSet<Integer> xs = new TreeSet<>();
            iv.sort(Comparator.comparingInt(d -> d[0] + s - 1));
            for(int[] d : iv){
                Integer hit = xs.ceiling(d[0]);
                if(hit != null && hit <= d[0] + s - 1) continue;
                for(int c = d[0] + s - 1; c >= d[0]; c--) if(waterTileOk(c, r)){ xs.add(c); break; }
            }
            if(!xs.isEmpty()) routers.put(r, xs);
        }

        if(!routers.isEmpty()){
            // port router, then one A* leg per water row junction (nearest first), each from any
            // router already placed; the reserved corridor is cheap so legs follow it
            int[] port = {waterU, waterV, LROUTER, 0, waterU, waterV};
            water.add(port); waterAt.put(key(waterU, waterV), port);
            occ[waterU][waterV] = OWATER;
            List<Integer> rows = new ArrayList<>(routers.keySet());
            rows.sort(Comparator.comparingInt(r -> Math.abs(r - waterV) + (p.waterSide == 2 ? 0 : waterU)));
            for(int r : rows){
                checkCancel();
                int jx = -1;
                for(int x = 0; x <= routers.get(r).first(); x++) if(waterPlaceable(x, r)){ jx = x; break; }
                if(jx < 0) continue;
                // keep the leg off the row it feeds (it would point back into the junction)
                List<int[]> reserved = new ArrayList<>();
                for(int x = jx + 1; x <= routers.get(r).last(); x++) if(occ[x][r] == FREE){ occ[x][r] = OWATER; reserved.add(new int[]{x, r}); }
                List<int[]> leg = routeLiquid(jx, r);
                for(int[] rv : reserved) occ[rv[0]][rv[1]] = FREE;
                if(leg == null) continue;
                for(int[] el : leg){ water.add(el); waterAt.put(key(el[0], el[1]), el); occ[el[0]][el[1]] = OWATER; }
                int[] junction = {jx, r, LROUTER, 0, jx, r};
                water.add(junction); waterAt.put(key(jx, r), junction); occ[jx][r] = OWATER;
                // the row itself: conduits east from the junction with routers by its drills
                List<int[]> line = new ArrayList<>();
                for(int x = jx; x <= routers.get(r).last(); x++) line.add(new int[]{x, r});
                Set<Long> rrs = new HashSet<>();
                for(int x : routers.get(r)) rrs.add(key(x, r));
                walkLine(line, rrs);
                for(int[] el : water) occ[el[0]][el[1]] = OWATER;
                snapshot("water rows", false);
            }
        }

        // a drill stays wet only if a router or bridge-conduit end sits next to it
        for(Feeder f : feeders) for(int[] d : f.drills){
            if(d.length < 3 || d[2] == 0) continue;
            boolean ok = false;
            for(int x = d[0]; x < d[0] + s && !ok; x++){
                ok = feeds(key(x, d[1] - 1), 1) || feeds(key(x, d[1] + s), 3);
            }
            if(!ok) d[2] = 0;
        }
        for(int[] el : water) occ[el[0]][el[1]] = OWATER;
    }

    /**
     * Liquid A* to a junction tile (tu,tv): starts next to any router / bridge-conduit end already
     * placed; moves are conduit steps or bridge-conduit jumps over anything. The last element must
     * output into the junction (a conduit pointing at it, or a bridge end beside it). Returns the
     * elements to place (possibly empty if a router is already adjacent), or null.
     */
    List<int[]> routeLiquid(int tu, int tv){
        for(int d = 0; d < 4; d++) if(feeds(key(tu + DU[d], tv + DV[d]), (d + 2) % 4)) return new ArrayList<>();
        int N = W * H * 8;
        float[] cost = new float[N];
        Arrays.fill(cost, Float.MAX_VALUE);
        int[] prev = new int[N];
        PriorityQueue<float[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        int range = p.liquidBridgeRange;
        for(int[] e : water){
            for(int d = 0; d < 4; d++){
                int nu = e[0] + DU[d], nv = e[1] + DV[d];
                if(!feeds(key(e[0], e[1]), d) || !liquidFree(nu, nv)) continue;
                int id = sid(nu, nv, d, 0);
                float c = stepCost(nu, nv);
                if(c < cost[id]){ cost[id] = c; prev[id] = -1; open.add(new float[]{c + Math.abs(nu - tu) + Math.abs(nv - tv), id}); }
            }
        }
        while(!open.isEmpty()){
            float[] cur = open.poll();
            int id = (int)cur[1];
            int L = id & 1, d = (id >> 1) & 3, t = id >> 3, u = t / H, v = t % H;
            float c = cost[id];
            if(cur[0] - (Math.abs(u - tu) + Math.abs(v - tv)) > c + 1e-3f) continue;
            // goal: output into the junction (conduit pointing at it / bridge end beside it)
            for(int nd = 0; nd < 4; nd++){
                if(nd == (d + 2) % 4) continue;
                if(u + DU[nd] == tu && v + DV[nd] == tv) return liquidPath(prev, id, nd);
            }
            for(int nd = 0; nd < 4; nd++){
                if(nd == (d + 2) % 4) continue;
                float turn = nd != d ? 0.2f : 0f;
                int nu = u + DU[nd], nv = v + DV[nd];
                if(liquidFree(nu, nv) && !(nu == tu && nv == tv)) relax(open, cost, prev, id, sid(nu, nv, nd, 0), c + stepCost(nu, nv) + turn, nu, nv, tu, tv);
                for(int k = 2; k <= range; k++){
                    int ju = u + DU[nd] * k, jv = v + DV[nd] * k;
                    if(ju < 0 || jv < 0 || ju >= W || jv >= H) break;
                    if(!liquidFree(ju, jv) || (ju == tu && jv == tv)) continue;
                    relax(open, cost, prev, id, sid(ju, jv, nd, 1), c + 3f + 0.1f * k + turn, ju, jv, tu, tv);
                }
            }
        }
        return null;
    }

    boolean liquidFree(int u, int v){
        // column W-1 stays free for the item collector
        return u >= 0 && v >= 0 && u < W - 1 && v < H && p.beltOk[u][v] && occ[u][v] == FREE;
    }

    /** the reserved corridor is cheaper, so legs follow it unless it's blocked */
    float stepCost(int u, int v){ return mask[u][v] ? 0.5f : 1f; }

    void relax(PriorityQueue<float[]> open, float[] cost, int[] prev, int from, int to, float nc, int u, int v, int tu, int tv){
        if(nc < cost[to]){
            cost[to] = nc; prev[to] = from;
            open.add(new float[]{nc + Math.abs(u - tu) + Math.abs(v - tv), to});
        }
    }

    /** elements {u, v, kind, dir, lu, lv}; lastDir: from the last tile into the junction */
    List<int[]> liquidPath(int[] prev, int end, int lastDir){
        LinkedList<int[]> out = new LinkedList<>();
        int nextId = -1;
        for(int id = end; id != -1; id = prev[id]){
            int L = id & 1, d = (id >> 1) & 3, t = id >> 3, u = t / H, v = t % H;
            int[] e;
            if(nextId == -1) e = L == 1 ? new int[]{u, v, LBRIDGE, d, u, v} : new int[]{u, v, CONDUIT, lastDir, u, v};
            else{
                int nL = nextId & 1, nd = (nextId >> 1) & 3, nt = nextId >> 3;
                if(nL == 1) e = new int[]{u, v, LBRIDGE, nd, nt / H, nt % H};
                else if(L == 1) e = new int[]{u, v, LBRIDGE, d, u, v};
                else e = new int[]{u, v, CONDUIT, nd, u, v};
            }
            out.addFirst(e);
            nextId = id;
        }
        return out;
    }

    /**
     * Does the water element at k output toward direction `dir`? Routers output everywhere; an
     * unlinked bridge conduit everywhere except back toward its incoming bridge (e[3] = arrival).
     */
    boolean feeds(long k, int dir){
        int[] e = waterAt.get(k);
        if(e == null) return false;
        if(e[2] == LROUTER) return true;
        return e[2] == LBRIDGE && e[4] == e[0] && e[5] == e[1] && dir != (e[3] + 2) % 4;
    }

    boolean waterPlaceable(int u, int v){
        return u >= 0 && v >= 0 && u < W - 1 && v < H && p.beltOk[u][v] && occ[u][v] == FREE;
    }

    /**
     * Lays water blocks along a straight line of tiles (flowing in list order): routers at
     * `routerTiles`, conduits elsewhere pointing to the next tile. Tiles that already hold water
     * are reused. An unbuildable gap is jumped with a bridge conduit pair when in range (not from a
     * router: routers can't link). Stops at the first gap it can't cross.
     */
    void walkLine(List<int[]> tiles, Set<Long> routerTiles){
        int[] last = null;
        int i = 0;
        while(i < tiles.size()){
            int[] t = tiles.get(i);
            long k = key(t[0], t[1]);
            int[] existing = waterAt.get(k);
            if(existing != null){ last = existing; i++; continue; }
            if(waterPlaceable(t[0], t[1])){
                int d = i + 1 < tiles.size() ? dirOf(t, tiles.get(i + 1)) : (i > 0 ? dirOf(tiles.get(i - 1), t) : 0);
                int[] el = routerTiles.contains(k) ? new int[]{t[0], t[1], LROUTER, 0, t[0], t[1]} : new int[]{t[0], t[1], CONDUIT, d, t[0], t[1]};
                water.add(el); waterAt.put(k, el);
                last = el;
                i++;
                continue;
            }
            if(last == null || last[2] == LROUTER || i == 0) return;
            int[] from = tiles.get(i - 1);
            int land = -1;
            for(int j = i + 1; j < tiles.size(); j++){
                int[] c = tiles.get(j);
                int dist = Math.abs(c[0] - from[0]) + Math.abs(c[1] - from[1]);
                if(!straight(from, c) || dist > p.liquidBridgeRange) break;
                if(waterPlaceable(c[0], c[1])){ land = j; break; }
            }
            if(land < 0) return;
            int[] c = tiles.get(land);
            last[2] = LBRIDGE; last[4] = c[0]; last[5] = c[1];
            int[] el = new int[]{c[0], c[1], LBRIDGE, dirOf(from, c), c[0], c[1]};
            water.add(el); waterAt.put(key(c[0], c[1]), el);
            last = el;
            i = land + 1;
        }
    }

    static long key(int u, int v){ return ((long)u << 32) | (v & 0xffffffffL); }

    boolean straight(int[] a, int[] b){ return a[0] == b[0] || a[1] == b[1]; }

    static int dirOf(int[] a, int[] b){
        int dx = b[0] - a[0], dy = b[1] - a[1];
        return dx > 0 ? 0 : dy > 0 ? 1 : dx < 0 ? 2 : 3;
    }

    // ------------------------------------------------------------------ layout: bridge lattice

    /**
     * Rows of bridge chains (bridges every dx tiles, rows every dy), drills packed around them.
     * Each drill must touch a chain bridge on a face that bridge accepts from: not its link side,
     * and not the chain's end bridge (an unlinked bridge only accepts from other bridges).
     * Sets feeders if it beats {@code toBeat}; returns the best value found.
     */
    double chooseLattice(double toBeat){
        double bestValue = toBeat;
        List<Feeder> bestFeeders = null;
        String bestName = null;
        // stage 1: randomized greedy on every spacing/phase; keep the top few for local search
        List<Object[]> top = new ArrayList<>(); // {value, lattice, selection}
        Set<Integer> dxs = new TreeSet<>(Arrays.asList(Math.min(3, p.bridgeRange), Math.min(4, p.bridgeRange)));
        for(int dx : dxs){
            if(dx < 2) continue;
            for(int dy = s + 1; dy <= 2 * s + 1; dy++){
                for(int px = 0; px < dx; px++){
                    for(int py = 0; py < dy; py++){
                        checkCancel();
                        Lattice lat = new Lattice(dx, dy, px, py);
                        boolean[] bestSel = null;
                        double latBest = -1;
                        for(int r = 0; r < 6; r++){
                            boolean[] sel = lat.greedy(r == 0);
                            double val = lat.value(sel);
                            if(val > latBest){ latBest = val; bestSel = sel; }
                        }
                        top.add(new Object[]{latBest, lat, bestSel});
                        if(latBest > bestValue){
                            bestValue = latBest; bestFeeders = lat.feeders(bestSel); bestName = lat.name();
                            feeders = bestFeeders; layout = bestName;
                            snapshot("layout: trying " + bestName, false);
                        }
                    }
                }
            }
        }
        // stage 2: large-neighbourhood search on the most promising lattices
        top.sort((a, b) -> Double.compare((double)b[0], (double)a[0]));
        for(int i = 0; i < Math.min(3, top.size()); i++){
            Lattice lat = (Lattice)top.get(i)[1];
            boolean[] sel = (boolean[])top.get(i)[2];
            lat.improve(sel, 1500);
            double val = lat.value(sel);
            if(val > bestValue){
                bestValue = val; bestFeeders = lat.feeders(sel); bestName = lat.name();
                feeders = bestFeeders; layout = bestName;
                snapshot("layout: improving " + bestName, false);
            }
        }
        if(bestFeeders != null){ feeders = bestFeeders; layout = bestName; }
        return bestValue;
    }

    class Lattice{
        final int dx, dy;
        /** chain id at bridge tiles, -1 elsewhere */
        final int[][] chain = new int[W][H];
        /** index of the bridge in its chain */
        final int[][] index = new int[W][H];
        final List<List<int[]>> chains = new ArrayList<>();
        /** candidate drills: {u, v, chainId, bridgeIndex} (first valid attachment) */
        final List<int[]> cands = new ArrayList<>();
        final List<Double> values = new ArrayList<>();

        Lattice(int dx, int dy, int px, int py){
            this.dx = dx; this.dy = dy;
            for(int[] row : chain) Arrays.fill(row, -1);
            for(int v = py; v < H; v += dy){
                List<int[]> cur = null;
                for(int u = px; u <= W - 2; u += dx){
                    if(p.beltOk[u][v]){
                        if(cur == null){ cur = new ArrayList<>(); chains.add(cur); }
                        chain[u][v] = chains.size() - 1;
                        index[u][v] = cur.size();
                        cur.add(new int[]{u, v});
                    }else cur = null;
                }
            }
            for(int u = 0; u + s <= W - 1; u++){
                for(int v = 0; v + s <= H; v++){
                    double val = drillValue(u, v);
                    if(val <= 0 || coversBridge(u, v)) continue;
                    int[] at = attach(u, v);
                    if(at == null) continue;
                    cands.add(new int[]{u, v, at[0], at[1]});
                    values.add(val);
                }
            }
        }

        boolean coversBridge(int u, int v){
            for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++) if(chain[x][y] >= 0) return true;
            return false;
        }

        /** Does a chain bridge at (bu,bv) accept from a drill on its faceDir side? */
        boolean accepts(int bu, int bv, int faceDir){
            if(bu < 0 || bv < 0 || bu >= W || bv >= H) return false;
            int c = chain[bu][bv];
            if(c < 0) return false;
            boolean end = index[bu][bv] == chains.get(c).size() - 1;
            // chain links point +u: the east face is the link side
            return !end && faceDir != 0;
        }

        /** first bridge the drill can output into: {chain, index} */
        int[] attach(int u, int v){
            for(int i = 0; i < s; i++){
                // bridge east of the drill sees it on its west face; a bridge west of it would see it on the (rejected) link face
                if(accepts(u + s, v + i, 2)) return new int[]{chain[u + s][v + i], index[u + s][v + i]};
                if(accepts(u + i, v - 1, 1)) return new int[]{chain[u + i][v - 1], index[u + i][v - 1]};
                if(accepts(u + i, v + s, 3)) return new int[]{chain[u + i][v + s], index[u + i][v + s]};
            }
            return null;
        }

        String name(){ return "bridge lattice " + dx + "x" + dy; }

        /** Randomized greedy by value; returns the chosen candidate set. */
        boolean[] greedy(boolean deterministic){
            Integer[] order = new Integer[cands.size()];
            double[] key = new double[cands.size()];
            for(int i = 0; i < order.length; i++){
                order[i] = i;
                key[i] = values.get(i) + (deterministic ? 0 : rand.nextDouble() * 0.9);
            }
            Arrays.sort(order, (a, b) -> Double.compare(key[b], key[a]));
            boolean[][] used = new boolean[W][H];
            boolean[] sel = new boolean[cands.size()];
            for(int i : order){
                int[] c = cands.get(i);
                if(overlaps(used, c[0], c[1])) continue;
                set(used, c[0], c[1], true);
                sel[i] = true;
            }
            return sel;
        }

        boolean overlaps(boolean[][] used, int u, int v){
            for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++) if(used[x][y]) return true;
            return false;
        }

        void set(boolean[][] used, int u, int v, boolean b){
            for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++) used[x][y] = b;
        }

        double value(boolean[] sel){
            double v = 0;
            for(Feeder f : feeders(sel)) v += f.value;
            return v;
        }

        List<Feeder> feeders(boolean[] sel){
            int nc = chains.size();
            int[] lo = new int[nc], hi = new int[nc];
            Arrays.fill(lo, Integer.MAX_VALUE); Arrays.fill(hi, -1);
            List<List<int[]>> drillsOf = new ArrayList<>();
            double[] val = new double[nc];
            for(int i = 0; i < nc; i++) drillsOf.add(new ArrayList<>());
            for(int i = 0; i < sel.length; i++){
                if(!sel[i]) continue;
                int[] c = cands.get(i);
                drillsOf.get(c[2]).add(new int[]{c[0], c[1], 0});
                val[c[2]] += values.get(i);
                lo[c[2]] = Math.min(lo[c[2]], c[3]);
                hi[c[2]] = Math.max(hi[c[2]], c[3]);
            }
            List<Feeder> out = new ArrayList<>();
            for(int c = 0; c < nc; c++){
                if(hi[c] < 0) continue;
                List<int[]> ch = chains.get(c);
                int end = hi[c] + 1; // one bridge past the last attachment: end bridges don't accept from drills
                Feeder f = new Feeder();
                f.drills.addAll(drillsOf.get(c));
                for(int i = lo[c]; i < end; i++) f.bridges.add(ch.get(i));
                f.su = ch.get(end)[0]; f.sv = ch.get(end)[1]; f.landed = true;
                f.value = val[c] - bridgeCost() * (end - lo[c] + 1);
                if(f.value > 0) out.add(f);
            }
            return out;
        }

        /**
         * Large-neighbourhood search: clear a random window, re-pack it exactly (DFS with bound),
         * keep the result if it is not worse. Mutates sel.
         */
        void improve(boolean[] sel, int iterations){
            int K = 3 * s; // window side
            if(W - 1 < K || H < K) return;
            boolean[][] used = new boolean[W][H];
            for(int i = 0; i < sel.length; i++) if(sel[i]) set(used, cands.get(i)[0], cands.get(i)[1], true);
            for(int it = 0; it < iterations; it++){
                if((it & 31) == 0) checkCancel();
                int wu = rand.nextInt(W - K), wv = rand.nextInt(H - K + 1);
                // remove selected drills fully inside the window
                double removed = 0;
                List<Integer> out = new ArrayList<>();
                for(int i = 0; i < sel.length; i++){
                    int[] c = cands.get(i);
                    if(sel[i] && inside(c, wu, wv, K)){ sel[i] = false; set(used, c[0], c[1], false); removed += values.get(i); out.add(i); }
                }
                List<Integer> local = new ArrayList<>();
                for(int i = 0; i < sel.length; i++){
                    int[] c = cands.get(i);
                    if(inside(c, wu, wv, K) && !overlaps(used, c[0], c[1])) local.add(i);
                }
                List<Integer> best = new ArrayList<>();
                double[] bestVal = {-1};
                double[] suffix = new double[local.size() + 1];
                for(int i = local.size() - 1; i >= 0; i--) suffix[i] = suffix[i + 1] + values.get(local.get(i));
                dfs(local, 0, used, new ArrayList<>(), 0, best, bestVal, suffix);
                if(bestVal[0] >= removed){
                    for(int i : best){ sel[i] = true; set(used, cands.get(i)[0], cands.get(i)[1], true); }
                }else{
                    for(int i : out){ sel[i] = true; set(used, cands.get(i)[0], cands.get(i)[1], true); }
                }
            }
        }

        boolean inside(int[] c, int wu, int wv, int K){
            return c[0] >= wu && c[1] >= wv && c[0] + s <= wu + K && c[1] + s <= wv + K;
        }

        void dfs(List<Integer> local, int i, boolean[][] used, List<Integer> cur, double val, List<Integer> best, double[] bestVal, double[] suffix){
            if(val > bestVal[0]){ bestVal[0] = val; best.clear(); best.addAll(cur); }
            if(i >= local.size() || val + suffix[i] <= bestVal[0]) return;
            int idx = local.get(i);
            int[] c = cands.get(idx);
            if(!overlaps(used, c[0], c[1])){
                set(used, c[0], c[1], true);
                cur.add(idx);
                dfs(local, i + 1, used, cur, val + values.get(idx), best, bestVal, suffix);
                cur.remove(cur.size() - 1);
                set(used, c[0], c[1], false);
            }
            dfs(local, i + 1, used, cur, val, best, bestVal, suffix);
        }
    }

    void mark(Feeder f, boolean on){
        for(int[] d : f.drills) fill(d[0], d[1], s, on ? ODRILL : FREE);
        for(int[] b : f.belts) occ[b[0]][b[1]] = on ? LANE : FREE;
        for(int[] b : f.bridges) occ[b[0]][b[1]] = on ? OBRIDGE : FREE;
        occ[f.su][f.sv] = on ? (f.landed ? OBRIDGE : LANE) : FREE;
    }

    void fill(int u, int v, int size, int val){
        for(int x = u; x < u + size; x++) for(int y = v; y < v + size; y++) occ[x][y] = val;
    }

    // ------------------------------------------------------------------ routing

    void commit(Feeder f, List<int[]> path){
        f.path = path;
        for(int[] b : f.belts) occ[b[0]][b[1]] = 0;
        for(int[] e : path){
            if(e[2] == BELT){
                occ[e[0]][e[1]] = e[3];
            }else{
                occ[e[0]][e[1]] = OBRIDGE;
                if(e[4] == e[0] && e[5] == e[1]){
                    // an end bridge dumps everywhere but back: keep belts off its other neighbours
                    for(int d = 0; d < 4; d++){
                        int x = e[0] + DU[d], y = e[1] + DV[d];
                        if(x >= 0 && y >= 0 && x < W && y < H && occ[x][y] == FREE) occ[x][y] = BUFFER;
                    }
                }
            }
        }
    }

    List<int[]> routeWithRetry(Feeder f, boolean toPort){
        Set<Integer> banned = new HashSet<>();
        for(int attempt = 0; attempt < 8; attempt++){
            List<int[]> path = route(f, toPort, banned);
            if(path == null) return null;
            int bad = selfLeak(path);
            if(bad < 0) return path;
            banned.add(bad);
        }
        return null;
    }

    /**
     * An end bridge dumps into every neighbour except back toward its source; within a single path
     * that could feed an upstream belt (a loop). Returns the offending tile (u*H+v) or -1.
     */
    int selfLeak(List<int[]> path){
        Set<Integer> tiles = new HashSet<>();
        for(int[] e : path) tiles.add(e[0] * H + e[1]);
        for(int i = 0; i < path.size(); i++){
            int[] e = path.get(i);
            if(e[2] != BRIDGE || e[4] != e[0] || e[5] != e[1]) continue;
            int next = i + 1 < path.size() ? path.get(i + 1)[0] * H + path.get(i + 1)[1] : -1;
            int back = (e[3] + 2) % 4; // e[3] holds the arrival direction for end bridges
            for(int d = 0; d < 4; d++){
                if(d == back) continue;
                int x = e[0] + DU[d], y = e[1] + DV[d];
                int id = x * H + y;
                if(id != next && x >= 0 && y >= 0 && x < W && y < H && tiles.contains(id)) return e[0] * H + e[1];
            }
        }
        return -1;
    }

    /** Side neighbours of an end bridge at (u,v) (all but `out` and back) must not take items. */
    boolean dumpSafe(int u, int v, int out, int arrival){
        int back = (arrival + 2) % 4;
        for(int d = 0; d < 4; d++){
            if(d == out || d == back) continue;
            int x = u + DU[d], y = v + DV[d];
            if(x < 0 || y < 0 || x >= W || y >= H) continue;
            int o = occ[x][y];
            if(o >= 0 || o == LANE || o == OBRIDGE) return false;
        }
        return true;
    }

    /**
     * A* over (tile, arrival dir, landed). Moves: conveyor step to a neighbour, or a bridge jump of
     * 2..range tiles in a straight line (over anything). Goal: exit through the port (first route)
     * or feed into a routed belt, not head-on. Returns elements {u, v, kind, dir, lu, lv}; for end
     * bridges dir holds the arrival direction.
     */
    List<int[]> route(Feeder f, boolean toPort, Set<Integer> bannedEnds){
        int N = W * H * 8;
        float[] cost = new float[N];
        Arrays.fill(cost, Float.MAX_VALUE);
        int[] prev = new int[N];
        PriorityQueue<float[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        int start = sid(f.su, f.sv, 0, f.landed ? 1 : 0);
        cost[start] = 0; prev[start] = -1;
        open.add(new float[]{h(f.su, f.sv, toPort), start});
        int range = p.bridges ? p.bridgeRange : 0;

        while(!open.isEmpty()){
            float[] cur = open.poll();
            int id = (int)cur[1];
            int L = id & 1, d = (id >> 1) & 3, t = id >> 3, u = t / H, v = t % H;
            float c = cost[id];
            if(cur[0] - h(u, v, toPort) > c + 1e-3f) continue; // stale
            boolean isPort = u == W - 1 && v == port;
            if(toPort && isPort && L == 0) return path(prev, id, 0);
            for(int nd = 0; nd < 4; nd++){
                if(nd == (d + 2) % 4) continue;
                float turn = nd != d ? 0.4f : 0f;
                // conveyor step (from a landed bridge this makes it an end bridge)
                int nu = u + DU[nd], nv = v + DV[nd];
                boolean endOk = L == 0 || (!bannedEnds.contains(t) && dumpSafe(u, v, nd, d));
                if(endOk && nu >= 0 && nv >= 0 && nu < W && nv < H && !(toPort && isPort)){
                    int o = occ[nu][nv];
                    if(!toPort && o >= 0 && nd != (o + 2) % 4) return path(prev, id, nd); // merge into routed belt
                    if(o == FREE && p.beltOk[nu][nv]){
                        push(open, cost, prev, id, sid(nu, nv, nd, 0), c + 1f + turn, nu, nv, toPort);
                    }
                }
                // bridge jump
                if(isPort && toPort) continue;
                for(int k = 2; k <= range; k++){
                    int ju = u + DU[nd] * k, jv = v + DV[nd] * k;
                    if(ju < 0 || jv < 0 || ju >= W || jv >= H) break;
                    if(occ[ju][jv] != FREE || !p.beltOk[ju][jv] || (ju == W - 1 && jv == port)) continue;
                    push(open, cost, prev, id, sid(ju, jv, nd, 1), c + 4f + 0.1f * k + turn, ju, jv, toPort);
                }
            }
        }
        return null;
    }

    void push(PriorityQueue<float[]> open, float[] cost, int[] prev, int from, int to, float nc, int u, int v, boolean toPort){
        if(nc < cost[to]){
            cost[to] = nc; prev[to] = from;
            open.add(new float[]{nc + h(u, v, toPort), to});
        }
    }

    int sid(int u, int v, int d, int landed){ return ((u * H + v) << 3) | (d << 1) | landed; }

    float h(int u, int v, boolean toPort){
        return toPort ? Math.abs(W - 1 - u) + Math.abs(port - v) : 0f;
    }

    /** Walk back from the end state. lastDir: direction leaving the last tile. */
    List<int[]> path(int[] prev, int end, int lastDir){
        LinkedList<int[]> out = new LinkedList<>();
        int nextId = -1;
        for(int id = end; id != -1; id = prev[id]){
            int L = id & 1, d = (id >> 1) & 3, t = id >> 3, u = t / H, v = t % H;
            int[] e;
            if(nextId == -1){
                // last tile of the route: a landed bridge dumps into the merge target
                e = L == 1 ? new int[]{u, v, BRIDGE, d, u, v} : new int[]{u, v, BELT, lastDir, u, v};
            }else{
                int nL = nextId & 1, nd = (nextId >> 1) & 3, nt = nextId >> 3;
                if(nL == 1) e = new int[]{u, v, BRIDGE, nd, nt / H, nt % H}; // jump: link to landing
                else if(L == 1) e = new int[]{u, v, BRIDGE, d, u, v};       // landed, then a step: end bridge
                else e = new int[]{u, v, BELT, nd, u, v};
            }
            out.addFirst(e);
            nextId = id;
        }
        return out;
    }

    // ------------------------------------------------------------------ power

    /** Same test the game uses: laser circle around the node centre touches the target's hitbox. */
    boolean reaches(int nu, int nv, int x, int y, int size){
        float r = p.nodeRange * 0.95f; // small safety margin
        float x0 = x - 0.5f, x1 = x + size - 0.5f, y0 = y - 0.5f, y1 = y + size - 0.5f;
        float dx = Math.max(0, Math.max(x0 - nu, nu - x1)), dy = Math.max(0, Math.max(y0 - nv, nv - y1));
        return dx * dx + dy * dy <= r * r;
    }

    boolean nodeFree(int u, int v){
        return (occ[u][v] == FREE || occ[u][v] == BUFFER) && p.nodeOk[u][v];
    }

    /** Places nodes covering every drill (dropping drills no node can reach). Mutates drillList. */
    List<int[]> placePower(List<int[]> drillList){
        // keep a couple of slots per node free for node-to-node links
        int cap = Math.max(1, p.nodeMaxLinks - 3);
        List<int[]> nodes;
        while(true){
            checkCancel();
            nodes = new ArrayList<>();
            nodeList = nodes;
            int[][] save = copy(occ);
            boolean[] covered = new boolean[drillList.size()];
            int left = drillList.size();
            while(left > 0){
                int bu = -1, bv = -1;
                double best = 0;
                for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
                    if(!nodeFree(u, v)) continue;
                    int c = 0;
                    double dist = 0;
                    for(int i = 0; i < drillList.size(); i++){
                        int[] d = drillList.get(i);
                        if(!covered[i] && reaches(u, v, d[0], d[1], s)){ c++; dist += Math.abs(d[0] + s / 2f - u) + Math.abs(d[1] + s / 2f - v); }
                    }
                    if(c == 0) continue;
                    // most drills covered; ties go to the node closest to them
                    double score = Math.min(c, cap) * 1000 - dist / c;
                    if(score > best){ best = score; bu = u; bv = v; }
                }
                if(bu < 0) break;
                occ[bu][bv] = ONODE;
                nodes.add(new int[]{bu, bv});
                int taken = 0;
                for(int i = 0; i < drillList.size() && taken < cap; i++){
                    if(!covered[i] && reaches(bu, bv, drillList.get(i)[0], drillList.get(i)[1], s)){ covered[i] = true; taken++; left--; }
                }
                snapshot("power nodes", false);
            }
            if(left == 0) break;
            // drop one unreachable drill; its tiles may host a node for the others
            occ = save;
            for(int i = 0; i < drillList.size(); i++){
                if(!covered[i]){
                    int[] d = drillList.remove(i);
                    removeDrill(d);
                    fill(d[0], d[1], s, FREE);
                    drillsDroppedForPower++;
                    break;
                }
            }
            if(drillList.isEmpty()) return nodes;
        }
        connectNodes(nodes, drillList);
        return nodes;
    }

    void removeDrill(int[] d){
        for(Feeder f : feeders) f.drills.removeIf(x -> x[0] == d[0] && x[1] == d[1]);
    }

    int[][] copy(int[][] a){
        int[][] b = new int[a.length][];
        for(int i = 0; i < a.length; i++) b[i] = a[i].clone();
        return b;
    }

    boolean nodeLink(int[] a, int[] b){ return reaches(a[0], a[1], b[0], b[1], 1); }

    /**
     * Adds relay nodes until all nodes are linked. Dijkstra from group 0 over node positions:
     * each relay costs 1, a relay under a drill costs 100 (that drill gets removed), others block.
     */
    void connectNodes(List<int[]> nodes, List<int[]> drillList){
        int R = (int)Math.ceil(p.nodeRange);
        while(true){
            checkCancel();
            int[] comp = components(nodes);
            powerGroups = 0;
            for(int c : comp) powerGroups = Math.max(powerGroups, c + 1);
            if(powerGroups <= 1) return;

            int[][] drillAt = new int[W][H];
            for(int[] row : drillAt) Arrays.fill(row, -1);
            for(int i = 0; i < drillList.size(); i++){
                int[] d = drillList.get(i);
                for(int x = d[0]; x < d[0] + s; x++) for(int y = d[1]; y < d[1] + s; y++) drillAt[x][y] = i;
            }

            int[] prev = new int[W * H], cost = new int[W * H];
            Arrays.fill(prev, -2);
            Arrays.fill(cost, Integer.MAX_VALUE);
            PriorityQueue<int[]> q = new PriorityQueue<>(Comparator.comparingInt(a -> a[0]));
            for(int i = 0; i < nodes.size(); i++) if(comp[i] == 0){
                int id = nodes.get(i)[0] * H + nodes.get(i)[1];
                prev[id] = -1; cost[id] = 0; q.add(new int[]{0, id});
            }
            int hit = -1;
            outer:
            while(!q.isEmpty()){
                int[] cur = q.poll();
                int id = cur[1], u = id / H, v = id % H;
                if(cur[0] > cost[id]) continue;
                for(int i = 0; i < nodes.size(); i++){
                    if(comp[i] != 0 && nodeLink(new int[]{u, v}, nodes.get(i))){ hit = id; break outer; }
                }
                for(int x = Math.max(0, u - R); x <= Math.min(W - 1, u + R); x++){
                    for(int y = Math.max(0, v - R); y <= Math.min(H - 1, v + R); y++){
                        if(!p.nodeOk[x][y] || !reaches(u, v, x, y, 1)) continue;
                        int step = nodeFree(x, y) ? 1 : occ[x][y] == ODRILL ? 100 : -1;
                        if(step < 0) continue;
                        int nid = x * H + y, nc = cur[0] + step;
                        if(nc < cost[nid]){ cost[nid] = nc; prev[nid] = id; q.add(new int[]{nc, nid}); }
                    }
                }
            }
            if(hit < 0) return; // cannot connect; leave separate groups

            Set<Integer> remove = new TreeSet<>(Collections.reverseOrder());
            List<int[]> relays = new ArrayList<>();
            for(int id = hit; prev[id] != -1; id = prev[id]){
                int u = id / H, v = id % H;
                if(drillAt[u][v] >= 0) remove.add(drillAt[u][v]);
                relays.add(new int[]{u, v});
            }
            for(int i : remove){
                int[] d = drillList.remove(i);
                removeDrill(d);
                fill(d[0], d[1], s, FREE);
                drillsDroppedForPower++;
            }
            for(int[] r : relays){ occ[r[0]][r[1]] = ONODE; nodes.add(r); }
            snapshot("power relays", false);
        }
    }

    int[] components(List<int[]> nodes){
        int n = nodes.size();
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int c = 0;
        for(int i = 0; i < n; i++){
            if(comp[i] >= 0) continue;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(i); comp[i] = c;
            while(!q.isEmpty()){
                int a = q.poll();
                for(int b = 0; b < n; b++) if(comp[b] < 0 && nodeLink(nodes.get(a), nodes.get(b))){ comp[b] = c; q.add(b); }
            }
            c++;
        }
        return comp;
    }
}
