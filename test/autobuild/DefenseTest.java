package autobuild;

import autobuild.plan.*;
import autobuild.plan.DefensePlanner.*;
import autobuild.plan.GridRouter;

import java.util.*;

/**
 * Offline checks of the defense planner on random maps (no game needed): an enemy spawn on the
 * left, a core on the right, rocks in between, a region somewhere along the route.
 * Invariants: no overlaps, nothing solid on the corridor, every turret supplied (item / liquid flow
 * simulated with the game's push rules, no mixing between networks, no leaking conduit), every
 * powered block reached by a linked node, enemy route unchanged.
 */
public class DefenseTest{
    static int failures;

    public static void main(String[] args){
        int ok = 0, n = 0;
        long time = 0;
        double sumG = 0, sumCov = 0;
        for(int seed = 0; seed < 60; seed++){
            for(int variant = 0; variant < 2; variant++){
                Case c = new Case(seed, variant);
                long t0 = System.currentTimeMillis();
                DefensePlanner pl = new DefensePlanner(c.p);
                boolean res = pl.plan();
                time += System.currentTimeMillis() - t0;
                n++;
                if(!res){
                    System.out.println("seed " + seed + " v" + variant + " " + c.desc + " FAILED: " + pl.failure);
                    continue;
                }
                String err = check(c, pl);
                if(err != null){
                    failures++;
                    System.out.println("seed " + seed + " v" + variant + " " + c.desc + " INVARIANT: " + err);
                    print(c, pl);
                    continue;
                }
                ok++;
                sumG += pl.meanGroundDps; sumCov += pl.coveredGround;
                if(seed < 3 && variant == 0){
                    System.out.println(String.format("seed %d %s turrets=%d walls=%d prot=%d pipes=%d bridges=%d nodes=%d mines=%d dps=%.0f cov=%.0f%% changed=%b",
                        seed, c.desc, pl.turrets, pl.walls, pl.protectors, pl.pipes, pl.bridges, pl.nodes, pl.mines, pl.meanGroundDps, pl.coveredGround * 100, pl.routeChanged));
                    print(c, pl);
                }
            }
        }
        System.out.println(String.format("defense: %d/%d planned and valid, mean route DPS %.0f, route covered %.0f%%, %d ms per plan",
            ok, n, sumG / Math.max(1, ok), 100 * sumCov / Math.max(1, ok), time / Math.max(1, n)));
        if(failures > 0){ System.out.println("DEFENSE FAILURES: " + failures); System.exit(1); }
        System.out.println("DEFENSE OK");
    }

    /** a random map with a spawn, a core, rocks; a region on the route; a mix of turrets */
    public static class Case{
        public Params p = new Params();
        boolean[] rock;
        String desc;
        int mw = 110, mh = 70;

