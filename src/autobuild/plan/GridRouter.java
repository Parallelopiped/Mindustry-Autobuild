package autobuild.plan;

import java.util.*;

/**
 * Supply-network routing on a grid (pure Java): from a network (its port, or blocks already
 * placed) to a block that feeds a consumer, for items (conveyors, routers, item bridges, an
 * unloader at a storage port) and liquids (conduits, liquid routers, bridge conduits) alike.
 *
 * <p>Search states are (tile, how the tile was entered): walked in direction d, landed from a
 * bridge moving in direction d, or a start whose source direction is unknown. The game's rules
 * this encodes:
 * <ul>
 * <li>a conveyor / conduit takes from its back and sides, never from the tile it points to, so a
 *     route never turns back toward where its items came from;</li>
 * <li>a bridge doesn't take items from the side it links to, and an end bridge doesn't output
 *     toward the bridge that feeds it;</li>
 * <li>a block that pushes into all its neighbours (router, end bridge, unloader) must not stand
 *     next to another network's blocks - the host's {@link Grid#pushOk} decides.</li>
 * </ul>
 * Multi-source Dijkstra; {@link #field} gives every tile's cheapest connection cost (for valuing
 * candidates), {@link #route} the cheapest route to a target footprint.
 */
public class GridRouter{
    /** block kinds of a route */
    public static final int PIPE = 0, ROUTER = 1, BRIDGE = 2, END = 3, SOURCE = 4;
    static final int[] DX = {1, 0, -1, 0}, DY = {0, 1, 0, -1};
    /** states per tile: 0..3 walked in dir d, 4..7 landed moving d, 8 start with unknown source side */
    static final int M = 9;

    public interface Grid{
        /** cost of a pipe (or bridge end) on this tile; < 0 = not allowed */
        double tileCost(int u, int v);

        /** a block pushing into all its neighbours (router, end bridge, unloader) may stand here */
        boolean pushOk(int u, int v);
    }

    /** where a new branch can begin: the first block goes on (u,v); the items come from direction `from` (-1 unknown) */
    public static class Start{
        public final int u, v, from;
        public final double cost;
        /** the first block is the network's source block (an unloader next to a storage port) */
        public final boolean source;
        /** the first block replaces the network's pipe at (cu,cv) by a router (branching), or cu = -1 */
        public int cu = -1, cv = -1;

        public Start(int u, int v, int from, double cost, boolean source){
            this.u = u; this.v = v; this.from = from; this.cost = cost; this.source = source;
        }
    }

    /** one block of a route */
    public static class Step{
        public final int kind, u, v, dir, lu, lv;

        public Step(int kind, int u, int v, int dir, int lu, int lv){
            this.kind = kind; this.u = u; this.v = v; this.dir = dir; this.lu = lu; this.lv = lv;
        }

        @Override
        public String toString(){ return kind + ":" + u + "," + v + ">" + dir + "@" + lu + "," + lv; }
    }

    public final int W, H;
    final Grid grid;
    /** bridge range in tiles (0 = no bridges), cost of a jump (on top of the landing tile) */
    public int bridgeRange;
    public double bridgeCost = 1.5, turnCost = 0.1;

    double[] cost;
    int[] prev;
    Start[] startOf;

    public GridRouter(int W, int H, Grid grid){
        this.W = W; this.H = H; this.grid = grid;
    }

    boolean in(int u, int v){ return u >= 0 && v >= 0 && u < W && v < H; }

    int id(int u, int v, int m){ return (u * H + v) * M + m; }

