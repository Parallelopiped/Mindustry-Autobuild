package autobuild.plan;

import java.util.*;

/**
 * Pure planner for a drill field, independent of game classes so it can be tested offline.
 *
 * Works in a canonical local frame: u in [0,W), v in [0,H), items leave the region toward +u
 * through the port tile (W-1, portV). Directions: 0=+u, 1=+v, 2=-u, 3=-v.
 *
 * Layout: horizontal belt lanes every (2s+1) rows, drills of size s on both sides of each lane.
 * Column W-1 is reserved as the collector corridor. Each lane segment is then routed to the port
 * (or into an already-routed belt) with A*.
 */
public class DrillLayoutPlanner{
    public static final int DRILL = 0, BELT = 1;
    static final int[] DU = {1, 0, -1, 0}, DV = {0, 1, 0, -1};

    public static class Place{
        public final int kind, u, v, dir;
        Place(int kind, int u, int v, int dir){ this.kind = kind; this.u = u; this.v = v; this.dir = dir; }
        @Override public String toString(){ return (kind == DRILL ? "D" : "B") + "(" + u + "," + v + ")" + (kind == BELT ? ">" + dir : ""); }
    }

    // ---- inputs ----
    final int W, H, s, target, minOre;
    final boolean[][] beltOk, drillOk;
    final int[][] drop;
    int portV;

    // ---- outputs ----
    public final List<Place> places = new ArrayList<>();
    public int drills, oreTiles, segmentsRouted, segmentsDropped, phase = -1, port = -1;
    public String failure;

    // ---- work state ----
    /** -1 free, -2 drill, >=0 routed belt with that direction, -3 unrouted segment belt */
    int[][] occ;

    /**
     * @param beltOk  conveyor placeable at (u,v)
     * @param drillOk drill footprint with local min corner (u,v) placeable
     * @param drop    mineable item id at (u,v) for this drill, or -1
     */
    public DrillLayoutPlanner(int W, int H, int drillSize, boolean[][] beltOk, boolean[][] drillOk, int[][] drop, int target, int portV, int minOre){
        this.W = W; this.H = H; this.s = drillSize; this.beltOk = beltOk; this.drillOk = drillOk;
        this.drop = drop; this.target = target; this.portV = portV; this.minOre = minOre;
    }

    static class Segment{
        int v, u0, u1; // belt from u0..u1 flowing +u
        final List<int[]> drills = new ArrayList<>(); // min corners
        int score;
    }

    public boolean plan(){
        if(W < s + 2 || H < 2){ failure = "region too small"; return false; }

        port = findPort();
        if(port < 0){ failure = "no buildable tile on the output edge"; return false; }

        // 1. choose lane phase with best raw ore coverage
        int period = 2 * s + 1;
        List<Segment> best = null; int bestScore = -1;
        for(int p = 0; p < period; p++){
            List<Segment> segs = layout(p);
            int sc = 0; for(Segment g : segs) sc += g.score;
            if(sc > bestScore){ bestScore = sc; best = segs; phase = p; }
        }
        if(best == null || best.isEmpty()){ failure = "no drill position with enough target ore"; return false; }

        // 2. mark occupancy
        occ = new int[W][H];
        for(int[] row : occ) Arrays.fill(row, -1);
        for(Segment g : best) mark(g, true);

        // 3. route segments, closest to the port first
        best.sort(Comparator.comparingInt(g -> Math.abs(g.v - port) + (W - 1 - g.u1)));
        List<Place> belts = new ArrayList<>();
        boolean portUsed = false;
        for(Segment g : best){
            List<int[]> path = route(g, !portUsed);
            if(path == null){ mark(g, false); segmentsDropped++; continue; }
            // segment belts flow +u; path tiles carry their own dirs (path includes segment end)
            for(int u = g.u0; u < g.u1; u++){ occ[u][g.v] = 0; belts.add(new Place(BELT, u, g.v, 0)); }
            for(int[] t : path){ occ[t[0]][t[1]] = t[2]; belts.add(new Place(BELT, t[0], t[1], t[2])); }
            if(!portUsed && occ[W - 1][port] >= 0) portUsed = true;
            for(int[] d : g.drills){
                places.add(new Place(DRILL, d[0], d[1], 0));
                drills++;
                oreTiles += oreCount(d[0], d[1]);
            }
            segmentsRouted++;
        }
        places.addAll(belts);
        if(segmentsRouted == 0){ failure = "no lane could be routed to the output port"; return false; }
        return true;
    }

