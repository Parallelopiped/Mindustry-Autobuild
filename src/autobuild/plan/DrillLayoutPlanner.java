package autobuild.plan;

import java.util.*;

/**
 * Pure planner for a drill field, independent of game classes so it can be tested offline.
 *
 * Works in a canonical local frame: u in [0,W), v in [0,H), items leave the region toward +u
 * through the port tile (W-1, portV). Directions: 0=+u, 1=+v, 2=-u, 3=-v.
 *
 * 1. Lanes: horizontal belt rows; drills of size s sit on one or both sides. Which rows get
 *    lanes (and which sides) is chosen by a DP over rows; drills along each lane by a DP over
 *    columns. Column W-1 is reserved as the collector corridor.
 * 2. Routing: each lane segment is connected to the port, or into an already-routed belt, by A*.
 * 3. Power (optional): power nodes on free tiles, greedy set cover over drills, then relay nodes
 *    so all nodes form one laser-linked group.
 */
public class DrillLayoutPlanner{
    public enum Mode{
        /** cover as much ore as possible */
        OUTPUT,
        /** only place drills that are mostly on ore; fewer drills/belts per item mined */
        EFFICIENCY
    }

    public static final int DRILL = 0, BELT = 1, NODE = 2;
    static final int[] DU = {1, 0, -1, 0}, DV = {0, 1, 0, -1};

    public static class Place{
        public final int kind, u, v, dir, ore;
        Place(int kind, int u, int v, int dir, int ore){ this.kind = kind; this.u = u; this.v = v; this.dir = dir; this.ore = ore; }
        @Override public String toString(){ return "DBN".charAt(kind) + "(" + u + "," + v + ")" + (kind == BELT ? ">" + dir : ""); }
    }

    public static class Params{
        public int W, H, drillSize, target, portV;
        /** conveyor / power node placeable at (u,v) */
        public boolean[][] beltOk, nodeOk;
        /** drill footprint with local min corner (u,v) placeable */
        public boolean[][] drillOk;
        /** mineable item id at (u,v) for this drill, or -1 */
        public int[][] drop;
        public Mode mode = Mode.OUTPUT;
        public boolean power;
        /** laser range in tiles, max links per node */
        public float nodeRange = 6f;
        public int nodeMaxLinks = 10;
    }

    // ---- inputs ----
    final Params p;
    final int W, H, s;

    // ---- outputs ----
    public final List<Place> places = new ArrayList<>();
    public int drills, oreTiles, nodes, segmentsRouted, segmentsDropped, drillsDroppedForPower, powerGroups, port = -1;
    public String failure;

    // ---- work state ----
    /** -1 free, -2 drill, -4 node, >=0 routed belt with that direction, -3 unrouted segment belt */
    int[][] occ;
    /** target ore under a drill at min corner (u,v) if it fits and would mine the target, else 0 */
    int[][] ore;

    public DrillLayoutPlanner(Params p){
        this.p = p; this.W = p.W; this.H = p.H; this.s = p.drillSize;
    }

    static class Segment{
        int v, u0, u1; // belt from u0..u1 flowing +u
        final List<int[]> drills = new ArrayList<>(); // min corners
        double value;
    }

    static class Lane{
        final List<Segment> segs = new ArrayList<>();
        double value;
    }

