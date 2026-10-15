package autobuild;

import arc.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import autobuild.plan.*;
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
import mindustry.world.blocks.production.*;

import static mindustry.Vars.*;

/**
 * F7 / "AutoBuild" button -> drag a region -> configure (ore, drill, output side, port position)
 * -> preview ghosts -> Build.
 */
public class AutoBuildMod extends Mod{
    static final String[] dirNames = {"East", "North", "West", "South"};

    boolean selecting, dragging;
    int x0, y0, x1, y1;

    /** current region (world tiles, inclusive) */
    int ax, ay, bx, by;
    // settings, kept between uses
    Drill drill;
    Item target;
    int outDir = 0;
    float portFrac = 0.5f;

    Seq<BuildPlan> preview;
    int portX, portY;

    public AutoBuildMod(){
        Events.on(ClientLoadEvent.class, e -> {
            drill = (Drill)Blocks.mechanicalDrill;
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
                ax = Mathf.clamp(Math.min(x0, x1), 0, world.width() - 1);
                bx = Mathf.clamp(Math.max(x0, x1), 0, world.width() - 1);
                ay = Mathf.clamp(Math.min(y0, y1), 0, world.height() - 1);
                by = Mathf.clamp(Math.max(y0, y1), 0, world.height() - 1);
                showDialog();
            }
        }
    }

    // ---------------------------------------------------------------- UI

    void showDialog(){
        ObjectIntMap<Item> ores = new ObjectIntMap<>();
        for(int x = ax; x <= bx; x++) for(int y = ay; y <= by; y++){
            Item d = world.tile(x, y).drop();
            if(d != null) ores.increment(d);
        }
        Seq<Item> oreList = new Seq<>();
        for(Item i : ores.keys()) oreList.add(i);
        oreList.sort(i -> -ores.get(i));
        if(target == null || !ores.containsKey(target)) target = oreList.isEmpty() ? null : oreList.first();

        BaseDialog dialog = new BaseDialog("AutoBuild: drill field");
        Table c = dialog.cont;
        c.defaults().left().pad(4f);
        c.add("Region: " + (bx - ax + 1) + " x " + (by - ay + 1) + " tiles").row();

        c.add("Ore:").row();
        c.table(t -> {
            if(oreList.isEmpty()) t.add("[scarlet]no ore in region");
            for(Item i : oreList){
                t.button(b -> {
                    b.image(i.uiIcon).size(24f).padRight(4f);
                    b.add(i.localizedName + " (" + ores.get(i) + ")");
                }, Styles.flatTogglet, () -> target = i).checked(b -> target == i).size(190f, 40f);
            }
        }).row();

        c.add("Drill:").row();
        c.table(t -> {
            for(Block b : new Block[]{Blocks.mechanicalDrill, Blocks.pneumaticDrill}){
                Drill d = (Drill)b;
                t.button(bt -> {
                    bt.image(d.uiIcon).size(24f).padRight(4f);
                    bt.add(d.localizedName + " (tier " + d.tier + ")");
                }, Styles.flatTogglet, () -> drill = d).checked(bt -> drill == d).size(260f, 40f);
            }
        }).row();

        c.add("Output side:").row();
        c.table(t -> {
            for(int i = 0; i < 4; i++){
                int dir = i;
                t.button(dirNames[i], Styles.flatTogglet, () -> outDir = dir).checked(b -> outDir == dir).size(100f, 40f);
            }
        }).row();

        c.add("Port position along that edge (left/bottom -> right/top):").row();
        c.table(t -> {
            Label l = new Label("");
            t.slider(0f, 1f, 0.01f, portFrac, v -> portFrac = v).width(300f);
            t.add(l).padLeft(8f);
            l.update(() -> l.setText(Mathf.round(portFrac * 100) + "%"));
        }).row();

        dialog.buttons.button("Cancel", Icon.cancel, () -> { dialog.hide(); preview = null; }).size(160f, 54f);
        dialog.buttons.button("Preview", Icon.ok, () -> {
            dialog.hide();
            plan();
        }).size(160f, 54f).disabled(b -> target == null);
        dialog.show();
    }

    // ---------------------------------------------------------------- planning

    /** local (u,v) -> world, for a frame where +u points toward outDir. */
    int wx(int u, int v){
        switch(outDir){
            case 0: return ax + u;
            case 1: return bx - v;
            case 2: return bx - u;
            default: return ax + v;
        }
    }

    int wy(int u, int v){
        switch(outDir){
            case 0: return ay + v;
            case 1: return ay + u;
            case 2: return by - v;
            default: return by - u;
        }
    }

