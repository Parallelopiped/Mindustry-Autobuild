package autobuild;

import mindustry.gen.*;

/**
 * A port of a planned region: on a region edge (side 0=E 1=N 2=W 3=S, position along it in world
 * order), an interior tile, or one of the player's existing buildings. What it means depends on its
 * {@link PortKind}: e.g. an edge item output's belt leaves the region there, an interior water input
 * is a liquid router the player feeds.
 */
public class Port{
    public static final String[] dirNames = {"East", "North", "West", "South"};

    public boolean edge;
    public int side;
    public float frac;
    public int x, y;
    /** an existing building (x,y = its tile) */
    public Building existing;
    /** one tile of a big existing building (several lines go into it, one per such tile) */
    public boolean sub;

    public static Port edge(int side, float frac){ Port p = new Port(); p.edge = true; p.side = side; p.frac = frac; return p; }

    public static Port at(int x, int y){ Port p = new Port(); p.x = x; p.y = y; return p; }

    public String name(){ return existing != null ? existing.block.localizedName : edge ? Tr.or("dir." + side, dirNames[side]) : "(" + x + "," + y + ")"; }
}
