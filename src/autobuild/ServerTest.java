package autobuild;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.DrillLayoutPlanner.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;

import static mindustry.Vars.*;

/**
 * Dedicated-server commands for end-to-end checks without a GUI:
 * <pre>
 * abtest &lt;item&gt; &lt;drill&gt; &lt;E|N|W|S|auto&gt; &lt;output|efficiency&gt; [size=N belt=NAME nobridge water=SIDE[:FRAC]|auto conduit=NAME]
 *     finds the size x size window with the most &lt;item&gt; ore, plans it, places every block
 *     instantly (a power source replaces the first node), puts a vault behind the port
 * abcount
 *     prints items collected in the vault and the measured rate since the previous abcount
 * </pre>
 */
public class ServerTest{
    static final Seq<Building> vaults = new Seq<>();
    static int lastCount;
    static long lastTime;
    static float predicted;

    static void register(CommandHandler handler){
        handler.register("abtest", "<item> <drill> <outputs> <mode> [options...]",
            "AutoBuild: plan and place a drill field. outputs: auto, or e.g. E:0.3,W:0.7,@12:8 (@ = interior tile relative to the region corner). "
            + "options: size=N belt=NAME nobridge nomix water=auto|<ports> conduit=NAME", args -> {
            if(!state.isGame()){ Log.err("host a map first"); return; }
            Item item = content.item(args[0]);
            Block drill = content.block(args[1]);
            int size = 30;
            Block belt = Blocks.conveyor, conduit = Blocks.conduit;
            boolean bridges = true, mix = true, water = false;
            String waterSpec = "auto";
            // a variadic "[options...]" parameter arrives as one space-joined string
            Seq<String> opts = new Seq<>();
            for(int i = 4; i < args.length; i++) for(String o : args[i].trim().split("\\s+")) if(!o.isEmpty()) opts.add(o);
            for(String opt : opts){
                String[] kv = opt.split("=", 2);
                switch(kv[0]){
                    case "size": size = Strings.parseInt(kv[1], 30); break;
                    case "belt": belt = content.block(kv[1]); break;
                    case "conduit": conduit = content.block(kv[1]); break;
                    case "nobridge": bridges = false; break;
                    case "nomix": mix = false; break;
                    case "water": water = true; waterSpec = kv[1]; break;
                    default: Log.err("unknown option @", opt); return;
                }
            }
            if(item == null || !(drill instanceof Drill) || belt == null){ Log.err("bad arguments"); return; }

            DrillFieldJob job = new DrillFieldJob();
            job.drill = (Drill)drill; job.target = item; job.belt = belt;
            job.mode = args[3].startsWith("e") ? Mode.EFFICIENCY : Mode.OUTPUT;
            job.bridges = bridges;
            job.mixDrills = mix;
            job.water = water; job.conduit = conduit;
            if(!densest(job, item, size)){ Log.err("no @ ore on this map", item.name); return; }
            if(!parsePorts(job, args[2], job.outs) || !parsePorts(job, waterSpec, job.waters)){ Log.err("bad port list"); return; }
            for(DrillFieldJob.Port pt : job.outs) if(!pt.edge) job.sinkReserve = 1; // room for a 3x3 vault
            Log.info("[abtest] region (@,@)-(@,@)", job.ax, job.ay, job.bx, job.by);
            DrillFieldJob.Snapshot sn = job.prepare();
            DrillFieldJob.Candidate cand = sn == null ? null : job.search(sn, 0, 3, null);
            if(cand == null || !cand.ok()){
                Log.err("[abtest] plan failed: @", cand == null ? job.error : cand.error);
                // world-oriented dump: 'W' wall, '#' otherwise unbuildable, 'o' target ore, '+' other ore, '~' deep, 'B' building
                for(int y = job.by; y >= job.ay; y--){
                    StringBuilder sb = new StringBuilder();
                    for(int x = job.ax; x <= job.bx; x++){
                        Tile t = world.tile(x, y);
                        sb.append(t.build != null ? 'B' : t.solid() ? 'W' : t.floor().isDeep() ? '~' : !Build.validPlace(job.belt, job.team, x, y, 0) ? '#' : t.drop() == item ? 'o' : t.drop() != null ? '+' : ' ');
                    }
                    Log.info("[abtest] |@|", sb);
                }
                return;
            }
            Log.info("[abtest] @ | @ | variant @", Strings.stripColors(cand.summary()).replace('\n', ' '), cand.portText(), cand.variant);

            boolean first = true;
            for(BuildPlan p : cand.plans){
                Block b = p.block;
                if(first && b == cand.node){ b = Blocks.powerSource; first = false; }
                Tile t = world.tile(p.x, p.y);
                t.setBlock(b, job.team, p.rotation);
                // same sequence as ConstructBlock.constructFinish for a player-built plan
                if(t.build != null){
                    t.build.placed();
                    if(p.config != null) t.build.configured(null, p.config);
                    t.build.playerPlaced(p.config);
                }
            }
            // a vault (3x3) at every output: past the edge, or on the reserved interior tiles
            vaults.clear();
            for(int k = 0; k < cand.outPorts.size; k++){
                DrillFieldJob.Port pt = cand.outPorts.get(k);
                int[] sink = cand.sinkWorld[k];
                int vx = sink[0] + (pt.edge ? Geometry.d4x(pt.side) : 0), vy = sink[1] + (pt.edge ? Geometry.d4y(pt.side) : 0);
                Tile vt = world.tile(vx, vy);
                if(vt == null){ Log.err("no room for vault @", k + 1); continue; }
                vt.setBlock(Blocks.vault, job.team, 0);
                vaults.add(vt.build);
                Log.info("[abtest] output @ at (@,@): vault (@,@), planned @/s", k + 1, sink[0], sink[1], vx, vy, Strings.autoFixed((float)cand.loads[k], 2));
            }
            // infinite water at every water port: outside an edge port, next to an interior one
            for(DrillFieldJob.Port pt : cand.waterPorts){
                int[] w = job.waterTile(pt);
                Tile st = null;
                if(pt.edge) st = world.tile(w[0] + Geometry.d4x(pt.side), w[1] + Geometry.d4y(pt.side));
                else for(int d = 0; d < 4 && st == null; d++){
                    Tile n = world.tile(w[0] + Geometry.d4x(d), w[1] + Geometry.d4y(d));
                    if(n != null && n.build == null && !n.solid()) st = n;
                }
                if(st == null){ Log.err("no room for a water source at (@,@)", w[0], w[1]); continue; }
                st.setBlock(Blocks.liquidSource, job.team, 0);
                st.build.configured(null, Liquids.water);
                Log.info("[abtest] water source at (@,@)", st.x, st.y);
            }
            lastCount = 0; lastTime = Time.millis();
            predicted = 0f;
            for(int k = 0; k < cand.loads.length; k++) predicted += Math.min((float)cand.loads[k], cand.beltCapacity);
            Log.info("[abtest] placed @ blocks, @ output(s), predicted @/s total", cand.plans.size, vaults.size, Strings.autoFixed(predicted, 2));
        });

        handler.register("abtile", "<x> <y> [radius]", "AutoBuild: describe tiles around (x,y)", args -> {
            int x = Strings.parseInt(args[0]), y = Strings.parseInt(args[1]), r = args.length > 2 ? Strings.parseInt(args[2]) : 1;
            for(int yy = y + r; yy >= y - r; yy--){
                StringBuilder sb = new StringBuilder();
                for(int xx = x - r; xx <= x + r; xx++){
                    Tile t = world.tile(xx, yy);
                    sb.append(String.format("%-28s", t == null ? "-" : t.block().name + (t.build != null ? "@" + t.build.rotation + " i" + (t.build.items == null ? 0 : t.build.items.total()) : "")));
                }
                Log.info("[abtile] @: @", yy, sb);
            }
        });

        handler.register("abcount", "AutoBuild: report items collected by the abtest vault", args -> {
            if(vaults.isEmpty()){ Log.err("run abtest first"); return; }
            int n = 0;
            StringBuilder per = new StringBuilder();
            for(Building v : vaults){ n += v.items.total(); per.append(per.length() > 0 ? "+" : "").append(v.items.total()); }
            Log.info("[abcount] per output: @", per);
            float secs = Time.timeSinceMillis(lastTime) / 1000f;
            Log.info("[abcount] vault=@ items, +@ in @s = @/s (predicted @/s)", n, n - lastCount, Strings.autoFixed(secs, 1),
                Strings.autoFixed((n - lastCount) / secs, 2), Strings.autoFixed(predicted, 2));
            lastCount = n; lastTime = Time.millis();
        });
    }

