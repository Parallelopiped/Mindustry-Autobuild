package autobuild.plan;

import java.util.*;

/**
 * Power-node placement shared by the planners (pure Java, local grid coordinates).
 * <ol>
 * <li>Greedy set cover: repeatedly the node position reaching the most uncovered consumers (at most
 *     {@code maxLinks - 3} each, keeping slots for node-to-node links). A consumer no node can reach
 *     is dropped and the cover restarts (its tiles may host a node for the others).</li>
 * <li>Relays: Dijkstra over node positions from the main group until every node is linked; a relay
 *     may replace a droppable consumer at a high cost.</li>
 * </ol>
 * Anchors are power buildings the network must join (existing nodes / batteries / generators, or
 * a node the planner placed at a power port): the main group is everything linked to an anchor.
 * Without anchors it is the group of the first node.
 */
public class PowerNetwork{
    /** What the owning planner knows about its grid. */
    public interface Host{
        /** a node may be placed here now */
        boolean nodeFree(int u, int v);

        /** terrain allows a node here (ignoring what the planner placed) */
        boolean nodeOk(int u, int v);

        /** preference penalty for a node here (subtracted from the greedy score) */
        default double penalty(int u, int v){ return 0; }

        /** a relay may replace this occupied tile: index of the consumer standing there, else -1 */
        default int droppableAt(int u, int v, List<int[]> consumers){ return -1; }

        int size(int[] consumer);

        void occupy(int u, int v);

        /** consumer removed from the plan (free its tiles) */
        void drop(int[] consumer);

        Object save();

        void restore(Object state);

        default void progress(List<int[]> nodes, String stage){}

        default void checkCancel(){}
    }

    final int W, H, maxLinks;
    final float range;
    final Host host;
    /** {u, v, size} rectangles (min corner) the network must link to */
    public final List<int[]> anchors = new ArrayList<>();
    public final List<int[]> nodes = new ArrayList<>();
    public int dropped, groups;

    public PowerNetwork(int W, int H, float range, int maxLinks, Host host){
        this.W = W; this.H = H; this.range = range; this.maxLinks = maxLinks; this.host = host;
    }

    /** Same test the game uses: laser circle around the node centre touches the target's hitbox. */
    public boolean reaches(int nu, int nv, int x, int y, int size){
        float r = range * 0.95f; // small safety margin
        float x0 = x - 0.5f, x1 = x + size - 0.5f, y0 = y - 0.5f, y1 = y + size - 0.5f;
        float dx = Math.max(0, Math.max(x0 - nu, nu - x1)), dy = Math.max(0, Math.max(y0 - nv, nv - y1));
        return dx * dx + dy * dy <= r * r;
    }

    boolean nodeLink(int[] a, int[] b){ return reaches(a[0], a[1], b[0], b[1], 1); }

    boolean anchorLink(int[] n, int[] a){ return reaches(n[0], n[1], a[0], a[1], a[2]); }