        public Case(int seed, int variant){
            Random r = new Random(seed);
            rock = new boolean[mw * mh];
            for(int i = 0; i < 40; i++){
                int cx = r.nextInt(mw), cy = r.nextInt(mh), rad = 1 + r.nextInt(5);
                for(int x = cx - rad; x <= cx + rad; x++) for(int y = cy - rad; y <= cy + rad; y++){
                    if(x >= 0 && y >= 0 && x < mw && y < mh && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) rock[x + y * mw] = true;
                }
            }
            int sx = 3, sy = 10 + r.nextInt(mh - 20), cx = mw - 5, cy = 10 + r.nextInt(mh - 20);
            for(int x = -3; x <= 3; x++) for(int y = -3; y <= 3; y++){ rock[sx + x + (sy + y) * mw] = false; rock[cx + x + (cy + y) * mw] = false; }
            p.mapW = mw; p.mapH = mh;
            p.pathCost = new int[mw * mh];
            p.nearSolid = new boolean[mw * mh];
            for(int i = 0; i < mw * mh; i++){
                int x = i % mw, y = i / mw;
                if(rock[i]){ p.pathCost[i] = -1; continue; }
                boolean near = false;
                for(int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}){
                    int nx = x + d[0], ny = y + d[1];
                    if(nx >= 0 && ny >= 0 && nx < mw && ny < mh && rock[nx + ny * mw]) near = true;
                }
                p.nearSolid[i] = near;
                p.pathCost[i] = 1 + (near ? 2 : 0);
            }
            p.targets = new int[]{cx + cy * mw};
            p.groundSpawns = new int[]{sx + sy * mw};
            p.airLines = new float[][]{{sx, sy, cx, cy}};
            p.groundWeight = 0.7; p.airWeight = 0.3;
            p.enemyRange = 7;
            // region: around the middle of the route
            EnemyRoutes er = new EnemyRoutes(mw, mh, p.pathCost, p.nearSolid);
            er.flow(p.targets);
            int[] route = er.trace(p.groundSpawns[0]);
            int mid = route.length == 0 ? sx + sy * mw : route[route.length * (3 + r.nextInt(4)) / 10];
            int rw = 22 + r.nextInt(14), rh = 18 + r.nextInt(14);
            int ox = Math.max(0, Math.min(mw - rw, mid % mw - rw / 2 + r.nextInt(9) - 4)), oy = Math.max(0, Math.min(mh - rh, mid / mw - rh / 2 + r.nextInt(9) - 4));
            p.ox = ox; p.oy = oy; p.W = rw; p.H = rh;
            p.free = new boolean[rw][rh];
            p.nearForeignItems = new boolean[rw][rh];
            p.nearForeignLiquids = new boolean[rw][rh];
            p.nodeOk = new boolean[rw][rh];
            for(int u = 0; u < rw; u++) for(int v = 0; v < rh; v++){
                p.free[u][v] = !rock[ox + u + (oy + v) * mw];
                p.nodeOk[u][v] = p.free[u][v];
            }
            List<Type> types = new ArrayList<>();
            boolean liquid = seed % 3 != 0, power = seed % 4 != 1, two = seed % 5 == 2;
            types.add(turret("duo", 1, 13.75f, 0, 25, 25, 0, false, false, 1.3, false, 35));
            types.add(turret("hail", 1, 29f, 0, 14, 0, 0, false, false, 1.2, false, 40));
            if(two) types.add(turret("scatter", 2, 27.5f, 0, 0, 60, 1, false, false, 1.3, false, 85));
            if(power) types.add(turret("lancer", 2, 20.6f, 0, 70, 0, -1, false, false, 1, true, 120));
            if(liquid) types.add(turret("wave", 2, 13.75f, 0, 5, 5, -1, true, false, 1, false, 60));
            if(liquid && power && seed % 2 == 0) types.add(turret("meltdown", 4, 23f, 0, 600, 600, -1, false, true, 1, true, 1500));
            Type w1 = new Type(); w1.kind = DefensePlanner.WALL; w1.size = 1; w1.price = 6; w1.pathCost = 40; w1.name = "wall";
            Type w2 = new Type(); w2.kind = DefensePlanner.WALL; w2.size = 2; w2.price = 24; w2.pathCost = 160; w2.name = "wall-large";
            types.add(w1); types.add(w2);
            if(power){
                Type m = new Type(); m.kind = DefensePlanner.MENDER; m.size = 1; m.range = 5; m.price = 20; m.powered = true; m.strength = 1; m.pathCost = 10; m.name = "mender";
                Type f = new Type(); f.kind = DefensePlanner.SHIELD; f.size = 3; f.range = 12.7f; f.price = 150; f.powered = true; f.strength = 2; f.pathCost = 40; f.name = "force";
                types.add(m); types.add(f);
            }
            Type mine = new Type(); mine.kind = DefensePlanner.MINE; mine.size = 1; mine.price = 10; mine.solid = false; mine.name = "mine";
            types.add(mine);
            p.mines = seed % 2 == 1;
            p.types = types.toArray(new Type[0]);
            p.fits = new boolean[p.types.length][rw][rh];
            for(int t = 0; t < p.types.length; t++){
                int s = p.types[t].size;
                for(int u = 0; u + s <= rw; u++) for(int v = 0; v + s <= rh; v++){
                    boolean f = true;
                    for(int i = 0; i < s && f; i++) for(int j = 0; j < s; j++) if(!p.free[u + i][v + j]){ f = false; break; }
                    p.fits[t][u][v] = f;
                }
            }
            List<Net> nets = new ArrayList<>();
            Net ammo = new Net(); ammo.auto = true; ammo.bridgeRange = 4; nets.add(ammo);
            if(two){ Net b = new Net(); b.auto = true; b.bridgeRange = 4; nets.add(b); }
            if(liquid){ Net l = new Net(); l.liquid = true; l.auto = true; l.bridgeRange = 4; nets.add(l); }
            p.nets = nets.toArray(new Net[0]);
            p.powerAuto = power;
            p.clearance = 1 + seed % 2;
            p.wallThickness = seed % 3;
            p.maxTurrets = 6 + seed % 10;
            p.variant = variant;
            p.seed = seed;
            p.nodePathCost = 5;
            p.logisticsPathCost = new int[][]{{0, 5, 5, 5, 5}, {0, 5, 5, 5, 0}};
            desc = rw + "x" + rh + (liquid ? " liquid" : "") + (power ? " power" : "") + (two ? " 2ammo" : "") + " clr=" + p.clearance + " wall=" + p.wallThickness;
        }

