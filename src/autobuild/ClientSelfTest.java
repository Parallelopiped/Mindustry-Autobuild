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
 * Automated client check, enabled with -Dautobuild.selftest=&lt;map&gt;[:water]: starts a sandbox game,
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

    final boolean water, autoPorts;
    boolean sawReplay, shotReplay;
    float previewShotAt = -1f;
    String shot;
    DrillFieldJob.Candidate built;

    /** spec: "&lt;map&gt;[:water][:auto]" */
    ClientSelfTest(AutoBuildMod mod, String spec){
        this.mod = mod;
        String[] parts = spec.split(":");
        this.mapName = parts[0];
        this.water = spec.contains(":water");
        this.autoPorts = spec.contains(":auto");
        Events.on(ClientLoadEvent.class, e -> Time.runTask(30f, this::start));
        Events.run(Trigger.update, this::update);
        // screenshots for checking what the player actually sees
        Events.run(Trigger.uiDrawEnd, () -> {
            if(shot == null) return;
            var pix = arc.util.ScreenUtils.getFrameBufferPixmap(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight(), true);
            var file = Core.settings.getDataDirectory().child("selftest-" + shot + ".png");
            arc.graphics.PixmapIO.writePng(file, pix);
            pix.dispose();
            Log.info("[selftest] screenshot @", file.absolutePath());
            shot = null;
        });
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
            case 1: // wait for the world and player unit, then go through the real UI flow
                if(state.isGame() && player.unit() != null && !player.unit().dead && timer > 3f){
                    DrillFieldJob job = mod.job;
                    if(!ServerTest.densest(job, Items.copper, 24)){ fail("no copper"); return; }
                    job.team = player.team();
                    job.target = Items.copper;
                    job.drill = (Drill)Blocks.mechanicalDrill;
                    job.belt = Blocks.titaniumConveyor;
                    job.bridges = true;
                    job.mode = Mode.OUTPUT;
                    job.water = water;
                    job.conduit = Blocks.conduit;
                    Log.info("[selftest] region (@,@)-(@,@), output @, water @", job.ax, job.ay, job.bx, job.by, autoPorts ? "auto" : "East (clicked)", water ? "auto" : "off");
                    mod.startPicking();
                    // what a click on the east edge / a right-click do
                    if(autoPorts) mod.pickPort(-1, 0f);
                    else{
                        float[] snap = job.snap(job.bx + 2, (job.ay + job.by) / 2);
                        mod.pickPort((int)snap[0], snap[1]);
                    }
                    if(water) mod.pickPort(-1, 0f);
                    phase = 2;
                }
                break;
            case 2: // planning, then the gray replay, then the preview
                keepNearRegion();
                if(mod.phase == AutoBuildMod.State.playback){
                    sawReplay = true;
                    if(!shotReplay && mod.playIndex >= mod.frames.size / 2){ shot = "replay"; shotReplay = true; }
                }
                if(mod.phase == AutoBuildMod.State.preview){
                    if(mod.variants.size != 1){ fail("expected 1 plan, got " + mod.variants.size); return; }
                    DrillFieldJob.Candidate c = mod.current();
                    if(previewShotAt < 0) Log.info("[selftest] plan 1: @ keyframes, replay seen: @; @ | @ | score @", mod.frames.size, sawReplay,
                        Strings.stripColors(c.summary()).replace('\n', ' '), c.portText(), Strings.autoFixed((float)c.score(), 1));
                    if(!sawReplay || mod.frames.size < 2){ fail("no gray replay"); return; }
                    if(previewShotAt < 0){ shot = "preview"; previewShotAt = timer; }
                    if(timer - previewShotAt < 0.5f) return; // let the screenshot happen first
                    mod.anotherPlan();
                    phase = 3;
                }else if(mod.phase == AutoBuildMod.State.idle){ fail("planning failed"); return; }
                break;
            case 3: // "Another plan" adds a second, different plan
                keepNearRegion();
                if(mod.phase == AutoBuildMod.State.preview && mod.variants.size == 2){
                    DrillFieldJob.Candidate a = mod.variants.get(0), b = mod.variants.get(1);
                    Log.info("[selftest] plan 2: @ | score @ (plan 1: @), different: @", b.portText(), Strings.autoFixed((float)b.score(), 1),
                        Strings.autoFixed((float)a.score(), 1), !a.signature.equals(b.signature));
                    if(a.signature.equals(b.signature)){ fail("another plan is identical"); return; }
                    mod.show(a.score() >= b.score() ? 0 : 1);
                    planned = mod.current().plans.size;
                    built = mod.current();
                    mod.commit();
                    phase = 4;
                    timer = 0f;
                }else if(mod.phase == AutoBuildMod.State.idle){ fail("another plan failed"); return; }
                break;
            case 4: // keep the unit next to the region while it builds
                DrillFieldJob job = mod.job;
                player.unit().set((job.ax + job.bx) / 2f * tilesize, (job.ay + job.by) / 2f * tilesize);
                if(player.unit().plans().isEmpty() || timer > 90f){
                    int done = 0;
                    for(BuildPlan p : built.plans){
                        var t = world.tile(p.x, p.y);
                        if(t != null && t.block() == p.block) done++;
                    }
                    Log.info("[selftest] built @/@ planned blocks (queue left: @)", done, planned, player.unit().plans().size);
                    Log.info(done == planned ? "[selftest] PASS" : "[selftest] FAIL: not everything was built");
                    phase = 0;
                    Core.app.exit();
                }
                break;
        }
    }

    void keepNearRegion(){
        DrillFieldJob job = mod.job;
        if(player.unit() != null) player.unit().set((job.ax + job.bx) / 2f * tilesize, (job.ay + job.by) / 2f * tilesize);
    }

    void fail(String msg){
        Log.err("[selftest] FAIL: @", msg);
        phase = 0;
        Core.app.exit();
    }
}
