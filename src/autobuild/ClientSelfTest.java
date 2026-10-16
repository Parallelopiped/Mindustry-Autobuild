package autobuild;

import arc.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.DrillLayoutPlanner.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.maps.*;
import mindustry.world.blocks.production.*;

import static mindustry.Vars.*;

/**
 * Automated client check, enabled with -Dautobuild.selftest=&lt;map&gt;: starts a sandbox game,
 * selects the densest copper window, runs the threaded planner (counting gray-preview frames),
 * commits the plan, lets the player's unit build it, then reports and exits.
 */
public class ClientSelfTest{
    final AutoBuildMod mod;
    final String mapName;
    int phase, frames;
    Seq<BuildPlan> lastGhosts;
    final ObjectSet<String> stages = new ObjectSet<>();
    float timer;
    int planned;

    ClientSelfTest(AutoBuildMod mod, String mapName){
        this.mod = mod;
        this.mapName = mapName;
        Events.on(ClientLoadEvent.class, e -> Time.runTask(30f, this::start));
        Events.run(Trigger.update, this::update);
    }

    void start(){
        Map map = maps.byName(mapName);
        if(map == null){ fail("no map " + mapName); return; }
        Rules rules = map.applyRules(Gamemode.sandbox);
        rules.waves = false;
        rules.buildSpeedMultiplier = 20f;
        rules.unitBuildSpeedMultiplier = 20f;
        control.playMap(map, rules);
        phase = 1;
        Log.info("[selftest] started @", mapName);
    }

    void update(){
        if(phase == 0) return;
        timer += Time.delta / 60f;
        if(timer > 150f){ fail("timeout in phase " + phase); return; }
        switch(phase){
            case 1: // wait for the world and player unit
                if(state.isGame() && player.unit() != null && !player.unit().dead && timer > 3f){
                    DrillFieldJob job = mod.job;
                    if(!ServerTest.densest(job, Items.copper, 24)){ fail("no copper"); return; }
                    job.team = player.team();
                    job.target = Items.copper;
                    job.drill = (Drill)Blocks.mechanicalDrill;
                    job.belt = Blocks.titaniumConveyor;
                    job.outDir = 0;
                    job.bridges = true;
                    job.mode = Mode.OUTPUT;
                    Log.info("[selftest] region (@,@)-(@,@)", job.ax, job.ay, job.bx, job.by);
                    mod.plan();
                    phase = 2;
                }
                break;
            case 2: // planning on the worker thread: count distinct gray snapshots
                Seq<BuildPlan> g = mod.ghosts;
                if(g != null && g != lastGhosts){ frames++; lastGhosts = g; stages.add(mod.stage); }
                if(!mod.planning){
                    if(mod.preview == null){ fail("no preview after planning"); return; }
                    Log.info("[selftest] planning done: @ gray frames, stages @; @", frames, stages, Strings.stripColors(mod.job.summary()).replace('\n', ' '));
                    planned = mod.preview.size;
                    mod.commit();
                    phase = 3;
                    timer = 0f;
                }
                break;
            case 3: // keep the unit next to the region while it builds
                DrillFieldJob job = mod.job;
                player.unit().set((job.ax + job.bx) / 2f * tilesize, (job.ay + job.by) / 2f * tilesize);
                if(player.unit().plans().isEmpty() || timer > 90f){
                    int built = 0;
                    for(BuildPlan p : job.plans){
                        var t = world.tile(p.x, p.y);
                        if(t != null && t.block() == p.block) built++;
                    }
                    Log.info("[selftest] built @/@ planned blocks (queue left: @)", built, planned, player.unit().plans().size);
                    Log.info(built == planned ? "[selftest] PASS" : "[selftest] FAIL: not everything was built");
                    phase = 0;
                    Core.app.exit();
                }
                break;
        }
    }

    void fail(String msg){
        Log.err("[selftest] FAIL: @", msg);
        phase = 0;
        Core.app.exit();
    }
}
