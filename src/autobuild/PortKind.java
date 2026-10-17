package autobuild;

import arc.func.*;
import arc.graphics.*;
import arc.struct.*;
import mindustry.gen.*;

/**
 * One kind of port the player places by clicking (item output, water input, ammo input, power
 * input...). Holds the ports picked so far; empty = let the planner choose.
 */
public class PortKind{
    /** "Item output"; plural for the picking prompt ("item outputs") */
    public final String name, plural;
    public final Color color;
    /** an input: the marker arrow points into the region */
    public final boolean input;
    /** what a port on the border / inside the region means, shown while picking */
    public final String edgeHelp, interiorHelp;
    /** why an existing building can't serve as this port, or null if it can */
    public final Func<Building, String> check;
    public final Seq<Port> picks = new Seq<>();

    public PortKind(String name, String plural, Color color, boolean input, String edgeHelp, String interiorHelp, Func<Building, String> check){
        this.name = name; this.plural = plural; this.color = color; this.input = input;
        this.edgeHelp = edgeHelp; this.interiorHelp = interiorHelp; this.check = check;
    }

    /** why this port can't be used (only existing buildings are checked), or null */
    public String error(Port p){
        return p.existing == null ? null : check.get(p.existing);
    }
}
