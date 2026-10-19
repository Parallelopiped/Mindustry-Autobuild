package autobuild;

import arc.*;

/**
 * Texts from the mod's bundles ({@code assets/bundles/bundle*.properties}, keys prefixed with
 * {@code autobuild.}). The game merges a mod's bundles into its own, so they follow the language
 * chosen in the game's settings. Arguments are inserted as text ({0}, {1}...), numbers already
 * formatted by the caller.
 */
public final class Tr{
    private Tr(){}

    public static String t(String key, Object... args){
        String full = "autobuild." + key;
        if(Core.bundle == null || !Core.bundle.has(full)) return fallback(key, args);
        String s = Core.bundle.get(full);
        for(int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /** a key or, if there is none, the text itself (planner stage names, layout names) */
    public static String or(String key, String text){
        String full = "autobuild." + key;
        return Core.bundle != null && Core.bundle.has(full) ? Core.bundle.get(full) : text;
    }

    /** without a bundle (offline tools): the key and its arguments */
    static String fallback(String key, Object... args){
        if(args.length == 0) return key;
        StringBuilder sb = new StringBuilder(key);
        for(Object a : args) sb.append(' ').append(a);
        return sb.toString();
    }

    /** "planner stage name" -> translated, e.g. "power nodes" */
    public static String stage(String stage){
        if(stage == null) return "";
        // "layout: trying bridge lattice 3x3" and similar
        for(String prefix : new String[]{"layout: improving ", "layout: trying ", "layout: "}){
            if(stage.startsWith(prefix)) return or("stage." + prefix.trim().replace(' ', '_').replace(":", ""), prefix) + layout(stage.substring(prefix.length()));
        }
        return or("stage." + stage.replace(' ', '_'), stage);
    }

    /** drill layout names ("belt lanes", "bridge lattice 3x3", "belt lanes + water rows", "drill-first") */
    public static String layout(String name){
        if(name == null) return "";
        String out = name;
        out = out.replace("belt lanes", or("layout.lanes", "belt lanes"));
        out = out.replace("water rows", or("layout.waterRows", "water rows"));
        out = out.replace("bridge lattice", or("layout.lattice", "bridge lattice"));
        out = out.replace("drill-first", or("layout.drillFirst", "drill-first"));
        return out;
    }

    /** a planner failure: its key when it has one, else its text */
    public static String failure(String key, Object[] args, String text){
        if(key == null) return text;
        String full = "autobuild.fail." + key;
        return Core.bundle != null && Core.bundle.has(full) ? t("fail." + key, args == null ? new Object[0] : args) : text;
    }
}
