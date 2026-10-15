package autobuild;

import arc.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.DrillLayoutPlanner.*;
import mindustry.content.*;
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
 * F7 / "AutoBuild" button -> drag a region -> configure (ore, drill, belt, mode, output side,
 * port position) -> preview ghosts -> Build.
 */
public class AutoBuildMod extends Mod{
    boolean selecting, dragging;
    int x0, y0, x1, y1;

    /** settings persist between uses; also holds the last result */
    final DrillFieldJob job = new DrillFieldJob();
    Seq<BuildPlan> preview;

    public AutoBuildMod(){
        Events.on(ClientLoadEvent.class, e -> {
            ui.hudGroup.fill(t -> {
                t.bottom().left().marginBottom(120f);
                t.button("AutoBuild", this::toggle).size(110f, 40f).visible(() -> preview == null);
            });
            ui.hudGroup.fill(t -> {
                t.bottom().marginBottom(120f);
                t.table(Styles.black6, b -> {
                    b.button("Build", Icon.ok, this::commit).size(120f, 44f);
                    b.button("Edit", Icon.settings, this::showDialog).size(120f, 44f);
                    b.button("Cancel", Icon.cancel, () -> preview = null).size(120f, 44f);
                }).visible(() -> preview != null);
            });
        });
        Events.on(WorldLoadEvent.class, e -> { preview = null; selecting = dragging = false; });

        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);
    }

    void toggle(){
        selecting = !selecting;
        dragging = false;
        preview = null;
        ui.showInfoToast(selecting ? "AutoBuild: drag to select a region" : "AutoBuild: off", 2f);
    }

    void update(){
        if(!state.isGame() || player == null) return;
        if(Core.input.keyTap(KeyCode.f7)) toggle();
        if(!selecting) return;

        int tx = World.toTile(Core.input.mouseWorldX()), ty = World.toTile(Core.input.mouseWorldY());
        if(Core.input.keyTap(KeyCode.mouseLeft) && !Core.scene.hasMouse()){
            dragging = true;
            x0 = x1 = tx; y0 = y1 = ty;
        }
        if(dragging){
            x1 = tx; y1 = ty;
            if(Core.input.keyRelease(KeyCode.mouseLeft)){
                dragging = selecting = false;
                job.ax = Mathf.clamp(Math.min(x0, x1), 0, world.width() - 1);
                job.bx = Mathf.clamp(Math.max(x0, x1), 0, world.width() - 1);
                job.ay = Mathf.clamp(Math.min(y0, y1), 0, world.height() - 1);
                job.by = Mathf.clamp(Math.max(y0, y1), 0, world.height() - 1);
                showDialog();
            }
        }
    }

    // ---------------------------------------------------------------- UI

    static boolean available(Block b){
        return b.isVisible() && b.environmentBuildable() && (state.rules.infiniteResources || b.unlockedNow());
    }

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

        Seq<Drill> drills = content.blocks().select(b -> b instanceof Drill && available(b)).map(b -> (Drill)b);
        drills.sort(d -> d.tier * 100 + d.size);
        Seq<Block> belts = Seq.with(Blocks.conveyor, Blocks.titaniumConveyor).select(AutoBuildMod::available);
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

        c.add("Optimize for:").row();
        c.table(t -> {
            t.button("Max output", Styles.flatTogglet, () -> job.mode = Mode.OUTPUT).checked(b -> job.mode == Mode.OUTPUT).size(170f, 40f);
            t.button("Material efficiency", Styles.flatTogglet, () -> job.mode = Mode.EFFICIENCY).checked(b -> job.mode == Mode.EFFICIENCY).size(220f, 40f);
        }).row();

        c.add("Output side:").row();
        c.table(t -> {
            for(int i = 0; i < 4; i++){
                int dir = i;
                t.button(DrillFieldJob.dirNames[i], Styles.flatTogglet, () -> job.outDir = dir).checked(b -> job.outDir == dir).size(100f, 40f);
            }
        }).row();

        c.add("Port position along that edge (left/bottom -> right/top):").row();
        c.table(t -> {
            Label l = new Label("");
            t.slider(0f, 1f, 0.01f, job.portFrac, v -> job.portFrac = v).width(300f);
            t.add(l).padLeft(8f);
            l.update(() -> l.setText(Mathf.round(job.portFrac * 100) + "%"));
        }).row();

        dialog.buttons.button("Cancel", Icon.cancel, () -> { dialog.hide(); preview = null; }).size(160f, 54f);
        dialog.buttons.button("Preview", Icon.ok, () -> {
            dialog.hide();
            plan();
        }).size(160f, 54f).disabled(b -> job.target == null || job.drill == null || job.belt == null || job.drill.tier < job.target.hardness);
        dialog.show();
    }

    void plan(){
        if(!job.run()){
            ui.showInfoToast("[scarlet]AutoBuild failed: " + job.error, 4f);
            preview = null;
            return;
        }
        preview = new Seq<>(job.plans);
        ui.showInfoToast(job.summary(), 6f);
    }

    void commit(){
        if(preview == null) return;
        if(player.unit() == null || !player.unit().canBuild()){
            ui.showInfoToast("[scarlet]Your unit can't build", 3f);
            return;
        }
        for(BuildPlan p : preview) player.unit().addBuild(p.copy());
        ui.showInfoToast("Queued " + preview.size + " build plans", 3f);
        preview = null;
    }

    // ---------------------------------------------------------------- drawing

    static void rect(int ax, int ay, int bx, int by){
        Lines.rect(ax * tilesize - tilesize / 2f, ay * tilesize - tilesize / 2f, (bx - ax + 1) * tilesize, (by - ay + 1) * tilesize);
    }

    void draw(){
        if(dragging){
            Draw.z(Layer.overlayUI);
            Lines.stroke(2f, Pal.accent);
            rect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
            Draw.reset();
        }
        if(preview != null){
            Draw.z(Layer.plans);
            for(BuildPlan p : preview) p.block.drawPlan(p, preview, true);
            Draw.z(Layer.overlayUI);
            Lines.stroke(1.5f, Pal.accent);
            rect(job.ax, job.ay, job.bx, job.by);
            // port marker: arrow pointing out of the region
            float px = job.portX * tilesize, py = job.portY * tilesize, ang = job.outDir * 90f;
            Draw.color(Pal.remove);
            Lines.square(px, py, tilesize / 2f + 1f);
            Fill.tri(px + Angles.trnsx(ang, 12f), py + Angles.trnsy(ang, 12f),
                px + Angles.trnsx(ang + 90f, 4f) + Angles.trnsx(ang, 6f), py + Angles.trnsy(ang + 90f, 4f) + Angles.trnsy(ang, 6f),
                px + Angles.trnsx(ang - 90f, 4f) + Angles.trnsx(ang, 6f), py + Angles.trnsy(ang - 90f, 4f) + Angles.trnsy(ang, 6f));
            Draw.reset();
        }
    }

    // ---------------------------------------------------------------- headless test

    @Override
    public void registerServerCommands(CommandHandler handler){
        ServerTest.register(handler);
    }
}
