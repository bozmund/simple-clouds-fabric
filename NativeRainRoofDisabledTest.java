import dev.nonamecrackers2.simpleclouds.client.NativeRainRoofProbe;
public final class NativeRainRoofDisabledTest {
    public static void main(String[] args) {
        if(NativeRainRoofProbe.enabled())throw new AssertionError("Unexpected roof opt-in");
        for(int i=0;i<100;i++) {
            NativeRainRoofProbe.spawned(null,0);
            NativeRainRoofProbe.verify(null,74,false,"disabled");
            if(!NativeRainRoofProbe.resume(null))throw new AssertionError("Inactive fixture paused normal frames");
        }
        System.out.println("PASS native rain roof fixture inert without both explicit opt-ins");
    }
}
