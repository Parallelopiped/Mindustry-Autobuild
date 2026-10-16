package autobuild;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import autobuild.DrillFieldJob.*;
import autobuild.plan.*;
import autobuild.plan.DrillLayoutPlanner.*;
import mindustry.content.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.mod.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;

import static mindustry.Vars.*;

/**
 * F7 / "AutoBuild" -> drag a region -> settings dialog -> click the ports (item output, water
 * input; right-click = auto) -> background search, replayed as gray ghosts -> preview with
 * Build / Another plan / previous-next / Ports / Edit / Cancel.
 */
public class AutoBuildMod extends Mod{
    enum State{ idle, select, pick, planning, playback, preview }

    /** port kinds the player places by clicking; future building types can add more */
    static final int portOutput = 0, portWater = 1;
    static final String[] portNames = {"Item output", "Water input"};

    State phase = State.idle;
    boolean dragging;
    int x0, y0, x1, y1;

    /** settings persist between uses */
    final DrillFieldJob job = new DrillFieldJob();
    DrillFieldJob.Snapshot snapshot;

    // port picking
    final IntSeq pickQueue = new IntSeq();
    int pickIndex;
    /** hovered port candidate: side, frac; valid */
    float[] hover;
    boolean hoverValid;

    // planning: recorded keyframes (planning thread appends), replayed afterwards
    static class Frame{
        final Seq<BuildPlan> plans;
        final String stage;
        Frame(Seq<BuildPlan> plans, String stage){ this.plans = plans; this.stage = stage; }
    }
    final Seq<Frame> frames = new Seq<>();
    volatile Frame latest;
    volatile String stage = "";
    float playTime;
    int playIndex;
    int planToken;

    // results
    final Seq<Candidate> variants = new Seq<>();
    int shown, nextVariant;

    public AutoBuildMod(){
        Events.on(ClientLoadEvent.class, e -> buildHud());
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);

