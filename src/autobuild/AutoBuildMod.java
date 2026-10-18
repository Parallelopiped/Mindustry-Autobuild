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
import autobuild.plan.DrillLayoutPlanner.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.mod.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/**
 * F7 / "AutoBuild" -> drag a region -> settings dialog (one tab per facility: drill field,
 * defense) -> click the ports of each kind the job needs (right-click = auto) -> background
 * search, replayed as gray ghosts -> preview with Build / Another plan / previous-next / Ports /
 * Add ... / Edit / Cancel. Everything facility-specific lives in the {@link BuildJob}s.
 */
public class AutoBuildMod extends Mod{
    enum State{ idle, select, pick, planning, playback, preview }

    State phase = State.idle;
    boolean dragging;
    int x0, y0, x1, y1;

    /** facilities; their settings persist between uses */
    final DrillFieldJob drillJob = new DrillFieldJob();
    final DefenseJob defenseJob = new DefenseJob();
    final Seq<BuildJob> features = Seq.with(drillJob, defenseJob);
    BuildJob job = drillJob;
    Object snapshot;

    // port picking: every kind the job needs, in order
    Seq<PortKind> kinds = new Seq<>();
    int pickIndex;
    /** "Add ...": keep the current ports and add more of one kind */
    boolean addingPorts;
    Port hover;
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
    final Seq<PlanResult> variants = new Seq<>();
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

    PlanResult current(){ return variants.isEmpty() ? null : variants.get(shown); }

    PortKind pickKind(){ return kinds.get(pickIndex); }

    Seq<Port> picks(){ return pickKind().picks; }

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
                b.label(() -> phase != State.pick ? "" : "Click tiles for the [accent]" + pickKind().plural + "[] (" + picks().size + " picked)\n"
                    + "[lightgray]" + (pickKind().anywhere ? "Anywhere outside the region: " + pickKind().interiorHelp
                        : "On the border: " + pickKind().edgeHelp + ". Inside: " + pickKind().interiorHelp)
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
                    r.button(b2 -> b2.label(() -> job.extraKind() == null ? "" : "Add " + job.extraKind().name.toLowerCase()), Styles.defaultt, this::addPorts)
                        .size(170f, 44f).visible(() -> job.extraKind() != null).tooltip("Pick more ports of this kind");
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
                        selectRegion(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
                        variants.clear();
                        phase = State.idle;
                        showDialog();
                    }
                }
                break;
            case pick:
                // inside the region: an interior port; on / near the border: snapped to the nearest edge
                boolean near = tx >= job.ax - 3 && tx <= job.bx + 3 && ty >= job.ay - 3 && ty <= job.by + 3;
                hover = pickKind().anywhere ? (world.tile(tx, ty) == null ? null : job.classifyAnywhere(tx, ty)) : near ? job.classify(tx, ty) : null;
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

    /** the same region for every facility */
    void selectRegion(int ax, int ay, int bx, int by){
        ax = Mathf.clamp(ax, 0, world.width() - 1); bx = Mathf.clamp(bx, 0, world.width() - 1);
        ay = Mathf.clamp(ay, 0, world.height() - 1); by = Mathf.clamp(by, 0, world.height() - 1);
        for(BuildJob j : features){
            j.team = player.team();
            j.setRegion(ax, ay, bx, by);
        }
    }

    String hoverReason(){
        String e = hover == null ? null : pickKind().error(hover);
        if(e == null && hover != null) e = job.portError(pickKind(), hover);
        return e != null ? e : "That tile is already a port";
    }

    /** one port per tile; an existing building must suit the kind */
    boolean portAllowed(Port p){
        if(pickKind().error(p) != null || job.portError(pickKind(), p) != null) return false;
        int[] t = job.portTile(p);
        for(PortKind k : kinds){
            for(Port o : k.picks){
                int[] q = job.portTile(o);
                if(q[0] == t[0] && q[1] == t[1]) return false;
            }
        }
        return true;
    }

