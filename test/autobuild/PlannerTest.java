package autobuild;

import autobuild.plan.DrillLayoutPlanner;
import autobuild.plan.DrillLayoutPlanner.*;
import java.util.*;

/**
 * Offline checks for the planner: random ore fields with walls, every combination of drill size,
 * mode, power and bridges. Each plan is validated by an independent item-flow model built from
 * the game's accept/dump rules (see FlowCheck).
 */
public class PlannerTest{
    static final int[] DU = {1, 0, -1, 0}, DV = {0, 1, 0, -1};
    static int variantsTried, variantsDiffer, variantsBetter;

    public static void main(String[] args){
        int fails = 0;
        String show = System.getProperty("show");
        for(int seed = 0; seed < 400; seed++) fails += run(seed, seed < 2 || Integer.toString(seed).equals(show));
        System.out.println("variants: " + variantsDiffer + "/" + variantsTried + " maps got a different alternative; "
            + variantsBetter + " alternatives scored >5% above the default plan");
        fails += oreRule();
        fails += waterAroundWall();
        mixGain();
        logisticsEfficiency();
        compare();
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if(fails > 0) System.exit(1);
    }

    static class Map{
        int W, H;
        boolean[][] belt, drill;
        int[][] drop;
    }

    static Map randomMap(long seed, int s){
        Random r = new Random(seed);
        Map m = new Map();
        int W = m.W = 10 + r.nextInt(40), H = m.H = 10 + r.nextInt(40);
        m.belt = new boolean[W][H];
        m.drop = new int[W][H];
        for(int[] row : m.drop) Arrays.fill(row, -1);
        for(boolean[] row : m.belt) Arrays.fill(row, true);
        for(int b = 0; b < 4; b++){ // ore blobs: item 1 (target) and 2
            int cx = r.nextInt(W), cy = r.nextInt(H), rad = 2 + r.nextInt(6), item = b < 3 ? 1 : 2;
            for(int x = 0; x < W; x++) for(int y = 0; y < H; y++) if((x-cx)*(x-cx)+(y-cy)*(y-cy) < rad*rad && r.nextFloat() < .85f) m.drop[x][y] = item;
        }
        for(int b = 0; b < 3; b++){ // walls
            int cx = r.nextInt(W), cy = r.nextInt(H), rad = 1 + r.nextInt(3);
            for(int x = 0; x < W; x++) for(int y = 0; y < H; y++) if(Math.abs(x-cx)+Math.abs(y-cy) < rad){ m.belt[x][y] = false; m.drop[x][y] = -1; }
        }
        m.drill = drillOk(m, s);
        return m;
    }

    static boolean[][] drillOk(Map m, int s){
        boolean[][] d = new boolean[m.W][m.H];
        for(int x = 0; x + s <= m.W; x++) for(int y = 0; y + s <= m.H; y++){
            boolean ok = true, ore = false;
            for(int i = 0; i < s; i++) for(int j = 0; j < s; j++){ ok &= m.belt[x+i][y+j]; ore |= m.drop[x+i][y+j] >= 0; }
            d[x][y] = ok && ore;
        }
        return d;
    }

    static Params params(Map m, int s, Mode mode, boolean power, boolean bridges, int waterSide, int waterPos){
        Params pa = params(m, s, mode, power, bridges);
        pa.water = waterSide > 0;
        pa.waterSide = Math.max(1, waterSide);
        pa.waterPos = waterPos;
        return pa;
    }

    /** power only for type 0 when power is on (like laser/airblast with pneumatic fillers) */
    static boolean typePower0;
    static boolean poweredType(int type){ return type == 0 ? typePower0 : false; }