    void plan(){
        boolean rot = outDir % 2 == 1;
        int W = rot ? by - ay + 1 : bx - ax + 1, H = rot ? bx - ax + 1 : by - ay + 1;
        int s = drill.size, off = (s - 1) / 2;
        long time = Time.millis();

        // snapshot the region in the local frame
        boolean[][] beltOk = new boolean[W][H], drillOk = new boolean[W][H];
        int[][] drop = new int[W][H];
        for(int u = 0; u < W; u++) for(int v = 0; v < H; v++){
            int x = wx(u, v), y = wy(u, v);
            Tile t = world.tile(x, y);
            beltOk[u][v] = Build.validPlace(Blocks.conveyor, player.team(), x, y, 0);
            Item d = t.drop();
            drop[u][v] = d != null && drill.tier >= d.hardness ? d.id : -1;
        }
        for(int u = 0; u + s <= W; u++) for(int v = 0; v + s <= H; v++){
            drillOk[u][v] = Build.validPlace(drill, player.team(), originX(u, v, s) , originY(u, v, s), 0);
        }

        // slider is in world order (left->right / bottom->top); local v runs the other way for N and W
        float f = outDir == 1 || outDir == 2 ? 1f - portFrac : portFrac;
        DrillLayoutPlanner p = new DrillLayoutPlanner(W, H, s, beltOk, drillOk, drop, target.id, Mathf.round(f * (H - 1)), Math.max(1, s * s / 2));
        boolean ok = p.plan();
        Log.info("[autobuild] @x@ local, out=@ ok=@ drills=@ ore=@ routed=@ dropped=@ phase=@ in @ms @",
            W, H, dirNames[outDir], ok, p.drills, p.oreTiles, p.segmentsRouted, p.segmentsDropped, p.phase, Time.timeSinceMillis(time), ok ? "" : p.failure);

        if(!ok){
            ui.showInfoToast("[scarlet]AutoBuild failed: " + p.failure, 4f);
            preview = null;
            return;
        }

        Seq<BuildPlan> plans = new Seq<>();
        for(DrillLayoutPlanner.Place pl : p.places){
            if(pl.kind == DrillLayoutPlanner.DRILL){
                plans.add(new BuildPlan(originX(pl.u, pl.v, s), originY(pl.u, pl.v, s), 0, drill));
            }else{
                plans.add(new BuildPlan(wx(pl.u, pl.v), wy(pl.u, pl.v), (pl.dir + outDir) % 4, Blocks.conveyor));
            }
        }
        // belts first so drills have somewhere to output when they finish
        plans.sort(b -> b.block == Blocks.conveyor ? 0 : 1);
        preview = plans;
        portX = wx(W - 1, p.port);
        portY = wy(W - 1, p.port);
        ui.showInfoToast(Strings.format("@ drills on @ @ ore tiles, @ lane(s) routed@", p.drills, p.oreTiles, target.localizedName, p.segmentsRouted,
            p.segmentsDropped > 0 ? ", " + p.segmentsDropped + " dropped (unroutable)" : ""), 4f);
    }

    /** world origin tile of an s x s block whose local footprint min corner is (u,v) */
    int originX(int u, int v, int s){
        int mx = Math.min(wx(u, v), wx(u + s - 1, v + s - 1));
        return mx + (s - 1) / 2;
    }

    int originY(int u, int v, int s){
        int my = Math.min(wy(u, v), wy(u + s - 1, v + s - 1));
        return my + (s - 1) / 2;
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

    void draw(){
        if(dragging){
            int ax = Math.min(x0, x1), ay = Math.min(y0, y1), bx = Math.max(x0, x1), by = Math.max(y0, y1);
            Draw.z(Layer.overlayUI);
            Lines.stroke(2f, Pal.accent);
            Lines.rect(ax * tilesize - tilesize / 2f, ay * tilesize - tilesize / 2f, (bx - ax + 1) * tilesize, (by - ay + 1) * tilesize);
            Draw.reset();
        }
        if(preview != null){
            Draw.z(Layer.plans);
            for(BuildPlan p : preview) p.block.drawPlan(p, preview, true);
            Draw.z(Layer.overlayUI);
            Lines.stroke(1.5f, Pal.accent);
            Lines.rect(ax * tilesize - tilesize / 2f, ay * tilesize - tilesize / 2f, (bx - ax + 1) * tilesize, (by - ay + 1) * tilesize);
            // port marker: arrow pointing out of the region
            float px = portX * tilesize, py = portY * tilesize, ang = outDir * 90f;
            Draw.color(Pal.remove);
            Lines.square(px, py, tilesize / 2f + 1f);
            Fill.tri(px + Angles.trnsx(ang, 12f), py + Angles.trnsy(ang, 12f),
                px + Angles.trnsx(ang + 90f, 4f) + Angles.trnsx(ang, 6f), py + Angles.trnsy(ang + 90f, 4f) + Angles.trnsy(ang, 6f),
                px + Angles.trnsx(ang - 90f, 4f) + Angles.trnsx(ang, 6f), py + Angles.trnsy(ang - 90f, 4f) + Angles.trnsy(ang, 6f));
            Draw.reset();
        }
    }
}
