package autobuild;

import arc.struct.Seq;
import java.util.Arrays;

/** The reported v1.3.6 crash: a remote water port with no reachable exit, after output sides
 * have been excluded. No graphics or world is needed to exercise the actual port selector. */
public class RemotePortsTest{
    public static void main(String[] args){
        int cases = 0;
        for(int width : new int[]{1, 2, 8}) for(int height : new int[]{1, 3, 9}){
            for(int avoid = 0; avoid < 16; avoid++) for(int lines = 1; lines <= 4; lines++){
                for(boolean open : new boolean[]{false, true}){
                    RemotePorts r = new RemotePorts();
                    r.ax = 2; r.ay = 2; r.bx = r.ax + width - 1; r.by = r.ay + height - 1;
                    r.gw = width + 5; r.gh = height + 5;
                    r.free = new boolean[r.gw][r.gh];
                    r.reach = new float[r.gw][r.gh];
                    for(int x = 0; x < r.gw; x++){
                        Arrays.fill(r.free[x], open);
                        Arrays.fill(r.reach[x], open ? 1 : Float.MAX_VALUE);
                    }
                    Seq<Port> ports = r.edgePorts(new int[]{20, 20, 1}, lines, avoid);
                    require(!ports.isEmpty() && ports.size <= lines, "port count");
                    for(Port p : ports){
                        require(p.side >= 0 && p.side < 4 && p.frac >= 0 && p.frac <= 1, "port bounds");
                        // Existing all-sides fallback is intentional when every side is occupied.
                        if(avoid != 15) require((avoid & 1 << p.side) == 0, "excluded side used");
                    }
                    cases++;
                }
            }
        }
        System.out.println("REMOTE PORTS OK: " + cases + " cases (all exclusion masks, blocked/open, thin regions)");
    }

    static void require(boolean ok, String message){ if(!ok) throw new AssertionError(message); }
}