    /** main drill of size s plus every smaller size >= 2 as a slower, unpowered filler */
    static void addTypes(Params pa, Map m, int s, boolean power){
        int n = s - 1;
        pa.typeSize = new int[n]; pa.typeRate = new double[n]; pa.typePowered = new boolean[n];
        pa.typeDrop = new int[n][][]; pa.typeDrillOk = new boolean[n][][];
        for(int i = 0; i < n; i++){
            int st = s - i;
            pa.typeSize[i] = st; pa.typeRate[i] = i == 0 ? 1 : 0.7 - 0.1 * i; pa.typePowered[i] = i == 0 && power;
            pa.typeDrop[i] = m.drop; pa.typeDrillOk[i] = drillOk(m, st);
        }
    }

    static Params params(Map m, int s, Mode mode, boolean power, boolean bridges){
        Params pa = new Params();
        pa.W = m.W; pa.H = m.H; pa.drillSize = s; pa.beltOk = m.belt; pa.nodeOk = m.belt; pa.drillOk = m.drill; pa.drop = m.drop;
        pa.target = 1; pa.portV = m.H / 2; pa.mode = mode; pa.power = power; pa.bridges = bridges;
        return pa;
    }

    static int run(long seed, boolean print){
        int s = 2 + (int)(seed % 3);
        Map m = randomMap(seed, s);
        Mode mode = seed % 2 == 0 ? Mode.OUTPUT : Mode.EFFICIENCY;
        boolean power = s > 2 || seed % 5 == 0;
        boolean bridges = seed % 4 != 3;
        boolean mixed = s > 2 && seed % 2 == 1;
        int waterSide = seed % 3 == 0 ? 1 + (int)(seed / 3 % 3) : 0;
        int waterPos = (int)(seed * 7 % Math.max(1, Math.min(m.W, m.H)));
        Params pp = params(m, s, mode, power, bridges, waterSide, waterPos);
        if(mixed) addTypes(pp, m, s, power);
        typePower0 = power;
        DrillLayoutPlanner p = new DrillLayoutPlanner(pp);
        int[] snapshots = {0};
        p.listener = (stage, snap) -> snapshots[0]++;
        p.snapshotInterval = 0;
        long t = System.nanoTime();
        boolean ok = p.plan();
        long ms = (System.nanoTime() - t) / 1_000_000;

        String errs = ok ? FlowCheck.check(m, s, p, power) : "";
        if(ok && seed % 5 == 0){
            // alternative plans ("Another plan") must be valid too, and differ from the best one
            Set<String> base = new HashSet<>();
            for(Place pl : p.places) base.add(pl.toString());
            boolean anyDifferent = false;
            for(int variant = 1; variant <= 3; variant++){
                Params pv = params(m, s, mode, power, bridges, waterSide, waterPos);
                if(mixed) addTypes(pv, m, s, power);
                pv.variant = variant;
                DrillLayoutPlanner q = new DrillLayoutPlanner(pv);
                if(!q.plan()) continue;
                String e = FlowCheck.check(m, s, q, power);
                if(!e.isEmpty()) errs += "variant" + variant + ": " + e;
                Set<String> vs = new HashSet<>();
                for(Place pl : q.places) vs.add(pl.toString());
                if(!vs.equals(base)) anyDifferent = true;
                if(q.score > p.score + 1e-6 && Math.abs(q.score - p.score) > 0.05 * Math.abs(p.score) + 1) variantsBetter++;
            }
            variantsTried++;
            if(anyDifferent) variantsDiffer++;
        }
        if(ok && snapshots[0] == 0) errs += "no progress snapshots ";
        if(print || !errs.isEmpty()){
            System.out.println("seed " + seed + " " + m.W + "x" + m.H + " s=" + s + (mixed ? "+mixed" : "") + " " + mode + " power=" + power + " bridges=" + bridges + " water=" + waterSide + " wet=" + p.wetDrills
                + " ok=" + ok + (ok ? "" : " " + p.failure) + " layout=" + p.layout + " drills=" + p.drills + " ore=" + p.oreTiles
                + " belts=" + p.belts + " bridges=" + p.bridges + " nodes=" + p.nodes + " routed=" + p.segmentsRouted
                + " dropped=" + p.segmentsDropped + " " + ms + "ms");
            print(m, s, p);
        }
        if(!errs.isEmpty()){
            System.out.println("ERRORS: " + errs);
            for(Place pl : p.places) if(pl.kind >= DrillLayoutPlanner.CONDUIT) System.out.print(pl + (pl.kind == DrillLayoutPlanner.CONDUIT ? ">" + pl.dir : pl.kind == DrillLayoutPlanner.LBRIDGE ? "->(" + pl.lu + "," + pl.lv + ")" : "") + " ");
            System.out.println();
            return 1;
        }
        return 0;
    }

