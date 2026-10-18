package autobuild.plan;

import java.util.*;

/**
 * Enemy ground routes, computed the way the game's {@code Pathfinder} does it (pure Java, map
 * coordinates, tile index = x + y * w):
 * <ul>
 * <li>a flow field from the target tiles (the cores enemies walk to): each tile's weight is the
 *     smallest sum of "cost of entering" over a 4-connected path to a target;</li>
 * <li>a unit on a tile moves to the 8-neighbour with the smallest weight below its own (diagonals
 *     only if both orthogonal tiles are passable), first in {@link #D8} order on ties - so the
 *     route from a spawn is the chain of those moves.</li>
 * </ul>
 * The game's ground cost of a tile is 1 + 5 x (scaled health of a solid building of another team)
 * + 2 if next to a solid block + terrain terms; impassable = -1. Placing a solid block of our team
 * on a free tile therefore adds 5 x min(health / 40, 80) to that tile and 2 to each neighbour that
 * wasn't already next to something solid: {@link #addSolid} applies exactly that.
 */
public class EnemyRoutes{
    public static final int IMPASSABLE = -1;
    /** arc's Geometry.d8 order */
    public static final int[][] D8 = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    public final int w, h;
    /** cost of entering each tile, -1 = impassable */
    public final int[] cost;
    /** next to a solid block (the +2 is already in cost) */
    public final boolean[] nearSolid;
    /** flow field: weight to the nearest target, Long.MAX_VALUE = unreachable */
    public long[] weight;

    public EnemyRoutes(int w, int h, int[] cost, boolean[] nearSolid){
        this.w = w; this.h = h; this.cost = cost; this.nearSolid = nearSolid;
    }

    public EnemyRoutes copy(){
        return new EnemyRoutes(w, h, cost.clone(), nearSolid.clone());
    }

    /** a solid block of our team on the (free, passable) tile x,y: its path cost and its neighbours' near-solid penalty */
    public void addSolid(int x, int y, int healthCost){
        int i = x + y * w;
        if(cost[i] != IMPASSABLE) cost[i] += healthCost;
        for(int[] d : D8){
            if(d[0] != 0 && d[1] != 0) continue;
            int nx = x + d[0], ny = y + d[1];
            if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
            int n = nx + ny * w;
            if(!nearSolid[n]){
                nearSolid[n] = true;
                if(cost[n] != IMPASSABLE) cost[n] += 2;
            }
        }
    }

    /** the game's health term for a solid building: 5 x min(health / 40, 80) */
    public static int healthCost(float health){
        return 5 * Math.min((int)(health / 40), 80);
    }

    /** Builds the flow field toward the targets (Dijkstra; the game's label-correcting search gives the same weights). */
    public void flow(int[] targets){
        int n = w * h;
        weight = new long[n];
        Arrays.fill(weight, Long.MAX_VALUE);
        LongHeap q = new LongHeap(1024);
        for(int t : targets){
            if(t < 0 || t >= n) continue;
            weight[t] = 0;
            q.add(t);
        }
        while(q.size > 0){
            long e = q.poll();
            long dist = e >>> 22;
            int i = (int)(e & ((1 << 22) - 1));
            if(dist > weight[i]) continue;
            int x = i % w, y = i / w;
            for(int k = 0; k < 8; k += 2){
                int nx = x + D8[k][0], ny = y + D8[k][1];
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int ni = nx + ny * w;
                int c = cost[ni];
                if(c == IMPASSABLE) continue;
                long nd = dist + c;
                if(nd < weight[ni]){
                    weight[ni] = nd;
                    q.add((nd << 22) | ni);
                }
            }
        }
    }

    boolean passable(int x, int y){
        return x >= 0 && y >= 0 && x < w && y < h && cost[x + y * w] != IMPASSABLE;
    }

    /** The tile a unit on (x,y) moves to next, or -1 if it stays (target reached / stuck). */
    public int next(int x, int y){
        long value = weight[x + y * w];
        int best = -1;
        long tl = 0;
        for(int[] d : D8){
            int nx = x + d[0], ny = y + d[1];
            if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
            int ni = nx + ny * w;
            long c = weight[ni];
            if(c < value && (best < 0 || c < tl) && passable(nx, ny)
                && !(d[0] != 0 && d[1] != 0 && (!passable(x + d[0], y) || !passable(x, y + d[1])))){
                best = ni;
                tl = c;
            }
        }
        return best;
    }

    /** Route of a unit spawned at tile `start`: the tiles it walks through, start first (bounded). */
    public int[] trace(int start){
        IntList out = new IntList();
        if(weight == null || start < 0 || start >= w * h || weight[start] == Long.MAX_VALUE) return out.toArray();
        int cur = start;
        out.add(cur);
        for(int guard = 0; guard < w * h; guard++){
            int nx = next(cur % w, cur / w);
            if(nx < 0) break;
            cur = nx;
            out.add(cur);
            if(weight[cur] == 0) break;
        }
        return out.toArray();
    }

    /** Tiles along the straight line from (x0,y0) to (x1,y1) (flying units), one per tile step. */
    public static int[] line(int w, int h, float x0, float y0, float x1, float y1){
        IntList out = new IntList();
        float len = (float)Math.hypot(x1 - x0, y1 - y0);
        int steps = Math.max(1, (int)Math.ceil(len));
        int last = -1;
        for(int s = 0; s <= steps; s++){
            int x = Math.round(x0 + (x1 - x0) * s / steps), y = Math.round(y0 + (y1 - y0) * s / steps);
            if(x < 0 || y < 0 || x >= w || y >= h) continue;
            int i = x + y * w;
            if(i != last) out.add(i);
            last = i;
        }
        return out.toArray();
    }

    /** growable int list */
    public static class IntList{
        int[] items = new int[16];
        public int size;

        public void add(int v){ if(size == items.length) items = Arrays.copyOf(items, size * 2); items[size++] = v; }

        public int get(int i){ return items[i]; }

        public int[] toArray(){ return Arrays.copyOf(items, size); }
    }

    /** binary min-heap of longs */
    static class LongHeap{
        long[] a;
        int size;

        LongHeap(int cap){ a = new long[cap]; }

        void add(long v){
            if(size == a.length) a = Arrays.copyOf(a, size * 2);
            int i = size++;
            while(i > 0){
                int p = (i - 1) >> 1;
                if(a[p] <= v) break;
                a[i] = a[p];
                i = p;
            }
            a[i] = v;
        }

        long poll(){
            long top = a[0], v = a[--size];
            int i = 0;
            while(true){
                int c = 2 * i + 1;
                if(c >= size) break;
                if(c + 1 < size && a[c + 1] < a[c]) c++;
                if(a[c] >= v) break;
                a[i] = a[c];
                i = c;
            }
            a[i] = v;
            return top;
        }
    }
}
