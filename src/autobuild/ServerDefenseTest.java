package autobuild;

import arc.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.*;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;

import static mindustry.Vars.*;

/**
 * Dedicated-server commands for checking the defense planner in the real game:
 * <pre>
 * abroute                       spawns, cores, the ground route (our model vs the game's flow field)
 * abdef &lt;turrets&gt; &lt;ammo|-&gt; [options]
 *     plans a region on the enemy route and places it instantly; sources at the ports
 *     options: size=N at=FRACTION liquid=NAME wall=NAME|none thick=N mender mendp force mines clr=N max=N nobuild
 * abdefcheck                    turrets with ammo / power / coolant; the game's route vs the prediction
 * abwave &lt;waves&gt;                spawns waves now and counts kills and losses since
 * </pre>
 */
public class ServerDefenseTest{
    static DefenseJob job;
    static DefenseJob.Result result;
    static int killed, killedNear, lost, peak;
    static boolean watching;
    static final IntSet seen = new IntSet();
    static final ObjectIntMap<String> seenTypes = new ObjectIntMap<>(), killedTypes = new ObjectIntMap<>();
    static final IntMap<String> lastPos = new IntMap<>();
    static float coreHealth;
    static boolean listening;

    static void register(CommandHandler handler){
        handler.register("abroute", "AutoBuild: enemy routes, our model vs the game", args -> {
            if(!state.isGame()){ Log.err("host a map first"); return; }
            DefenseJob j = new DefenseJob();
            j.team = state.rules.defaultTeam;
            j.setRegion(0, 0, world.width() - 1, world.height() - 1);
            Log.info("[abroute] spawns @, wave team @, @ route(s); @", spawner.getSpawns().size, state.rules.waveTeam, j.previewRoutes.size, Strings.stripColors(j.threatText));
            for(int[] r : j.previewRoutes) Log.info("[abroute] model route: @ tiles from (@,@) to (@,@)", r.length, r[0] % world.width(), r[0] / world.width(),
                r[r.length - 1] % world.width(), r[r.length - 1] / world.width());
            compare(j.previewRoutes);
        });

        handler.register("abdef", "<turrets> <ammo> [options...]", "AutoBuild: plan and place a defense on the enemy route. turrets: duo,hail,... ammo: graphite,... or -", args -> {
            if(!state.isGame()){ Log.err("host a map first"); return; }
            DefenseJob j = new DefenseJob();
            j.defaults();
            j.turrets.clear(); j.ammo.clear();
            j.mender = false;
            for(String n : args[0].split(",")){
                Block b = content.block(n);
                if(!(b instanceof Turret t)){ Log.err("not a turret: @", n); return; }
                j.turrets.add(t);
            }
            if(!args[1].equals("-")) for(String n : args[1].split(",")){
                Item i = content.item(n);
                if(i == null){ Log.err("no item @", n); return; }
                j.ammo.add(i);
            }
            int size = 30;
            float at = 0.5f;
            boolean build = true;
            Seq<String> opts = new Seq<>();
            for(int i = 2; i < args.length; i++) for(String o : args[i].trim().split("\\s+")) if(!o.isEmpty()) opts.add(o);
            for(String opt : opts){
                String[] kv = opt.split("=", 2);
                switch(kv[0]){
                    case "size": size = Strings.parseInt(kv[1], 30); break;
                    case "at": at = kv[1].equals("auto") ? -1f : Strings.parseFloat(kv[1], 0.5f); break;
                    case "liquid": j.liquid = content.liquid(kv[1]); break;
                    case "nocool": j.coolant = false; break;
                    case "wall": j.wall = kv[1].equals("none") ? null : (Wall)content.block(kv[1]); break;
                    case "thick": j.wallThickness = Strings.parseInt(kv[1], 1); break;
                    case "mender": j.mender = true; break;
                    case "mendp": j.mendProjector = true; break;
                    case "force": j.forceProjector = true; break;
                    case "mines": j.mines = true; break;
                    case "clr": j.clearance = Strings.parseInt(kv[1], 1); break;
                    case "max": j.maxTurrets = Strings.parseInt(kv[1], 10); break;
                    case "nobuild": build = false; break;
                    default: Log.err("unknown option @", opt); return;
                }
            }
            j.team = state.rules.defaultTeam;
            // region: centred on the route, `at` of the way from the spawn
            j.setRegion(0, 0, world.width() - 1, world.height() - 1);
            if(j.previewRoutes.isEmpty()){ Log.err("[abdef] no ground route on this map"); return; }
            int[] route = j.previewRoutes.max(r -> r.length);
            if(at < 0) Log.info("[abdef] at=@ (most open ground)", Strings.autoFixed(at = j.openStretch(route, size), 3));
            j.regionOnRoute(route, at, size);
            int ax = j.ax, ay = j.ay;
            Log.info("[abdef] region (@,@)-(@,@) on a @-tile route; @", ax, ay, ax + size - 1, ay + size - 1, route.length, Strings.stripColors(j.threatText));
            DefenseJob.Snapshot sn = j.prepare();
            if(sn == null){ Log.err("[abdef] @", j.error); return; }
            PlanResult res = j.search(sn, 0, 3, null);
            if(res == null || !res.ok()){ Log.err("[abdef] plan failed: @", res == null ? "-" : res.error); return; }
            DefenseJob.Result r = (DefenseJob.Result)res;
            job = j; result = r;
            Log.info("[abdef] @ | @", Strings.stripColors(r.summary()).replace('\n', ' '), r.portText());
            dump(j, r);
            if(!build){ Log.info("[abdef] not built (nobuild)"); return; }
            // ports that get a power source instead of a node
            IntSet powerPorts = new IntSet();
            for(Port pt : r.ports(j.powerKind)){
                int[] t = j.portTile(pt);
                powerPorts.add(Point2.pack(t[0], t[1]));
            }
            boolean anyNode = false;
            for(BuildPlan p : r.plans){
                Block b = p.block;
                if(b == sn.node && powerPorts.contains(Point2.pack(p.x, p.y))){ b = Blocks.powerSource; anyNode = true; }
                Tile t = world.tile(p.x, p.y);
                t.setBlock(b, j.team, p.rotation);
                // same sequence as ConstructBlock.constructFinish for a player-built plan
                if(t.build != null){
                    t.build.placed();
                    if(p.config != null) t.build.configured(null, p.config);
                    t.build.playerPlaced(p.config);
                }
            }
            if(!anyNode && j.powerUsed()) Log.err("[abdef] no node at a power port");
            // sources outside the input ports
            for(int k = 0; k < sn.netKinds.size; k++){
                Item item = sn.netItems.get(k);
                for(Port pt : r.ports(sn.netKinds.get(k))){
                    if(!pt.edge){ Log.err("[abdef] interior port not supported by the test"); continue; }
                    int[] t = j.edgeTile(pt.side, pt.frac);
                    Tile st = world.tile(t[0] + Geometry.d4x(pt.side), t[1] + Geometry.d4y(pt.side));
                    if(st == null || st.build != null || st.solid()){ Log.err("[abdef] no room for a source at port @", pt.name()); continue; }
                    st.setBlock(item == null ? Blocks.liquidSource : Blocks.itemSource, j.team, 0);
                    st.build.configured(null, item == null ? sn.liquid : item);
                    Log.info("[abdef] @ source at (@,@)", item == null ? sn.liquid.name : item.name, st.x, st.y);
                }
            }
            Log.info("[abdef] placed @ blocks", r.plans.size);
        });

        handler.register("abdefcheck", "AutoBuild: status of the placed defense", args -> {
            if(result == null){ Log.err("run abdef first"); return; }
            int turrets = 0, armed = 0, powered = 0, needPower = 0, cooled = 0, coolable = 0;
            for(BuildPlan p : result.plans){
                Tile t = world.tile(p.x, p.y);
                if(t != null && t.block() == Blocks.powerSource) continue;
                if(t == null || t.build == null || t.block() != p.block){ Log.err("[abdefcheck] missing @ at (@,@)", p.block.name, p.x, p.y); continue; }
                if(!(t.build instanceof Turret.TurretBuild tb)) continue;
                turrets++;
                if(tb.hasAmmo()) armed++;
                else Log.info("[abdefcheck] no ammo: @ at (@,@)", p.block.name, p.x, p.y);
                if(BuildJob.needsPower(p.block)){
                    needPower++;
                    if(tb.power != null && tb.power.status > 0.99f) powered++;
                    else Log.info("[abdefcheck] unpowered: @ at (@,@) status @", p.block.name, p.x, p.y, tb.power == null ? -1 : tb.power.status);
                }
                Turret tu = (Turret)p.block;
                if(tu.coolant != null && !(tu instanceof LiquidTurret)){
                    coolable++;
                    if(tb.liquids != null && tb.liquids.currentAmount() > 0.01f) cooled++;
                }
            }
            Log.info("[abdefcheck] turrets @: with ammo @, powered @/@, holding coolant @/@ (planned cooled: @)", turrets, armed, powered, needPower, cooled, coolable, plannedCooled());
            Seq<int[]> predicted = new Seq<>();
            for(int[] r : result.planner.newGroundRoutes) predicted.add(r);
            compare(predicted);
        });

        handler.register("abwave", "<waves>", "AutoBuild: spawn waves now and count kills / losses from here on", args -> {
            if(!listening){
                listening = true;
                Events.on(UnitDestroyEvent.class, e -> {
                    if(e.unit.team != state.rules.waveTeam) return;
                    killed++;
                    // near the defended region (its turrets' reach) or elsewhere
                    if(job != null && e.unit.tileX() >= job.ax - 12 && e.unit.tileX() <= job.bx + 12 && e.unit.tileY() >= job.ay - 12 && e.unit.tileY() <= job.by + 12) killedNear++;
                });
                Events.on(BlockDestroyEvent.class, e -> { if(e.tile.team() == state.rules.defaultTeam) lost++; });
                Events.run(Trigger.update, () -> {
                    if(!watching) return;
                    peak = Math.max(peak, killed + state.rules.waveTeam.data().units.size);
                    for(Unit u : state.rules.waveTeam.data().units){
                        if(seen.add(u.id)) seenTypes.increment(u.type.name + (u.isFlying() ? "(air)" : ""));
                        lastPos.put(u.id, u.tileX() + "," + u.tileY());
                    }
                });
                Events.on(UnitDestroyEvent.class, e -> { if(e.unit.team == state.rules.waveTeam) killedTypes.increment(e.unit.type.name); });
            }
            killed = 0; killedNear = 0; lost = 0; peak = 0; watching = true;
            seen.clear(); seenTypes.clear(); killedTypes.clear(); lastPos.clear();
            int n = Strings.parseInt(args[0], 1);
            for(int i = 0; i < n; i++) logic.runWave();
            var core = state.rules.defaultTeam.core();
            coreHealth = core == null ? 0 : core.health;
            Log.info("[abwave] wave now @", state.wave);
        });

        handler.register("abwavecount", "AutoBuild: kills and losses since abwave", args -> {
            var core = state.rules.defaultTeam.core();
            int alive = state.rules.waveTeam.data().units.size;
            Log.info("[abwave] enemies @: killed @ (@ near the defense), @ alive; our buildings lost @, core health lost @", seen.size, killed, killedNear, alive, lost,
                core == null ? "core destroyed" : Strings.autoFixed(coreHealth - core.health, 0));
            Log.info("[abwave] seen by type @, killed by type @", seenTypes, killedTypes);
            // units that vanished next to a core (no death event) reached it
            IntSet alive2 = new IntSet();
            for(Unit u : state.rules.waveTeam.data().units) alive2.add(u.id);
            int leaked = 0;
            for(var e : lastPos){
                if(alive2.contains(e.key)) continue;
                String[] xy = e.value.split(",");
                Building c = state.rules.defaultTeam.core();
                if(c != null && Math.abs(Strings.parseInt(xy[0]) - c.tileX()) < 15 && Math.abs(Strings.parseInt(xy[1]) - c.tileY()) < 15) leaked++;
            }
            Log.info("[abwave] reached the core: @ (plus kills there)", leaked);
        });
    }