    static void print(Map m, int s, DrillLayoutPlanner p){
        char[][] g = new char[m.W][m.H];
        for(int x = 0; x < m.W; x++) for(int y = 0; y < m.H; y++) g[x][y] = !m.belt[x][y] ? '#' : m.drop[x][y] == 1 ? '.' : m.drop[x][y] == 2 ? ',' : ' ';
        for(Place pl : p.places){
            if(pl.kind == DrillLayoutPlanner.DRILL){ for(int i = 0; i < pl.size; i++) for(int j = 0; j < pl.size; j++) g[pl.u+i][pl.v+j] = pl.dir == 1 ? 'W' : pl.type == 0 ? 'D' : (char)('a' + pl.type - 1); }
            else if(pl.kind == DrillLayoutPlanner.CONDUIT) g[pl.u][pl.v] = "-|-|".charAt(pl.dir);
            else if(pl.kind == DrillLayoutPlanner.LROUTER) g[pl.u][pl.v] = 'O';
            else if(pl.kind == DrillLayoutPlanner.LBRIDGE) g[pl.u][pl.v] = pl.lu == pl.u && pl.lv == pl.v ? 'e' : '=';
            else if(pl.kind == DrillLayoutPlanner.BELT) g[pl.u][pl.v] = ">^<v".charAt(pl.dir);
            else if(pl.kind == DrillLayoutPlanner.NODE) g[pl.u][pl.v] = 'N';
            else g[pl.u][pl.v] = pl.lu == pl.u && pl.lv == pl.v ? 'E' : "RTLB".charAt(dirOf(pl.lu - pl.u, pl.lv - pl.v));
        }
        for(int y = m.H - 1; y >= 0; y--){
            StringBuilder sb = new StringBuilder();
            for(int x = 0; x < m.W; x++) sb.append(g[x][y]);
            System.out.println(sb + (y == p.port ? "  <- port" : ""));
        }
    }

    static int dirOf(int dx, int dy){ return dx > 0 ? 0 : dy > 0 ? 1 : dx < 0 ? 2 : 3; }

    /** Independent model of item movement using the game's rules (ItemBridge / Conveyor / Drill). */
    static class FlowCheck{
        static final int SINK = 0;

