package autobuild;

import autobuild.plan.DrillLayoutPlanner;
import autobuild.plan.DrillLayoutPlanner.*;
import java.util.*;

/** Offline check: random ore blobs + walls, plan, print ASCII, verify invariants. */
public class PlannerTest{
    public static void main(String[] args){
        int fails = 0;
        for(int seed = 0; seed < 300; seed++) fails += run(seed, seed < 2 || Integer.toString((int)seed).equals(System.getProperty("show")));
        compareModes();
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if(fails > 0) System.exit(1);
    }

    static int run(long seed, boolean print){
        Random r = new Random(seed);
        int W = 10 + r.nextInt(40), H = 10 + r.nextInt(40), s = 2 + (int)(seed % 3);
        Mode mode = seed % 2 == 0 ? Mode.OUTPUT : Mode.EFFICIENCY;
        boolean power = s > 2 || seed % 5 == 0;
        boolean[][] belt = new boolean[W][H];
        int[][] drop = new int[W][H];
        for(int[] row : drop) Arrays.fill(row, -1);
        for(boolean[] row : belt) Arrays.fill(row, true);
        for(int b = 0; b < 4; b++){ // ore blobs: item 1 (target) and 2
            int cx = r.nextInt(W), cy = r.nextInt(H), rad = 2 + r.nextInt(6), item = b < 3 ? 1 : 2;
            for(int x = 0; x < W; x++) for(int y = 0; y < H; y++) if((x-cx)*(x-cx)+(y-cy)*(y-cy) < rad*rad && r.nextFloat() < .85f) drop[x][y] = item;
        }
        for(int b = 0; b < 3; b++){ // walls
            int cx = r.nextInt(W), cy = r.nextInt(H), rad = 1 + r.nextInt(3);
            for(int x = 0; x < W; x++) for(int y = 0; y < H; y++) if(Math.abs(x-cx)+Math.abs(y-cy) < rad){ belt[x][y] = false; drop[x][y] = -1; }
        }
        boolean[][] drill = new boolean[W][H];
        for(int x = 0; x + s <= W; x++) for(int y = 0; y + s <= H; y++){
            boolean ok = true, ore = false;
            for(int i = 0; i < s; i++) for(int j = 0; j < s; j++){ ok &= belt[x+i][y+j]; ore |= drop[x+i][y+j] >= 0; }
            drill[x][y] = ok && ore;
        }
        Params pa = new Params();
        pa.W = W; pa.H = H; pa.drillSize = s; pa.beltOk = belt; pa.nodeOk = belt; pa.drillOk = drill; pa.drop = drop;
        pa.target = 1; pa.portV = H / 2; pa.mode = mode; pa.power = power;
        DrillLayoutPlanner p = new DrillLayoutPlanner(pa);
        long t = System.nanoTime();
        boolean ok = p.plan();
        long ms = (System.nanoTime() - t) / 1_000_000;

        char[][] g = new char[W][H];
        for(int x = 0; x < W; x++) for(int y = 0; y < H; y++) g[x][y] = !belt[x][y] ? '#' : drop[x][y] == 1 ? '.' : drop[x][y] == 2 ? ',' : ' ';
        String errs = "";
        int[][] beltDir = new int[W][H];
        for(int[] row : beltDir) Arrays.fill(row, -1);
        for(Place pl : p.places){
            if(pl.kind == DrillLayoutPlanner.NODE){
                if(g[pl.u][pl.v] == '#' || g[pl.u][pl.v] == 'D' || g[pl.u][pl.v] == 'N') errs += "badnode@" + pl + " ";
                g[pl.u][pl.v] = 'N';
            }else if(pl.kind == DrillLayoutPlanner.DRILL){
                if(mode == Mode.EFFICIENCY && pl.ore * 2 <= s * s) errs += "inefficient@" + pl + " ";
                for(int i = 0; i < s; i++) for(int j = 0; j < s; j++){
                    if(g[pl.u+i][pl.v+j] == '#' || Character.isLetter(g[pl.u+i][pl.v+j]) || "><^v".indexOf(g[pl.u+i][pl.v+j]) >= 0) errs += "overlap@" + pl + " ";
                    g[pl.u+i][pl.v+j] = 'D';
                }
            }else{
                if(!belt[pl.u][pl.v] || g[pl.u][pl.v] == 'D' || beltDir[pl.u][pl.v] >= 0) errs += "badbelt@" + pl + " ";
                g[pl.u][pl.v] = ">^<v".charAt(pl.dir);
                beltDir[pl.u][pl.v] = pl.dir;
            }
        }
        // every belt must flow (following dirs) to the port and out
        int[] DU = {1,0,-1,0}, DV = {0,1,0,-1};
        for(int x = 0; x < W; x++) for(int y = 0; y < H; y++) if(beltDir[x][y] >= 0){
            int u = x, v = y, steps = 0;
            while(true){
                int d = beltDir[u][v];
                int nu = u + DU[d], nv = v + DV[d];
                if(nu == W && v == p.port) break;
                if(nu < 0 || nv < 0 || nu >= W || nv >= H || beltDir[nu][nv] < 0){ errs += "deadend@(" + u + "," + v + ") "; break; }
                if(beltDir[nu][nv] == (d + 2) % 4){ errs += "headon@(" + u + "," + v + ") "; break; }
                u = nu; v = nv;
                if(++steps > W * H){ errs += "loop@(" + x + "," + y + ") "; break; }
            }
        }
        // every drill must touch a belt
        for(Place pl : p.places) if(pl.kind == DrillLayoutPlanner.DRILL){
            boolean touch = false;
            for(int i = -1; i <= s; i++) for(int j = -1; j <= s; j++){
                int x = pl.u + i, y = pl.v + j;
                boolean edge = (i == -1 || i == s) != (j == -1 || j == s);
                if(edge && x >= 0 && y >= 0 && x < W && y < H && beltDir[x][y] >= 0) touch = true;
            }
            if(!touch) errs += "isolated@" + pl + " ";
            if(power){
                boolean pow = false;
                for(Place n : p.places) if(n.kind == DrillLayoutPlanner.NODE && reach(n.u, n.v, pl.u, pl.v, s)) pow = true;
                if(!pow) errs += "unpowered@" + pl + " ";
            }
        }
        if(power && p.drills > 0 && p.powerGroups > 1) System.out.println("note seed " + seed + ": " + p.powerGroups + " power groups");

        if(print || !errs.isEmpty()){
            System.out.println("seed " + seed + " " + W + "x" + H + " ok=" + ok + " " + (ok ? "" : p.failure) + " drills=" + p.drills + " ore=" + p.oreTiles
                + " s=" + s + " " + mode + " power=" + power + " nodes=" + p.nodes + " routed=" + p.segmentsRouted + " dropped=" + p.segmentsDropped + " port=" + p.port + " " + ms + "ms");
            for(int y = H - 1; y >= 0; y--){ StringBuilder sb = new StringBuilder(); for(int x = 0; x < W; x++) sb.append(g[x][y]); System.out.println(sb + (y == p.port ? "  <- port (exit right)" : "")); }
        }
        if(!errs.isEmpty()){ System.out.println("ERRORS: " + errs); return 1; }
        return 0;
    }

