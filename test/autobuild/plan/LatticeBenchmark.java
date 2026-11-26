package autobuild.plan;

import autobuild.plan.DrillLayoutPlanner.*;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Standalone benchmark of the greedy phase sweep and its three local searches. */
public class LatticeBenchmark{
    static Params fixture(){
        Params p = new Params();
        p.W = 32; p.H = 28; p.drillSize = 3; p.target = 1;
        p.seed = 641; p.sinkCapacity = 3; p.itemsPerOre = .25f;
        p.typeSize = new int[]{3, 2}; p.typeRate = new double[]{1, .65};
        p.typePowered = new boolean[2]; p.drop = new int[p.W][p.H];
        p.typeDrop = new int[][][]{p.drop, p.drop};
        p.typeDrillOk = new boolean[2][p.W][p.H]; p.beltOk = new boolean[p.W][p.H];
        Random random = new Random(826);
        for(int x = 0; x < p.W; x++) for(int y = 0; y < p.H; y++){
            int roll = random.nextInt(10);
            p.drop[x][y] = roll < 7 ? 1 : roll < 9 ? -1 : 2;
            p.beltOk[x][y] = random.nextInt(16) != 0;
            for(boolean[][] ok : p.typeDrillOk) ok[x][y] = true;
        }
        return p;
    }

    // Include ordered output, drill type and exact group scores so comparisons also detect layout changes.
    static long fingerprint(DrillLayoutPlanner planner, double score){
        long hash = 31 * Double.doubleToLongBits(score) + planner.layout.hashCode();
        for(Feeder f : planner.feeders){
            hash = 31 * hash + Double.doubleToLongBits(f.value);
            hash = 31 * hash + f.su;
            hash = 31 * hash + f.sv;
            for(int[] b : f.bridges) hash = 31 * hash + Arrays.hashCode(b);
            for(int[] d : f.drills) hash = 31 * hash + Arrays.hashCode(d);
        }
        return hash;
    }

    public static void main(String[] args){
        Params p = fixture();
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(bean == null || !bean.isThreadAllocatedMemorySupported()){
            throw new IllegalStateException("Benchmark requires JVM thread allocation counters");
        }
        if(!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long[] nanos = new long[7], bytes = new long[7];
        long expected = 0;
        for(int run = -3; run < nanos.length; run++){
            DrillLayoutPlanner planner = new DrillLayoutPlanner(p);
            planner.mask = new boolean[p.W][p.H];
            planner.computeOre();
            long allocated = bean.getCurrentThreadAllocatedBytes(), start = System.nanoTime();
            double score = planner.chooseLattice(-1);
            long elapsed = System.nanoTime() - start, used = bean.getCurrentThreadAllocatedBytes() - allocated;
            long hash = fingerprint(planner, score);
            if(run == -3) expected = hash;
            if(hash != expected) throw new AssertionError("inconsistent lattice output");
            if(run >= 0){ nanos[run] = elapsed; bytes[run] = used; }
        }
        Arrays.sort(nanos); Arrays.sort(bytes);
        System.out.printf(Locale.ROOT,
            "32x28 lattice, 2 drill tiers, 3 warmups/7 samples: median %.3f ms, %.3f MiB allocated, fingerprint %016x%n",
            nanos[3] / 1e6, bytes[3] / 1048576.0, expected);
    }
}