        static String check(Map m, int s, DrillLayoutPlanner p, boolean power){
            StringBuilder err = new StringBuilder();
            int W = m.W, H = m.H;
            int[][] ent = new int[W][H];
            List<Place> ents = new ArrayList<>();
            ents.add(null); // SINK
            for(Place pl : p.places){
                int id = ents.size();
                ents.add(pl);
                int size = pl.kind == DrillLayoutPlanner.DRILL ? pl.size : 1;
                for(int i = 0; i < size; i++) for(int j = 0; j < size; j++){
                    int x = pl.u + i, y = pl.v + j;
                    if(x >= W || y >= H){ err.append("outside@").append(pl).append(' '); continue; }
                    if(ent[x][y] != 0) err.append("overlap@").append(pl).append(" with ").append(ents.get(ent[x][y])).append(' ');
                    if(!m.belt[x][y]) err.append("onwall@").append(pl).append(' ');
                    ent[x][y] = id;
                }
                if(pl.kind == DrillLayoutPlanner.BRIDGE && !(pl.lu == pl.u && pl.lv == pl.v)){
                    int dx = pl.lu - pl.u, dy = pl.lv - pl.v;
                    if((dx != 0 && dy != 0) || Math.abs(dx + dy) > 4) err.append("badlink@").append(pl).append(' ');
                }
            }
            if(err.length() > 0) return err.toString();

            int n = ents.size();
            List<List<Integer>> out = new ArrayList<>();
            for(int i = 0; i < n; i++) out.add(new ArrayList<>());
            for(int id = 1; id < n; id++){
                Place pl = ents.get(id);
                switch(pl.kind){
                    case DrillLayoutPlanner.DRILL:
                        for(int i = 0; i < pl.size; i++){
                            int ds = pl.size;
                            int[][] adj = {{pl.u + ds, pl.v + i, 2}, {pl.u - 1, pl.v + i, 0}, {pl.u + i, pl.v + ds, 3}, {pl.u + i, pl.v - 1, 1}};
                            // {x, y, face of the target that looks at the drill}
                            for(int[] a : adj) addIfAccepts(m, ent, ents, out, id, a[0], a[1], a[2], s);
                        }
                        break;
                    case DrillLayoutPlanner.BELT: {
                        int fx = pl.u + DU[pl.dir], fy = pl.v + DV[pl.dir];
                        if(fx == W && pl.v == p.port && pl.dir == 0) out.get(id).add(SINK);
                        else addIfAccepts(m, ent, ents, out, id, fx, fy, (pl.dir + 2) % 4, s);
                        break;
                    }
                    case DrillLayoutPlanner.BRIDGE:
                        if(pl.lu != pl.u || pl.lv != pl.v){
                            int t = ent[pl.lu][pl.lv];
                            if(t == 0 || ents.get(t).kind != DrillLayoutPlanner.BRIDGE) err.append("linkToNothing@").append(pl).append(' ');
                            else out.get(id).add(t);
                        }else{
                            // end bridge: dumps everywhere except toward incoming bridges
                            Set<Integer> inDirs = new HashSet<>();
                            for(Place o : p.places) if(o.kind == DrillLayoutPlanner.BRIDGE && o.lu == pl.u && o.lv == pl.v && !(o.u == pl.u && o.v == pl.v)){
                                inDirs.add(dirOf(o.u - pl.u, o.v - pl.v));
                            }
                            for(int d = 0; d < 4; d++){
                                if(inDirs.contains(d)) continue;
                                addIfAccepts(m, ent, ents, out, id, pl.u + DU[d], pl.v + DV[d], (d + 2) % 4, s);
                            }
                        }
                        break;
                    default: break;
                }
            }

            // every drill outputs; everything a drill can reach can reach the sink; no cycles
            List<List<Integer>> in = new ArrayList<>();
            for(int i = 0; i < n; i++) in.add(new ArrayList<>());
            for(int a = 0; a < n; a++) for(int b : out.get(a)) in.get(b).add(a);
            boolean[] toSink = new boolean[n];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(SINK); toSink[SINK] = true;
            while(!q.isEmpty()) for(int a : in.get(q.poll())) if(!toSink[a]){ toSink[a] = true; q.add(a); }
            boolean[] reach = new boolean[n];
            for(int id = 1; id < n; id++){
                Place pl = ents.get(id);
                if(pl.kind != DrillLayoutPlanner.DRILL) continue;
                if(out.get(id).isEmpty()) err.append("drillNoOutput@").append(pl).append(' ');
                if(!reach[id]){ reach[id] = true; q.add(id); }
            }
            while(!q.isEmpty()) for(int b : out.get(q.poll())) if(!reach[b]){ reach[b] = true; q.add(b); }
            for(int id = 1; id < n; id++) if(reach[id] && !toSink[id]) err.append("stuck@").append(ents.get(id)).append(' ');
            if(hasCycle(out, reach)) err.append("cycle ");

            err.append(waterCheck(m, s, p, ent, ents));

            if(power){
                for(Place d : p.places) if(d.kind == DrillLayoutPlanner.DRILL){
                    boolean pow = false;
                    for(Place nd : p.places) if(nd.kind == DrillLayoutPlanner.NODE && reach(nd.u, nd.v, d.u, d.v, d.size)) pow = true;
                    if(!pow && poweredType(d.type)) err.append("unpowered@").append(d).append(' ');
                }
                if(p.drills > 0 && p.powerGroups > 1) err.append("powerGroups=").append(p.powerGroups).append(' ');
            }
            return err.toString();
        }