    static int plannedCooled(){
        int n = 0;
        for(var b : result.planner.built) if(b.cooled && result.planner.p.types[b.type].kind == DefensePlanner.TURRET) n++;
        return n;
    }

    /** our route vs the game's flow field traced from every spawn (once the field is complete) */
    static void compare(Seq<int[]> ours){
        Pathfinder.Flowfield field = pathfinder.getField(state.rules.waveTeam, Pathfinder.costGround, Pathfinder.fieldCore);
        if(!field.hasCompleteWeights()){ Log.info("[route] the game's flow field isn't complete yet"); return; }
        int w = world.width();
        int i = 0;
        for(Tile s : spawner.getSpawns()){
            IntSeq game = new IntSeq();
            Tile cur = s;
            game.add(cur.array());
            for(int g = 0; g < w * world.height(); g++){
                Tile next = pathfinder.getTargetTile(cur, field);
                if(next == null || next == cur) break;
                cur = next;
                game.add(cur.array());
            }
            int[] o = i < ours.size ? ours.get(i) : new int[0];
            int same = 0;
            IntSet os = new IntSet();
            for(int t : o) os.add(t);
            for(int k = 0; k < game.size; k++) if(os.contains(game.get(k))) same++;
            Log.info("[route] spawn (@,@): game route @ tiles, predicted @ tiles, @ shared, identical: @", s.x, s.y, game.size, o.length, same,
                java.util.Arrays.equals(game.toArray(), o));
            i++;
        }
    }