        String selftest = System.getProperty("autobuild.selftest");
        if(selftest != null) new ClientSelfTest(this, selftest);
    }

    boolean animate(){ return Core.settings.getBool("autobuild-animate", true); }

    Candidate current(){ return variants.isEmpty() ? null : variants.get(shown); }

    void reset(){
        job.cancel();
        phase = State.idle;
        dragging = false;
        variants.clear();
        latest = null;
    }

    // ---------------------------------------------------------------- HUD

    void buildHud(){
        ui.hudGroup.fill(t -> {
            t.bottom().left().marginBottom(120f);
            t.button("AutoBuild", this::toggle).size(110f, 40f).visible(() -> phase == State.idle || phase == State.select);
        });
        // port picking
        ui.hudGroup.fill(t -> {
            t.bottom().marginBottom(120f);
            t.table(Styles.black6, b -> {
                b.label(() -> phase != State.pick ? "" : "Click a tile in the region for the [accent]" + portNames[pickQueue.get(pickIndex)]
                    + "[] (snaps to the nearest edge)\n[lightgray]Right-click or Auto: let the planner choose").pad(8f).row();
                b.table(r -> {
                    r.button("Auto", Icon.refresh, () -> pickPort(-1, 0f)).size(140f, 44f);
                    r.button("Cancel", Icon.cancel, this::reset).size(140f, 44f);
                });
            }).visible(() -> phase == State.pick);
        });
        // planning / playback
        ui.hudGroup.fill(t -> {
            t.bottom().marginBottom(120f);
            t.table(Styles.black6, b -> {
                b.label(() -> (phase == State.planning ? "Planning: " : "Replay: ") + stage).padLeft(10f).padRight(10f).minWidth(300f);
                b.button("Skip", Icon.right, () -> { if(phase == State.playback) phase = State.preview; }).size(110f, 44f).visible(() -> phase == State.playback);
                b.button("Cancel", Icon.cancel, this::cancelPlanning).size(110f, 44f);
            }).visible(() -> phase == State.planning || phase == State.playback);
        });
        // preview (top of the screen, so the region and its south side stay visible)
        ui.hudGroup.fill(t -> {
            t.top().left().marginTop(130f).marginLeft(10f);
            t.table(Styles.black6, b -> {
                b.label(() -> current() == null ? "" : current().summary()).pad(6f).left().wrap().width(640f).row();
                b.label(() -> current() == null ? "" : "[lightgray]" + current().portText() + "   Plan " + (shown + 1) + "/" + variants.size
                    + (isBest(shown) ? " [green](best)" : "") + "   score " + Strings.autoFixed((float)current().score(), 1)).pad(4f).left().row();
                b.table(r -> {
                    r.defaults().size(118f, 44f);
                    r.button("Build", Icon.ok, this::commit);
                    r.button("Another plan", Icon.refresh, this::anotherPlan).size(170f, 44f).tooltip("Search again with different preferences");
                    r.button(Icon.left, Styles.defaulti, () -> show(shown - 1)).size(44f).disabled(x -> shown <= 0);
                    r.button(Icon.right, Styles.defaulti, () -> show(shown + 1)).size(44f).disabled(x -> shown >= variants.size - 1);
                    r.button("Ports", Icon.move, this::startPicking);
                    r.button("Edit", Icon.settings, this::showDialog);
                    r.button("Cancel", Icon.cancel, this::reset);
                });
            }).visible(() -> phase == State.preview && current() != null);
        });
    }

    boolean isBest(int i){
        for(int j = 0; j < variants.size; j++) if(variants.get(j).score() > variants.get(i).score() + 1e-6) return false;
        return true;
    }

    void show(int i){
        shown = Mathf.clamp(i, 0, variants.size - 1);
    }

    void toggle(){
        if(phase == State.select){ phase = State.idle; ui.showInfoToast("AutoBuild: off", 2f); return; }
        if(phase != State.idle) return;
        phase = State.select;
        dragging = false;
        ui.showInfoToast("AutoBuild: drag to select a region", 2f);
    }

    // ---------------------------------------------------------------- input

    void update(){
        if(!Vars.state.isGame() || player == null){
            return;
        }
        if(Core.input.keyTap(KeyCode.f7)) toggle();
        int tx = World.toTile(Core.input.mouseWorldX()), ty = World.toTile(Core.input.mouseWorldY());

        switch(phase){
            case select:
                if(Core.input.keyTap(KeyCode.mouseLeft) && !Core.scene.hasMouse()){
                    dragging = true;
                    x0 = x1 = tx; y0 = y1 = ty;
                }
                if(dragging){
                    x1 = tx; y1 = ty;
                    if(Core.input.keyRelease(KeyCode.mouseLeft)){
                        dragging = false;
                        job.ax = Mathf.clamp(Math.min(x0, x1), 0, world.width() - 1);
                        job.bx = Mathf.clamp(Math.max(x0, x1), 0, world.width() - 1);
                        job.ay = Mathf.clamp(Math.min(y0, y1), 0, world.height() - 1);
                        job.by = Mathf.clamp(Math.max(y0, y1), 0, world.height() - 1);
                        variants.clear();
                        phase = State.idle;
                        showDialog();
                    }
                }
                break;
            case pick:
                // any tile in (or just around) the region snaps to the nearest edge
                boolean near = tx >= job.ax - 3 && tx <= job.bx + 3 && ty >= job.ay - 3 && ty <= job.by + 3;
                hover = near ? job.snap(tx, ty) : null;
                hoverValid = hover != null && portAllowed(pickQueue.get(pickIndex), (int)hover[0]);
                if(Core.input.keyTap(KeyCode.escape)) reset();
                else if(Core.input.keyTap(KeyCode.mouseRight)) pickPort(-1, 0f);
                else if(Core.input.keyTap(KeyCode.mouseLeft) && !Core.scene.hasMouse() && hover != null){
                    if(hoverValid) pickPort((int)hover[0], hover[1]);
                    else ui.showInfoToast("[scarlet]The water input must be on a different side than the item output", 2f);
                }
                break;
            case playback:
                playTime += Time.delta / 60f;
                // at most ~2.5 s however many keyframes were recorded
                float fps = Math.max(20f, frames.size / 2.5f);
                playIndex = (int)(playTime * fps);
                if(playIndex >= frames.size){ phase = State.preview; break; }
                stage = frames.get(playIndex).stage;
                break;
            default:
                break;
        }
    }

    boolean portAllowed(int kind, int side){
        if(kind == portWater && job.outPick >= 0 && side == job.outPick) return false;
        if(kind == portOutput && job.water && job.waterPick >= 0 && side == job.waterPick && pickIndex > 0) return false;
        return true;
    }

    void startPicking(){
        pickQueue.clear();
        pickQueue.add(portOutput);
        if(job.water) pickQueue.add(portWater);
        pickIndex = 0;
        job.outPick = -1;
        job.waterPick = -1;
        phase = State.pick;
    }

    /** side = -1: auto */
    void pickPort(int side, float frac){
        if(phase != State.pick) return;
        int kind = pickQueue.get(pickIndex);
        if(kind == portOutput){ job.outPick = side; job.outFrac = frac; }
        else{ job.waterPick = side; job.waterFrac = frac; }
        if(++pickIndex >= pickQueue.size) startPlanning(true);
    }

    // ---------------------------------------------------------------- dialog

    void showDialog(){
        ObjectIntMap<Item> ores = new ObjectIntMap<>();
        for(int x = job.ax; x <= job.bx; x++) for(int y = job.ay; y <= job.by; y++){
            Tile t = world.tile(x, y);
            // drop() also reports ore hidden under walls; only count reachable floor
            if(t.drop() != null && !t.solid()) ores.increment(t.drop());
        }
        Seq<Item> oreList = new Seq<>();
        for(Item i : ores.keys()) oreList.add(i);
        oreList.sort(i -> -ores.get(i));
        if(job.target == null || !ores.containsKey(job.target)) job.target = oreList.isEmpty() ? null : oreList.first();

        Seq<Drill> drills = content.blocks().select(b -> b instanceof Drill && DrillFieldJob.available(b)).map(b -> (Drill)b);
        drills.sort(d -> d.tier * 100 + d.size);
        Seq<Block> belts = Seq.with(Blocks.conveyor, Blocks.titaniumConveyor).select(DrillFieldJob::available);
        if(job.drill == null || !drills.contains(job.drill)) job.drill = drills.isEmpty() ? null : drills.first();
        if(!belts.contains(job.belt)) job.belt = belts.isEmpty() ? null : belts.first();
        job.team = player.team();

        BaseDialog dialog = new BaseDialog("AutoBuild: drill field");
        Table c = dialog.cont;
        c.defaults().left().pad(3f);
        c.add("Region: " + (job.bx - job.ax + 1) + " x " + (job.by - job.ay + 1) + " tiles").row();

        c.add("Ore:").row();
        c.table(t -> {
            if(oreList.isEmpty()) t.add("[scarlet]no ore in region");
            int i = 0;
            for(Item it : oreList){
                t.button(b -> {
                    b.image(it.uiIcon).size(24f).padRight(4f);
                    b.add(it.localizedName + " (" + ores.get(it) + ")");
                }, Styles.flatTogglet, () -> {
                    job.target = it;
                    // pick the cheapest drill that can mine it
                    if(job.drill == null || job.drill.tier < it.hardness) job.drill = drills.find(d -> d.tier >= it.hardness);
                }).checked(b -> job.target == it).size(190f, 40f);
                if(++i % 4 == 0) t.row();
            }
        }).row();

        c.add("Drill:").row();
        c.table(t -> {
            int i = 0;
            for(Drill d : drills){
                String tags = (DrillFieldJob.needsPower(d) ? " [accent]⚡" : "") + (DrillFieldJob.needsLiquid(d) ? " [sky]liquid!" : "");
                t.button(bt -> {
                    bt.image(d.uiIcon).size(24f).padRight(4f);
                    bt.add(d.localizedName + " [lightgray]T" + d.tier + " " + d.size + "x" + d.size + tags).growX();
                }, Styles.flatTogglet, () -> job.drill = d).checked(bt -> job.drill == d)
                    .disabled(bt -> job.target != null && d.tier < job.target.hardness).size(290f, 40f);
                if(++i % 2 == 0) t.row();
            }
        }).row();

        c.add("Belt:").row();
        c.table(t -> {
            if(belts.isEmpty()) t.add("[scarlet]no supported conveyor available here");
            for(Block b : belts){
                float speed = b instanceof Conveyor cv ? cv.displayedSpeed : 0f;
                t.button(bt -> {
                    bt.image(b.uiIcon).size(24f).padRight(4f);
                    bt.add(b.localizedName + " [lightgray]" + Strings.autoFixed(speed, 1) + "/s");
                }, Styles.flatTogglet, () -> job.belt = b).checked(bt -> job.belt == b).size(240f, 40f);
            }
        }).row();

        c.check("Use item bridges (denser layouts, routes over obstacles)", job.bridges, b -> job.bridges = b)
            .disabled(b -> !DrillFieldJob.available(Blocks.itemBridge)).row();
        c.check("Fill gaps with smaller drills (up to the chosen tier)", job.mixDrills, b -> job.mixDrills = b).row();

        Seq<Block> conduits = Seq.with(Blocks.conduit, Blocks.pulseConduit).select(DrillFieldJob::available);
        if(!conduits.contains(job.conduit) && !conduits.isEmpty()) job.conduit = conduits.first();
        c.table(t -> {
            t.check("Water boost", job.water, b -> job.water = b)
                .disabled(b -> job.drill == null || DrillFieldJob.waterBoost(job.drill) == null || !DrillFieldJob.waterAvailable(job.conduit)).padRight(10f);
            for(Block b : conduits){
                t.button(bt -> bt.image(b.uiIcon).size(24f), Styles.flatTogglet, () -> job.conduit = b).checked(bt -> job.conduit == b)
                    .disabled(bt -> !job.water).size(40f, 36f).padRight(4f).tooltip(b.localizedName);
            }
            t.label(() -> job.drill == null || DrillFieldJob.waterBoost(job.drill) == null ? "" :
                "[lightgray]x" + Strings.autoFixed(job.drill.liquidBoostIntensity * job.drill.liquidBoostIntensity, 2) + " speed, "
                + Strings.autoFixed(DrillFieldJob.waterBoost(job.drill).amount * 60f, 1) + " water/s per drill").padLeft(6f);
        }).row();

        c.add("Optimize for:").row();
        c.table(t -> {
            t.button("Max output", Styles.flatTogglet, () -> job.mode = Mode.OUTPUT).checked(b -> job.mode == Mode.OUTPUT).size(170f, 40f);
            t.button("Material efficiency", Styles.flatTogglet, () -> job.mode = Mode.EFFICIENCY).checked(b -> job.mode == Mode.EFFICIENCY).size(220f, 40f);
        }).row();

        c.check("Replay the search as a gray preview", animate(), b -> Core.settings.put("autobuild-animate", b)).row();
        c.add("[lightgray]Next you click where items leave (and water enters) the region; right-click lets the planner choose.").row();

        boolean editing = !variants.isEmpty();
        dialog.buttons.button("Cancel", Icon.cancel, () -> { dialog.hide(); if(!editing) reset(); }).size(170f, 54f);
        if(editing){
            dialog.buttons.button("Re-plan", Icon.refresh, () -> { dialog.hide(); startPlanning(true); }).size(170f, 54f).disabled(b -> !ready());
        }
        dialog.buttons.button("Pick ports", Icon.right, () -> { dialog.hide(); startPicking(); }).size(170f, 54f).disabled(b -> !ready());
        dialog.show();
    }

    boolean ready(){
        return job.target != null && job.drill != null && job.belt != null && job.drill.tier >= job.target.hardness;
    }

    // ---------------------------------------------------------------- planning

    /** fresh: new search (best of a few variants); otherwise "another plan" (next variant, kept with the others) */
    void startPlanning(boolean fresh){
        snapshot = job.prepare();
        if(snapshot == null){
            ui.showInfoToast("[scarlet]AutoBuild: " + job.error, 4f);
            phase = variants.isEmpty() ? State.idle : State.preview;
            return;
        }
        if(fresh){ variants.clear(); nextVariant = 0; }
        int base = nextVariant, count = fresh ? 3 : 1;
        nextVariant += count;
        DrillFieldJob.Snapshot sn = snapshot;
        int token = ++planToken;
        synchronized(frames){ frames.clear(); }
        latest = null;
        stage = "snapshot";
        phase = State.planning;

        Progress progress = (cand, st, places) -> {
            Frame f = new Frame(cand.toPlans(places), DrillFieldJob.dirNames[cand.outDir] + " #" + cand.variant + ": " + st);
            latest = f;
            stage = f.stage;
            synchronized(frames){
                frames.add(f);
                // keep the replay bounded: drop every other frame when it gets long
                if(frames.size > 300){
                    Seq<Frame> keep = new Seq<>();
                    for(int i = 0; i < frames.size; i += 2) keep.add(frames.get(i));
                    frames.set(keep);
                }
            }
        };
        Thread worker = new Thread(() -> {
            try{
                Candidate best = job.search(sn, base, count, progress);
                // "another plan" should actually look different
                for(int tries = 0; !fresh && best != null && best.ok() && tries < 4 && duplicate(best); tries++){
                    int v = nextVariant++;
                    best = job.search(sn, v, 1, progress);
                }
                Candidate result = best;
                Core.app.post(() -> finished(token, result));
            }catch(CancelledException e){
                // reset() already moved on
            }catch(Throwable e){
                Log.err("[autobuild] planner crashed", e);
                Core.app.post(() -> {
                    if(token != planToken) return;
                    phase = variants.isEmpty() ? State.idle : State.preview;
                    ui.showInfoToast("[scarlet]AutoBuild planner crashed: " + e, 5f);
                });
            }
        }, "autobuild-planner");
        worker.setDaemon(true);
        worker.start();
    }

    boolean duplicate(Candidate c){
        for(Candidate o : variants) if(o.signature.equals(c.signature)) return true;
        return false;
    }

    void finished(int token, Candidate result){
        if(token != planToken || phase != State.planning) return;
        if(result == null || !result.ok()){
            ui.showInfoToast("[scarlet]AutoBuild failed: " + (result == null ? "nothing to plan" : result.error), 4f);
            phase = variants.isEmpty() ? State.idle : State.preview;
            return;
        }
        variants.add(result);
        shown = variants.size - 1;
        playTime = 0f; playIndex = 0;
        phase = animate() && frames.size > 1 ? State.playback : State.preview;
    }

    void anotherPlan(){
        if(phase == State.preview) startPlanning(false);
    }

    void cancelPlanning(){
        job.cancel();
        planToken++;
        phase = variants.isEmpty() ? State.idle : State.preview;
    }

    void commit(){
        Candidate c = current();
        if(c == null) return;
        if(player.unit() == null || !player.unit().canBuild()){
            ui.showInfoToast("[scarlet]Your unit can't build", 3f);
            return;
        }
        for(BuildPlan p : c.plans) player.unit().addBuild(p.copy());
        ui.showInfoToast("Queued " + c.plans.size + " build plans", 3f);
        reset();
    }

    // ---------------------------------------------------------------- drawing

    static void rect(int ax, int ay, int bx, int by){
        Lines.rect(ax * tilesize - tilesize / 2f, ay * tilesize - tilesize / 2f, (bx - ax + 1) * tilesize, (by - ay + 1) * tilesize);
    }

    static void arrow(float px, float py, float ang){
        Fill.tri(px + Angles.trnsx(ang, 12f), py + Angles.trnsy(ang, 12f),
            px + Angles.trnsx(ang + 90f, 4f) + Angles.trnsx(ang, 6f), py + Angles.trnsy(ang + 90f, 4f) + Angles.trnsy(ang, 6f),
            px + Angles.trnsx(ang - 90f, 4f) + Angles.trnsx(ang, 6f), py + Angles.trnsy(ang - 90f, 4f) + Angles.trnsy(ang, 6f));
    }

    static void label(String text, float x, float y){
        WorldLabel.drawAt(text, x, y, Layer.overlayUI + 1f, WorldLabel.flagOutline | WorldLabel.flagBackground, 1f, Align.center, Align.center);
    }

    /** port marker at a world tile on side `side`; output points out of the region, input points in */
    static void port(int kind, int tx, int ty, int side, String text, boolean valid){
        float px = tx * tilesize, py = ty * tilesize;
        float ang = side * 90f + (kind == portOutput ? 0f : 180f);
        Draw.z(Layer.overlayUI);
        Draw.color(!valid ? Color.scarlet : kind == portOutput ? Pal.remove : Pal.water);
        Lines.stroke(1.5f);
        Lines.square(px, py, tilesize / 2f + 1f);
        float ox = Geometry.d4x(side) * tilesize, oy = Geometry.d4y(side) * tilesize;
        if(kind == portOutput) arrow(px + ox * 0.5f, py + oy * 0.5f, ang);
        else arrow(px + ox * 1.5f, py + oy * 1.5f, ang);
        Draw.reset();
        if(text != null) label(text, px + ox * 3f, py + oy * 3f + (side % 2 == 0 ? tilesize : 0f));
    }

    void ghosts(Seq<BuildPlan> plans){
        Draw.z(Layer.plans);
        Draw.color(0.62f, 0.62f, 0.62f, 0.55f);
        for(BuildPlan p : plans) Draw.rect(p.block.fullIcon, p.drawx(), p.drawy(), p.block.rotate ? p.rotation * 90f : 0f);
        Lines.stroke(1f, Color.gray);
        for(BuildPlan p : plans){
            if(p.config instanceof Point2 l && (l.x != 0 || l.y != 0)) Lines.line(p.drawx(), p.drawy(), (p.x + l.x) * tilesize, (p.y + l.y) * tilesize);
        }
        Draw.reset();
    }

    void draw(){
        if(phase == State.select && dragging){
            Draw.z(Layer.overlayUI);
            Lines.stroke(2f, Pal.accent);
            rect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
            Draw.reset();
            return;
        }
        if(phase == State.idle || phase == State.select) return;

        Draw.z(Layer.overlayUI);
        Lines.stroke(1.5f, phase == State.preview ? Pal.accent : Color.lightGray);
        rect(job.ax, job.ay, job.bx, job.by);
        Draw.reset();

        switch(phase){
            case pick: {
                // ports picked so far, then the one under the cursor
                for(int i = 0; i < pickIndex; i++){
                    int kind = pickQueue.get(i), side = kind == portOutput ? job.outPick : job.waterPick;
                    float f = kind == portOutput ? job.outFrac : job.waterFrac;
                    if(side < 0) continue;
                    int[] t = job.edgeTile(side, f);
                    port(kind, t[0], t[1], side, portNames[kind], true);
                }
                if(hover != null){
                    int kind = pickQueue.get(pickIndex);
                    int[] t = job.edgeTile((int)hover[0], hover[1]);
                    port(kind, t[0], t[1], (int)hover[0], hoverValid ? portNames[kind] + "?" : "not on the output side", hoverValid);
                }
                break;
            }
            case planning: {
                Frame f = latest;
                if(f != null) ghosts(f.plans);
                break;
            }
            case playback: {
                Frame f;
                synchronized(frames){ f = playIndex < frames.size ? frames.get(playIndex) : null; }
                if(f != null) ghosts(f.plans);
                break;
            }
            case preview: {
                Candidate c = current();
                if(c == null) break;
                // like a schematic being placed: normal ghosts, red where the tile is no longer free
                Draw.z(Layer.plans);
                for(BuildPlan p : c.plans){
                    boolean valid = Build.validPlace(p.block, job.team, p.x, p.y, p.rotation);
                    // drawPlan scales by animScale, which the game only animates for the player's own queue (starts at 0)
                    p.animScale = 1f;
                    p.block.drawPlan(p, c.plans, valid);
                }
                Lines.stroke(1f, Pal.accent);
                for(BuildPlan p : c.plans){
                    if(p.config instanceof Point2 l && (l.x != 0 || l.y != 0)) Lines.line(p.drawx(), p.drawy(), (p.x + l.x) * tilesize, (p.y + l.y) * tilesize);
                }
                Draw.reset();
                port(portOutput, c.portX, c.portY, c.outDir, "Item output" + (c.outAuto ? " (auto)" : ""), true);
                if(c.waterPortX >= 0) port(portWater, c.waterPortX, c.waterPortY, c.waterDir, "Water input" + (c.waterAuto ? " (auto)" : ""), true);
                break;
            }
            default: break;
        }
    }

    // ---------------------------------------------------------------- headless test

    @Override
    public void registerServerCommands(CommandHandler handler){
        ServerTest.register(handler);
    }
}