        /** Liquid model: every drill flagged wet must be reachable from the water port. */
        static String waterCheck(Map m, int s, DrillLayoutPlanner p, int[][] ent, List<Place> ents){
            if(p.wetDrills == 0) return "";
            int n = ents.size();
            int start = ent[p.waterU][p.waterV];
            if(start == 0 || ents.get(start).kind != DrillLayoutPlanner.LROUTER) return "noWaterPort ";
            boolean[] seen = new boolean[n];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(start); seen[start] = true;
            while(!q.isEmpty()){
                int id = q.poll();
                Place pl = ents.get(id);
                List<int[]> outs = new ArrayList<>(); // {x, y, face}
                switch(pl.kind){
                    case DrillLayoutPlanner.LROUTER:
                        for(int d = 0; d < 4; d++) outs.add(new int[]{pl.u + DU[d], pl.v + DV[d], (d + 2) % 4});
                        break;
                    case DrillLayoutPlanner.CONDUIT:
                        outs.add(new int[]{pl.u + DU[pl.dir], pl.v + DV[pl.dir], (pl.dir + 2) % 4});
                        break;
                    case DrillLayoutPlanner.LBRIDGE:
                        if(pl.lu != pl.u || pl.lv != pl.v) outs.add(new int[]{pl.lu, pl.lv, -1});
                        else{
                            Set<Integer> inDirs = new HashSet<>();
                            for(Place o : p.places) if(o.kind == DrillLayoutPlanner.LBRIDGE && o.lu == pl.u && o.lv == pl.v && !(o.u == pl.u && o.v == pl.v)) inDirs.add(dirOf(o.u - pl.u, o.v - pl.v));
                            for(int d = 0; d < 4; d++) if(!inDirs.contains(d)) outs.add(new int[]{pl.u + DU[d], pl.v + DV[d], (d + 2) % 4});
                        }
                        break;
                    default: continue; // drills don't pass liquid on
                }
                for(int[] o : outs){
                    if(o[0] < 0 || o[1] < 0 || o[0] >= m.W || o[1] >= m.H) continue;
                    int t = ent[o[0]][o[1]];
                    if(t == 0 || seen[t]) continue;
                    Place tp = ents.get(t);
                    boolean ok;
                    switch(tp.kind){
                        case DrillLayoutPlanner.CONDUIT: ok = o[2] != tp.dir; break;
                        case DrillLayoutPlanner.LROUTER: case DrillLayoutPlanner.DRILL: ok = true; break;
                        case DrillLayoutPlanner.LBRIDGE:
                            boolean linkedIn = pl.kind == DrillLayoutPlanner.LBRIDGE && pl.lu == tp.u && pl.lv == tp.v;
                            boolean end = tp.lu == tp.u && tp.lv == tp.v;
                            ok = linkedIn || (!end && o[2] != dirOf(tp.lu - tp.u, tp.lv - tp.v));
                            break;
                        default: ok = false;
                    }
                    if(ok){ seen[t] = true; q.add(t); }
                }
            }
            StringBuilder err = new StringBuilder();
            for(int id = 1; id < n; id++){
                Place pl = ents.get(id);
                if(pl.kind == DrillLayoutPlanner.DRILL && pl.dir == 1 && !seen[id]) err.append("dryButWet@").append(pl).append(' ');
            }
            return err.toString();
        }

