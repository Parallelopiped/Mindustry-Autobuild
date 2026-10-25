package autobuild;

import arc.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.DrillLayoutPlanner.*;
import mindustry.content.*;
import mindustry.world.*;
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

    final boolean water, autoPorts, defense;
    final String mapSpec;
    boolean sawReplay, shotReplay;
    float previewShotAt = -1f, previewShotAt2 = -1f;
    String shot;
    DrillFieldJob.Candidate built;

    /** spec: "&lt;map&gt;[:water][:auto]" */
    ClientSelfTest(AutoBuildMod mod, String spec){
        this.mod = mod;
        String[] parts = spec.split(":");
        this.mapName = parts[0];
        this.mapSpec = spec;
        this.water = spec.contains(":water");
        this.autoPorts = spec.contains(":auto");
        this.defense = spec.contains(":defense");
        Events.on(ClientLoadEvent.class, e -> Time.runTask(30f, this::start));
        Events.run(Trigger.update, this::update);
        // screenshots for checking what the player actually sees
        recDir = System.getProperty("autobuild.record");
        if(recDir != null){
            // footage: a fixed time step per frame (30 fps of game time), whatever the capture costs
            Time.setDeltaProvider(() -> fastForward ? 8f : 2f);
            new arc.files.Fi(recDir).mkdirs();
        }
        Events.run(Trigger.uiDrawEnd, () -> {
            if(rec && recDir != null && !fastForward){
                var pix = arc.util.ScreenUtils.getFrameBufferPixmap(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight(), true);
                arc.graphics.PixmapIO.writePng(new arc.files.Fi(recDir).child(Strings.format("f@.png", String.format("%05d", recFrame++))), pix);
                pix.dispose();
            }
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
        if(timer > (phase == 14 ? 600f : 150f)){ fail("timeout in phase " + phase); return; }
        if(phase >= 9){ updateDefense(); return; }
        switch(phase){
            case 1: // wait for the world and player unit, then go through the real UI flow
                if(defense && state.isGame() && player.unit() != null && !player.unit().dead && timer > 3f){
                    startDefense();
                    break;
                }
                if(state.isGame() && player.unit() != null && !player.unit().dead && timer > 3f){
                    DrillFieldJob job = mod.drillJob;
                    mod.job = job;
                    // ":laser": the advanced blocks (laser drills on titanium, pulse conduits), a bigger region
                    boolean laser = mapSpec.contains(":laser");
                    mindustry.type.Item ore = Items.copper;
                    if(!ServerTest.densest(job, ore, laser ? 30 : 24)){ fail("no " + ore.name); return; }
                    mod.selectRegion(job.ax, job.ay, job.bx, job.by);
                    job.target = ore;
                    job.drill = (Drill)(laser ? Blocks.laserDrill : Blocks.mechanicalDrill);
                    job.belt = Blocks.titaniumConveyor;
                    job.bridges = true;
                    job.mode = Mode.OUTPUT;
                    job.water = water;
                    job.conduit = laser ? Blocks.pulseConduit : Blocks.conduit;
                    Log.info("[selftest] region (@,@)-(@,@), output @, water @", job.ax, job.ay, job.bx, job.by, autoPorts ? "auto" : "East (clicked)", water ? "auto" : "off");
                    mod.startPicking();
                    // clicks the player can make: on the edge, inside the region, far away on open ground
                    int cx = (job.ax + job.bx) / 2, cy = (job.ay + job.by) / 2;
                    Port edgeP = job.classify(job.bx + 1, cy), insideP = job.classify(cx, cy);
                    if(!mod.portAllowed(edgeP) || !edgeP.edge){ fail("edge output refused: " + mod.hoverReasonFor(edgeP)); return; }
                    if(!mod.portAllowed(insideP) || insideP.edge){ fail("inside output refused: " + mod.hoverReasonFor(insideP)); return; }
                    Log.info("[selftest] edge and inside output picks accepted");
                    // what a click on the east edge / right-clicks do
                    if(autoPorts) mod.autoKind();
                    else{
                        mod.addPick(job.classify(job.bx + 2, (job.ay + job.by) / 2));
                        // footage with the advanced blocks: a second output, so one belt's capacity doesn't cap the field
                        if(mapSpec.contains(":laser")) mod.addPick(job.classify(job.ax - 2, (job.ay + job.by) / 2));
                        mod.finishKind();
                    }
                    if(water) mod.autoKind();
                    rec = true;
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
                    DrillFieldJob.Candidate c = (DrillFieldJob.Candidate)mod.current();
                    if(previewShotAt < 0) Log.info("[selftest] plan 1: @ keyframes, replay seen: @; @ | @ | score @", mod.frames.size, sawReplay,
                        Strings.stripColors(c.summary()).replace('\n', ' '), c.portText(), Strings.autoFixed((float)c.score(), 1));
                    if(!sawReplay || mod.frames.size < 2){ fail("no gray replay"); return; }
                    if(previewShotAt < 0){ shot = "preview"; previewShotAt = timer; }
                    if(timer - previewShotAt < (recDir != null ? 2.5f : 0.5f)) return; // let the screenshot happen first
                    rec = false;
                    mod.anotherPlan();
                    phase = 3;
                }else if(mod.phase == AutoBuildMod.State.idle){ fail("planning failed"); return; }
                break;
            case 3: // "Another plan" adds a second, different plan
                keepNearRegion();
                if(mod.phase == AutoBuildMod.State.preview && mod.variants.size == 2){
                    DrillFieldJob.Candidate a = (DrillFieldJob.Candidate)mod.variants.get(0), b = (DrillFieldJob.Candidate)mod.variants.get(1);
                    Log.info("[selftest] plan 2: @ | score @ (plan 1: @), different: @", b.portText(), Strings.autoFixed((float)b.score(), 1),
                        Strings.autoFixed((float)a.score(), 1), !a.signature.equals(b.signature));
                    if(a.signature.equals(b.signature)){ fail("another plan is identical"); return; }
                    mod.show(a.score() >= b.score() ? 0 : 1);
                    // "Add output": a second output on the opposite side
                    int side = (((DrillFieldJob.Candidate)mod.current()).outDir + 2) % 4;
                    mod.addPorts();
                    // (not 0.5: an auto-chosen water port may sit in the middle of that side; the UI refuses that tile)
                    mod.addPick(Port.edge(side, 0.25f));
                    mod.finishKind();
                    phase = 5;
                }else if(mod.phase == AutoBuildMod.State.idle){ fail("another plan failed"); return; }
                break;
            case 5: // two outputs now
                keepNearRegion();
                if(mod.phase == AutoBuildMod.State.preview && mod.current() instanceof DrillFieldJob.Candidate cc && cc.outPorts.size >= 2 && cc.ports(mod.drillJob.outKind).size >= (mapSpec.contains(":laser") ? 3 : 2)){
                    DrillFieldJob.Candidate c = (DrillFieldJob.Candidate)mod.current();
                    if(previewShotAt2 < 0){
                        Log.info("[selftest] with 2 outputs: @ | loads @", c.portText(), java.util.Arrays.toString(c.loads));
                        // (the second output only carries load when one line can't take the whole field)
                        if(c.loads.length < 2){ fail("second output missing"); return; }
                        shot = "outputs"; previewShotAt2 = timer;
                    }
                    if(timer - previewShotAt2 < 0.5f) return;
                    planned = c.plans.size;
                    built = c;
                    mod.commit();
                    rec = true;
                    phase = 4;
                    timer = 0f;
                }else if(mod.phase == AutoBuildMod.State.idle){ fail("add output failed"); return; }
                break;
            case 4: // keep the unit next to the region while it builds
                BuildJob job = mod.job;
                player.unit().set((job.ax + job.bx) / 2f * tilesize, (job.ay + job.by) / 2f * tilesize);
                if(player.unit().plans().isEmpty() || timer > 90f){
                    if(recDir != null && holdFrom < 0){
                        holdFrom = timer;
                        // a power source next to the first node, so laser drills run in the footage
                        for(BuildPlan p : built.plans){
                            if(!(p.block instanceof mindustry.world.blocks.power.PowerNode)) continue;
                            for(int d = 0; d < 4; d++){
                                var t = world.tile(p.x + arc.math.geom.Geometry.d4x(d) * 2, p.y + arc.math.geom.Geometry.d4y(d) * 2);
                                if(t != null && t.build == null && !t.solid()){
                                    t.setBlock(Blocks.powerSource, player.team(), 0);
                                    if(t.build != null) t.build.placed();
                                    break;
                                }
                            }
                            break;
                        }
                    }
                    if(recDir != null && timer - holdFrom < 4f) break;
                    int done = 0;
                    for(BuildPlan p : built.plans){
                        var t = world.tile(p.x, p.y);
                        if(t != null && t.block() == p.block) done++;
                    }
                    Log.info("[selftest] built @/@ planned blocks (queue left: @)", done, planned, player.unit().plans().size);
                    Log.info(done == planned ? "[selftest] PASS" : "[selftest] FAIL: not everything was built");
                    if(recDir != null) Log.info("[selftest] footage: @ frames", recFrame);
                    phase = 0;
                    Core.app.exit();
                }
                break;
        }
    }

    /** defense: a region on the most open stretch of the enemy route, every port auto, lancers + duos + hails, walls, menders */
    void startDefense(){
        DefenseJob job = mod.defenseJob;
        mod.job = job;
        mod.selectRegion(0, 0, world.width() - 1, world.height() - 1);
        if(job.previewRoutes.isEmpty()){ fail("no enemy route on this map"); return; }
        int[] route = job.previewRoutes.max(r -> r.length);
        job.regionOnRoute(route, job.openStretch(route, 24), 24);
        mod.selectRegion(job.ax, job.ay, job.bx, job.by);
        job.turrets.clear();
        job.turrets.add((mindustry.world.blocks.defense.turrets.Turret)Blocks.duo, (mindustry.world.blocks.defense.turrets.Turret)Blocks.hail,
            (mindustry.world.blocks.defense.turrets.Turret)Blocks.lancer);
        job.ammo.clear(); job.ammo.add(Items.graphite);
        job.mender = true; job.forceProjector = true;
        if(!BuildJob.available(job.belt)) job.belt = Blocks.conveyor;
        Log.info("[selftest] defense region (@,@)-(@,@), @ route(s); @", job.ax, job.ay, job.bx, job.by, job.previewRoutes.size, Strings.stripColors(job.threatText));
        if(job.problem() != null){ fail(job.problem()); return; }
        // the settings dialog, for a screenshot (in the game's language)
        mod.showDialog();
        phase = 9;
        timer = 0f;
    }

    void pickDefensePorts(){
        rec = true;
        mod.startPicking();
        for(int i = 0; i < 6 && mod.phase == AutoBuildMod.State.pick; i++) mod.autoKind();
        phase = 10;
    }

    void updateDefense(){
        switch(phase){
            case 9:
                keepNearRegion();
                if(timer > 0.5f && previewShotAt < 0){ shot = "dialog"; previewShotAt = timer; }
                if(previewShotAt >= 0 && timer - previewShotAt > 0.5f){
                    previewShotAt = -1f;
                    if(Core.scene.getDialog() != null) Core.scene.getDialog().hide(null);
                    pickDefensePorts();
                }
                break;
            case 10: // planning, replay, preview with the route drawn
                keepNearRegion();
                if(mod.phase == AutoBuildMod.State.playback) sawReplay = true;
                if(mod.phase == AutoBuildMod.State.preview){
                    PlanResult c = mod.current();
                    if(previewShotAt < 0 && previewSeenAt < 0){ previewSeenAt = timer; return; }
                    // a moment later: glyphs drawn for the first time are generated during that frame
                    if(previewShotAt < 0 && timer - previewSeenAt < 1f) return;
                    if(previewShotAt < 0){
                        Log.info("[selftest] defense plan: @ keyframes, replay seen @; @ | @ | score @", mod.frames.size, sawReplay,
                            Strings.stripColors(c.summary()).replace('\n', ' '), c.portText(), Strings.autoFixed((float)c.score(), 1));
                        if(!sawReplay){ fail("no gray replay"); return; }
                        shot = "defense"; previewShotAt = timer;
                    }
                    if(timer - previewShotAt < (recDir != null ? 3f : 0.5f)) return;
                    rec = false;
                    mod.anotherPlan();
                    phase = 11;
                }else if(mod.phase == AutoBuildMod.State.idle){ fail("defense planning failed"); return; }
                break;
            case 11:
                keepNearRegion();
                if(mod.phase == AutoBuildMod.State.preview && mod.variants.size == 2){
                    PlanResult a = mod.variants.get(0), b = mod.variants.get(1);
                    Log.info("[selftest] another plan: score @ (first @), different: @", Strings.autoFixed((float)b.score(), 1), Strings.autoFixed((float)a.score(), 1), !a.signature.equals(b.signature));
                    if(a.signature.equals(b.signature)){ fail("another plan is identical"); return; }
                    mod.show(a.score() >= b.score() ? 0 : 1);
                    built = null;
                    planned = mod.current().plans.size;
                    builtPlans = mod.current().plans;
                    mod.commit();
                    rec = true;
                    phase = 12;
                    timer = 0f;
                }else if(mod.phase == AutoBuildMod.State.idle){ fail("another plan failed"); return; }
                break;
            case 12:
                keepNearRegion();
                if(player.unit().plans().isEmpty() || timer > 120f){
                    int done = 0;
                    for(BuildPlan p : builtPlans){
                        var t = world.tile(p.x, p.y);
                        if(t != null && t.block() == p.block) done++;
                    }
                    Log.info("[selftest] built @/@ planned blocks (queue left: @)", done, planned, player.unit().plans().size);
                    if(done == planned){ shot = "defense-built"; phase = 13; timer = 0f; }
                    else{ Log.info("[selftest] FAIL: not everything was built"); phase = 0; Core.app.exit(); }
                }
                break;
            case 13:
                keepNearRegion();
                if(timer > 1f){
                    Log.info("[selftest] PASS");
                    if(recDir == null){ phase = 0; Core.app.exit(); break; }
                    // footage: enemy waves against the defense
                    rec = false;
                    for(int i = 0; i < 6; i++) logic.runWave();
                    fastForward = true;
                    phase = 14;
                    timer = 0f;
                }
                break;
            case 14:
                keepNearRegion();
                if(player.unit() != null) player.unit().shield = 1e7f;
                DefenseJob dj = mod.defenseJob;
                float cx = (dj.ax + dj.bx) / 2f * tilesize, cy = (dj.ay + dj.by) / 2f * tilesize;
                boolean near = false;
                for(var u : state.rules.waveTeam.data().units) if(u.within(cx, cy, 45 * tilesize)) near = true;
                if(fastForward && (near || timer > 400f)){ fastForward = false; rec = true; battleStart = timer; Log.info("[selftest] battle footage from @s", timer); }
                if(!fastForward && (state.rules.waveTeam.data().units.isEmpty() || timer - battleStart > 50f)){
                    Log.info("[selftest] footage: @ frames", recFrame);
                    phase = 0;
                    Core.app.exit();
                }
                break;
        }
    }

    Seq<BuildPlan> builtPlans;
    /** footage recording (-Dautobuild.record=dir): frames saved while rec */
    String recDir;
    boolean rec, fastForward;
    int recFrame;
    float battleStart = -1f, holdFrom = -1f;
    float previewSeenAt = -1f;

    void keepNearRegion(){
        BuildJob job = mod.job;
        if(player.unit() != null) player.unit().set((job.ax + job.bx) / 2f * tilesize, (job.ay + job.by) / 2f * tilesize);
    }

    void fail(String msg){
        Log.err("[selftest] FAIL: @", msg);
        phase = 0;
        Core.app.exit();
    }
}
