package autobuild;

import arc.struct.Seq;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Standalone full-map port distance benchmark. Compile the same fixture against both revisions. */
public class RemotePortsBenchmark{
    public static void main(String[] args){
        RemotePorts ports = new RemotePorts();
        ports.gw = 384;
        ports.gh = 256;
        ports.free = new boolean[ports.gw][ports.gh];
        Random random = new Random(913);
        for(boolean[] column : ports.free){
            for(int y = 0; y < column.length; y++) column[y] = random.nextInt(5) != 0;
        }
        Seq<int[]> starts = new Seq<>();
        for(int[] tile : new int[][]{{9, 10}, {11, 10}, {10, 9}, {10, 11}}){
            ports.free[tile[0]][tile[1]] = true;
            starts.add(tile);
        }
        for(int range : new int[]{0, 4}) measure(ports, starts, range);
    }

    static void measure(RemotePorts ports, Seq<int[]> starts, int range){
        ThreadMXBean bean = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        if(bean == null || !bean.isThreadAllocatedMemorySupported()){
            throw new IllegalStateException("Benchmark requires JVM thread allocation counters");
        }
        if(!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        for(int i = 0; i < 5; i++) ports.flood(starts, range);
        long[] nanos = new long[9], bytes = new long[9];
        int checksum = 1;
        for(int i = 0; i < nanos.length; i++){
            long allocated = bean.getCurrentThreadAllocatedBytes();
            long start = System.nanoTime();
            float[][] field = ports.flood(starts, range);
            nanos[i] = System.nanoTime() - start;
            bytes[i] = bean.getCurrentThreadAllocatedBytes() - allocated;
            checksum = 31 * checksum + Arrays.deepHashCode(field);
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        System.out.printf(Locale.ROOT, "%dx%d, range %d: median %.3f ms, %.3f MiB allocated, checksum %08x%n",
            ports.gw, ports.gh, range, nanos[nanos.length / 2] / 1e6, bytes[bytes.length / 2] / 1048576.0, checksum);
    }
}