        /** source `src` offers an item to whatever is at (x,y); face = side of the target facing the source. */
        static void addIfAccepts(Map m, int[][] ent, List<Place> ents, List<List<Integer>> out, int src, int x, int y, int face, int s){
            if(x < 0 || y < 0 || x >= m.W || y >= m.H) return;
            int t = ent[x][y];
            if(t == 0 || t == src) return;
            Place tp = ents.get(t), sp = ents.get(src);
            boolean ok;
            switch(tp.kind){
                case DrillLayoutPlanner.BELT: ok = face != tp.dir; break; // conveyors refuse items from their front
                case DrillLayoutPlanner.BRIDGE:
                    boolean linkedIn = sp.kind == DrillLayoutPlanner.BRIDGE && sp.lu == tp.u && sp.lv == tp.v;
                    boolean end = tp.lu == tp.u && tp.lv == tp.v;
                    ok = linkedIn || (!end && face != dirOf(tp.lu - tp.u, tp.lv - tp.v));
                    break;
                default: ok = false;
            }
            if(ok && !out.get(src).contains(t)) out.get(src).add(t);
        }

        static boolean hasCycle(List<List<Integer>> out, boolean[] reach){
            int n = out.size();
            int[] state = new int[n];
            for(int i = 0; i < n; i++){
                if(!reach[i] || state[i] != 0) continue;
                Deque<int[]> st = new ArrayDeque<>();
                st.push(new int[]{i, 0}); state[i] = 1;
                while(!st.isEmpty()){
                    int[] top = st.peek();
                    if(top[1] < out.get(top[0]).size()){
                        int b = out.get(top[0]).get(top[1]++);
                        if(state[b] == 1) return true;
                        if(state[b] == 0){ state[b] = 1; st.push(new int[]{b, 0}); }
                    }else{ state[top[0]] = 2; st.pop(); }
                }
            }
            return false;
        }
    }

    static boolean reach(int nu, int nv, int x, int y, int size){
        float r = 6f;
        float x0 = x - 0.5f, x1 = x + size - 0.5f, y0 = y - 0.5f, y1 = y + size - 0.5f;
        float dx = Math.max(0, Math.max(x0 - nu, nu - x1)), dy = Math.max(0, Math.max(y0 - nv, nv - y1));
        return dx * dx + dy * dy <= r * r;
    }

    /** Drill.countOre order: (not lowPriority, count, id). Items: 0 copper, 1 lead, 5 sand(low). */
    static int oreRule(){
        int[][][] cases = {
            // 2x2 footprint {a,b,c,d}, target, expected ore count
            {{5, 5, 5, 0}, {0}, {1}},   // 3 sand + 1 copper: copper wins (sand is low priority)
            {{5, 5, 5, 0}, {5}, {0}},
            {{0, 0, 1, 1}, {1}, {2}},   // tie: higher id (lead) wins
            {{0, 0, 1, 1}, {0}, {0}},
            {{0, 0, 0, 1}, {0}, {3}},
            {{5, 5, -1, -1}, {5}, {2}}, // only sand: sand is mined
            {{1, 0, 5, 5}, {1}, {1}},   // lead/copper tie at 1, sand ignored -> lead
        };
        int bad = 0;
        boolean[] low = new boolean[8];
        low[5] = true;
        for(int[][] c : cases){
            Params pa = new Params();
            pa.W = 4; pa.H = 3; pa.drillSize = 2; pa.target = c[1][0]; pa.lowPriority = low;
            pa.drop = new int[4][3];
            for(int[] row : pa.drop) Arrays.fill(row, -1);
            pa.drop[0][0] = c[0][0]; pa.drop[1][0] = c[0][1]; pa.drop[0][1] = c[0][2]; pa.drop[1][1] = c[0][3];
            pa.drillOk = new boolean[4][3]; pa.drillOk[0][0] = true;
            int got = new DrillLayoutPlanner(pa).oreUnder(0, 0);
            if(got != c[2][0]){ System.out.println("ore rule FAIL " + Arrays.toString(c[0]) + " target " + c[1][0] + ": got " + got + " want " + c[2][0]); bad++; }
        }
        System.out.println("ore rule: " + (cases.length - bad) + "/" + cases.length + " ok");
        return bad;
    }