    void search(List<Start> starts, int tu, int tv, int ts, Set<Integer> banned, int[] hit){
        int n = W * H * M;
        cost = new double[n];
        prev = new int[n];
        startOf = new Start[W * H];
        Arrays.fill(cost, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> q = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        for(Start s : starts){
            if(!in(s.u, s.v) || banned.contains(s.u * H + s.v)) continue;
            double c = grid.tileCost(s.u, s.v);
            if(c < 0) continue;
            if(s.source && !grid.pushOk(s.u, s.v)) continue;
            int m = s.from < 0 ? 8 : (s.from + 2) % 4;
            int i = id(s.u, s.v, m);
            double total = c + s.cost;
            if(total < cost[i]){
                cost[i] = total;
                startOf[s.u * H + s.v] = s;
                q.add(new double[]{total, i});
            }
        }
        while(!q.isEmpty()){
            double[] cur = q.poll();
            int i = (int)cur[1];
            if(cur[0] > cost[i]) continue;
            int m = i % M, t = i / M, u = t / H, v = t % H;
            Start st = prev[i] < 0 ? startOf[t] : null;
            boolean source = st != null && st.source;
            int a = m < 4 ? m : m < 8 ? m - 4 : -1;
            int back = a < 0 ? -1 : (a + 2) % 4;
            boolean landed = m >= 4 && m < 8;
            // pushing block here (end bridge / unloader) when the route goes on by walking or ends
            boolean pushes = landed || source;
            boolean outOk = !pushes || grid.pushOk(u, v);
            if(ts > 0 && outOk){
                for(int d = 0; d < 4; d++){
                    if(d == back) continue;
                    int nu = u + DX[d], nv = v + DY[d];
                    if(nu >= tu && nu < tu + ts && nv >= tv && nv < tv + ts){ hit[0] = i; hit[1] = d; return; }
                }
            }
            for(int d = 0; d < 4; d++){
                if(d == back) continue;
                // walk
                int nu = u + DX[d], nv = v + DY[d];
                if(outOk && in(nu, nv) && !banned.contains(nu * H + nv)){
                    double c = grid.tileCost(nu, nv);
                    if(c >= 0){
                        double nc = cur[0] + c + (a >= 0 && d != a && !landed ? turnCost : 0);
                        int ni = id(nu, nv, d);
                        if(nc < cost[ni]){ cost[ni] = nc; prev[ni] = i; q.add(new double[]{nc, ni}); }
                    }
                }
                // bridge jump (not from the source block)
                if(source) continue;
                for(int k = 2; k <= bridgeRange; k++){
                    int lu = u + DX[d] * k, lv = v + DY[d] * k;
                    if(!in(lu, lv)) break;
                    if(banned.contains(lu * H + lv)) continue;
                    double c = grid.tileCost(lu, lv);
                    if(c < 0) continue;
                    double nc = cur[0] + c + bridgeCost;
                    int ni = id(lu, lv, 4 + d);
                    if(nc < cost[ni]){ cost[ni] = nc; prev[ni] = i; q.add(new double[]{nc, ni}); }
                }
            }
        }
    }

    /**
     * Cheapest cost, per tile, of a network block there that can feed a neighbour (ignoring which
     * side): connection cost estimates for candidate consumers. Double.MAX_VALUE = unreachable.
     */
    public double[][] field(List<Start> starts){
        search(starts, 0, 0, 0, Collections.emptySet(), new int[]{-1, -1});
        double[][] f = new double[W][H];
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            double best = Double.MAX_VALUE;
            for(int m = 0; m < M; m++){
                double c = cost[id(u, v, m)];
                if(c < best && ((m < 4 || m == 8) || grid.pushOk(u, v))) best = c;
            }
            f[u][v] = best;
        }
        return f;
    }

    /** total cost of the last route */
    public double lastCost;
    /** the start the last route used */
    public Start lastStart;

    /**
     * Cheapest route from the starts to a block feeding the footprint (tu,tv,size ts), or null.
     * The first step's block replaces a network pipe when its start says so (see {@link Start#cu}).
     * Routes that cross themselves are searched again without the crossing tile.
     */
    public List<Step> route(List<Start> starts, int tu, int tv, int ts){
        Set<Integer> banned = new HashSet<>();
        for(int attempt = 0; attempt < 6; attempt++){
            int[] hit = {-1, -1};
            search(starts, tu, tv, ts, banned, hit);
            if(hit[0] < 0) return null;
            List<Step> steps = build(hit[0], hit[1]);
            int dup = duplicate(steps);
            if(dup < 0){
                lastCost = cost[hit[0]];
                return steps;
            }
            banned.add(dup);
        }
        return null;
    }

    List<Step> build(int end, int endDir){
        ArrayDeque<int[]> states = new ArrayDeque<>();
        for(int i = end; i >= 0; i = prev[i]) states.addFirst(new int[]{i});
        List<Step> out = new ArrayList<>();
        int[][] st = states.toArray(new int[0][]);
        lastStart = startOf[st[0][0] / M];
        for(int k = 0; k < st.length; k++){
            int i = st[k][0], m = i % M, t = i / M, u = t / H, v = t % H;
            boolean landed = m >= 4 && m < 8;
            boolean source = k == 0 && lastStart != null && lastStart.source;
            if(k == st.length - 1){
                out.add(source ? new Step(SOURCE, u, v, endDir, u, v) : landed ? new Step(END, u, v, endDir, u, v) : new Step(PIPE, u, v, endDir, u, v));
                break;
            }
            int j = st[k + 1][0], nm = j % M, nt = j / M, nu = nt / H, nv = nt % H;
            if(nm >= 4 && nm < 8){
                // jump: a bridge here linking to the next tile
                out.add(new Step(BRIDGE, u, v, nm - 4, nu, nv));
            }else{
                int d = nm;
                out.add(source ? new Step(SOURCE, u, v, d, u, v) : landed ? new Step(END, u, v, d, u, v) : new Step(PIPE, u, v, d, u, v));
            }
        }
        return out;
    }

    /** a tile used twice by one route (it crossed itself), or -1 */
    int duplicate(List<Step> steps){
        Set<Integer> seen = new HashSet<>();
        for(Step s : steps) if(!seen.add(s.u * H + s.v)) return s.u * H + s.v;
        return -1;
    }
}