    /** "auto" (empty list) or comma-separated E:0.3 / @dx:dy (interior, relative to the region corner) */
    static boolean parsePorts(DrillFieldJob job, String spec, Seq<DrillFieldJob.Port> out){
        out.clear();
        if(spec.equalsIgnoreCase("auto")) return true;
        for(String s : spec.split(",")){
            if(s.startsWith("@")){
                String[] xy = s.substring(1).split(":");
                if(xy.length != 2) return false;
                out.add(DrillFieldJob.Port.at(job.ax + Strings.parseInt(xy[0], 0), job.ay + Strings.parseInt(xy[1], 0)));
            }else{
                String[] sf = s.split(":");
                int side = "ENWS".indexOf(Character.toUpperCase(sf[0].charAt(0)));
                if(side < 0) return false;
                out.add(DrillFieldJob.Port.edge(side, sf.length > 1 ? Strings.parseFloat(sf[1], 0.5f) : 0.5f));
            }
        }
        return true;
    }

    /** Pick the size x size window with the most ore of this item (2D prefix sums). */
    static boolean densest(DrillFieldJob job, Item item, int size){
        int w = world.width(), h = world.height();
        size = Math.min(size, Math.min(w, h) - 6);
        int[][] ps = new int[w + 1][h + 1];
        for(int x = 0; x < w; x++) for(int y = 0; y < h; y++){
            ps[x + 1][y + 1] = ps[x][y + 1] + ps[x + 1][y] - ps[x][y] + (world.tile(x, y).drop() == item && !world.tile(x, y).solid() ? 1 : 0);
        }
        int best = 0;
        // keep 3 tiles of margin so the vault fits behind any port
        for(int x = 3; x + size <= w - 3; x++) for(int y = 3; y + size <= h - 3; y++){
            int c = ps[x + size][y + size] - ps[x][y + size] - ps[x + size][y] + ps[x][y];
            if(c > best){ best = c; job.ax = x; job.ay = y; job.bx = x + size - 1; job.by = y + size - 1; }
        }
        return best > 0;
    }
}