    /** Fixed irregular ore field: lanes only vs with bridges, both modes. */
    static void compare(){
        Map m = new Map();
        int W = m.W = 40, H = m.H = 30;
        m.belt = new boolean[W][H];
        m.drop = new int[W][H];
        Random r = new Random(42);
        for(int x = 0; x < W; x++) for(int y = 0; y < H; y++){
            m.belt[x][y] = true;
            double d = Math.hypot((x - 18) / 1.6, y - 15) + 3 * Math.sin(x * 0.7) + 2 * Math.cos(y * 0.9);
            m.drop[x][y] = d < 11 && r.nextFloat() < 0.9 ? 1 : -1;
        }
        int total = 0; for(int[] c : m.drop) for(int v : c) if(v == 1) total++;
        for(int s = 2; s <= 4; s++){
            m.drill = drillOk(m, s);
            for(Mode mode : Mode.values()){
                for(int variant = 0; variant < (s > 2 ? 4 : 3); variant++){
                    boolean br = variant > 0;
                    Params pa = params(m, s, mode, s > 2, br, variant == 2 ? 2 : 0, H / 2);
                    if(variant == 3) addTypes(pa, m, s, true);
                    typePower0 = s > 2;
                    DrillLayoutPlanner p = new DrillLayoutPlanner(pa);
                    long t = System.nanoTime();
                    p.plan();
                    String errs = FlowCheck.check(m, s, p, s > 2);
                    double eff = 0;
                    for(Place pl : p.places) if(pl.kind == DrillLayoutPlanner.DRILL) eff += pl.ore * (pl.dir == 1 ? 2.56 : 1);
                    System.out.printf("compare s=%d %-10s %-12s ore %3d/%d (%2.0f%%) output=%5.0f drills=%3d wet=%3d belts=%3d bridges=%3d water=%3d nodes=%2d %-24s %4dms %s%n",
                        s, mode, variant == 0 ? "belts" : variant == 1 ? "+bridges" : variant == 2 ? "+water" : "+bridges+mix", p.oreTiles, total, 100.0 * p.oreTiles / total, eff,
                        p.drills, p.wetDrills, p.belts, p.bridges, p.waterTiles, p.nodes, p.layout, (System.nanoTime() - t) / 1_000_000, errs.isEmpty() ? "" : "ERR " + errs);
                    if(s == 2 && mode == Mode.OUTPUT && variant == Integer.getInteger("showcompare", -1)){ System.out.println("routed=" + p.segmentsRouted + " dropped=" + p.segmentsDropped); print(m, s, p); }
                }
            }
        }
    }

    /**
     * Regression for the screenshot case: ore clusters on both sides of a long wall that cuts the
     * water rows; open ground lets water go around. Drills beyond the wall must still get water.
     */
    static int waterAroundWall(){
        Map m = new Map();
        int W = m.W = 40, H = m.H = 30;
        m.belt = new boolean[W][H];
        m.drop = new int[W][H];
        for(int x = 0; x < W; x++) for(int y = 0; y < H; y++){
            m.belt[x][y] = !(x >= 12 && x <= 22 && y <= 17); // wall from the bottom edge up, cutting rows 0..17
            boolean ore = (x >= 3 && x <= 10 && y >= 4 && y <= 14) || (x >= 25 && x <= 35 && y >= 4 && y <= 14);
            m.drop[x][y] = ore && m.belt[x][y] ? 1 : -1;
        }
        m.drill = drillOk(m, 2);
        DrillLayoutPlanner p = new DrillLayoutPlanner(params(m, 2, Mode.OUTPUT, false, false, 1, 5));
        p.plan();
        String errs = FlowCheck.check(m, 2, p, false);
        int far = 0, farWet = 0;
        for(Place pl : p.places) if(pl.kind == DrillLayoutPlanner.DRILL && pl.u > 22){ far++; if(pl.dir == 1) farWet++; }
        boolean ok = errs.isEmpty() && far > 0 && farWet * 10 >= far * 8;
        System.out.println("water around wall: wet=" + p.wetDrills + "/" + p.drills + ", beyond wall " + farWet + "/" + far + (ok ? " ok" : " FAIL " + errs));
        if(!ok || System.getProperty("showwall") != null) print(m, 2, p);
        return ok ? 0 : 1;
    }

