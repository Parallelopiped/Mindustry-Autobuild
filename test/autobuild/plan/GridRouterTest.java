package autobuild.plan;

import java.util.*;
import autobuild.plan.GridRouter.*;

public class GridRouterTest{
    static final Grid OPEN = new Grid(){
        public double tileCost(int u, int v){ return 1; }
        public boolean pushOk(int u, int v){ return true; }
    };

    public static void main(String[] args){
        heap();
        sameTileStarts();
        randomRoutes();
        cancellation();
        System.out.println("GRID ROUTER OK: heap oracle, start identity, 160 A*/Dijkstra comparisons, cancellation");
    }

    static void heap(){
        StateHeap heap = new StateHeap();
        PriorityQueue<double[]> oracle = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Random r = new Random(19);
        for(int i = 0; i < 100000; i++){
            if(oracle.isEmpty() || r.nextBoolean()){
                double priority = r.nextInt(200);
                heap.add(priority, i);
                oracle.add(new double[]{priority, i});
            }else{
                double[] e = oracle.remove();
                require(heap.priority() == e[0] && heap.poll() == (int)e[1], "heap mismatch");
            }
        }
        heap.clear();
        require(heap.isEmpty(), "heap reset");
    }

    static void sameTileStarts(){
        GridRouter router = new GridRouter(8, 5, OPEN);
        Start cheap = new Start(1, 2, 2, 0, false);
        Start costly = new Start(1, 2, 1, 100, true);
        List<Step> path = router.route(Arrays.asList(cheap, costly), 6, 2, 1);
        require(path != null && router.lastStart == cheap, "different entry directions overwrote their start");
        require(path.get(0).kind == GridRouter.PIPE, "wrong source block");
        router.route(Collections.emptyList(), 6, 2, 1);
        require(router.lastStart == null && router.lastCost == Double.MAX_VALUE, "stale failed-route metadata");
    }

    static void randomRoutes(){
        for(int seed = 0; seed < 160; seed++){
            Random r = new Random(seed);
            int w = 12, h = 11;
            double[][] prices = new double[w][h];
            for(int x = 0; x < w; x++) for(int y = 0; y < h; y++) prices[x][y] = r.nextInt(5) == 0 ? -1 : 1 + r.nextInt(4);
            prices[0][0] = 1;
            Grid grid = new Grid(){
                public double tileCost(int u, int v){ return prices[u][v]; }
                public boolean pushOk(int u, int v){ return true; }
            };
            GridRouter astar = new GridRouter(w, h, grid);
            GridRouter dijkstra = new GridRouter(w, h, grid){
                @Override double h(int u, int v, int tu, int tv, int ts){ return 0; }
            };
            astar.bridgeRange = dijkstra.bridgeRange = seed % 5;
            List<Start> starts = Collections.singletonList(new Start(0, 0, -1, 0, false));
            // Compare shortest state paths before the self-crossing retry heuristic.
            int[] a = {-1, -1}, b = {-1, -1};
            astar.search(starts, w - 1, h - 1, 1, Collections.emptySet(), a);
            dijkstra.search(starts, w - 1, h - 1, 1, Collections.emptySet(), b);
            require((a[0] < 0) == (b[0] < 0), "reachability seed " + seed);
            if(a[0] >= 0) require(Math.abs(astar.cost[a[0]] - dijkstra.cost[b[0]]) < 1e-8, "cost seed " + seed);
        }
    }

    static void cancellation(){
        Thread.currentThread().interrupt();
        try{
            new GridRouter(400, 400, OPEN).field(Collections.singletonList(new Start(0, 0, -1, 0, false)));
            throw new AssertionError("ignored interruption");
        }catch(DrillLayoutPlanner.CancelledException expected){
            require(Thread.currentThread().isInterrupted(), "interrupt flag was cleared");
        }finally{ Thread.interrupted(); }
    }

    static void require(boolean ok, String message){ if(!ok) throw new AssertionError(message); }
}