    static boolean reach(int nu, int nv, int x, int y, int size){
        float r = 6f;
        float x0 = x - 0.5f, x1 = x + size - 0.5f, y0 = y - 0.5f, y1 = y + size - 0.5f;
        float dx = Math.max(0, Math.max(x0 - nu, nu - x1)), dy = Math.max(0, Math.max(y0 - nv, nv - y1));
        return dx * dx + dy * dy <= r * r;
    }

    /** Fixed irregular ore field: compare old periodic layout baseline vs both modes. */
    static void compareModes(){
        int W = 40, H = 30;
        boolean[][] belt = new boolean[W][H];
        int[][] drop = new int[W][H];
        Random r = new Random(42);
        for(int x = 0; x < W; x++) for(int y = 0; y < H; y++){
            belt[x][y] = true;
            double d = Math.hypot((x - 18) / 1.6, y - 15) + 3 * Math.sin(x * 0.7) + 2 * Math.cos(y * 0.9);
            drop[x][y] = d < 11 && r.nextFloat() < 0.9 ? 1 : -1;
        }
        int total = 0; for(int[] c : drop) for(int v : c) if(v == 1) total++;
        for(int s = 2; s <= 3; s++){
            boolean[][] drill = new boolean[W][H];
            for(int x = 0; x + s <= W; x++) for(int y = 0; y + s <= H; y++){
                boolean ore = false;
                for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) ore |= drop[x+i][y+j] >= 0;
                drill[x][y] = ore;
            }
            for(Mode m : Mode.values()){
                Params pa = new Params();
                pa.W = W; pa.H = H; pa.drillSize = s; pa.beltOk = belt; pa.nodeOk = belt; pa.drillOk = drill; pa.drop = drop;
                pa.target = 1; pa.portV = H / 2; pa.mode = m; pa.power = s == 3;
                DrillLayoutPlanner p = new DrillLayoutPlanner(pa);
                p.plan();
                int belts = 0; for(Place pl : p.places) if(pl.kind == DrillLayoutPlanner.BELT) belts++;
                System.out.printf("compare s=%d %-10s ore covered %3d/%d (%.0f%%)  drills=%3d belts=%3d nodes=%2d  ore/drill=%.2f%n",
                    s, m, p.oreTiles, total, 100.0 * p.oreTiles / total, p.drills, belts, p.nodes, (double)p.oreTiles / Math.max(1, p.drills));
            }
        }
    }
}