    /**
     * Screenshot case (v0.6.0 feedback): a long region with ore clusters at both ends. Reports how
     * many tiles the logistics (belts, bridges, water) occupy per drill.
     */
    static void logisticsEfficiency(){
        String[] names = {"far clusters", "far clusters + water", "irregular field", "irregular field + water"};
        for(int c = 0; c < 4; c++){
            Map m = new Map();
            int W, H;
            Random r = new Random(7);
            if(c < 2){
                W = m.W = 60; H = m.H = 14;
                m.belt = new boolean[W][H]; m.drop = new int[W][H];
                for(int x = 0; x < W; x++) for(int y = 0; y < H; y++){
                    m.belt[x][y] = true;
                    boolean ore = ((x >= 2 && x <= 9) || (x >= 44 && x <= 52)) && y >= 2 && y <= 11 && r.nextFloat() < 0.8f;
                    m.drop[x][y] = ore ? 1 : -1;
                }
            }else{
                W = m.W = 40; H = m.H = 30;
                m.belt = new boolean[W][H]; m.drop = new int[W][H];
                for(int x = 0; x < W; x++) for(int y = 0; y < H; y++){
                    m.belt[x][y] = true;
                    double d = Math.hypot((x - 18) / 1.6, y - 15) + 3 * Math.sin(x * 0.7) + 2 * Math.cos(y * 0.9);
                    m.drop[x][y] = d < 11 && r.nextFloat() < 0.9 ? 1 : -1;
                }
            }
            m.drill = drillOk(m, 2);
            DrillLayoutPlanner p = new DrillLayoutPlanner(params(m, 2, Mode.OUTPUT, false, true, c % 2 == 1 ? 2 : 0, H / 2));
            p.plan();
            String errs = FlowCheck.check(m, 2, p, false);
            double eff = 0;
            for(Place pl : p.places) if(pl.kind == DrillLayoutPlanner.DRILL) eff += pl.ore * (pl.dir == 1 ? 2.56 : 1);
            int logistic = p.belts + p.bridges + p.waterTiles;
            System.out.printf("logistics %-24s drills=%3d wet=%3d output=%5.0f belts=%3d bridges=%3d water=%3d -> %.2f tiles/drill, %.2f tiles per output %s%n",
                names[c], p.drills, p.wetDrills, eff, p.belts, p.bridges, p.waterTiles, (double)logistic / Math.max(1, p.drills), logistic / Math.max(1, eff),
                errs.isEmpty() ? "" : "ERR " + errs);
            if(System.getProperty("showlog") != null && Integer.getInteger("showlog") == c) print(m, 2, p);
        }
    }

    /** Output with smaller filler drills vs the chosen drill alone, over the random maps. */
    static void mixGain(){
        for(int s = 3; s <= 4; s++){
            double base = 0, mixed = 0;
            int better = 0, worse = 0, n = 0;
            for(int seed = 0; seed < 120; seed++){
                Map m = randomMap(seed, s);
                Params a = params(m, s, Mode.OUTPUT, false, true);
                Params b = params(m, s, Mode.OUTPUT, false, true);
                addTypes(b, m, s, false);
                DrillLayoutPlanner pa = new DrillLayoutPlanner(a), pb = new DrillLayoutPlanner(b);
                if(!pa.plan() || !pb.plan()) continue;
                double oa = output(pa, b.typeRate), ob = output(pb, b.typeRate);
                base += oa; mixed += ob; n++;
                if(ob > oa * 1.01) better++;
                if(ob < oa * 0.99) worse++;
            }
            System.out.printf("mix s=%d: output %+.1f%% over %d maps (better on %d, worse on %d)%n", s, 100 * (mixed / base - 1), n, better, worse);
        }
    }

    static double output(DrillLayoutPlanner p, double[] rate){
        double o = 0;
        for(Place pl : p.places) if(pl.kind == DrillLayoutPlanner.DRILL) o += pl.ore * rate[pl.type];
        return o;
    }
}