    /**
     * Places nodes covering every consumer (dropping consumers no node can reach) and links them.
     * Mutates {@code consumers}. Returns the nodes (also in {@link #nodes}).
     */
    public List<int[]> place(List<int[]> consumers){
        // keep a couple of slots per node free for node-to-node links
        int cap = Math.max(1, maxLinks - 3);
        List<int[]> placed;
        while(true){
            host.checkCancel();
            placed = new ArrayList<>(nodes);
            Object save = host.save();
            boolean[] covered = new boolean[consumers.size()];
            int left = consumers.size();
            while(left > 0){
                int bu = -1, bv = -1;
                double best = 0;
                for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
                    if(!host.nodeFree(u, v)) continue;
                    int c = 0;
                    double dist = 0;
                    for(int i = 0; i < consumers.size(); i++){
                        int[] d = consumers.get(i);
                        int s = host.size(d);
                        if(!covered[i] && reaches(u, v, d[0], d[1], s)){ c++; dist += Math.abs(d[0] + s / 2f - u) + Math.abs(d[1] + s / 2f - v); }
                    }
                    if(c == 0) continue;
                    // most consumers covered; ties go to the node closest to them
                    double score = Math.min(c, cap) * 1000 - dist / c - host.penalty(u, v);
                    if(score > best){ best = score; bu = u; bv = v; }
                }
                if(bu < 0) break;
                host.occupy(bu, bv);
                placed.add(new int[]{bu, bv});
                int taken = 0;
                for(int i = 0; i < consumers.size() && taken < cap; i++){
                    int[] d = consumers.get(i);
                    if(!covered[i] && reaches(bu, bv, d[0], d[1], host.size(d))){ covered[i] = true; taken++; left--; }
                }
                host.progress(placed, "power nodes");
            }
            if(left == 0) break;
            // drop one unreachable consumer; its tiles may host a node for the others
            host.restore(save);
            for(int i = 0; i < consumers.size(); i++){
                if(!covered[i]){
                    host.drop(consumers.remove(i));
                    dropped++;
                    break;
                }
            }
            if(consumers.isEmpty()){ placed = new ArrayList<>(nodes); break; }
        }
        nodes.clear();
        nodes.addAll(placed);
        connect(consumers);
        return nodes;
    }

    /**
     * Adds relay nodes until all nodes are linked to the main group. Dijkstra from the main group
     * over node positions: each relay costs 1, a relay replacing a droppable consumer costs 100
     * (that consumer gets dropped), others block.
     */
    void connect(List<int[]> consumers){
        int R = (int)Math.ceil(range);
        while(true){
            host.checkCancel();
            int[] comp = components();
            groups = (int)Arrays.stream(comp).distinct().count();
            boolean anchorsLinked = anchors.isEmpty() || nodes.isEmpty() || comp[0] == 0;
            if(groups <= 1 && anchorsLinked) return;

            int[] prev = new int[W * H], cost = new int[W * H];
            Arrays.fill(prev, -2);
            Arrays.fill(cost, Integer.MAX_VALUE);
            PriorityQueue<int[]> q = new PriorityQueue<>(Comparator.comparingInt(a -> a[0]));
            boolean anyMain = false;
            for(int i = 0; i < nodes.size(); i++) if(main(comp, i)){
                int id = nodes.get(i)[0] * H + nodes.get(i)[1];
                prev[id] = -1; cost[id] = 0; q.add(new int[]{0, id});
                anyMain = true;
            }
            if(!anyMain){
                // no node reaches an anchor yet: start from relays next to the anchors
                for(int[] a : anchors) for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
                    if(!host.nodeOk(u, v) || !reaches(u, v, a[0], a[1], a[2])) continue;
                    int step = stepCost(u, v, consumers);
                    if(step < 0) continue;
                    int id = u * H + v;
                    if(step < cost[id]){ cost[id] = step; prev[id] = -1; q.add(new int[]{step, id}); }
                }
            }
            int hit = -1;
            outer:
            while(!q.isEmpty()){
                int[] cur = q.poll();
                int id = cur[1], u = id / H, v = id % H;
                if(cur[0] > cost[id]) continue;
                for(int i = 0; i < nodes.size(); i++){
                    if(!main(comp, i) && nodeLink(new int[]{u, v}, nodes.get(i))){ hit = id; break outer; }
                }
                for(int x = Math.max(0, u - R); x <= Math.min(W - 1, u + R); x++){
                    for(int y = Math.max(0, v - R); y <= Math.min(H - 1, v + R); y++){
                        if(!host.nodeOk(x, y) || !reaches(u, v, x, y, 1)) continue;
                        int step = stepCost(x, y, consumers);
                        if(step < 0) continue;
                        int nid = x * H + y, nc = cur[0] + step;
                        if(nc < cost[nid]){ cost[nid] = nc; prev[nid] = id; q.add(new int[]{nc, nid}); }
                    }
                }
            }
            if(hit < 0) return; // cannot connect; leave separate groups

            Set<int[]> remove = new LinkedHashSet<>();
            List<int[]> relays = new ArrayList<>();
            for(int id = hit; id >= 0; id = prev[id]){
                int u = id / H, v = id % H;
                // seeds from the main group are existing nodes, not relays
                if(prev[id] == -1 && anyMain) break;
                int k = host.droppableAt(u, v, consumers);
                if(k >= 0) remove.add(consumers.get(k));
                relays.add(new int[]{u, v});
            }
            for(int[] d : remove){
                consumers.remove(d);
                host.drop(d);
                dropped++;
            }
            for(int[] r : relays){ host.occupy(r[0], r[1]); nodes.add(r); }
            host.progress(nodes, "power relays");
        }
    }

    int stepCost(int x, int y, List<int[]> consumers){
        return host.nodeFree(x, y) ? 1 : host.droppableAt(x, y, consumers) >= 0 ? 100 : -1;
    }

    /** node i belongs to the main group: linked to an anchor, or (no anchors) to node 0 */
    boolean main(int[] comp, int i){ return comp[i] == 0; }

    /**
     * Linked groups of nodes. Group 0 is the main group: the nodes linked (through other nodes) to
     * an anchor, or without anchors the group of node 0. Without any node linked to an anchor, no
     * node is in group 0.
     */
    int[] components(){
        int n = nodes.size();
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int c = 0;
        if(!anchors.isEmpty()){
            // the main group first
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for(int i = 0; i < n; i++){
                for(int[] a : anchors) if(anchorLink(nodes.get(i), a)){ comp[i] = 0; q.add(i); break; }
            }
            flood(q, comp, 0);
            c = 1;
        }
        for(int i = 0; i < n; i++){
            if(comp[i] >= 0) continue;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(i); comp[i] = c;
            flood(q, comp, c);
            c++;
        }
        return comp;
    }

    void flood(ArrayDeque<Integer> q, int[] comp, int c){
        int n = nodes.size();
        while(!q.isEmpty()){
            int a = q.poll();
            for(int b = 0; b < n; b++) if(comp[b] < 0 && nodeLink(nodes.get(a), nodes.get(b))){ comp[b] = c; q.add(b); }
        }
    }
}
