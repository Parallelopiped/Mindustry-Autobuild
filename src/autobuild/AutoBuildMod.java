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
    static final String[] portPlural = {"item outputs", "water inputs"};

    State phase = State.idle;
    boolean dragging;
    int x0, y0, x1, y1;

    /** settings persist between uses */
    final DrillFieldJob job = new DrillFieldJob();
    DrillFieldJob.Snapshot snapshot;

    // port picking: several item outputs, then (with water) several water inputs
    int pickKind;
    /** "Add output": keep the current outputs and add more */
    boolean addingOutputs;
    DrillFieldJob.Port hover;
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
                b.label(() -> phase != State.pick ? "" : "Click tiles for the [accent]" + portPlural[pickKind] + "[] (" + picks().size + " picked)\n"
                    + "[lightgray]On the border: " + (pickKind == portOutput ? "the belt leaves the region there" : "connect your pipe from outside")
                    + ". Inside: " + (pickKind == portOutput ? "items are delivered into that tile (put your conveyor / core there)" : "a liquid router you feed")
                    + "\nRight-click / Done: continue.  Auto: let the planner choose").pad(8f).row();
                b.table(r -> {
                    r.defaults().size(130f, 44f);
                    r.button("Done", Icon.ok, this::finishKind).disabled(x -> picks().isEmpty());
                    r.button("Auto", Icon.refresh, this::autoKind);
                    r.button("Undo", Icon.undo, () -> { if(!picks().isEmpty()) picks().pop(); }).disabled(x -> picks().isEmpty());
                    r.button("Cancel", Icon.cancel, this::reset);
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
                    r.button("Add output", Icon.add, this::addOutputs).size(150f, 44f)
                        .tooltip("Pick more item outputs (one belt line can only carry so much)");
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
                // inside the region: an interior port; on / near the border: snapped to the nearest edge
                boolean near = tx >= job.ax - 3 && tx <= job.bx + 3 && ty >= job.ay - 3 && ty <= job.by + 3;
                hover = near ? job.classify(tx, ty) : null;
                hoverValid = hover != null && portAllowed(hover);
                if(Core.input.keyTap(KeyCode.escape)) reset();
                else if(Core.input.keyTap(KeyCode.mouseRight)){ if(picks().isEmpty()) autoKind(); else finishKind(); }
                else if(Core.input.keyTap(KeyCode.mouseLeft) && !Core.scene.hasMouse() && hover != null){
                    if(hoverValid) addPick(hover);
                    else ui.showInfoToast("[scarlet]" + hoverReason(), 2f);
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

    Seq<DrillFieldJob.Port> picks(){ return pickKind == portOutput ? job.outs : job.waters; }

    /** the tile a port's marker sits on */
    int[] markerTile(int kind, DrillFieldJob.Port p){
        return p.edge ? job.edgeTile(p.side, p.frac) : new int[]{p.x, p.y};
    }

    String hoverReason(){
        String e = hover == null ? null : DrillFieldJob.existingError(hover, pickKind == portOutput);
        return e != null ? e : "That tile is already a port";
    }

    /** one port per tile; an existing building must be able to take items / give water */
    boolean portAllowed(DrillFieldJob.Port p){
        if(DrillFieldJob.existingError(p, pickKind == portOutput) != null) return false;
        int[] t = markerTile(pickKind, p);
        for(int kind = 0; kind < 2; kind++){
            for(DrillFieldJob.Port o : kind == portOutput ? job.outs : job.waters){
                int[] q = markerTile(kind, o);
                if(q[0] == t[0] && q[1] == t[1]) return false;
            }
        }
        return true;
    }

    void startPicking(){
        job.outs.clear();
        job.waters.clear();
        pickKind = portOutput;
        addingOutputs = false;
        phase = State.pick;
    }

    /** "Add output": keep the current plan's ports (also auto-chosen ones) and pick more outputs */
    void addOutputs(){
        Candidate c = current();
        if(c == null) return;
        job.outs.set(c.outPorts);
        if(job.water) job.waters.set(c.waterPorts);
        pickKind = portOutput;
        addingOutputs = true;
        phase = State.pick;
    }

    void addPick(DrillFieldJob.Port p){
        if(phase != State.pick) return;
        picks().add(p);
    }

    /** this kind is done: next kind, or plan */
    void finishKind(){
        if(phase != State.pick) return;
        if(pickKind == portOutput && job.water && !addingOutputs){ pickKind = portWater; return; }
        startPlanning(true);
    }

    /** let the planner choose this kind */
    void autoKind(){
        picks().clear();
        finishKind();
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

    /**
     * Port marker. Edge output: arrow out of the region; edge water input: arrow into it; interior
     * port: a framed tile (items are delivered into it / water router there).
     */
    void port(int kind, DrillFieldJob.Port p, String text, boolean valid){
        int[] t = markerTile(kind, p);
        float px = t[0] * tilesize, py = t[1] * tilesize;
        Draw.z(Layer.overlayUI);
        Draw.color(!valid ? Color.scarlet : kind == portOutput ? Pal.remove : Pal.water);
        Lines.stroke(1.5f);
        Lines.square(px, py, tilesize / 2f + 1f);
        float lx = px, ly = py + tilesize * 2f;
        if(p.edge){
            float ang = p.side * 90f + (kind == portOutput ? 0f : 180f);
            float ox = Geometry.d4x(p.side) * tilesize, oy = Geometry.d4y(p.side) * tilesize;
            if(kind == portOutput) arrow(px + ox * 0.5f, py + oy * 0.5f, ang);
            else arrow(px + ox * 1.5f, py + oy * 1.5f, ang);
            lx = px + ox * 3f; ly = py + oy * 3f + (p.side % 2 == 0 ? tilesize : 0f);
        }else if(p.existing != null){
            // an existing building: frame it whole
            float off = (p.existing.block.size % 2 == 0) ? tilesize / 2f : 0f;
            Lines.square(px + off, py + off, p.existing.block.size * tilesize / 2f + 2f);
            ly = py + (p.existing.block.size + 2) * tilesize / 2f;
        }else{
            Lines.square(px, py, tilesize / 2f + 3f, 45f);
        }
        Draw.reset();
        if(text != null) label(text, lx, ly);
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
                for(int i = 0; i < job.outs.size; i++) port(portOutput, job.outs.get(i), portNames[portOutput] + " " + (i + 1), true);
                for(int i = 0; i < job.waters.size; i++) port(portWater, job.waters.get(i), portNames[portWater] + " " + (i + 1), true);
                if(hover != null){
                    String what = hover.existing != null ? (pickKind == portOutput ? " into " : " from ") + hover.existing.block.localizedName + "?"
                        : hover.edge ? " (edge)?" : " here?";
                    port(pickKind, hover, hoverValid ? portNames[pickKind] + what : hoverReason(), hoverValid);
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
                    boolean valid = DrillFieldJob.placeable(p, job.team);
                    // drawPlan scales by animScale, which the game only animates for the player's own queue (starts at 0)
                    p.animScale = 1f;
                    p.block.drawPlan(p, c.plans, valid);
                }
                Lines.stroke(1f, Pal.accent);
                for(BuildPlan p : c.plans){
                    if(p.config instanceof Point2 l && (l.x != 0 || l.y != 0)) Lines.line(p.drawx(), p.drawy(), (p.x + l.x) * tilesize, (p.y + l.y) * tilesize);
                }
                Draw.reset();
                for(int k = 0; k < c.outPorts.size; k++){
                    String load = k < c.loads.length ? Strings.autoFixed((float)c.loads[k], 1) + "/" + Strings.autoFixed(c.beltCapacity, 1) + " per s" : "";
                    port(portOutput, c.outPorts.get(k), (c.outPorts.size > 1 ? "Output " + (k + 1) : "Item output") + (c.outAuto ? " (auto)" : "") + "  " + load,
                        k >= c.loads.length || !c.overloaded(k));
                }
                for(int k = 0; k < c.waterPorts.size; k++){
                    port(portWater, c.waterPorts.get(k), (c.waterPorts.size > 1 ? "Water input " + (k + 1) : "Water input") + (c.waterAuto ? " (auto)" : ""), true);
                }
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
