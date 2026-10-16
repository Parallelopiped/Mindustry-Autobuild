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
    static Building vault;
    static int lastCount;
    static long lastTime;
    static float predicted;

    static void register(CommandHandler handler){
        handler.register("abtest", "<item> <drill> <dir> <mode> [options...]", "AutoBuild: plan and place a drill field. dir may be auto. options: size=N belt=NAME nobridge nomix water=SIDE[:FRAC]|auto conduit=NAME", args -> {
            if(!state.isGame()){ Log.err("host a map first"); return; }
            Item item = content.item(args[0]);
            Block drill = content.block(args[1]);
            boolean autoOut = args[2].equalsIgnoreCase("auto");
            int dir = autoOut ? -1 : "ENWS".indexOf(Character.toUpperCase(args[2].charAt(0)));
            int size = 30;
            Block belt = Blocks.conveyor, conduit = Blocks.conduit;
            boolean bridges = true, mix = true;
            int waterDir = -1;
            boolean water = false;
            float waterFrac = 0.5f;
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
                    case "water":
                        water = true;
                        if(kv[1].equalsIgnoreCase("auto")) break;
                        String[] wf = kv[1].split(":");
                        waterDir = "ENWS".indexOf(Character.toUpperCase(wf[0].charAt(0)));
                        if(wf.length > 1) waterFrac = Strings.parseFloat(wf[1], 0.5f);
                        break;
                    default: Log.err("unknown option @", opt); return;
                }
            }
            if(item == null || !(drill instanceof Drill) || (dir < 0 && !autoOut) || belt == null){ Log.err("bad arguments"); return; }

            DrillFieldJob job = new DrillFieldJob();
            job.drill = (Drill)drill; job.target = item; job.outPick = dir; job.outFrac = 0.5f; job.belt = belt;
            job.mode = args[3].startsWith("e") ? Mode.EFFICIENCY : Mode.OUTPUT;
            job.bridges = bridges;
            job.mixDrills = mix;
            job.water = water; job.waterPick = waterDir; job.waterFrac = waterFrac; job.conduit = conduit;
            if(!densest(job, item, size)){ Log.err("no @ ore on this map", item.name); return; }
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
            {
                // same region without bridges or water, same output side (ore is randomized per server start)
                DrillFieldJob alt = new DrillFieldJob();
                alt.drill = job.drill; alt.target = job.target; alt.outPick = cand.outDir; alt.belt = job.belt; alt.mode = job.mode;
                alt.ax = job.ax; alt.ay = job.ay; alt.bx = job.bx; alt.by = job.by; alt.bridges = false; alt.mixDrills = false;
                DrillFieldJob.Snapshot asn = alt.prepare();
                DrillFieldJob.Candidate a = asn == null ? null : alt.search(asn, 0, 1, null);
                if(a != null && a.ok()) Log.info("[abtest] baseline (chosen drill only, no bridges, no water): @", Strings.stripColors(a.summary()).replace('\n', ' '));
            }

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
            // vault (3x3) right behind the port, outside the region
            int od = cand.outDir;
            int ex = cand.portX + (od == 0 ? 2 : od == 2 ? -2 : 0), ey = cand.portY + (od == 1 ? 2 : od == 3 ? -2 : 0);
            Tile vt = world.tile(ex, ey);
            if(vt == null){ Log.err("no room for the vault"); return; }
            vt.setBlock(Blocks.vault, job.team, 0);
            if(cand.waterPortX >= 0){
                // infinite water right outside the water port
                Tile st = world.tile(cand.waterPortX + Geometry.d4x(cand.waterDir), cand.waterPortY + Geometry.d4y(cand.waterDir));
                if(st != null){
                    st.setBlock(Blocks.liquidSource, job.team, 0);
                    st.build.configured(null, Liquids.water);
                    Log.info("[abtest] water source at (@,@)", st.x, st.y);
                }
            }
            vault = vt.build;
            lastCount = 0; lastTime = Time.millis(); predicted = Math.min(cand.itemsPerSecond, cand.beltCapacity);
            Log.info("[abtest] placed @ blocks, vault at (@,@), predicted @/s", cand.plans.size, ex, ey, Strings.autoFixed(predicted, 2));
        });

        handler.register("abcount", "AutoBuild: report items collected by the abtest vault", args -> {
            if(vault == null){ Log.err("run abtest first"); return; }
            int n = vault.items.total();
            float secs = Time.timeSinceMillis(lastTime) / 1000f;
            Log.info("[abcount] vault=@ items, +@ in @s = @/s (predicted @/s)", n, n - lastCount, Strings.autoFixed(secs, 1),
                Strings.autoFixed((n - lastCount) / secs, 2), Strings.autoFixed(predicted, 2));
            lastCount = n; lastTime = Time.millis();
        });
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
