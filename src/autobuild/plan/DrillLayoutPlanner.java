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

    /** layout strategies; AUTO runs every applicable one through the whole pipeline and keeps the best score */
    public enum Family{ AUTO, LANES, LATTICE, WATER_ROWS, DRILL_FIRST }

    public static final int DRILL = 0, BELT = 1, NODE = 2, BRIDGE = 3, CONDUIT = 4, LROUTER = 5, LBRIDGE = 6;
    /** an existing water source (not ours, not built): dir holds a mask of the directions it outputs to */
    static final int EXISTING_WATER = 7;
    static final int[] DU = {1, 0, -1, 0}, DV = {0, 1, 0, -1};

    // occupancy codes; >= 0 is a routed belt with that direction
    static final int FREE = -1, ODRILL = -2, LANE = -3, ONODE = -4, OBRIDGE = -5, BUFFER = -6, OWATER = -7, RESERVED = -8;

    public static class Place{
        public final int kind, u, v, dir, ore;
        /** BRIDGE/LBRIDGE: link target (equal to u,v for an end bridge). DRILL: dir = 1 if water-boosted */
        public final int lu, lv;
        /** DRILL: drill type index and footprint size */
        public int type, size = 1;
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
        /**
         * 0 = best plan. Higher values explore alternatives: drill values get deterministic noise,
         * routing order and turn penalty change, the lattice search uses another seed.
         */
        public int variant;
        /**
         * Optional extra drill types (smaller / lower tier drills that fill gaps). Index 0 is the
         * chosen drill and must have the largest size. Null: a single type from drillSize, drop,
         * drillOk and power.
         */
        public int[] typeSize;
        /** output per ore tile relative to type 0 */
        public double[] typeRate;
        public boolean[] typePowered;
        /** per type: mineable item per tile, and footprint placeable at local min corner */
        public int[][][] typeDrop;
        public boolean[][][] typeDrillOk;
        public Family family = Family.AUTO;
        /**
         * Item outputs: tiles the network delivers into (local coords; an edge output lies just
         * outside the region). Null: one output past the +u edge at portV.
         */
        public int[][] sinks;
        /** tiles kept free around an interior output (e.g. 1 for a 3x3 block) */
        public int sinkReserve;
        /** items/s one output line (belt) can carry; items/s per unit of drill output (ore x rate). 0 = no capacity limits */
        public float sinkCapacity, itemsPerOre;
        /** water ports (liquid routers, local coords). Null: one on waterSide at waterPos */
        public int[][] waterPorts;
        /** existing (not ours) buildings that take items: an end bridge must not dump into them */
        public boolean[][] foreignAcceptor;
        /** tiles with other valuable ore (not the target, not low priority): keep free for later mining. Null: from drop */
        public boolean[][] otherOre;
        /** optional per sink: tiles it covers {u0, v0, u1, v1} (an existing building: deliver into any of them) */
        public int[][] sinkRects;
        /** tiles next to an existing output building: a belt there may only point into the output */
        public boolean[][] approachOnly;
        /** existing water sources: tiles {u, v, dirMask} (they are not built; they output where the mask says) */
        public int[][] waterSources;

        public Params copy(){
            Params q = new Params();
            q.W = W; q.H = H; q.drillSize = drillSize; q.target = target; q.portV = portV;
            q.beltOk = beltOk; q.nodeOk = nodeOk; q.drillOk = drillOk; q.drop = drop; q.lowPriority = lowPriority;
            q.mode = mode; q.power = power; q.nodeRange = nodeRange; q.nodeMaxLinks = nodeMaxLinks;
            q.bridges = bridges; q.bridgeRange = bridgeRange; q.seed = seed;
            q.water = water; q.waterBoost = waterBoost; q.waterSide = waterSide; q.waterPos = waterPos; q.liquidBridgeRange = liquidBridgeRange;
            q.variant = variant;
            q.typeSize = typeSize; q.typeRate = typeRate; q.typePowered = typePowered; q.typeDrop = typeDrop; q.typeDrillOk = typeDrillOk;
            q.family = family;
            q.foreignAcceptor = foreignAcceptor; q.otherOre = otherOre;
            q.sinkRects = sinkRects; q.approachOnly = approachOnly; q.waterSources = waterSources;
            q.sinks = sinks; q.sinkReserve = sinkReserve; q.sinkCapacity = sinkCapacity; q.itemsPerOre = itemsPerOre; q.waterPorts = waterPorts;
            return q;
        }
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
    /** AUTO runs one sub-planner per family; cancelling the parent cancels them */
    DrillLayoutPlanner parent;

    // ---- outputs ----
    public final List<Place> places = new ArrayList<>();
    public int drills, wetDrills, waterTiles, oreTiles, nodes, belts, bridges, segmentsRouted, segmentsDropped, drillsDroppedForPower, powerGroups, port = -1;
    public String layout = "", failure;
    /** objective of the final plan without variant noise (higher is better); comparable across ports/variants */
    public double score;
    /** water was requested but a dry layout scored better (e.g. the water network couldn't reach the rows) */
    public boolean waterSkipped;
    /** item outputs (local coords) and items/s delivered to each */
    public List<int[]> sinkList = new ArrayList<>();
    /** items/s the field could add if there were more output capacity */
    public double excessFlow;
    public double[] sinkLoad = new double[0];
    /** output line each routed tile drains into, -1 elsewhere */
    int[][] sinkOf;
    /** which sink the last route ended in */
    int lastSink;
    static final int OSINK = -9;

    // ---- work state ----
    int[][] occ;
    /** other ore under a tile (see Params.otherOre) */
    final boolean[][] otherOre;
    /** direction of the link of every routed, linked item bridge (a merge target); -1 elsewhere */
    int[][] bridgeLink;
    /** tiles strictly between the ends of a placed bridge link (item or liquid): a corridor already in use */
    boolean[][] underLink;
    /** per type: target ore under a drill at min corner (u,v) if it fits and would mine the target, else 0 */
    int[][][] ore;
    /** drill types */
    final int T;
    final int[] sz;
    final double[] rate;
    final boolean[] powered;
    final int[][][] tDrop;
    final boolean[][][] tOk;
    final Random rand;
    long lastSnapshot;
    final float turnCost;
    /** variant noise is applied while choosing the layout, not when scoring */
    boolean noise;
    List<Feeder> feeders = new ArrayList<>();
    List<int[]> nodeList = new ArrayList<>();
    /** tiles reserved for the water spine / feed row (not usable by the layout) */
    boolean[][] mask;
    /** water rows chosen by the layout */
    List<Integer> waterRows = new ArrayList<>();
    /** water elements {u, v, kind, dir, lu, lv} */
    List<int[]> water = new ArrayList<>();
    public int waterU = -1, waterV = -1;
    /** every water port (liquid routers fed from outside) */
    public List<int[]> waterPortList = new ArrayList<>();
    /** existing water sources registered as (unbuilt) water elements */
    final List<int[]> existingWater = new ArrayList<>();

    public DrillLayoutPlanner(Params p){
        this.p = p; this.W = p.W; this.H = p.H; this.s = p.drillSize;
        if(p.otherOre != null) otherOre = p.otherOre;
        else{
            otherOre = new boolean[W][H];
            for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
                int d = p.drop[u][v];
                otherOre[u][v] = d >= 0 && d != p.target && (p.lowPriority == null || !p.lowPriority[d]);
            }
        }
        if(p.typeSize == null){
            T = 1; sz = new int[]{p.drillSize}; rate = new double[]{1}; powered = new boolean[]{p.power};
            tDrop = new int[][][]{p.drop}; tOk = new boolean[][][]{p.drillOk};
        }else{
            T = p.typeSize.length; sz = p.typeSize; rate = p.typeRate; powered = p.typePowered;
            tDrop = p.typeDrop; tOk = p.typeDrillOk;
        }
        this.rand = new Random(p.seed * 31 + p.variant * 7919L);
        this.turnCost = p.variant == 0 ? 0.4f : 0.15f + rand.nextFloat() * 0.8f;
    }

    /** Something that must be routed to the port: a lane segment or a bridge chain. */
    static class Feeder{
        final List<int[]> drills = new ArrayList<>(); // min corners
        final List<int[]> belts = new ArrayList<>();  // lane belts before the start tile, flowing +u
        final List<int[]> bridges = new ArrayList<>(); // chain bridges before the start tile, each links to the next
        int su, sv;        // routing start (arrives moving +u)
        double flow;       // items/s
        int sink = -1;
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
        if(p.family == Family.AUTO) return planAuto();
        return planFamily();
    }

    /** every applicable family through the whole pipeline; the best final score wins */
    boolean planAuto(){
        List<Family> fams = new ArrayList<>();
        fams.add(Family.LANES);
        if(p.bridges) fams.add(Family.LATTICE);
        if(p.water) fams.add(Family.WATER_ROWS);
        fams.add(Family.DRILL_FIRST);
        DrillLayoutPlanner best = null;
        String firstFail = null;
        for(Family fam : fams){
            checkCancel();
            Params q = p.copy();
            q.family = fam;
            DrillLayoutPlanner sub = new DrillLayoutPlanner(q);
            sub.parent = this;
            sub.listener = listener;
            sub.snapshotInterval = snapshotInterval;
            if(sub.plan()){
                if(best == null || sub.score > best.score) best = sub;
            }else if(firstFail == null) firstFail = sub.failure;
        }
        if(best == null){ failure = firstFail; return false; }
        places.clear(); places.addAll(best.places);
        drills = best.drills; wetDrills = best.wetDrills; waterTiles = best.waterTiles; oreTiles = best.oreTiles; nodes = best.nodes;
        belts = best.belts; bridges = best.bridges; segmentsRouted = best.segmentsRouted; segmentsDropped = best.segmentsDropped;
        drillsDroppedForPower = best.drillsDroppedForPower; powerGroups = best.powerGroups; port = best.port;
        layout = best.layout; score = best.score; waterU = best.waterU; waterV = best.waterV;
        sinkList = best.sinkList; sinkLoad = best.sinkLoad; waterPortList = best.waterPortList; excessFlow = best.excessFlow;
        waterSkipped = p.water && best.wetDrills == 0;
        return true;
    }

    boolean planFamily(){
        Family fam = p.family;
        mask = new boolean[W][H];
        if(!setupSinks()) return false;

        boolean useWater = p.water && (fam == Family.WATER_ROWS || fam == Family.DRILL_FIRST);
        assumeWet = useWater && fam == Family.DRILL_FIRST;
        if(useWater && !setupWater()){ failure = "no buildable tile for the water port"; return false; }
        computeOre();
        occ = new int[W][H];
        noise = true;

        switch(fam){
            case LANES: chooseLanes(); layout = "belt lanes"; break;
            case LATTICE: chooseLattice(0); if(layout.isEmpty()) layout = "bridge lattice"; break;
            case WATER_ROWS: chooseLanesWater(); layout = "belt lanes + water rows"; break;
            default: feeders = new ArrayList<>(); layout = "drill-first"; break;
        }
        if(fam != Family.DRILL_FIRST && feeders.isEmpty()){
            failure = p.mode == Mode.EFFICIENCY ? "no drill position with enough target ore (try Max output)" : "no drill position on the target ore";
            return false;
        }

        for(int[] row : occ) Arrays.fill(row, FREE);
        for(Feeder f : feeders) mark(f, true);
        markSinks();
        sinkOf = new int[W][H];
        for(int[] row : sinkOf) Arrays.fill(row, -1);
        sinkLoad = new double[sinkList.size()];
        for(Feeder f : feeders){ f.flow = 0; for(int[] d : f.drills) f.flow += flowOf(d); }
        underLink = new boolean[W][H];
        for(Feeder f : feeders) for(int i = 0; i < f.bridges.size(); i++){
            int[] b = f.bridges.get(i), n = i + 1 < f.bridges.size() ? f.bridges.get(i + 1) : new int[]{f.su, f.sv};
            markLink(b[0], b[1], n[0], n[1]);
        }
        bridgeLink = new int[W][H];
        for(int[] row : bridgeLink) Arrays.fill(row, -1);
        if(fam == Family.WATER_ROWS){
            buildWater();
            for(int[] el : water) if(el[2] == LBRIDGE) markLink(el[0], el[1], el[4], el[5]);
        }
        snapshot("layout: " + layout, true);

        if(fam == Family.DRILL_FIRST){
            drillFirst();
        }else{
            // route, closest to an output first; respect line capacity, overload only if nothing else fits
            Map<Feeder, Double> order = new HashMap<>();
            for(Feeder f : feeders) order.put(f, sinkDistance(f.su, f.sv) + (p.variant == 0 ? 0 : rand.nextDouble() * 12));
            feeders.sort(Comparator.comparingDouble(order::get));
            List<Feeder> routed = new ArrayList<>(), pending = new ArrayList<>(feeders);
            for(Feeder f : pending){
                checkCancel();
                List<int[]> path = routeWithRetry(f, true);
                // over capacity: leave it out (its output would only back up) unless nothing is routed yet
                if(path == null && capacityOn() && routed.isEmpty()) path = routeWithRetry(f, false);
                if(path == null){
                    if(capacityOn() && routeWithRetry(f, false) != null){
                        excessFlow += f.flow; // could be connected, but no output line has room
                    }else segmentsDropped++;
                    mark(f, false);
                    feeders.remove(f);
                    continue;
                }
                commit(f, path);
                routed.add(f);
                segmentsRouted++;
                snapshot("routing", false);
            }
            feeders = routed;
        }
        if(segmentsRouted == 0){ failure = "nothing could be routed to an output"; return false; }

        fillGaps();
        if(fam == Family.DRILL_FIRST && useWater) waterByDrill();
        if(useWater) updateWet();
        assumeWet = false;
        if(capacityOn()){
            // actual loads with the water network as built
            Arrays.fill(sinkLoad, 0);
            for(Feeder f : feeders) if(f.sink >= 0) for(int[] d : f.drills) sinkLoad[f.sink] += flowOf(d);
        }
        noise = false;

        boolean anyPowered = false;
        for(boolean b : powered) anyPowered |= b;
        if(anyPowered){
            List<int[]> drillList = new ArrayList<>();
            for(Feeder f : feeders) for(int[] d : f.drills) if(powered[type(d)]) drillList.add(d);
            if(!drillList.isEmpty()) nodeList = placePower(drillList);
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
        score = 0;
        for(Feeder f : feeders) for(int[] d : f.drills) score += drillValue(d);
        score -= beltCost() * belts + bridgeCost() * bridges + waterTileCost() * waterTiles + nodeCost() * nodes;
        // other ore under non-drill blocks (drills already pay for theirs in drillValue)
        for(Place pl : places) if(pl.kind != DRILL && otherOre[pl.u][pl.v]) score -= oreTileValue();
        // output that would back up is lost, and the drills producing it are wasted
        if(capacityOn()) for(double load : sinkLoad) score -= 1.5 * Math.max(0, load - p.sinkCapacity) / p.itemsPerOre;
        snapshot("done", true);
        return true;
    }

    // ------------------------------------------------------------------ drill-first, gap filling

    /** footprint free (buffer tiles are fine: an end bridge doesn't dump into drills) and inside the field */
    boolean footprintFree(int u, int v, int st){
        if(u < 0 || v < 0 || u + st > W - 1 || v + st > H) return false;
        for(int x = u; x < u + st; x++) for(int y = v; y < v + st; y++) if(occ[x][y] != FREE && occ[x][y] != BUFFER) return false;
        return true;
    }

    /**
     * A routed belt (any side but its front) or linked bridge (any side but its link side) next to the
     * footprint takes its output. Returns that line's output index if it has room for `flow`, else -1.
     */
    int touchesNetwork(int u, int v, int st, double flow){
        for(int i = 0; i < st; i++){
            int[][] adj = {{u + st, v + i, 2}, {u - 1, v + i, 0}, {u + i, v + st, 3}, {u + i, v - 1, 1}}; // {x, y, direction from the tile to the drill}
            for(int[] a : adj){
                int x = a[0], y = a[1];
                if(x < 0 || y < 0 || x >= W || y >= H) continue;
                boolean takes = occ[x][y] >= 0 // its front is never inside a free footprint
                    || (bridgeLink[x][y] >= 0 && bridgeLink[x][y] != a[2]);
                if(takes && fits(sinkOf[x][y], flow, true)) return sinkOf[x][y];
                // an existing output building right next to the drill takes its items directly
                int sk = occ[x][y] == OSINK ? sinkAt(x, y) : -1;
                if(sk >= 0 && p.sinkRects != null && sk < p.sinkRects.length && p.sinkRects[sk] != null && fits(sk, flow, true)) return sk;
            }
        }
        return -1;
    }

    /** a drill directly attached to the network, without logistics of its own */
    void placeAttached(int u, int v, int ty){
        Feeder f = new Feeder();
        f.drills.add(new int[]{u, v, 0, ty});
        f.path = new ArrayList<>();
        f.su = u; f.sv = v;
        fill(u, v, sz[ty], ODRILL);
        feeders.add(f);
    }

    /** All candidate drills (any type) with positive value, best first. {type, u, v} */
    List<int[]> candidates(){
        List<int[]> c = new ArrayList<>();
        List<Double> val = new ArrayList<>();
        for(int ty = 0; ty < T; ty++){
            for(int u = 0; u + sz[ty] <= W - 1; u++) for(int v = 0; v + sz[ty] <= H; v++){
                double dv = drillValue(ty, u, v);
                if(dv > 0){ c.add(new int[]{ty, u, v, c.size()}); val.add(dv); }
            }
        }
        double[] vals = new double[val.size()];
        for(int i = 0; i < vals.length; i++) vals[i] = val.get(i);
        c.sort((a, b) -> Double.compare(vals[b[3]], vals[a[3]]));
        return c;
    }

    /** Adds drills (any type) on free tiles that already touch the network: no logistics needed. */
    int fillGaps(){
        int added = 0;
        for(int[] c : candidates()){
            int ty = c[0], u = c[1], v = c[2];
            if(!footprintFree(u, v, sz[ty])) continue;
            double flow = flowOf(new int[]{u, v, 0, ty});
            int k = touchesNetwork(u, v, sz[ty], flow);
            if(k >= 0){
                placeAttached(u, v, ty);
                feeders.get(feeders.size() - 1).sink = k;
                sinkLoad[k] += flow;
                added++;
            }
        }
        if(added > 0) snapshot("filling gaps", false);
        return added;
    }

    /**
     * Drill-first layout: repeatedly attach every drill that already touches the network, then
     * pick the drill maximising (value - kappa * logistics tiles) with one multi-start search
     * (all candidates' neighbour tiles start at once, offset by their value) and build it
     * together with its connection.
     */
    void drillFirst(){
        // A* cost units (tiles, bridge ~1.5-2.5) -> ore tiles of value
        double kappa = p.mode == Mode.OUTPUT ? 0.12 : 0.15;
        List<int[]> cands = candidates();
        double vmax = 0;
        for(int[] c : cands) vmax = Math.max(vmax, drillValue(c[0], c[1], c[2]));
        Set<Long> bannedStarts = new HashSet<>();
        Set<Integer> bannedEnds = new HashSet<>();
        int guard = 0;
        while(guard++ < 4000){
            checkCancel();
            fillGaps();
            IntSeqLite starts = new IntSeqLite();
            List<Float> costs = new ArrayList<>();
            List<Double> flows = new ArrayList<>();
            List<int[]> owner = new ArrayList<>();
            for(int[] c : cands){
                int ty = c[0], u = c[1], v = c[2], st = sz[ty];
                if(!footprintFree(u, v, st)) continue;
                double val = drillValue(ty, u, v);
                for(int i = 0; i < st; i++){
                    int[][] adj = {{u + st, v + i, 0}, {u - 1, v + i, 2}, {u + i, v + st, 1}, {u + i, v - 1, 3}}; // {x, y, direction away from the drill}
                    for(int[] a : adj){
                        int x = a[0], y = a[1];
                        if(x < 0 || y < 0 || x >= W || y >= H || occ[x][y] != FREE || !p.beltOk[x][y]) continue;
                        if(bannedStarts.contains(key(c[3], x * H + y))) continue;
                        starts.add(sid(x, y, a[2], 0));
                        costs.add((float)((vmax - val) / kappa) + tileCost(x, y));
                        flows.add(flowOf(new int[]{u, v, 0, ty}));
                        owner.add(new int[]{c[3], ty, u, v, x, y});
                    }
                }
            }
            if(starts.size == 0) break;
            float[] sc = new float[costs.size()];
            for(int i = 0; i < sc.length; i++) sc[i] = costs.get(i);
            double[] fl = new double[flows.size()];
            for(int i = 0; i < fl.length; i++) fl[i] = flows.get(i);
            List<int[]> path = routeFrom(starts.toArray(), sc, fl, true, bannedEnds);
            if(path == null){
                // every output line is full: estimate what more outputs would add
                if(capacityOn() && routeFrom(starts.toArray(), sc, fl, false, bannedEnds) != null) excessFlow = remainingFlow(cands);
                break;
            }
            int[] o = owner.get(lastStart);
            int ty = o[1], u = o[2], v = o[3], st = sz[ty];
            double material = 0;
            for(int[] e : path) material += e[2] == BRIDGE ? bridgeCost() : beltCost();
            if(drillValue(ty, u, v) - material <= 0) break; // nothing left that pays for its connection
            boolean overlap = false;
            for(int[] e : path) if(e[0] >= u && e[0] < u + st && e[1] >= v && e[1] < v + st) overlap = true;
            if(overlap){ bannedStarts.add(key(o[0], o[4] * H + o[5])); continue; }
            int dup = dupTile(path);
            if(dup >= 0){ bannedStarts.add(key(o[0], o[4] * H + o[5])); continue; }
            int leak = selfLeak(path);
            if(leak >= 0){ bannedEnds.add(leak); continue; }
            Feeder f = new Feeder();
            f.drills.add(new int[]{u, v, 0, ty});
            f.flow = flowOf(f.drills.get(0));
            f.su = o[4]; f.sv = o[5];
            fill(u, v, st, ODRILL);
            commit(f, path);
            feeders.add(f);
            segmentsRouted++;
            snapshot("drill-first", false);
        }
    }

    /**
     * A* states carry direction / landed-bridge, so a path may pass through the same tile twice;
     * returns such a tile (u*H+v) or -1.
     */
    int dupTile(List<int[]> path){
        Set<Integer> seen = new HashSet<>();
        for(int[] e : path) if(!seen.add(e[0] * H + e[1])) return e[0] * H + e[1];
        return -1;
    }

    /** liquid A* that blocks a tile the path crossed twice and searches again */
    List<int[]> routeLiquidSafe(List<int[]> targets, List<Float> terminal, int x0, int y0, int x1, int y1, int[] junction){
        List<int[]> blocked = new ArrayList<>();
        List<int[]> leg = null;
        for(int attempt = 0; attempt < 6; attempt++){
            leg = routeLiquid(targets, terminal, x0, y0, x1, y1, junction);
            int dup = leg == null ? -1 : dupTile(leg);
            if(dup < 0) break;
            int u = dup / H, v = dup % H;
            if(occ[u][v] != FREE){ leg = null; break; }
            occ[u][v] = RESERVED;
            blocked.add(new int[]{u, v});
            leg = null;
        }
        for(int[] b : blocked) occ[b[0]][b[1]] = FREE;
        return leg;
    }

    /** greedy estimate of the output still placeable (non-overlapping candidates on free tiles) */
    double remainingFlow(List<int[]> cands){
        boolean[][] used = new boolean[W][H];
        double sum = 0;
        for(int[] c : cands){
            int ty = c[0], u = c[1], v = c[2], st = sz[ty];
            if(!footprintFree(u, v, st)) continue;
            boolean clash = false;
            for(int x = u; x < u + st && !clash; x++) for(int y = v; y < v + st; y++) if(used[x][y]){ clash = true; break; }
            if(clash) continue;
            for(int x = u; x < u + st; x++) for(int y = v; y < v + st; y++) used[x][y] = true;
            sum += flowOf(new int[]{u, v, 0, ty});
        }
        return sum;
    }

    /** tiny int list (avoids boxing in the hot loop) */
    static class IntSeqLite{
        int[] items = new int[64];
        int size;
        void add(int v){ if(size == items.length) items = Arrays.copyOf(items, size * 2); items[size++] = v; }
        int[] toArray(){ return Arrays.copyOf(items, size); }
    }

    /**
     * Water for the drill-first layout: port router, then for each drill (largest boost first) a
     * liquid A* from the water network to a free tile next to it, where a router goes.
     */
    void waterByDrill(){
        addExistingWater();
        boolean any = !existingWater.isEmpty();
        for(int[] wp : waterPortList){
            if(waterAt.containsKey(key(wp[0], wp[1])) || occ[wp[0]][wp[1]] != FREE) continue;
            placeWater(new int[]{wp[0], wp[1], LROUTER, 0, wp[0], wp[1]});
            any = true;
        }
        if(!any) return;
        List<int[]> all = new ArrayList<>();
        for(Feeder f : feeders) all.addAll(f.drills);
        all.sort((a, b) -> Double.compare(drillValue(type(b), b[0], b[1]), drillValue(type(a), a[0], a[1])));
        for(int[] d : all){
            checkCancel();
            if(fedWater(d)) continue;
            int st = sz(d);
            List<int[]> targets = new ArrayList<>();
            List<Float> terminal = new ArrayList<>();
            for(int i = 0; i < st; i++){
                int[][] adj = {{d[0] + st, d[1] + i}, {d[0] - 1, d[1] + i}, {d[0] + i, d[1] + st}, {d[0] + i, d[1] - 1}};
                for(int[] a : adj) if(liquidFree(a[0], a[1])){ targets.add(a); terminal.add(0f); }
            }
            if(targets.isEmpty()) continue;
            for(int[] tg : targets) occ[tg[0]][tg[1]] = OWATER;
            int[] junction = new int[2];
            List<int[]> leg = routeLiquidSafe(targets, terminal, d[0] - 1, d[1] - 1, d[0] + st, d[1] + st, junction);
            for(int[] tg : targets) occ[tg[0]][tg[1]] = FREE;
            if(leg == null) continue;
            double gain = drillValue(type(d), d[0], d[1], true) - drillValue(type(d), d[0], d[1], false);
            if(gain <= waterTileCost() * (leg.size() + 1)) continue;
            for(int[] el : leg){
                placeWater(el);
                if(el[2] == LBRIDGE) markLink(el[0], el[1], el[4], el[5]);
            }
            placeWater(new int[]{junction[0], junction[1], LROUTER, 0, junction[0], junction[1]});
            snapshot("water", false);
        }
    }

    /** does a router / bridge-conduit end next to the drill output water toward it */
    boolean fedWater(int[] d){
        int st = sz(d);
        for(int i = 0; i < st; i++){
            if(feeds(key(d[0] + st, d[1] + i), 2) || feeds(key(d[0] - 1, d[1] + i), 0)
                || feeds(key(d[0] + i, d[1] + st), 3) || feeds(key(d[0] + i, d[1] - 1), 1)) return true;
        }
        return false;
    }

    /** wet flags from the water network actually built (any side of the drill) */
    void updateWet(){
        for(Feeder f : feeders) for(int[] d : f.drills){
            if(d.length > 2) d[2] = fedWater(d) ? 1 : 0;
        }
    }

    /** drill records are {u, v, wet, type} */
    int sz(int[] d){ return d.length > 3 ? sz[d[3]] : sz[0]; }

    int type(int[] d){ return d.length > 3 ? d[3] : 0; }

    boolean wet(int[] d){ return d.length > 2 && d[2] == 1; }

    void checkCancel(){
        if(cancelled || (parent != null && parent.cancelled)) throw new CancelledException();
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
        for(int[] e : water) if(e[2] != EXISTING_WATER) out.add(new Place(e[2], e[0], e[1], e[3], 0, e[4], e[5]));
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
            for(int[] d : f.drills){
                Place pl = new Place(DRILL, d[0], d[1], wet(d) ? 1 : 0, ore[type(d)][d[0]][d[1]]);
                pl.type = type(d); pl.size = sz(d);
                out.add(pl);
            }
        }
        return out;
    }

    boolean capacityOn(){ return p.sinkCapacity > 0 && p.itemsPerOre > 0; }

    /** items/s of a drill record */
    double flowOf(int[] d){
        if(p.itemsPerOre <= 0) return 0;
        int ty = type(d);
        // drill-first connects water after the layout: plan line capacity for boosted drills
        return ore[ty][d[0]][d[1]] * rate[ty] * (wet(d) || assumeWet ? p.waterBoost : 1) * p.itemsPerOre;
    }

    boolean assumeWet;

    /** may a route carrying `flow` items/s end in sink k */
    boolean fits(int k, double flow, boolean strict){
        return k >= 0 && (!strict || !capacityOn() || sinkLoad[k] + flow <= p.sinkCapacity + 1e-6);
    }

    /** index of the sink at (x,y) (may be just outside the region), or -1 */
    int sinkAt(int x, int y){
        // the reserved area around an interior output is the player's block: delivering into any of it counts
        for(int k = 0; k < sinkList.size(); k++){
            int[] r = sinkRect(k);
            if(x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return k;
        }
        return -1;
    }

    /** tiles sink k covers: its rect if given (existing building), else the tile +- reserve (interior) */
    int[] sinkRect(int k){
        if(p.sinkRects != null && k < p.sinkRects.length && p.sinkRects[k] != null) return p.sinkRects[k];
        int[] s0 = sinkList.get(k);
        int r = reserveOf(s0);
        return new int[]{s0[0] - r, s0[1] - r, s0[0] + r, s0[1] + r};
    }

    /** only an interior output keeps tiles free around it; an edge output's tile lies outside the region */
    int reserveOf(int[] sink){
        return sink[0] >= 0 && sink[1] >= 0 && sink[0] < W && sink[1] < H ? p.sinkReserve : 0;
    }

    int sinkDistance(int u, int v){
        int best = Integer.MAX_VALUE;
        for(int[] s0 : sinkList) best = Math.min(best, Math.abs(s0[0] - u) + Math.abs(s0[1] - v));
        return best;
    }

    /**
     * Outputs: the given sinks, or the legacy single port past the +u edge. An interior sink (and
     * `sinkReserve` tiles around it) is kept free for the player's own block.
     */
    boolean setupSinks(){
        sinkList = new ArrayList<>();
        if(p.sinks == null){
            port = findPort();
            if(port < 0){ failure = "no buildable tile on the output edge"; return false; }
            sinkList.add(new int[]{W, port});
        }else{
            for(int[] s0 : p.sinks) sinkList.add(new int[]{s0[0], s0[1]});
            port = sinkList.get(0)[1];
        }
        for(int k = 0; k < sinkList.size(); k++){
            int[] r = sinkRect(k);
            for(int x = r[0]; x <= r[2]; x++) for(int y = r[1]; y <= r[3]; y++){
                if(x >= 0 && y >= 0 && x < W && y < H) mask[x][y] = true;
            }
        }
        return true;
    }

    void markSinks(){
        for(int k = 0; k < sinkList.size(); k++){
            int[] r = sinkRect(k);
            for(int x = r[0]; x <= r[2]; x++) for(int y = r[1]; y <= r[3]; y++){
                if(x >= 0 && y >= 0 && x < W && y < H) occ[x][y] = OSINK;
            }
        }
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
        ore = new int[T][W][H];
        int[] counts = new int[4096];
        for(int ty = 0; ty < T; ty++){
            int st = sz[ty];
            int[][] drop = tDrop[ty];
            for(int u = 0; u + st <= W - 1; u++){ // column W-1 is the collector
                for(int v = 0; v + st <= H; v++){
                    if(!tOk[ty][u][v]) continue;
                    for(int x = u; x < u + st; x++) for(int y = v; y < v + st; y++) if(drop[x][y] >= 0) counts[drop[x][y]]++;
                    int mined = -1;
                    for(int x = u; x < u + st; x++) for(int y = v; y < v + st; y++){
                        int d = drop[x][y];
                        if(d >= 0 && (mined < 0 || minedBefore(d, counts[d], mined, counts[mined]))) mined = d;
                    }
                    ore[ty][u][v] = mined == p.target ? counts[mined] : 0;
                    for(int x = u; x < u + st; x++) for(int y = v; y < v + st; y++) if(drop[x][y] >= 0) counts[drop[x][y]] = 0;
                }
            }
        }
    }

    /** Target ore a drill at min corner (u,v) would mine (0 if it would mine something else). */
    public int oreUnder(int u, int v){
        if(ore == null) computeOre();
        return ore[0][u][v];
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

    double drillValue(int u, int v){ return drillValue(0, u, v); }

    /** value of a drill of type ty at min corner (u,v): its output (ore x relative speed), minus costs */
    double drillValue(int ty, int u, int v){
        int st = sz[ty];
        if(u < 0 || v < 0 || v + st > H || u + st > W - 1) return 0;
        int o = ore[ty][u][v];
        if(o == 0 || masked(u, v, st)) return 0;
        // efficiency: a drill must sit mostly on ore to be worth its cost
        double val = (p.mode == Mode.OUTPUT ? o * rate[ty] - 0.01 : o * rate[ty] - 0.5 * st * st) - oreTileValue() * otherOreUnder(u, v, st);
        return val > 0 ? val * noiseAt(u, v) : val;
    }

    /** deterministic per-position multiplier in [0.7, 1.3] for variants, 1 otherwise */
    double noiseAt(int u, int v){
        if(!noise || p.variant == 0) return 1;
        long h = (u * 73856093L) ^ (v * 19349663L) ^ (p.variant * 83492791L) ^ p.seed;
        h ^= (h >>> 13); h *= 0x5bd1e995L; h ^= (h >>> 15);
        return 0.7 + 0.6 * ((h & 0xffff) / 65535.0);
    }

    boolean masked(int u, int v, int size){
        if(mask == null) return false;
        for(int x = u; x < u + size; x++) for(int y = v; y < v + size; y++) if(mask[x][y]) return true;
        return false;
    }

    /** Value of a drill that gets water (if `wet`), or the plain value. */
    double drillValue(int u, int v, boolean wet){ return drillValue(0, u, v, wet); }

    double drillValue(int ty, int u, int v, boolean wet){
        double base = drillValue(ty, u, v);
        if(!wet || base <= 0) return base;
        int o = ore[ty][u][v], st = sz[ty];
        double val = (p.mode == Mode.OUTPUT ? o * rate[ty] * p.waterBoost - 0.01 : o * rate[ty] * p.waterBoost - 0.5 * st * st - 0.5)
            - oreTileValue() * otherOreUnder(u, v, st);
        return val * noiseAt(u, v);
    }

    double drillValue(int[] d){ return drillValue(type(d), d[0], d[1], wet(d)); }

    /** Water row tile can carry a conduit/router. */
    boolean waterTileOk(int x, int r){
        return r >= 0 && r < H && x >= 0 && x < W - 1 && p.beltOk[x][r] && !mask[x][r] && !approach(x, r);
    }

    /** Lane belt tile usable by the layout. */
    boolean laneOk(int u, int v){
        return p.beltOk[u][v] && !mask[u][v] && !approach(u, v);
    }

    /** next to an existing output building: only a routed belt pointing into it may stand there */
    boolean approach(int u, int v){
        return p.approachOnly != null && p.approachOnly[u][v];
    }

    /**
     * Routing costs are in occupied tiles: a conveyor step occupies one tile, a bridge jump of any
     * length occupies one tile (the landing) plus a material premium.
     */
    float jumpCost(){ return p.mode == Mode.OUTPUT ? 1.5f : 2.5f; }

    float liquidJumpCost(){ return p.mode == Mode.OUTPUT ? 1.3f : 2.5f; }

    /** lanes / water rows are split where drills are further apart than this (tiles) */
    static final int splitGap = 2;

    /*
     * Material prices in "ore tiles of output". Logistics are built once while drills keep producing,
     * so in efficiency mode they weigh 1/5 of the raw material ratio (a bridge ~12 conveyors):
     * otherwise remote ore patches in a large region never pay for their connection and plans with
     * negative scores win. The drill-on-ore threshold (0.5 * size^2) is what efficiency means.
     */
    double beltCost(){ return p.mode == Mode.OUTPUT ? 0.01 : 0.05; }

    double waterTileCost(){ return p.mode == Mode.OUTPUT ? 0.02 : 0.06; }

    double nodeCost(){ return p.mode == Mode.OUTPUT ? 0.05 : 0.2; }

    /** A bridge costs ~12x a conveyor in materials. */
    double bridgeCost(){ return p.mode == Mode.OUTPUT ? 0.05 : 0.4; }

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
            for(Feeder g : segment(v, a, u - 1, sides, wetTop, wetBot)){ l.segs.add(g); l.value += g.value; }
        }
        return memo[v][k] = l;
    }

    /**
     * Best drills beside a straight belt run [a,b] in row v, via 1D DP per side; the drills are
     * then split into clusters wherever they are more than `splitGap` tiles apart, and each
     * cluster gets its own short belt (routing connects the clusters, e.g. with bridges).
     */
    List<Feeder> segment(int v, int a, int b, int sides, boolean wetTop, boolean wetBot){
        List<int[]> chosen = new ArrayList<>();
        int n = b - a + 1;
        for(int side = 0; side < 2; side++){
            if((sides & (1 << side)) == 0) continue;
            boolean wetSide = side == 0 ? wetTop : wetBot;
            int wRow = side == 0 ? v + s + 1 : v - s - 1;
            double[] dp = new double[n + 1];
            int[] take = new int[n + 1]; // drill type placed at column a+i, or -1
            // dp[i] = best using columns a+i..b; any drill type, flush against the lane
            for(int i = n - 1; i >= 0; i--){
                dp[i] = dp[i + 1]; take[i] = -1;
                for(int ty = 0; ty < T; ty++){
                    int st = sz[ty];
                    if(i + st > n) continue;
                    int dv = side == 0 ? v + 1 : v - st;
                    // only a full-height drill reaches the water row on the far side
                    boolean wet = wetSide && st == s && wettable(a + i, st, wRow);
                    double val = drillValue(ty, a + i, dv, wet);
                    if(val > 0 && val + dp[i + st] > dp[i]){ dp[i] = val + dp[i + st]; take[i] = ty; }
                }
            }
            for(int i = 0; i < n; ){
                if(take[i] >= 0){
                    int ty = take[i], st = sz[ty], u = a + i;
                    int dv = side == 0 ? v + 1 : v - st;
                    boolean wet = wetSide && st == s && wettable(u, st, wRow);
                    chosen.add(new int[]{u, dv, wet ? 1 : 0, ty});
                    i += st;
                }else i++;
            }
        }
        List<Feeder> out = new ArrayList<>();
        if(chosen.isEmpty()) return out;
        chosen.sort(Comparator.comparingInt(d -> d[0]));
        Feeder g = null;
        int end = Integer.MIN_VALUE;
        double gflow = 0;
        for(int[] d : chosen){
            double df = flowOf(d);
            // split for capacity only where the two belts won't overlap
            boolean full = capacityOn() && g != null && gflow + df > p.sinkCapacity && d[0] > end;
            if(g == null || d[0] > end + splitGap || full){
                if(g != null) closeSegment(g, v, out);
                g = new Feeder();
                g.su = d[0]; // temporarily: cluster start
                gflow = 0;
            }
            gflow += df;
            g.drills.add(d);
            g.value += drillValue(d);
            end = Math.max(end, d[0] + sz(d) - 1);
            g.sv = end; // temporarily: cluster end
        }
        closeSegment(g, v, out);
        return out;
    }

    /** turn a cluster (su..sv temporarily hold its column span) into a lane feeder */
    void closeSegment(Feeder g, int v, List<Feeder> out){
        int minU = g.su, maxU = g.sv;
        for(int u = minU; u < maxU; u++) g.belts.add(new int[]{u, v});
        g.su = maxU; g.sv = v; g.landed = false;
        // belt span, plus roughly what connecting it costs, plus other ore the belt covers
        g.value -= beltCost() * (maxU - minU + 1 + 2);
        for(int u = minU; u <= maxU; u++) if(otherOre[u][v]) g.value -= oreTileValue();
        if(g.value > 0) out.add(g);
    }

    /** a drill at columns u..u+st-1 can get a router in water row r */
    boolean wettable(int u, int st, int r){
        for(int x = u; x < u + st; x++) if(waterTileOk(x, r)) return true;
        return false;
    }

    /**
     * Row DP with water rows. State: whether the row just below is a water row. Structures:
     * skip, W, [D][I], [I][D](+W), [D][I][D](+W). A drill band is watered when a water row runs
     * along its outer side; watered drills are valued at the boosted rate.
     */
    double chooseLanesWater(){
        Lane[][] memo = new Lane[H][12];
        double rowCost = waterTileCost() * W;
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
        waterPortList = new ArrayList<>();
        existingWater.clear();
        if(p.waterSources != null){
            for(int[] ws : p.waterSources){
                if(ws[0] < 0 || ws[1] < 0 || ws[0] >= W || ws[1] >= H) continue;
                existingWater.add(new int[]{ws[0], ws[1], EXISTING_WATER, ws[2], ws[0], ws[1]});
                mask[ws[0]][ws[1]] = true;
            }
            if(p.waterPorts == null && !existingWater.isEmpty()){
                waterU = existingWater.get(0)[0]; waterV = existingWater.get(0)[1];
                return true;
            }
        }
        if(p.waterPorts != null){
            // explicit ports (edge or interior); the corridor is reserved only for an edge port
            for(int[] wp : p.waterPorts){
                if(wp[0] < 0 || wp[1] < 0 || wp[0] >= W - 1 || wp[1] >= H || !p.beltOk[wp[0]][wp[1]]) continue;
                waterPortList.add(new int[]{wp[0], wp[1]});
                mask[wp[0]][wp[1]] = true;
            }
            if(waterPortList.isEmpty()) return false;
            waterU = waterPortList.get(0)[0];
            waterV = waterPortList.get(0)[1];
            int side = waterU == 0 ? 2 : waterV == H - 1 ? 1 : waterV == 0 ? 3 : 0;
            if(side != 0){
                for(int v = 0; v < H; v++) mask[0][v] = true;
                if(side != 2) for(int u = 0; u <= waterU; u++) mask[u][waterV] = true;
            }
            return true;
        }

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
        waterPortList.add(new int[]{waterU, waterV});
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
    void addExistingWater(){
        for(int[] e : existingWater){
            if(waterAt.containsKey(key(e[0], e[1]))) continue;
            water.add(e); waterAt.put(key(e[0], e[1]), e); occ[e[0]][e[1]] = OWATER;
        }
    }

    void buildWater(){
        water.clear();
        waterAt.clear();
        addExistingWater();
        underLink = new boolean[W][H];
        List<int[]> wetList = new ArrayList<>();
        for(Feeder f : feeders) for(int[] d : f.drills) if(wet(d)) wetList.add(d);

        // routers per water row: fewest points stabbing every adjacent wet drill's columns
        TreeMap<Integer, TreeSet<Integer>> routers = new TreeMap<>();
        for(int r : waterRows){
            List<int[]> iv = new ArrayList<>();
            for(int[] d : wetList) if(d[1] == r + 1 || d[1] + sz(d) == r) iv.add(d);
            TreeSet<Integer> xs = new TreeSet<>();
            iv.sort(Comparator.comparingInt(d -> d[0] + sz(d) - 1));
            for(int[] d : iv){
                Integer hit = xs.ceiling(d[0]);
                if(hit != null && hit <= d[0] + sz(d) - 1) continue;
                for(int c = d[0] + sz(d) - 1; c >= d[0]; c--) if(waterTileOk(c, r)){ xs.add(c); break; }
            }
            if(!xs.isEmpty()) routers.put(r, xs);
        }

        if(!routers.isEmpty()){
            addExistingWater();
            for(int[] wp : waterPortList){
                if(occ[wp[0]][wp[1]] != FREE) continue;
                int[] port = {wp[0], wp[1], LROUTER, 0, wp[0], wp[1]};
                water.add(port); waterAt.put(key(wp[0], wp[1]), port);
                occ[wp[0]][wp[1]] = OWATER;
            }
            connectWaterRows(routers);
        }

        // a drill stays wet only if a router or bridge-conduit end sits next to it
        for(Feeder f : feeders) for(int[] d : f.drills){
            if(!wet(d)) continue;
            boolean ok = false;
            for(int x = d[0]; x < d[0] + sz(d) && !ok; x++){
                ok = feeds(key(x, d[1] - 1), 1) || feeds(key(x, d[1] + sz(d)), 3);
            }
            if(!ok) d[2] = 0;
        }
        for(int[] el : water) occ[el[0]][el[1]] = OWATER;
    }

    /**
     * Connects every water row router. A row is split into runs of contiguous buildable tiles;
     * each run gets its own connection: a liquid A* from any element that already has water to any
     * tile of the run (so it can go around walls the row itself can't cross). That tile becomes the
     * junction router and conduits run from it both ways, only as far as the outermost router.
     * Runs that can't be reached yet are retried after other runs have added more water sources.
     */
    void connectWaterRows(TreeMap<Integer, TreeSet<Integer>> routers){
        List<int[]> runs = new ArrayList<>(); // {row, a, b}
        for(Map.Entry<Integer, TreeSet<Integer>> en : routers.entrySet()){
            int r = en.getKey();
            TreeSet<Integer> left = new TreeSet<>(en.getValue());
            while(!left.isEmpty()){
                // contiguous buildable stretch around the first router, then split it into
                // clusters of routers no more than splitGap+1 apart; each cluster is connected on its own
                int x = left.first(), a = x, b = x;
                while(a - 1 >= 0 && waterPlaceable(a - 1, r)) a--;
                while(b + 1 <= W - 2 && waterPlaceable(b + 1, r)) b++;
                List<Integer> in = new ArrayList<>(left.subSet(a, true, b, true));
                int lo = in.get(0), prev = lo;
                for(int i = 1; i <= in.size(); i++){
                    if(i == in.size() || in.get(i) - prev > splitGap + 1){
                        runs.add(new int[]{r, lo, prev});
                        if(i < in.size()) lo = in.get(i);
                    }
                    if(i < in.size()) prev = in.get(i);
                }
                left.removeAll(in);
            }
        }
        runs.sort(Comparator.comparingInt(rn -> Math.abs(rn[0] - waterV) + Math.abs(rn[1] - waterU)));
        // keep every run's router span off-limits to other runs' connections
        for(int[] rn : runs) reserveSpan(routers, rn, true);
        boolean progress = true;
        while(progress && !runs.isEmpty()){
            progress = false;
            for(Iterator<int[]> it = runs.iterator(); it.hasNext(); ){
                checkCancel();
                int[] rn = it.next();
                int r = rn[0];
                TreeSet<Integer> rs = new TreeSet<>(routers.get(r).subSet(rn[1], true, rn[2], true));
                int lo = rs.first(), hi = rs.last();
                reserveSpan(routers, rn, false);
                // junction candidates: the span plus free tiles next to it (others' connections may have taken some)
                int a = lo, b = hi;
                while(a - 1 >= 0 && waterPlaceable(a - 1, r)) a--;
                while(b + 1 <= W - 2 && waterPlaceable(b + 1, r)) b++;
                // keep the connection off the run itself; it may only end by feeding one of its tiles
                List<int[]> targets = new ArrayList<>();
                List<Float> terminal = new ArrayList<>();
                for(int x = a; x <= b; x++){
                    occ[x][r] = OWATER;
                    targets.add(new int[]{x, r});
                    terminal.add(0.5f * Math.max(0, Math.max(lo - x, x - hi))); // extra conduits outside the router span
                }
                int[] junction = new int[2];
                List<int[]> leg = routeLiquidSafe(targets, terminal, a, r, b, r, junction);
                for(int x = a; x <= b; x++) occ[x][r] = FREE;
                if(leg == null){ reserveSpan(routers, rn, true); continue; }
                it.remove();
                progress = true;
                for(int[] el : leg){
                    water.add(el); waterAt.put(key(el[0], el[1]), el); occ[el[0]][el[1]] = OWATER;
                    if(el[2] == LBRIDGE) markLink(el[0], el[1], el[4], el[5]);
                }
                int j = junction[0];
                placeWater(new int[]{j, r, LROUTER, 0, j, r});
                for(int x = j + 1; x <= hi; x++) placeWater(rs.contains(x) ? new int[]{x, r, LROUTER, 0, x, r} : new int[]{x, r, CONDUIT, 0, x, r});
                for(int x = j - 1; x >= lo; x--) placeWater(rs.contains(x) ? new int[]{x, r, LROUTER, 0, x, r} : new int[]{x, r, CONDUIT, 2, x, r});
                snapshot("water rows", false);
            }
        }
        for(int[] rn : runs) reserveSpan(routers, rn, false); // unreachable runs
    }

    void reserveSpan(TreeMap<Integer, TreeSet<Integer>> routers, int[] rn, boolean on){
        NavigableSet<Integer> rs = routers.get(rn[0]).subSet(rn[1], true, rn[2], true);
        for(int x = rs.first(); x <= rs.last(); x++){
            if(on && occ[x][rn[0]] == FREE) occ[x][rn[0]] = RESERVED;
            else if(!on && occ[x][rn[0]] == RESERVED) occ[x][rn[0]] = FREE;
        }
    }

    void placeWater(int[] el){
        water.add(el);
        waterAt.put(key(el[0], el[1]), el);
        occ[el[0]][el[1]] = OWATER;
    }

    /**
     * Multi-target liquid A*: starts next to any element that outputs water in that direction;
     * moves are conduit steps or bridge-conduit jumps over anything; finishes by feeding one of
     * `targets` (a conduit pointing at it, or a bridge end beside it), paying that target's
     * terminal cost. The targets lie in row r, columns a..b (used for the heuristic). Writes the
     * chosen target to `junction`; returns the elements to place (empty if water is already
     * adjacent), or null.
     */
    List<int[]> routeLiquid(List<int[]> targets, List<Float> terminal, int bx0, int by0, int bx1, int by1, int[] junction){
        int T = targets.size();
        Map<Long, Integer> tIndex = new HashMap<>();
        for(int k = 0; k < T; k++) tIndex.put(key(targets.get(k)[0], targets.get(k)[1]), k);
        int N = W * H * 8;
        float[] cost = new float[N + T];
        Arrays.fill(cost, Float.MAX_VALUE);
        int[] prev = new int[N + T];
        int[] goalDir = new int[T];
        PriorityQueue<float[]> open = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0]));
        int range = p.liquidBridgeRange;
        // water already next to a target: no connection needed
        for(int k = 0; k < T; k++){
            int[] tg = targets.get(k);
            for(int d = 0; d < 4; d++){
                if(feeds(key(tg[0] + DU[d], tg[1] + DV[d]), (d + 2) % 4) && terminal.get(k) < cost[N + k]){
                    cost[N + k] = terminal.get(k); prev[N + k] = -2;
                    open.add(new float[]{cost[N + k], N + k});
                }
            }
        }
        long[] srcOf = new long[N];
        for(int[] e : water){
            for(int d = 0; d < 4; d++){
                int nu = e[0] + DU[d], nv = e[1] + DV[d];
                // a conduit can become a branch point by turning it into a router (small premium)
                boolean branch = e[2] == CONDUIT && d != (e[3] + 2) % 4;
                if(!(feeds(key(e[0], e[1]), d) || branch) || !liquidFree(nu, nv)) continue;
                int id = sid(nu, nv, d, 0);
                float c = stepCost(nu, nv) + (branch ? 0.6f : 0f);
                if(c < cost[id]){ cost[id] = c; prev[id] = -1; srcOf[id] = key(e[0], e[1]); open.add(new float[]{c + hBox(nu, nv, bx0, by0, bx1, by1), id}); }
            }
        }
        while(!open.isEmpty()){
            float[] cur = open.poll();
            int id = (int)cur[1];
            if(id >= N){
                int k = id - N;
                if(cur[0] > cost[id] + 1e-3f) continue;
                junction[0] = targets.get(k)[0]; junction[1] = targets.get(k)[1];
                if(prev[id] == -2) return new ArrayList<>();
                // the leg may branch off a conduit: that conduit becomes a router
                int first = prev[id];
                while(prev[first] != -1) first = prev[first];
                int[] src = waterAt.get(srcOf[first]);
                if(src != null && src[2] == CONDUIT) src[2] = LROUTER;
                return liquidPath(prev, prev[id], goalDir[k]);
            }
            int L = id & 1, d = (id >> 1) & 3, t = id >> 3, u = t / H, v = t % H;
            float c = cost[id];
            if(cur[0] - hBox(u, v, bx0, by0, bx1, by1) > c + 1e-3f) continue; // stale
            for(int nd = 0; nd < 4; nd++){
                if(nd == (d + 2) % 4) continue;
                float turn = nd != d ? 0.2f : 0f;
                int nu = u + DU[nd], nv = v + DV[nd];
                Integer k = tIndex.get(key(nu, nv));
                if(k != null){
                    // feed the run here (conduit pointing at it, or a bridge end dumping into it)
                    float gc = c + terminal.get(k);
                    if(gc < cost[N + k]){ cost[N + k] = gc; prev[N + k] = id; goalDir[k] = nd; open.add(new float[]{gc, N + k}); }
                    continue;
                }
                if(liquidFree(nu, nv)) relax(open, cost, prev, id, sid(nu, nv, nd, 0), c + stepCost(nu, nv) + turn, hBox(nu, nv, bx0, by0, bx1, by1));
                for(int jump = 2; jump <= range; jump++){
                    int ju = u + DU[nd] * jump, jv = v + DV[nd] * jump;
                    if(ju < 0 || jv < 0 || ju >= W || jv >= H) break;
                    if(!liquidFree(ju, jv)) continue;
                    relax(open, cost, prev, id, sid(ju, jv, nd, 1), c + liquidJumpCost() - 1f + stepCost(ju, jv) + 0.05f * jump + turn, hBox(ju, jv, bx0, by0, bx1, by1));
                }
            }
        }
        return null;
    }

    /** admissible: a bridge-conduit jump along a corridor costs about 0.16 per tile */
    float hBox(int u, int v, int x0, int y0, int x1, int y1){
        return 0.15f * (Math.max(0, Math.max(x0 - u, u - x1)) + Math.max(0, Math.max(y0 - v, v - y1)));
    }

    boolean liquidFree(int u, int v){
        // column W-1 stays free for the item collector
        return u >= 0 && v >= 0 && u < W - 1 && v < H && p.beltOk[u][v] && occ[u][v] == FREE && !approach(u, v);
    }

    /** the reserved corridor is cheaper, so legs follow it unless it's blocked */
    float stepCost(int u, int v){ return Math.min(mask[u][v] ? 0.5f : 1f, underLink != null && underLink[u][v] ? 0.35f : 1f) + (otherOre[u][v] ? oreRoutePenalty : 0f); }

    void relax(PriorityQueue<float[]> open, float[] cost, int[] prev, int from, int to, float nc, float h){
        if(nc < cost[to]){
            cost[to] = nc; prev[to] = from;
            open.add(new float[]{nc + h, to});
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
        if(e[2] == EXISTING_WATER) return ((e[3] >> dir) & 1) != 0;
        return e[2] == LBRIDGE && e[4] == e[0] && e[5] == e[1] && dir != (e[3] + 2) % 4;
    }

    boolean waterPlaceable(int u, int v){
        return u >= 0 && v >= 0 && u < W - 1 && v < H && p.beltOk[u][v] && occ[u][v] == FREE && !approach(u, v);
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
                    if(laneOk(u, v)){
                        if(cur == null){ cur = new ArrayList<>(); chains.add(cur); }
                        chain[u][v] = chains.size() - 1;
                        index[u][v] = cur.size();
                        cur.add(new int[]{u, v});
                    }else cur = null;
                }
            }
            for(int ty = 0; ty < T; ty++){
                int st = sz[ty];
                for(int u = 0; u + st <= W - 1; u++){
                    for(int v = 0; v + st <= H; v++){
                        double val = drillValue(ty, u, v);
                        if(val <= 0 || coversBridge(u, v, st)) continue;
                        int[] at = attach(u, v, st);
                        if(at == null) continue;
                        cands.add(new int[]{u, v, at[0], at[1], ty});
                        values.add(val);
                    }
                }
            }
        }

        boolean coversBridge(int u, int v, int st){
            for(int x = u; x < u + st; x++) for(int y = v; y < v + st; y++) if(chain[x][y] >= 0) return true;
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
        int[] attach(int u, int v, int s){
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
                if(overlaps(used, c)) continue;
                set(used, c, true);
                sel[i] = true;
            }
            return sel;
        }

        boolean overlaps(boolean[][] used, int[] c){
            int st = sz[c[4]];
            for(int x = c[0]; x < c[0] + st; x++) for(int y = c[1]; y < c[1] + st; y++) if(used[x][y]) return true;
            return false;
        }

        void set(boolean[][] used, int[] c, boolean b){
            int st = sz[c[4]];
            for(int x = c[0]; x < c[0] + st; x++) for(int y = c[1]; y < c[1] + st; y++) used[x][y] = b;
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
                drillsOf.get(c[2]).add(new int[]{c[0], c[1], 0, c[4]});
                val[c[2]] += values.get(i);
                lo[c[2]] = Math.min(lo[c[2]], c[3]);
                hi[c[2]] = Math.max(hi[c[2]], c[3]);
            }
            List<Feeder> out = new ArrayList<>();
            for(int c = 0; c < nc; c++){
                if(hi[c] < 0) continue;
                List<int[]> ch = chains.get(c);
                // drills of this chain by attachment index; a chain that would carry more than one
                // output line is cut into consecutive sub-chains (they must not share a bridge)
                List<int[]> ds = new ArrayList<>(); // {drillIndexInCands}
                for(int i = 0; i < sel.length; i++) if(sel[i] && cands.get(i)[2] == c) ds.add(new int[]{i});
                ds.sort(Comparator.comparingInt(x -> cands.get(x[0])[3]));
                int gLo = -1, gHi = -1;
                double gFlow = 0, gVal = 0;
                List<int[]> gDrills = new ArrayList<>();
                for(int j = 0; j <= ds.size(); j++){
                    int[] cd = j < ds.size() ? cands.get(ds.get(j)[0]) : null;
                    double df = cd == null ? 0 : flowOf(new int[]{cd[0], cd[1], 0, cd[4]});
                    boolean cut = cd == null || (gLo >= 0 && capacityOn() && gFlow + df > p.sinkCapacity && cd[3] > gHi + 1);
                    if(cut && gLo >= 0){
                        int end = gHi + 1; // one bridge past the last attachment: end bridges don't accept from drills
                        Feeder f = new Feeder();
                        f.drills.addAll(gDrills);
                        for(int i = gLo; i < end; i++) f.bridges.add(ch.get(i));
                        f.su = ch.get(end)[0]; f.sv = ch.get(end)[1]; f.landed = true;
                        f.value = gVal - bridgeCost() * (end - gLo + 1);
                        for(int i = gLo; i <= end; i++) if(otherOre[ch.get(i)[0]][ch.get(i)[1]]) f.value -= oreTileValue();
                        if(f.value > 0) out.add(f);
                        gLo = -1; gFlow = 0; gVal = 0; gDrills = new ArrayList<>();
                    }
                    if(cd == null) break;
                    if(gLo < 0) gLo = cd[3];
                    gHi = Math.max(gHi < 0 || gDrills.isEmpty() ? cd[3] : gHi, cd[3]);
                    gFlow += df;
                    gVal += values.get(ds.get(j)[0]);
                    gDrills.add(new int[]{cd[0], cd[1], 0, cd[4]});
                }
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
            for(int i = 0; i < sel.length; i++) if(sel[i]) set(used, cands.get(i), true);
            for(int it = 0; it < iterations; it++){
                if((it & 31) == 0) checkCancel();
                int wu = rand.nextInt(W - K), wv = rand.nextInt(H - K + 1);
                // remove selected drills fully inside the window
                double removed = 0;
                List<Integer> out = new ArrayList<>();
                for(int i = 0; i < sel.length; i++){
                    int[] c = cands.get(i);
                    if(sel[i] && inside(c, wu, wv, K)){ sel[i] = false; set(used, c, false); removed += values.get(i); out.add(i); }
                }
                List<Integer> local = new ArrayList<>();
                for(int i = 0; i < sel.length; i++){
                    int[] c = cands.get(i);
                    if(inside(c, wu, wv, K) && !overlaps(used, c)) local.add(i);
                }
                List<Integer> best = new ArrayList<>();
                double[] bestVal = {-1};
                double[] suffix = new double[local.size() + 1];
                for(int i = local.size() - 1; i >= 0; i--) suffix[i] = suffix[i + 1] + values.get(local.get(i));
                dfs(local, 0, used, new ArrayList<>(), 0, best, bestVal, suffix);
                if(bestVal[0] >= removed){
                    for(int i : best){ sel[i] = true; set(used, cands.get(i), true); }
                }else{
                    for(int i : out){ sel[i] = true; set(used, cands.get(i), true); }
                }
            }
        }

        boolean inside(int[] c, int wu, int wv, int K){
            int st = sz[c[4]];
            return c[0] >= wu && c[1] >= wv && c[0] + st <= wu + K && c[1] + st <= wv + K;
        }

        void dfs(List<Integer> local, int i, boolean[][] used, List<Integer> cur, double val, List<Integer> best, double[] bestVal, double[] suffix){
            if(val > bestVal[0]){ bestVal[0] = val; best.clear(); best.addAll(cur); }
            if(i >= local.size() || val + suffix[i] <= bestVal[0]) return;
            int idx = local.get(i);
            int[] c = cands.get(idx);
            if(!overlaps(used, c)){
                set(used, c, true);
                cur.add(idx);
                dfs(local, i + 1, used, cur, val + values.get(idx), best, bestVal, suffix);
                cur.remove(cur.size() - 1);
                set(used, c, false);
            }
            dfs(local, i + 1, used, cur, val, best, bestVal, suffix);
        }
    }

    void mark(Feeder f, boolean on){
        for(int[] d : f.drills) fill(d[0], d[1], sz(d), on ? ODRILL : FREE);
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
        int k = lastSink;
        f.sink = k;
        if(k >= 0){
            sinkLoad[k] += f.flow;
            for(int[] b : f.belts) sinkOf[b[0]][b[1]] = k;
            for(int[] b : f.bridges) sinkOf[b[0]][b[1]] = k;
            for(int[] e : path) sinkOf[e[0]][e[1]] = k;
        }
        for(int[] b : f.belts) occ[b[0]][b[1]] = 0;
        // routed linked bridges become merge targets (chain bridges link to the next one, the last to the start tile)
        for(int i = 0; i < f.bridges.size(); i++){
            int[] b = f.bridges.get(i), n = i + 1 < f.bridges.size() ? f.bridges.get(i + 1) : new int[]{f.su, f.sv};
            bridgeLink[b[0]][b[1]] = dirOf(b, n);
        }
        for(int[] e : path){
            if(e[2] == BRIDGE && !(e[4] == e[0] && e[5] == e[1])){
                bridgeLink[e[0]][e[1]] = dirOf(e, new int[]{e[4], e[5]});
                markLink(e[0], e[1], e[4], e[5]);
            }
        }
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

    List<int[]> routeWithRetry(Feeder f, boolean strict){
        Set<Integer> banned = new HashSet<>();
        List<int[]> blocked = new ArrayList<>();
        List<int[]> result = null;
        for(int attempt = 0; attempt < 8; attempt++){
            List<int[]> path = route(f, strict, banned);
            if(path == null) break;
            int dup = dupTile(path);
            if(dup >= 0){
                int u = dup / H, v = dup % H;
                if(occ[u][v] != FREE || (u == f.su && v == f.sv)) break;
                occ[u][v] = RESERVED; blocked.add(new int[]{u, v});
                continue;
            }
            int bad = selfLeak(path);
            if(bad < 0){ result = path; break; }
            banned.add(bad);
        }
        for(int[] b : blocked) occ[b[0]][b[1]] = FREE;
        return result;
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

    /** mark the tiles strictly between a bridge and its link target */
    void markLink(int u, int v, int lu, int lv){
        if(underLink == null || (u == lu && v == lv)) return;
        int d = dirOf(new int[]{u, v}, new int[]{lu, lv});
        for(int x = u + DU[d], y = v + DV[d]; !(x == lu && y == lv); x += DU[d], y += DV[d]) underLink[x][y] = true;
    }

    /**
     * Area cost of putting something on (u,v): a tile under an existing bridge link is part of a
     * corridor already in use, so bridges of the other kind can interleave there cheaply.
     */
    float tileCost(int u, int v){
        return (underLink != null && underLink[u][v] ? 0.35f : 1f) + (otherOre[u][v] ? oreRoutePenalty : 0f);
    }

    /** extra routing cost (tiles) for covering another ore: such tiles are kept for later mining */
    static final float oreRoutePenalty = 1.2f;

    /** value lost per tile of other ore covered by a drill / belt / bridge (ore tiles of output) */
    double oreTileValue(){ return p.mode == Mode.OUTPUT ? 0.3 : 0.4; }

    int otherOreUnder(int u, int v, int st){
        int n = 0;
        for(int x = u; x < u + st; x++) for(int y = v; y < v + st; y++) if(otherOre[x][y]) n++;
        return n;
    }

    /** free side tiles an end bridge at (u,v) would reserve as buffer */
    float bufferTiles(int u, int v, int out, int arrival){
        int back = (arrival + 2) % 4, n = 0;
        for(int d = 0; d < 4; d++){
            if(d == out || d == back) continue;
            int x = u + DU[d], y = v + DV[d];
            if(x >= 0 && y >= 0 && x < W && y < H && occ[x][y] == FREE) n++;
        }
        return n;
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
            if(p.foreignAcceptor != null && p.foreignAcceptor[x][y]) return false;
        }
        return true;
    }

    /**
     * A* over (tile, arrival dir, landed). Moves: conveyor step to a neighbour, or a bridge jump of
     * 2..range tiles in a straight line (over anything). Goal: exit through the port (first route)
     * or feed into a routed belt, not head-on. Returns elements {u, v, kind, dir, lu, lv}; for end
     * bridges dir holds the arrival direction.
     */
    List<int[]> route(Feeder f, boolean strict, Set<Integer> bannedEnds){
        return routeFrom(new int[]{sid(f.su, f.sv, 0, f.landed ? 1 : 0)}, new float[]{0f}, new double[]{f.flow}, strict, bannedEnds);
    }

    /** which start the last routeFrom path came from */
    int lastStart;

    /**
     * Multi-start item A*: every start state with its own initial cost and flow (items/s).
     * Goals: deliver into a sink, merge into a routed belt (not head-on), feed a routed linked bridge
     * from a side other than its link, or link a new bridge into one. With `strict`, only output
     * lines with capacity left for the route's flow count as goals.
     */
    List<int[]> routeFrom(int[] starts, float[] startCosts, double[] startFlows, boolean strict, Set<Integer> bannedEnds){
        int N = W * H * 8;
        float[] cost = new float[N];
        Arrays.fill(cost, Float.MAX_VALUE);
        int[] prev = new int[N];
        int[] origin = new int[N];
        curOrigin = origin;
        anyTree = false;
        for(double l : sinkLoad) if(l > 0) anyTree = true;
        if(!anyTree && sinkOf != null) outer: for(int[] row : sinkOf) for(int k : row) if(k >= 0){ anyTree = true; break outer; }
        PriorityQueue<float[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        for(int i = 0; i < starts.length; i++){
            int st = starts[i];
            if(startCosts[i] < cost[st]){
                cost[st] = startCosts[i]; prev[st] = -1; origin[st] = i;
                int t0 = st >> 3;
                open.add(new float[]{startCosts[i] + h(t0 / H, t0 % H), st});
            }
        }
        int range = p.bridges ? p.bridgeRange : 0;

        while(!open.isEmpty()){
            float[] cur = open.poll();
            int id = (int)cur[1];
            int L = id & 1, d = (id >> 1) & 3, t = id >> 3, u = t / H, v = t % H;
            float c = cost[id];
            if(cur[0] - h(u, v) > c + 1e-3f) continue; // stale
            double flow = startFlows[origin[id]];
            boolean approach = p.approachOnly != null && p.approachOnly[u][v];
            for(int nd = 0; nd < 4; nd++){
                if(nd == (d + 2) % 4) continue;
                // next to an existing output building: only a belt pointing into it may stand here
                if(approach && (L == 1 || sinkAt(u + DU[nd], v + DV[nd]) < 0)) continue;
                float turn = nd != d ? turnCost : 0f;
                // conveyor step (from a landed bridge this makes it an end bridge)
                int nu = u + DU[nd], nv = v + DV[nd];
                boolean endOk = L == 0 || (!bannedEnds.contains(t) && dumpSafe(u, v, nd, d));
                // an end bridge reserves its free side tiles as a buffer: charge for them
                float endCost = L == 1 ? bufferTiles(u, v, nd, d) : 0f;
                int sk = sinkAt(nu, nv);
                if(endOk && sk >= 0){
                    if(fits(sk, flow, strict)){ lastSink = sk; return path(prev, id, nd); } // deliver into an output
                    continue;
                }
                if(endOk && nu >= 0 && nv >= 0 && nu < W && nv < H){
                    int o = occ[nu][nv];
                    if(o >= 0 && nd != (o + 2) % 4 && fits(sinkOf[nu][nv], flow, strict)){ lastSink = sinkOf[nu][nv]; return path(prev, id, nd); } // merge into routed belt
                    // feed a routed linked bridge from any side but its link side
                    if(bridgeLink[nu][nv] >= 0 && (nd + 2) % 4 != bridgeLink[nu][nv] && fits(sinkOf[nu][nv], flow, strict)){ lastSink = sinkOf[nu][nv]; return path(prev, id, nd); }
                    if(o == FREE && p.beltOk[nu][nv]){
                        push(open, cost, prev, id, sid(nu, nv, nd, 0), c + tileCost(nu, nv) + turn + endCost, nu, nv);
                    }
                }
                // bridge jump
                for(int k = 2; k <= range; k++){
                    int ju = u + DU[nd] * k, jv = v + DV[nd] * k;
                    if(ju < 0 || jv < 0 || ju >= W || jv >= H) break;
                    // link straight into a routed linked bridge (bridges accept any number of incoming links)
                    if(bridgeLink[ju][jv] >= 0 && fits(sinkOf[ju][jv], flow, strict)){
                        lastSink = sinkOf[ju][jv];
                        List<int[]> pth = path(prev, id, nd);
                        int[] last = pth.get(pth.size() - 1);
                        last[2] = BRIDGE; last[3] = nd; last[4] = ju; last[5] = jv;
                        return pth;
                    }
                    if(occ[ju][jv] != FREE || !p.beltOk[ju][jv] || (p.approachOnly != null && p.approachOnly[ju][jv])) continue;
                    push(open, cost, prev, id, sid(ju, jv, nd, 1), c + jumpCost() - 1f + tileCost(ju, jv) + 0.05f * k + turn, ju, jv);
                }
            }
        }
        return null;
    }

    boolean anyTree;

    void push(PriorityQueue<float[]> open, float[] cost, int[] prev, int from, int to, float nc, int u, int v){
        if(nc < cost[to]){
            cost[to] = nc; prev[to] = from;
            if(curOrigin != null) curOrigin[to] = curOrigin[from];
            open.add(new float[]{nc + h(u, v), to});
        }
    }

    int[] curOrigin;

    int sid(int u, int v, int d, int landed){ return ((u * H + v) << 3) | (d << 1) | landed; }

    /** towards the nearest output while nothing is routed yet (afterwards merges can be anywhere) */
    float h(int u, int v){
        return anyTree ? 0f : 0.2f * sinkDistance(u, v);
    }

    /** Walk back from the end state. lastDir: direction leaving the last tile. */
    List<int[]> path(int[] prev, int end, int lastDir){
        if(curOrigin != null) lastStart = curOrigin[end];
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

    boolean nodeFree(int u, int v){
        return (occ[u][v] == FREE || occ[u][v] == BUFFER) && p.nodeOk[u][v];
    }

    /** Places nodes covering every drill (dropping drills no node can reach), see {@link PowerNetwork}. Mutates drillList. */
    List<int[]> placePower(List<int[]> drillList){
        PowerNetwork net = new PowerNetwork(W, H, p.nodeRange, p.nodeMaxLinks, new PowerNetwork.Host(){
            public boolean nodeFree(int u, int v){ return DrillLayoutPlanner.this.nodeFree(u, v); }
            public boolean nodeOk(int u, int v){ return p.nodeOk[u][v]; }
            public double penalty(int u, int v){ return otherOre[u][v] ? 200 : 0; }
            public int droppableAt(int u, int v, List<int[]> consumers){
                if(occ[u][v] != ODRILL) return -1;
                for(int i = 0; i < consumers.size(); i++){
                    int[] d = consumers.get(i);
                    if(u >= d[0] && u < d[0] + sz(d) && v >= d[1] && v < d[1] + sz(d)) return i;
                }
                return -1;
            }
            public int size(int[] d){ return sz(d); }
            public void occupy(int u, int v){ occ[u][v] = ONODE; }
            public void drop(int[] d){
                removeDrill(d);
                fill(d[0], d[1], sz(d), FREE);
            }
            public Object save(){ return copy(occ); }
            public void restore(Object state){ occ = (int[][])state; }
            public void progress(List<int[]> nodes, String stage){ nodeList = nodes; snapshot(stage, false); }
            public void checkCancel(){ DrillLayoutPlanner.this.checkCancel(); }
        });
        List<int[]> nodes = net.place(drillList);
        drillsDroppedForPower += net.dropped;
        powerGroups = net.groups;
        return nodes;
    }

    void removeDrill(int[] d){
        for(Feeder f : feeders) f.drills.removeIf(x -> x == d);
    }

    int[][] copy(int[][] a){
        int[][] b = new int[a.length][];
        for(int i = 0; i < a.length; i++) b[i] = a[i].clone();
        return b;
    }
}
