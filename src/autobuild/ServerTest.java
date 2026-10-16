package autobuild;

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
 * abtest &lt;item&gt; &lt;drill&gt; &lt;E|N|W|S&gt; &lt;output|efficiency&gt; [size] [belt]
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
        handler.register("abtest", "<item> <drill> <dir> <mode> [size] [belt] [nobridge]", "AutoBuild: plan and instantly place a drill field", args -> {
            if(!state.isGame()){ Log.err("host a map first"); return; }
            Item item = content.item(args[0]);
            Block drill = content.block(args[1]);
            int dir = "ENWS".indexOf(Character.toUpperCase(args[2].charAt(0)));
            int size = args.length > 4 ? Strings.parseInt(args[4], 30) : 30;
            Block belt = args.length > 5 ? content.block(args[5]) : Blocks.conveyor;
            boolean bridges = args.length <= 6 || !args[6].equals("nobridge");
            if(item == null || !(drill instanceof Drill) || dir < 0 || belt == null){ Log.err("bad arguments"); return; }

            DrillFieldJob job = new DrillFieldJob();
            job.drill = (Drill)drill; job.target = item; job.outDir = dir; job.belt = belt;
            job.mode = args[3].startsWith("e") ? Mode.EFFICIENCY : Mode.OUTPUT;
            job.bridges = bridges;
            if(!densest(job, item, size)){ Log.err("no @ ore on this map", item.name); return; }
            Log.info("[abtest] region (@,@)-(@,@)", job.ax, job.ay, job.bx, job.by);
            if(!job.run()){
                Log.err("[abtest] plan failed: @", job.error);
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
            Log.info("[abtest] @", Strings.stripColors(job.summary()).replace('\n', ' '));
            if(bridges){
                // same region without bridges, for a like-for-like comparison (ore is randomized per server start)
                DrillFieldJob alt = new DrillFieldJob();
                alt.drill = job.drill; alt.target = job.target; alt.outDir = job.outDir; alt.belt = job.belt; alt.mode = job.mode;
                alt.ax = job.ax; alt.ay = job.ay; alt.bx = job.bx; alt.by = job.by; alt.bridges = false;
                if(alt.run()) Log.info("[abtest] without bridges: @", Strings.stripColors(alt.summary()).replace('\n', ' '));
            }

            boolean first = true;
            for(BuildPlan p : job.plans){
                Block b = p.block;
                if(first && b == job.node){ b = Blocks.powerSource; first = false; }
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
            int ex = job.portX + (dir == 0 ? 2 : dir == 2 ? -2 : 0), ey = job.portY + (dir == 1 ? 2 : dir == 3 ? -2 : 0);
            Tile vt = world.tile(ex, ey);
            if(vt == null){ Log.err("no room for the vault"); return; }
            vt.setBlock(Blocks.vault, job.team, 0);
            vault = vt.build;
            lastCount = 0; lastTime = Time.millis(); predicted = Math.min(job.itemsPerSecond, job.beltCapacity);
            Log.info("[abtest] placed @ blocks, vault at (@,@), predicted @/s", job.plans.size, ex, ey, Strings.autoFixed(predicted, 2));
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