        Type turret(String name, int size, float range, float minRange, double g, double a, int ammo, boolean liquidAmmo, boolean coolReq, double cool, boolean powered, double price){
            Type t = new Type();
            t.kind = DefensePlanner.TURRET; t.size = size; t.range = range; t.minRange = minRange; t.dpsGround = g; t.dpsAir = a;
            t.ammoNet = ammo; t.liquidAmmo = liquidAmmo; t.coolantRequired = coolReq; t.coolantBoost = cool; t.powered = powered; t.price = price;
            t.pathCost = 20; t.name = name;
            return t;
        }
    }

    // ------------------------------------------------------------------ checks

    static String check(Case c, DefensePlanner pl){
        Params p = c.p;
        int W = p.W, H = p.H;
        String[][] at = new String[W][H];
        int[][] block = new int[W][H];
        for(int[] r : block) Arrays.fill(r, -1);
        // footprints
        for(int i = 0; i < pl.places.size(); i++){
            Place q = pl.places.get(i);
            int s = q.kind == DefensePlanner.BLOCK ? p.types[q.type].size : 1;
            for(int x = q.u; x < q.u + s; x++) for(int y = q.v; y < q.v + s; y++){
                if(x < 0 || y < 0 || x >= W || y >= H) return "outside the region: " + q;
                if(block[x][y] >= 0) return "overlap at " + x + "," + y + ": " + pl.places.get(block[x][y]) + " and " + q;
                if(!p.free[x][y]) return "on an unbuildable tile: " + q;
                block[x][y] = i;
            }
        }
        // corridor: only mines on route tiles; nothing within clearance
        Set<Integer> route = new HashSet<>();
        for(int[] r : pl.groundRoutes) for(int t : r) route.add(t);
        for(int i = 0; i < pl.places.size(); i++){
            Place q = pl.places.get(i);
            boolean mine = q.kind == DefensePlanner.BLOCK && p.types[q.type].kind == DefensePlanner.MINE;
            int s = q.kind == DefensePlanner.BLOCK ? p.types[q.type].size : 1;
            for(int x = q.u; x < q.u + s; x++) for(int y = q.v; y < q.v + s; y++){
                int dmin = Integer.MAX_VALUE;
                for(int t : route) dmin = Math.min(dmin, Math.max(Math.abs(t % p.mapW - p.ox - x), Math.abs(t / p.mapW - p.oy - y)));
                if(mine ? dmin != 0 : dmin <= p.clearance) return "in the corridor (d=" + dmin + "): " + q;
            }
        }
        if(pl.routeChanged) return "the enemy route changed";
        // supply
        String e = flows(c, pl, block);
        if(e != null) return e;
        // power
        return power(c, pl);
    }

    static final int[] DX = {1, 0, -1, 0}, DY = {0, 1, 0, -1};

    /** simulates every network with the game's push rules */
    static String flows(Case c, DefensePlanner pl, int[][] block){
        Params p = c.p;
        int W = p.W, H = p.H;
        for(int k = 0; k < p.nets.length; k++){
            Net net = p.nets[k];
            boolean[][] reached = new boolean[W][H];
            ArrayDeque<int[]> q = new ArrayDeque<>();
            // the port: items enter the port tile's block (edge: from outside)
            for(int[] s : net.starts){
                if(s[0] < 0 || s[1] < 0 || s[0] >= W || s[1] >= H || block[s[0]][s[1]] < 0) continue;
                Place b = pl.places.get(block[s[0]][s[1]]);
                if(b.kind == DefensePlanner.BLOCK || b.kind == DefensePlanner.NODE) continue;
                if(b.type != k) return "net " + k + " port tile holds another network's block";
                reached[s[0]][s[1]] = true; q.add(new int[]{s[0], s[1]});
            }
            Set<Integer> fedBlocks = new HashSet<>();
            while(!q.isEmpty()){
                int[] cur = q.poll();
                Place b = pl.places.get(block[cur[0]][cur[1]]);
                List<int[]> outs = new ArrayList<>();
                switch(b.kind){
                    case GridRouter.PIPE: outs.add(new int[]{b.u + DX[b.dir], b.v + DY[b.dir], b.dir}); break;
                    case GridRouter.BRIDGE: outs.add(new int[]{b.lu, b.lv, -1}); break;
                    default: // router, end bridge, unloader push into every neighbour
                        for(int d = 0; d < 4; d++){
                            if(b.kind == GridRouter.END && incoming(pl, b) == d) continue;
                            outs.add(new int[]{b.u + DX[d], b.v + DY[d], d});
                        }
                }
                for(int[] o : outs){
                    if(o[0] < 0 || o[1] < 0 || o[0] >= W || o[1] >= H){
                        if(b.kind == GridRouter.PIPE && net.liquid) return "conduit leaks out of the region at " + b;
                        continue;
                    }
                    int bi = block[o[0]][o[1]];
                    if(bi < 0){
                        if(b.kind == GridRouter.PIPE) return (net.liquid ? "conduit leaks" : "belt ends") + " into nothing at " + b;
                        continue;
                    }
                    Place t = pl.places.get(bi);
                    if(t.kind == DefensePlanner.BLOCK){
                        fedBlocks.add(bi);
                        if(b.kind == GridRouter.PIPE){
                            Built tb = null;
                            for(Built x : pl.built) if(x.u == t.u && x.v == t.v) tb = x;
                            Type tt = p.types[t.type];
                            boolean consumer = tb != null && (tt.ammoNet == k || net.liquid && (tt.liquidAmmo || tt.coolantRequired || tb.cooled));
                            if(!consumer) return "pipe points into " + tt.name + " that doesn't use network " + k + ": " + b;
                        }
                        continue;
                    }
                    if(t.kind == DefensePlanner.NODE) continue;
                    if(p.nets[t.type].liquid != net.liquid) continue;
                    if(t.type != k) return "network " + k + " pushes into network " + t.type + " at " + t;
                    // a pipe doesn't take from its front
                    if(t.kind == GridRouter.PIPE && o[2] >= 0 && (t.dir + 2) % 4 == o[2]) {
                        if(b.kind == GridRouter.PIPE) return "belt points into the front of " + t;
                        continue;
                    }
                    // a bridge doesn't take from its link side / an end bridge from non-bridges is fine here
                    if(!reached[o[0]][o[1]]){ reached[o[0]][o[1]] = true; q.add(new int[]{o[0], o[1]}); }
                }
            }
            for(Built bt : pl.built){
                Type t = p.types[bt.type];
                boolean needs = t.ammoNet == k || net.liquid && (t.liquidAmmo || t.coolantRequired || bt.cooled);
                if(!needs) continue;
                int bi = block[bt.u][bt.v];
                if(!fedBlocks.contains(bi)) return t.name + " at " + bt.u + "," + bt.v + " not fed by network " + k;
            }
            for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
                if(block[u][v] < 0) continue;
                Place b = pl.places.get(block[u][v]);
                if(b.kind < DefensePlanner.BLOCK && b.type == k && !reached[u][v]) return "network block not reached from the port: " + b;
            }
        }
        return null;
    }

    static int incoming(DefensePlanner pl, Place end){
        for(Place s : pl.places) if(s.kind == GridRouter.BRIDGE && s.lu == end.u && s.lv == end.v && s.type == end.type) return (s.dir + 2) % 4;
        return -1;
    }

    static String power(Case c, DefensePlanner pl){
        Params p = c.p;
        List<int[]> nodes = new ArrayList<>();
        for(Place q : pl.places) if(q.kind == DefensePlanner.NODE) nodes.add(new int[]{q.u, q.v});
        boolean anyPowered = false;
        for(Built b : pl.built) anyPowered |= p.types[b.type].powered;
        if(!anyPowered) return nodes.isEmpty() ? null : "nodes without powered blocks";
        if(nodes.isEmpty()) return "powered blocks but no node";
        PowerNetwork pn = new PowerNetwork(p.W, p.H, p.nodeRange, p.nodeMaxLinks, null);
        // every group is linked to a port: a port node, an extra port the planner marked
        List<int[]> ports = new ArrayList<>(pl.extraPowerPorts);
        if(pl.chosenPowerPort != null) ports.add(pl.chosenPowerPort);
        for(int[] pp : p.powerPorts) ports.add(pp);
        int[] comp = new int[nodes.size()];
        Arrays.fill(comp, -1);
        int groups = 0;
        for(int i = 0; i < nodes.size(); i++){
            if(comp[i] >= 0) continue;
            ArrayDeque<Integer> g = new ArrayDeque<>();
            g.add(i); comp[i] = groups;
            boolean port = false;
            while(!g.isEmpty()){
                int a = g.poll();
                for(int[] pp : ports) if(pp[0] == nodes.get(a)[0] && pp[1] == nodes.get(a)[1]) port = true;
                for(int b = 0; b < nodes.size(); b++) if(comp[b] < 0 && pn.reaches(nodes.get(a)[0], nodes.get(a)[1], nodes.get(b)[0], nodes.get(b)[1], 1)){ comp[b] = groups; g.add(b); }
            }
            if(!port) return "power group without a port";
            groups++;
        }
        for(Built b : pl.built){
            Type t = p.types[b.type];
            if(!t.powered || !b.powered) continue;
            boolean r = false;
            for(int[] n : nodes) if(pn.reaches(n[0], n[1], b.u, b.v, t.size)){ r = true; break; }
            if(!r) return t.name + " at " + b.u + "," + b.v + " not reached by a node";
        }
        return null;
    }

    static void print(Case c, DefensePlanner pl){
        Params p = c.p;
        char[][] g = new char[p.W][p.H];
        for(int u = 0; u < p.W; u++) for(int v = 0; v < p.H; v++) g[u][v] = p.free[u][v] ? '.' : '#';
        for(int[] r : pl.groundRoutes) for(int t : r){
            int u = t % p.mapW - p.ox, v = t / p.mapW - p.oy;
            if(u >= 0 && v >= 0 && u < p.W && v < p.H) g[u][v] = '~';
        }
        char[] pipes = {'>', '^', '<', 'v'}, lpipes = {'}', '\'', '{', ','};
        for(Place q : pl.places){
            if(q.kind == DefensePlanner.BLOCK){
                Type t = p.types[q.type];
                char ch = t.kind == DefensePlanner.WALL ? '=' : t.kind == DefensePlanner.MINE ? 'x' : t.kind == DefensePlanner.MENDER ? 'm' : t.kind == DefensePlanner.SHIELD ? 'F' : Character.toUpperCase(t.name.charAt(0));
                for(int i = 0; i < t.size; i++) for(int j = 0; j < t.size; j++) g[q.u + i][q.v + j] = ch;
            }else if(q.kind == DefensePlanner.NODE) g[q.u][q.v] = '*';
            else{
                boolean liq = p.nets[q.type].liquid;
                g[q.u][q.v] = q.kind == GridRouter.PIPE ? (liq ? lpipes[q.dir] : pipes[q.dir])
                    : q.kind == GridRouter.ROUTER ? (liq ? 'O' : 'R') : q.kind == GridRouter.SOURCE ? 'U' : (liq ? 'b' : 'B');
            }
        }
        for(int v = p.H - 1; v >= 0; v--){
            StringBuilder sb = new StringBuilder("  |");
            for(int u = 0; u < p.W; u++) sb.append(g[u][v]);
            System.out.println(sb.append('|'));
        }
    }
}
