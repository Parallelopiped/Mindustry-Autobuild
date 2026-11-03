package autobuild;

import arc.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import autobuild.plan.DrillLayoutPlanner.CancelledException;
import mindustry.entities.units.BuildPlan;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the real UI controller with a deterministic, deliberately slow search and a
 * main-thread post queue. No window or save files are touched. */
public class PlanningLifecycleTest{
    static final ConcurrentLinkedQueue<Runnable> posts = new ConcurrentLinkedQueue<>();

    static class Mod extends AutoBuildMod{
        Throwable failure;
        @Override boolean animate(){ return true; }
        @Override void planningFailed(int token, Throwable e){
            if(token == planToken){ failure = e; clearFrames(); phase = State.idle; }
        }
    }

    static class Job extends BuildJob{
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean rejected = new AtomicBoolean();
        boolean failPrepare, failSearch;
        public String name(){ return "test"; }
        public Seq<PortKind> portKinds(){ return new Seq<>(); }
        public boolean ready(){ return true; }
        public void settings(Table t, Runnable r){}
        public Object prepare(){ if(failPrepare) throw new IllegalStateException("snapshot failure"); return new Object(); }
        public PlanResult search(Object snapshot, int base, int count, Progress progress){
            if(failSearch) throw new AssertionError("search failure");
            if(ax == 0){
                Seq<BuildPlan> plans = new Seq<>();
                for(int i = 0; i < 1000; i++) plans.add(new BuildPlan());
                for(int i = 0; i < 180; i++) progress.frame("old", plans);
                entered.countDown();
                // Simulate a non-interruptible operation completing after reset/new selection.
                boolean done = false;
                while(!done) try{ release.await(); done = true; }catch(InterruptedException ignored){}
                Thread.interrupted();
                try{ progress.frame("stale", plans); }catch(CancelledException expected){ rejected.set(true); }
            }
            final int captured = ax;
            PlanResult result = new PlanResult(){
                public double score(){ return captured; }
                public String summary(){ return "test"; }
                public String portText(){ return ""; }
                public Seq<Port> ports(PortKind kind){ return new Seq<>(); }
            };
            result.signature.add(Integer.toString(captured));
            return result;
        }
    }

    public static void main(String[] args) throws Exception{
        Core.app = (Application)Proxy.newProxyInstance(Application.class.getClassLoader(), new Class[]{Application.class},
            (proxy, method, values) -> {
                if(method.getName().equals("post")) posts.add((Runnable)values[0]);
                return null;
            });
        Mod mod = new Mod();
        try{
            Job job = new Job();
            mod.job = job;
            mod.startPlanning(true);
            require(job.entered.await(5, TimeUnit.SECONDS), "worker did not start");
            synchronized(mod.frames){
                require(mod.framePlans <= AutoBuildMod.MAX_FRAME_PLANS && mod.frames.size <= AutoBuildMod.MAX_FRAMES, "unbounded replay");
            }
            mod.reset();
            require(mod.latest == null && mod.frames.isEmpty() && mod.snapshot == null, "reset retained frames/snapshot");
            job.ax = 42;
            mod.startPlanning(true);
            job.release.countDown();
            mod.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            drain();
            require(job.rejected.get(), "old worker published stale progress");
            require(mod.variants.size == 1 && mod.current().score() == 42, "stale completion replaced new result");
            require(mod.failure == null, "worker failed");
            // A queued completion must not revive the UI after leaving the map.
            mod.startPlanning(true);
            mod.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            mod.reset();
            drain();
            require(mod.phase == AutoBuildMod.State.idle && mod.variants.isEmpty(), "completion survived reset");
            job.failPrepare = true;
            mod.startPlanning(true);
            require(mod.failure instanceof IllegalStateException, "prepare exception escaped controller");
            job.failPrepare = false; job.failSearch = true;
            mod.startPlanning(true);
            mod.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            drain();
            require(mod.failure instanceof AssertionError && mod.phase == AutoBuildMod.State.idle, "executor swallowed worker failure");
            copies();
            System.out.println("PLANNING LIFECYCLE OK: cancel/restart, stale progress/results, reset, replay bounds, snapshot errors, settings copies");
        }finally{ mod.reset(); mod.executor.shutdownNow(); }
    }

    static void copies(){
        DrillFieldJob job = new DrillFieldJob();
        job.outs.add(Port.edge(1, .5f));
        DrillFieldJob copy = job.copyForSearch();
        job.outs.clear(); job.cancel(); job.ax = 7;
        require(copy.outs.size == 1 && !copy.cancelled && copy.ax == 0, "drill copy shares mutable settings");
        require(copy.outKind == job.outKind, "result port identity changed");
        DefenseJob defense = new DefenseJob();
        DefenseJob frozen = defense.copyForSearch();
        require(frozen.turrets != defense.turrets && frozen.powerKind == defense.powerKind, "defense settings copy");
    }

    static void drain(){ for(Runnable r; (r = posts.poll()) != null;) r.run(); }
    static void require(boolean ok, String message){ if(!ok) throw new AssertionError(message); }
}
