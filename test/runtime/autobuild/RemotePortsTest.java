package autobuild;

import arc.struct.Seq;
import java.util.*;

/** Remote-port path costs and edge selection, exercised without a world or graphics. */
public class RemotePortsTest{
    public static void main(String[] args){
        int cases = 0;
        for(int width : new int[]{1, 2, 8}) for(int height : new int[]{1, 3, 9}){
            for(int avoid = 0; avoid < 16; avoid++) for(int lines = 1; lines <= 4; lines++){
                for(boolean open : new boolean[]{false, true}){
                    RemotePorts r = new RemotePorts();
                    r.ax = 2; r.ay = 2; r.bx = r.ax + width - 1; r.by = r.ay + height - 1;
                    r.gw = width + 5; r.gh = height + 5;
                    r.free = new boolean[r.gw][r.gh];
                    r.reach = new float[r.gw][r.gh];
                    for(int x = 0; x < r.gw; x++){
                        Arrays.fill(r.free[x], open);
                        Arrays.fill(r.reach[x], open ? 1 : Float.MAX_VALUE);
                    }
                    Seq<Port> ports = r.edgePorts(new int[]{20, 20, 1}, lines, avoid);
                    require(!ports.isEmpty() && ports.size <= lines, "port count");
                    for(Port p : ports){
                        require(p.side >= 0 && p.side < 4 && p.frac >= 0 && p.frac <= 1, "port bounds");
                        // Existing all-sides fallback is intentional when every side is occupied.
                        if(avoid != 15) require((avoid & 1 << p.side) == 0, "excluded side used");
                    }
                    cases++;
                }
            }
        }
        floodCosts();
        System.out.println("REMOTE PORTS OK: " + cases + " edge selections and weighted flood comparisons");
    }

    static void floodCosts(){
        Random random = new Random(9121);
        for(int[] shape : new int[][]{{1, 1}, {1, 18}, {13, 1}, {9, 7}, {12, 5}}){
            RemotePorts r = new RemotePorts();
            r.gx = 19; r.gy = 41; r.gw = shape[0]; r.gh = shape[1];
            r.free = new boolean[r.gw][r.gh];
            for(int trial = 0; trial < 16; trial++){
                for(int x = 0; x < r.gw; x++) for(int y = 0; y < r.gh; y++){
                    // Open field, then a wall with a gap, then patchy terrain.
                    r.free[x][y] = trial == 0 || (trial == 1 ? x != r.gw / 2 || y == r.gh - 1 : random.nextInt(3) != 0);
                }
                Seq<int[]> starts = new Seq<>();
                starts.add(new int[]{r.gx - 1, r.gy});
                if(trial != 2){ // also exercise a flood without a valid start
                    r.free[0][0] = true;
                    starts.add(new int[]{r.gx, r.gy});
                    starts.add(new int[]{r.gx, r.gy});
                    if(trial % 2 == 0) starts.add(new int[]{r.gx + r.gw - 1, r.gy + r.gh - 1});
                    if(trial > 2 && r.gw > 1){
                        r.free[1][0] = false;
                        starts.add(new int[]{r.gx + 1, r.gy});
                    }
                }
                for(int range : new int[]{0, 1, 2, 4}){
                    int[][] expected = referenceFlood(r, starts, range);
                    float[][] actual = r.flood(starts, range);
                    for(int x = 0; x < r.gw; x++) for(int y = 0; y < r.gh; y++){
                        float cost = expected[x][y] == Integer.MAX_VALUE ? Float.MAX_VALUE : expected[x][y] / 2f;
                        if(actual[x][y] != cost) throw new AssertionError("flood " + r.gw + "x" + r.gh + " trial " + trial
                            + " range " + range + " at (" + x + "," + y + "): expected " + cost + ", got " + actual[x][y]);
                    }
                }
            }
        }
    }

    /** Heap Dijkstra in half-tile units. Enumerate landing tiles directly on these small maps. */
    static int[][] referenceFlood(RemotePorts r, Seq<int[]> starts, int range){
        int[][] dist = new int[r.gw][r.gh];
        for(int[] col : dist) Arrays.fill(col, Integer.MAX_VALUE);
        PriorityQueue<int[]> queue = new PriorityQueue<>(Comparator.comparingInt(entry -> entry[2]));
        for(int[] start : starts){
            int x = start[0] - r.gx, y = start[1] - r.gy;
            if(x < 0 || y < 0 || x >= r.gw || y >= r.gh || !r.free[x][y] || dist[x][y] == 0) continue;
            dist[x][y] = 0;
            queue.add(new int[]{x, y, 0});
        }
        while(!queue.isEmpty()){
            int[] from = queue.remove();
            if(from[2] != dist[from[0]][from[1]]) continue;
            for(int x = 0; x < r.gw; x++) for(int y = 0; y < r.gh; y++){
                if(!r.free[x][y] || (x != from[0] && y != from[1])) continue;
                int length = Math.abs(x - from[0]) + Math.abs(y - from[1]);
                if(length == 0 || (length > 1 && length > range)) continue;
                int cost = from[2] + (length == 1 ? 2 : 5);
                if(cost >= dist[x][y]) continue;
                dist[x][y] = cost;
                queue.add(new int[]{x, y, cost});
            }
        }
        return dist;
    }

    static void require(boolean ok, String message){ if(!ok) throw new AssertionError(message); }
}
