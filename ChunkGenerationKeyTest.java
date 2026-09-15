import java.util.List;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkGenerationKey;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkGenerationKey.Mask;

public class ChunkGenerationKeyTest
{
    private static Mask circle(float x, float z, float r, int g) { return new Mask(x,z,r,1,0,0,1,g); }
    private static long key(int lod, Mask... m) { return ChunkGenerationKey.local(0,0,32*lod,lod,List.of(m)); }
    private static void require(boolean b, String label) { if (!b) throw new AssertionError(label); }
    public static void main(String[] args)
    {
        Mask near = circle(100,100,130,0);
        require(key(1,near,circle(10000,10000,1,1)) == key(1,near,circle(11000,10000,2,1)), "unrelated formation invalidates local chunk");
        require(key(1,near) != key(1,circle(110,100,130,0)), "boundary movement not detected");
        require(key(1,circle(0,0,1000,0)) == key(1,circle(20,20,1000,0)), "saturated interior regenerated");
        require(key(1,circle(0,0,1000,0)) != key(1,circle(0,0,1000,1)), "type change ignored");
        require(key(1) != key(1,circle(10000,10000,1,0)), "infinite preview confused with empty sky");
        require(key(8,circle(200,0,200,0)) == key(8,circle(200.01f,0,200,0)), "subsample motion churn");
        require(key(1,circle(300,0,100,0)) != key(1,circle(400,0,100,0)), "outer fade intersection omitted");
        float generated = 4, current = 5, featureX = 20;
        require(featureX + ChunkGenerationKey.drawOffset(generated,current) + current == featureX + generated, "wind translation reverses noise phase");
        for (int phase = -20; phase <= 20; phase++)
            for (int lod : new int[] {1,2,4,8}) {
                int origin = ChunkGenerationKey.latticeShift(phase,8);
                require(Math.floorMod(origin + phase,lod) == 0, "noise-space lattice shifts at LOD " + lod);
                float drawn = origin + ChunkGenerationKey.drawOffset(phase,phase + .25f);
                require(drawn + phase + .25f == origin + phase, "continuous draw and generation lattice disagree");
            }
        System.out.println("PASS: 8 identity regressions and 328 lattice/translation checks");
    }
}