    public boolean plan(){
        if(W < s + 2 || H < s + 1){ failure = "region too small for this drill"; return false; }

        boolean any = false;
        for(boolean[] col : p.beltOk) for(boolean b : col) any |= b;
        if(!any){ failure = "nothing in the region is buildable (walls, deep water or enemy territory)"; return false; }

        port = findPort();
        if(port < 0){ failure = "no buildable tile on the output edge"; return false; }

        computeOre();
        List<Segment> segs = chooseLanes();
        if(segs.isEmpty()){
            failure = p.mode == Mode.EFFICIENCY ? "no drill position with enough target ore (try Max output)" : "no drill position on the target ore";
            return false;
        }

        occ = new int[W][H];
        for(int[] row : occ) Arrays.fill(row, -1);
        for(Segment g : segs) mark(g, true);

        // route segments, closest to the port first
        segs.sort(Comparator.comparingInt(g -> Math.abs(g.v - port) + (W - 1 - g.u1)));
        List<Place> belts = new ArrayList<>();
        List<int[]> drillList = new ArrayList<>();
        boolean portUsed = false;
        for(Segment g : segs){
            List<int[]> path = route(g, !portUsed);
            if(path == null){ mark(g, false); segmentsDropped++; continue; }
            // segment belts flow +u; path tiles carry their own dirs (path includes segment end)
            for(int u = g.u0; u < g.u1; u++){ occ[u][g.v] = 0; belts.add(new Place(BELT, u, g.v, 0, 0)); }
            for(int[] t : path){ occ[t[0]][t[1]] = t[2]; belts.add(new Place(BELT, t[0], t[1], t[2], 0)); }
            if(!portUsed && occ[W - 1][port] >= 0) portUsed = true;
            drillList.addAll(g.drills);
            segmentsRouted++;
        }
        if(segmentsRouted == 0){ failure = "no lane could be routed to the output port"; return false; }

        List<int[]> nodeList = p.power ? placePower(drillList) : Collections.emptyList();

        for(int[] n : nodeList){ places.add(new Place(NODE, n[0], n[1], 0, 0)); nodes++; }
        places.addAll(belts);
        for(int[] d : drillList){
            places.add(new Place(DRILL, d[0], d[1], 0, ore[d[0]][d[1]]));
            drills++;
            oreTiles += ore[d[0]][d[1]];
        }
        return true;
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

    // ------------------------------------------------------------------ layout

    void computeOre(){
        ore = new int[W][H];
        int[] counts = new int[4096];
        for(int u = 0; u + s <= W - 1; u++){ // column W-1 is the collector
            for(int v = 0; v + s <= H; v++){
                if(!p.drillOk[u][v]) continue;
                int t = 0, other = 0;
                for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++){
                    int d = p.drop[x][y];
                    if(d < 0) continue;
                    if(d == p.target) t++;
                    else other = Math.max(other, ++counts[d]);
                }
                for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++) if(p.drop[x][y] >= 0) counts[p.drop[x][y]] = 0;
                // a drill mines whichever ore is most common under it
                ore[u][v] = t > other ? t : 0;
            }
        }
    }

    double drillValue(int u, int v){
        if(u < 0 || v < 0 || v + s > H || u + s > W - 1) return 0;
        int o = ore[u][v];
        if(o == 0) return 0;
        // efficiency: a drill must sit mostly on ore to be worth its cost
        return p.mode == Mode.OUTPUT ? o - 0.01 : o - 0.5 * s * s;
    }

    double beltCost(){ return p.mode == Mode.OUTPUT ? 0.01 : 0.25; }

    /** Row DP: pick lane rows and sides so drill bands never overlap. */
    List<Segment> chooseLanes(){
        Lane[][] memo = new Lane[H][3];
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
        List<Segment> out = new ArrayList<>();
        for(int i = 0; i < H; ){
            switch(choice[i]){
                case 0: i++; break;
                case 1: out.addAll(lane(memo, i, 1).segs); i += s + 1; break;
                case 2: out.addAll(lane(memo, i + s, 2).segs); i += s + 1; break;
                default: out.addAll(lane(memo, i + s, 3).segs); i += 2 * s + 1; break;
            }
        }
        return out;
    }

    /** sides: 1 = top (+v), 2 = bottom, 3 = both */
    Lane lane(Lane[][] memo, int v, int sides){
        if(memo[v][sides - 1] != null) return memo[v][sides - 1];
        Lane l = new Lane();
        int u = 0;
        while(u < W - 1){
            if(!p.beltOk[u][v]){ u++; continue; }
            int a = u;
            while(u < W - 1 && p.beltOk[u][v]) u++;
            Segment g = segment(v, a, u - 1, sides);
            if(g != null){ l.segs.add(g); l.value += g.value; }
        }
        return memo[v][sides - 1] = l;
    }

    /** Best drills beside a straight belt run [a,b] in row v, via 1D DP per side. */
    Segment segment(int v, int a, int b, int sides){
        Segment g = new Segment();
        g.v = v;
        int minU = Integer.MAX_VALUE, maxU = -1;
        int n = b - a + 1;
        for(int side = 0; side < 2; side++){
            if((sides & (1 << side)) == 0) continue;
            int dv = side == 0 ? v + 1 : v - s;
            double[] dp = new double[n + 1];
            boolean[] take = new boolean[n + 1];
            // dp[i] = best using columns a+i..b
            for(int i = n - 1; i >= 0; i--){
                dp[i] = dp[i + 1];
                if(i + s <= n){
                    double val = drillValue(a + i, dv);
                    if(val > 0 && val + dp[i + s] > dp[i]){ dp[i] = val + dp[i + s]; take[i] = true; }
                }
            }
            for(int i = 0; i < n; ){
                if(take[i]){
                    int u = a + i;
                    g.drills.add(new int[]{u, dv});
                    g.value += drillValue(u, dv);
                    minU = Math.min(minU, u); maxU = Math.max(maxU, u + s - 1);
                    i += s;
                }else i++;
            }
        }
        if(g.drills.isEmpty()) return null;
        g.u0 = minU; g.u1 = maxU;
        g.value -= beltCost() * (maxU - minU + 1);
        return g.value > 0 ? g : null;
    }

    void mark(Segment g, boolean on){
        for(int[] d : g.drills) fill(d[0], d[1], s, on ? -2 : -1);
        for(int u = g.u0; u <= g.u1; u++) occ[u][g.v] = on ? -3 : -1;
    }

    void fill(int u, int v, int size, int val){
        for(int x = u; x < u + size; x++) for(int y = v; y < v + size; y++) occ[x][y] = val;
    }

    // ------------------------------------------------------------------ routing

    /**
     * A* from the segment's last tile to either the port (first route) or any routed belt it can
     * feed into (not head-on). State = (tile, incoming dir) so turns can be penalised.
     * Returns path tiles {u, v, dir} starting with the segment end tile, or null.
     */
    List<int[]> route(Segment g, boolean toPort){
        int su = g.u1, sv = g.v;
        int N = W * H * 4;
        float[] cost = new float[N];
        Arrays.fill(cost, Float.MAX_VALUE);
        int[] prev = new int[N];
        PriorityQueue<float[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));

        int start = idx(su, sv, 0); // arrived at start moving +u (segment flow)
        cost[start] = 0; prev[start] = -1;
        open.add(new float[]{h(su, sv, toPort), start});

        while(!open.isEmpty()){
            float[] cur = open.poll();
            int id = (int)cur[1];
            int d = id & 3, t = id >> 2, u = t / H, v = t % H;
            float c = cost[id];
            if(cur[0] - h(u, v, toPort) > c + 1e-3f) continue; // stale

            for(int nd = 0; nd < 4; nd++){
                if(nd == (d + 2) % 4) continue;
                int nu = u + DU[nd], nv = v + DV[nd];
                // the port tile emits outward (+u): exit when we stand on it
                if(toPort && u == W - 1 && v == port && nd == 0) return build(prev, id, 0);
                if(nu < 0 || nv < 0 || nu >= W || nv >= H) continue;
                int o = occ[nu][nv];
                if(!toPort && o >= 0 && nd != (o + 2) % 4) return build(prev, id, nd); // merge into routed belt
                if(o != -1 || !p.beltOk[nu][nv]) continue;
                int nid = idx(nu, nv, nd);
                float nc = c + 1f + (nd != d ? 0.4f : 0f);
                if(nc < cost[nid]){
                    cost[nid] = nc; prev[nid] = id;
                    open.add(new float[]{nc + h(nu, nv, toPort), nid});
                }
            }
        }
        return null;
    }

    int idx(int u, int v, int d){ return ((u * H + v) << 2) | d; }

    float h(int u, int v, boolean toPort){
        return toPort ? Math.abs(W - 1 - u) + Math.abs(port - v) : 0f;
    }

    /** Walk back from end state; each tile's dir points to the next tile, the last one uses lastDir. */
    List<int[]> build(int[] prev, int end, int lastDir){
        LinkedList<int[]> out = new LinkedList<>();
        int dir = lastDir;
        for(int id = end; id != -1; id = prev[id]){
            int t = id >> 2;
            out.addFirst(new int[]{t / H, t % H, dir});
            dir = id & 3;
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

    /** Places nodes covering every drill (dropping drills no node can reach). Mutates drillList. */
    List<int[]> placePower(List<int[]> drillList){
        // keep a couple of slots per node free for node-to-node links
        int cap = Math.max(1, p.nodeMaxLinks - 3);
        List<int[]> nodes;
        while(true){
            nodes = new ArrayList<>();
            int[][] save = copy(occ);
            boolean[] covered = new boolean[drillList.size()];
            int left = drillList.size();
            while(left > 0){
                int bu = -1, bv = -1;
                double best = 0;
                for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
                    if(occ[u][v] != -1 || !p.nodeOk[u][v]) continue;
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
                occ[bu][bv] = -4;
                nodes.add(new int[]{bu, bv});
                int taken = 0;
                for(int i = 0; i < drillList.size() && taken < cap; i++){
                    if(!covered[i] && reaches(bu, bv, drillList.get(i)[0], drillList.get(i)[1], s)){ covered[i] = true; taken++; left--; }
                }
            }
            if(left == 0) break;
            // drop one unreachable drill; its tiles may host a node for the others
            occ = save;
            for(int i = 0; i < drillList.size(); i++){
                if(!covered[i]){
                    int[] d = drillList.remove(i);
                    fill(d[0], d[1], s, -1);
                    drillsDroppedForPower++;
                    break;
                }
            }
            if(drillList.isEmpty()) return nodes;
        }
        connectNodes(nodes, drillList);
        return nodes;
    }

    int[][] copy(int[][] a){
        int[][] b = new int[a.length][];
        for(int i = 0; i < a.length; i++) b[i] = a[i].clone();
        return b;
    }

    boolean nodeLink(int[] a, int[] b){ return reaches(a[0], a[1], b[0], b[1], 1); }

    /**
     * Adds relay nodes until all nodes are linked. Dijkstra from group 0 over node positions:
     * each relay costs 1, a relay under a drill costs 100 (that drill gets removed), belts block.
     */
    void connectNodes(List<int[]> nodes, List<int[]> drillList){
        int R = (int)Math.ceil(p.nodeRange);
        while(true){
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
                        int step = occ[x][y] == -1 ? 1 : occ[x][y] == -2 ? 100 : -1;
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
                fill(d[0], d[1], s, -1);
                drillsDroppedForPower++;
            }
            for(int[] r : relays){ occ[r[0]][r[1]] = -4; nodes.add(r); }
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
