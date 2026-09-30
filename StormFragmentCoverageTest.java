import dev.nonamecrackers2.simpleclouds.client.renderer.v2.*;
import java.util.*;

public class StormFragmentCoverageTest {
    public static void main(String[] args) {
        int checks = 0;
        Random random = new Random(941);
        for (int lod : new int[] {1,2,4,8}) for (int base : new int[] {-64,0}) {
            byte[] columns = new byte[16]; boolean[] flags = new boolean[16];
            for (int i=0;i<16;i++) { flags[i]=random.nextBoolean(); columns[i]=(byte)(flags[i]?1:0); }
            var left = new CloudWorldCoverage.Rect(base,base,base+2*lod,base+4*lod,lod);
            var right = new CloudWorldCoverage.Rect(base+2*lod,base,base+4*lod,base+4*lod,lod);
            for (int x=base-4;x<=base+4*lod+4;x++) for (int z=base-4;z<=base+4*lod+4;z++) {
                float expected = StormCoverage.contribution(flags,4,4,base,base,lod,x+.3F,z+.7F);
                float split = StormCoverage.contribution(columns,4,4,base,base,lod,x+.3F,z+.7F,left)
                    + StormCoverage.contribution(columns,4,4,base,base,lod,x+.3F,z+.7F,right);
                if (expected!=split) throw new AssertionError("split changes overhead storm fraction");
                checks++;
            }
            var full = new StormFogMap(); var split = new StormFogMap();
            full.begin(base*8,base*8); split.begin(base*8,base*8);
            full.addChunk(columns,4,4,base*8,base*8,lod*8);
            split.addChunk(columns,4,4,base*8,base*8,lod*8,left);
            split.addChunk(columns,4,4,base*8,base*8,lod*8,right);
            for (int x=0;x<StormFogMap.CELLS;x++) for (int z=0;z<StormFogMap.CELLS;z++) {
                if(full.get(x,z)!=split.get(x,z)) throw new AssertionError("split changes fog map");
                checks++;
            }
        }
        System.out.println("PASS: " + checks + " split-source storm fraction and fog-map checks, negative origins and four LODs");
    }
}