    static void dump(DefenseJob j, DefenseJob.Result r){
        int W = j.bx - j.ax + 1, H = j.by - j.ay + 1;
        char[][] g = new char[W][H];
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            Tile t = world.tile(j.ax + u, j.ay + v);
            g[u][v] = t.solid() ? '#' : t.floor().isDeep() ? '~' : '.';
        }
        int w = world.width();
        for(int[] route : r.planner.groundRoutes) for(int t : route){
            int u = t % w - j.ax, v = t / w - j.ay;
            if(u >= 0 && v >= 0 && u < W && v < H) g[u][v] = '-';
        }
        for(BuildPlan p : r.plans){
            int off = (p.block.size - 1) / 2;
            char c = p.block instanceof Turret ? Character.toUpperCase(p.block.name.charAt(0)) : p.block instanceof Wall ? '=' : p.block == Blocks.powerNode ? '*'
                : p.block.name.contains("conveyor") ? ">^<v".charAt(p.rotation) : p.block.name.contains("conduit") && !p.block.name.contains("bridge") ? "}'{,".charAt(p.rotation)
                : p.block.name.contains("bridge") ? 'B' : p.block.name.contains("router") ? 'R' : p.block == Blocks.unloader ? 'U' : p.block.name.contains("mine") ? 'x' : 'm';
            for(int a = 0; a < p.block.size; a++) for(int b = 0; b < p.block.size; b++){
                int u = p.x - off + a - j.ax, v = p.y - off + b - j.ay;
                if(u >= 0 && v >= 0 && u < W && v < H) g[u][v] = c;
            }
        }
        for(int v = H - 1; v >= 0; v--){
            StringBuilder sb = new StringBuilder();
            for(int u = 0; u < W; u++) sb.append(g[u][v]);
            Log.info("[abdef] |@|", sb);
        }
    }
}