    int findPort(){
        for(int d = 0; d < H; d++){
            for(int sgn = -1; sgn <= 1; sgn += 2){
                int v = portV + d * sgn;
                if(v >= 0 && v < H && beltOk[W - 1][v]) return v;
            }
        }
        return -1;
    }

    List<Segment> layout(int phase){
        List<Segment> out = new ArrayList<>();
        for(int v = phase; v < H; v += 2 * s + 1){
            int u = 0;
            while(u < W - 1){
                if(!beltOk[u][v]){ u++; continue; }
                int a = u;
                while(u < W - 1 && beltOk[u][v]) u++;
                Segment g = segment(v, a, u - 1);
                if(g != null) out.add(g);
            }
        }
        return out;
    }

    /** Best drills on both sides of a straight belt run [a,b] in row v, via 1D DP per side. */
    Segment segment(int v, int a, int b){
        Segment g = new Segment();
        g.v = v;
        int minU = Integer.MAX_VALUE, maxU = -1;
        for(int side = 0; side < 2; side++){
            int dv = side == 0 ? v + 1 : v - s;
            int n = b - a + 1;
            int[] dp = new int[n + 1];
            boolean[] take = new boolean[n + 1];
            // dp[i] = best using columns a+i..b
            for(int i = n - 1; i >= 0; i--){
                dp[i] = dp[i + 1];
                int u = a + i;
                if(i + s <= n){
                    int sc = drillScore(u, dv);
                    if(sc > 0 && sc + dp[i + s] > dp[i]){ dp[i] = sc + dp[i + s]; take[i] = true; }
                }
            }
            for(int i = 0; i < n; ){
                if(take[i]){
                    int u = a + i;
                    g.drills.add(new int[]{u, dv});
                    g.score += drillScore(u, dv);
                    minU = Math.min(minU, u); maxU = Math.max(maxU, u + s - 1);
                    i += s;
                }else i++;
            }
        }
        if(g.drills.isEmpty()) return null;
        g.u0 = minU; g.u1 = maxU;
        return g;
    }

    /** Target ore count if a drill fits here and would actually mine the target, else 0. */
    int drillScore(int u, int v){
        if(u < 0 || v < 0 || u + s > W - 1 || v + s > H) return 0; // column W-1 is the collector
        if(!drillOk[u][v]) return 0;
        Map<Integer, Integer> counts = new HashMap<>();
        for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++) if(drop[x][y] >= 0) counts.merge(drop[x][y], 1, Integer::sum);
        int t = counts.getOrDefault(target, 0);
        if(t < minOre) return 0;
        for(Map.Entry<Integer, Integer> e : counts.entrySet()) if(e.getKey() != target && e.getValue() >= t) return 0;
        return t;
    }

    int oreCount(int u, int v){
        int c = 0;
        for(int x = u; x < u + s; x++) for(int y = v; y < v + s; y++) if(drop[x][y] == target) c++;
        return c;
    }

    void mark(Segment g, boolean on){
        for(int[] d : g.drills) for(int x = d[0]; x < d[0] + s; x++) for(int y = d[1]; y < d[1] + s; y++) occ[x][y] = on ? -2 : -1;
        for(int u = g.u0; u <= g.u1; u++) occ[u][g.v] = on ? -3 : -1;
    }

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
                if(o != -1 || !beltOk[nu][nv]) continue;
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
}
