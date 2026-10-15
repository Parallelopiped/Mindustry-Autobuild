package autobuild;

import autobuild.plan.DrillLayoutPlanner;
import autobuild.plan.DrillLayoutPlanner.Place;
import java.util.*;

/** Offline check: random ore blobs + walls, plan, print ASCII, verify invariants. */
public class PlannerTest{
    public static void main(String[] args){
        int fails = 0;
        for(int seed = 0; seed < 200; seed++) fails += run(seed, seed < 2);
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if(fails > 0) System.exit(1);
    }

    static int run(long seed, boolean print){
        Random r = new Random(seed);
        int W = 10 + r.nextInt(40), H = 10 + r.nextInt(40), s = 2;
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
        DrillLayoutPlanner p = new DrillLayoutPlanner(W, H, s, belt, drill, drop, 1, H / 2, 2);
        long t = System.nanoTime();
        boolean ok = p.plan();
        long ms = (System.nanoTime() - t) / 1_000_000;

        char[][] g = new char[W][H];
        for(int x = 0; x < W; x++) for(int y = 0; y < H; y++) g[x][y] = !belt[x][y] ? '#' : drop[x][y] == 1 ? '.' : drop[x][y] == 2 ? ',' : ' ';
        String errs = "";
        int[][] beltDir = new int[W][H];
        for(int[] row : beltDir) Arrays.fill(row, -1);
        for(Place pl : p.places){
            if(pl.kind == DrillLayoutPlanner.DRILL){
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
        }

        if(print || !errs.isEmpty()){
            System.out.println("seed " + seed + " " + W + "x" + H + " ok=" + ok + " " + (ok ? "" : p.failure) + " drills=" + p.drills + " ore=" + p.oreTiles
                + " routed=" + p.segmentsRouted + " dropped=" + p.segmentsDropped + " port=" + p.port + " " + ms + "ms");
            for(int y = H - 1; y >= 0; y--){ StringBuilder sb = new StringBuilder(); for(int x = 0; x < W; x++) sb.append(g[x][y]); System.out.println(sb + (y == p.port ? "  <- port (exit right)" : "")); }
        }
        if(!errs.isEmpty()){ System.out.println("ERRORS: " + errs); return 1; }
        return 0;
    }
}
