package autobuild;

import autobuild.plan.GridRouter;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Standalone deterministic allocation/time benchmark, runnable against either revision. */
public class RouterBenchmark{
    public static void main(String[] args){
        int size = 160;
        boolean[][] blocked = new boolean[size][size];
        Random random = new Random(913);
        for(int x = 0; x < size; x++) for(int y = 0; y < size; y++) blocked[x][y] = random.nextInt(5) == 0;
        blocked[0][0] = false;
        GridRouter router = new GridRouter(size, size, new GridRouter.Grid(){
            public double tileCost(int x, int y){ return blocked[x][y] ? -1 : 1; }
            public boolean pushOk(int x, int y){ return true; }
        });
        router.bridgeRange = 4;
        List<GridRouter.Start> starts = Collections.singletonList(new GridRouter.Start(0, 0, -1, 0, false));
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        for(int i = 0; i < 5; i++) router.field(starts);
        long[] times = new long[9], allocations = new long[9];
        double checksum = 0;
        for(int i = 0; i < times.length; i++){
            long allocated = bean.getThreadAllocatedBytes(tid), begin = System.nanoTime();
            double[][] field = router.field(starts);
            times[i] = System.nanoTime() - begin;
            allocations[i] = bean.getThreadAllocatedBytes(tid) - allocated;
            for(double[] row : field) for(double value : row) if(value < Double.MAX_VALUE) checksum += value;
        }
        Arrays.sort(times); Arrays.sort(allocations);
        System.out.printf(Locale.ROOT, "160x160 field, bridges=4, 5 warmups/9 samples: median %.3f ms, %.3f MiB allocated, checksum %.4f%n",
            times[4] / 1e6, allocations[4] / 1048576.0, checksum);
    }
}