    void startPicking(){
        kinds = job.portKinds();
        for(PortKind k : kinds) k.picks.clear();
        pickIndex = 0;
        addingPorts = false;
        phase = kinds.isEmpty() ? State.idle : State.pick;
        if(kinds.isEmpty()) startPlanning(true);
    }

    /** "Add ...": keep the current plan's ports (also auto-chosen ones) and pick more of the extra kind */
    void addPorts(){
        PlanResult c = current();
        PortKind extra = job.extraKind();
        if(c == null || extra == null) return;
        kinds = job.portKinds();
        for(PortKind k : kinds) k.picks.set(c.ports(k));
        pickIndex = Math.max(0, kinds.indexOf(extra));
        addingPorts = true;
        phase = State.pick;
    }

    void addPick(Port p){
        if(phase != State.pick) return;
        picks().add(p);
    }

    /** this kind is done: next kind, or plan */
    void finishKind(){
        if(phase != State.pick) return;
        if(!addingPorts && pickIndex < kinds.size - 1){ pickIndex++; return; }
        startPlanning(true);
    }

    /** let the planner choose this kind */
    void autoKind(){
        picks().clear();
        finishKind();
    }

    // ---------------------------------------------------------------- dialog

    void showDialog(){
        boolean editing = !variants.isEmpty();
        BaseDialog dialog = new BaseDialog("AutoBuild");
        Runnable[] rebuild = {null};
        rebuild[0] = () -> {
            Table c = dialog.cont;
            c.clear();
            c.defaults().left().pad(3f);
            c.table(t -> {
                for(BuildJob f : features){
                    t.button(f.name(), Styles.flatTogglet, () -> {
                        if(job == f) return;
                        job = f;
                        variants.clear();
                        rebuild[0].run();
                    }).checked(b -> job == f).size(200f, 46f).disabled(b -> editing && job != f);
                }
                t.add("  Region: " + (job.bx - job.ax + 1) + " x " + (job.by - job.ay + 1) + " tiles").padLeft(10f);
            }).row();
            c.image().color(Pal.accent).height(3f).growX().padBottom(6f).row();
            c.pane(p -> {
                p.defaults().left().pad(3f);
                job.settings(p, rebuild[0]);
                p.check("Replay the search as a gray preview", animate(), b -> Core.settings.put("autobuild-animate", b)).row();
            }).grow().maxHeight(Core.graphics.getHeight() * 0.75f / Scl.scl()).row();
        };
        rebuild[0].run();
        dialog.buttons.button("Cancel", Icon.cancel, () -> { dialog.hide(); if(!editing) reset(); }).size(170f, 54f);
        if(editing){
            dialog.buttons.button("Re-plan", Icon.refresh, () -> { dialog.hide(); startPlanning(true); }).size(170f, 54f).disabled(b -> !job.ready());
        }
        dialog.buttons.button("Pick ports", Icon.right, () -> { dialog.hide(); startPicking(); }).size(170f, 54f).disabled(b -> !job.ready());
        dialog.show();
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
        Object sn = snapshot;
        BuildJob j = job;
        int token = ++planToken;
        synchronized(frames){ frames.clear(); }
        latest = null;
        stage = "snapshot";
        phase = State.planning;

        BuildJob.Progress progress = (st, plans) -> {
            Frame f = new Frame(plans, st);
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
                PlanResult best = j.search(sn, base, count, progress);
                // "another plan" should actually look different
                for(int tries = 0; !fresh && best != null && best.ok() && tries < 4 && duplicate(best); tries++){
                    int v = nextVariant++;
                    best = j.search(sn, v, 1, progress);
                }
                PlanResult result = best;
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

    boolean duplicate(PlanResult c){
        for(PlanResult o : variants) if(o.signature.equals(c.signature)) return true;
        return false;
    }

    void finished(int token, PlanResult result){
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
        PlanResult c = current();
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
     * Port marker. Edge output: arrow out of the region; edge input: arrow into it; interior port:
     * a diamond; an existing building: framed whole.
     */
    void port(PortKind kind, Port p, String text, boolean valid){ port(kind, p, text, valid, 0); }

    /** `row`: labels of different kinds are stacked so neighbouring ports stay readable */
    void port(PortKind kind, Port p, String text, boolean valid, int row){
        int[] t = job.portTile(p);
        float px = t[0] * tilesize, py = t[1] * tilesize;
        Draw.z(Layer.overlayUI);
        Draw.color(!valid ? Color.scarlet : kind.color);
        Lines.stroke(1.5f);
        Lines.square(px, py, tilesize / 2f + 1f);
        float lx = px, ly = py + tilesize * 2f;
        if(p.edge){
            float ang = p.side * 90f + (kind.input ? 180f : 0f);
            float ox = Geometry.d4x(p.side) * tilesize, oy = Geometry.d4y(p.side) * tilesize;
            if(!kind.input) arrow(px + ox * 0.5f, py + oy * 0.5f, ang);
            else arrow(px + ox * 1.5f, py + oy * 1.5f, ang);
            lx = px + ox * 3f; ly = py + oy * 3f + (p.side % 2 == 0 ? tilesize : 0f);
        }else if(p.existing != null){
            float off = (p.existing.block.size % 2 == 0) ? tilesize / 2f : 0f;
            Lines.square(px + off, py + off, p.existing.block.size * tilesize / 2f + 2f);
            ly = py + (p.existing.block.size + 2) * tilesize / 2f;
        }else{
            Lines.square(px, py, tilesize / 2f + 3f, 45f);
        }
        Draw.reset();
        if(text != null) label(text, lx, ly + row * tilesize * 1.6f);
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
        job.drawOverlay(phase == State.preview ? current() : null);

        switch(phase){
            case pick: {
                // ports picked so far, then the one under the cursor
                for(PortKind k : kinds){
                    for(int i = 0; i < k.picks.size; i++) port(k, k.picks.get(i), k.name + " " + (i + 1), true);
                }
                if(hover != null){
                    PortKind k = pickKind();
                    String what = hover.existing != null ? (k.input ? " from " : " into ") + hover.existing.block.localizedName + "?"
                        : hover.edge ? " (edge)?" : " here?";
                    port(k, hover, hoverValid ? k.name + what : hoverReason(), hoverValid);
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
                PlanResult c = current();
                if(c == null) break;
                // like a schematic being placed: normal ghosts, red where the tile is no longer free
                Draw.z(Layer.plans);
                for(BuildPlan p : c.plans){
                    boolean valid = BuildJob.placeable(p, job.team);
                    // drawPlan scales by animScale, which the game only animates for the player's own queue (starts at 0)
                    p.animScale = 1f;
                    p.block.drawPlan(p, c.plans, valid);
                }
                Lines.stroke(1f, Pal.accent);
                for(BuildPlan p : c.plans){
                    if(p.config instanceof Point2 l && (l.x != 0 || l.y != 0)) Lines.line(p.drawx(), p.drawy(), (p.x + l.x) * tilesize, (p.y + l.y) * tilesize);
                }
                Draw.reset();
                int row = 0;
                for(PortKind k : job.portKinds()){
                    Seq<Port> ps = c.ports(k);
                    if(ps.isEmpty()) continue;
                    for(int i = 0; i < ps.size; i++){
                        String extra = c.portLabel(k, i);
                        port(k, ps.get(i), (ps.size > 1 ? k.name + " " + (i + 1) : k.name) + (c.auto(k) ? " (auto)" : "") + (extra == null ? "" : "  " + extra), c.portOk(k, i), row);
                    }
                    row++;
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
        ServerDefenseTest.register(handler);
    }
}
