package autobuild;

import arc.struct.*;
import mindustry.entities.units.*;

import java.util.*;

/** One planned layout in world terms, as the UI shows it: build plans, ports, summary, score. */
public abstract class PlanResult{
    public final Seq<BuildPlan> plans = new Seq<>();
    /** for "another plan": same placements = same plan */
    public final Set<String> signature = new HashSet<>();
    public String error;

    public boolean ok(){ return error == null; }

    public abstract double score();

    /** multi-line, colored */
    public abstract String summary();

    /** one line: which ports were used */
    public abstract String portText();

    /** ports this plan uses for a kind (auto-chosen ones included) */
    public abstract Seq<Port> ports(PortKind kind);

    /** ports of this kind were chosen by the planner */
    public boolean auto(PortKind kind){ return false; }

    /** extra text on the i-th port marker of a kind (e.g. its load), or null */
    public String portLabel(PortKind kind, int i){ return null; }

    /** false draws the marker as a problem (e.g. an overloaded output) */
    public boolean portOk(PortKind kind, int i){ return true; }
}
