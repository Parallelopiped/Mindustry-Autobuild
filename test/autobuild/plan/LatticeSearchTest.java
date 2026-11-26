package autobuild.plan;

import java.util.*;
import autobuild.plan.DrillLayoutPlanner.*;

public class LatticeSearchTest{
    public static void main(String[] args){
        checkChainCuts();
        Params p = new Params();
        p.W = 30; p.H = 30; p.drillSize = 4; p.target = 1;
        p.beltOk = new boolean[p.W][p.H]; p.drop = new int[p.W][p.H];
        p.typeSize = new int[]{4, 3, 2}; p.typeRate = new double[]{1, .6, .5};
        p.typePowered = new boolean[3]; p.typeDrop = new int[3][][];
        p.typeDrillOk = new boolean[3][p.W][p.H];
        for(int x = 0; x < p.W; x++){
            Arrays.fill(p.beltOk[x], true); Arrays.fill(p.drop[x], 1);
            for(int t = 0; t < 3; t++) Arrays.fill(p.typeDrillOk[t][x], true);
        }
        Arrays.fill(p.typeDrop, p.drop);
        DrillLayoutPlanner planner = new DrillLayoutPlanner(p);
        planner.mask = new boolean[p.W][p.H];
        planner.computeOre();
        DrillLayoutPlanner.Lattice lattice = planner.new Lattice(4, 7, 0, 0);
        boolean[] selection = lattice.greedy(true);
        double before = value(lattice, selection);
        lattice.improve(selection, 1500);
        require(lattice.improveNodes > 0 && lattice.improveNodes <= DrillLayoutPlanner.Lattice.MAX_IMPROVE_NODES, "search budget");
        require(value(lattice, selection) + 1e-8 >= before, "lost incumbent value");
        boolean[][] occupied = new boolean[p.W][p.H];
        for(int i = 0; i < selection.length; i++) if(selection[i]){
            int[] c = lattice.cands.get(i);
            require(!lattice.overlaps(occupied, c), "overlapping selected drills");
            require(!lattice.coversBridge(c[0], c[1], p.typeSize[c[4]]), "drill covers bridge");
            lattice.set(occupied, c, true);
        }
        lattice.windowNodes = 0;
        Thread.currentThread().interrupt();
        try{
            lattice.dfs(Collections.emptyList(), 0, occupied, new ArrayList<>(), 0, new ArrayList<>(), new double[]{0}, new double[]{0});
            throw new AssertionError("recursive search ignored cancellation");
        }catch(CancelledException expected){}finally{ Thread.interrupted(); }
        System.out.println("LATTICE SEARCH OK: dense mixed drills, incumbent preserved, no overlap, bounded work, recursive cancellation");
    }

    static void checkChainCuts(){
        Params p = new Params();
        p.W = 27; p.H = 7; p.drillSize = 2; p.target = 1;
        p.sinkCapacity = 1; p.itemsPerOre = 1;
        p.beltOk = new boolean[p.W][p.H]; p.drillOk = new boolean[p.W][p.H];
        p.drop = new int[p.W][p.H]; p.otherOre = new boolean[p.W][p.H];
        for(int x = 0; x < p.W; x++){
            Arrays.fill(p.beltOk[x], true); Arrays.fill(p.drillOk[x], true); Arrays.fill(p.drop[x], 1);
        }
        p.otherOre[10][3] = p.otherOre[22][3] = true;
        DrillLayoutPlanner planner = new DrillLayoutPlanner(p);
        planner.mask = new boolean[p.W][p.H];
        planner.computeOre();
        DrillLayoutPlanner.Lattice lattice = planner.new Lattice(4, 8, 2, 3);
        boolean[] selected = new boolean[lattice.cands.size()];
        for(int i = 0; i < selected.length; i++){
            int[] d = lattice.cands.get(i);
            selected[i] = d[0] == 2 && d[1] == 1 || (d[0] == 2 || d[0] == 6 || d[0] == 18) && d[1] == 4;
        }
        List<Feeder> feeders = lattice.feeders(selected);
        require(feeders.size() == 2, "split only after a free end bridge");
        Feeder first = feeders.get(0), last = feeders.get(1);
        require(first.su == 10 && first.sv == 3 && last.su == 22 && last.sv == 3, "chain endpoints");
        require(first.drills.size() == 3 && last.drills.size() == 1, "chain drill counts");
        require(first.drills.get(0)[1] == 1 && first.drills.get(1)[1] == 4, "equal attachment order");
        double fullDrill = 4 - .01;
        require(first.value == (fullDrill + fullDrill + fullDrill) - .05 * 3 - .3, "first chain costs");
        require(last.value == fullDrill - .05 * 2 - .3, "last chain costs");
        require(lattice.value(selected) == first.value + last.value, "score matches materialized chains");
    }

    static double value(DrillLayoutPlanner.Lattice l, boolean[] selection){
        double result = 0;
        for(int i = 0; i < selection.length; i++) if(selection[i]) result += l.values.get(i);
        return result;
    }
    static void require(boolean ok, String message){ if(!ok) throw new AssertionError(message); }
}
