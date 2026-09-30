import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;

public class CpuEmptyChunkTest {
    private static final CpuCloudGenerator.NoiseLayer LAYER =
        new CpuCloudGenerator.NoiseLayer(16,3,16,16,16,1,0,0);
    private static void clippedHeightMatchesFull(int lod, int start, int height) {
        var layer = new CpuCloudGenerator.NoiseLayer(height,3,16,16,16,1,start,0);
        var gen = new CpuCloudGenerator(List.of(new CpuCloudGenerator.CloudLayerGroup(List.of(layer),1,true,0,0,1)));
        gen.setRegions(List.of(new CpuCloudGenerator.RegionMask(0,0,10000,1,0,0,1,0)));
        ByteBuffer[] full = new ByteBuffer[2];
        float[] fullCoverage = new float[1];
        for (int pass=0;pass<2;pass++) {
            var opaque = ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder());
            var transparent = ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder());
            float[] coverage = new float[1];
            var result = gen.generate(0,0,0,32*lod,pass==0?256:start+height,32*lod,
                    8,0,0,0,0,lod,128,opaque,transparent,
                    (b,n,w)->{throw new AssertionError("unexpected growth");},
                    new float[1],new float[1],0,new float[2],coverage);
            if (pass==0) { full=result; fullCoverage=coverage; }
            else if (full[0].remaining()==0 || !full[0].equals(result[0])
                    || !full[1].equals(result[1]) || fullCoverage[0]!=coverage[0])
                throw new AssertionError("local Y cap changed cloud output at LOD "+lod
                        +" for layer "+start+".."+(start+height));
        }
    }
    public static void main(String[] args) {
        for (int lod : new int[]{1,2,4,8}) {
            clippedHeightMatchesFull(lod,16,32);  // cumulus
            clippedHeightMatchesFull(lod,64,64);  // stratocumulus
        }
        var gen = new CpuCloudGenerator(List.of(new CpuCloudGenerator.CloudLayerGroup(List.of(LAYER),1,true,0,0,1)));
        var opaque = ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder());
        var transparent = ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder());
        for (int lod : new int[]{1,2,4,8}) {
            // Far outside, then intersecting only the neighbor halo.
            for (var mask : List.of(
                    new CpuCloudGenerator.RegionMask(10000,10000,1,1,0,0,1,0),
                    new CpuCloudGenerator.RegionMask(-lod/2f,lod/2f,lod/4f,1,0,0,1,0))) {
                gen.setRegions(List.of(mask));
                opaque.clear().putInt(1234);
                transparent.clear().putInt(5678);
                float[] oc={99}, tc={99}, coverage={99};
                var result=gen.generate(0,0,0,32*lod,256,32*lod,8,0,0,0,0,lod,128,opaque,transparent,
                    (b,n,w)->{throw new AssertionError("empty chunk allocated mesh");},oc,tc,0,new float[2],coverage);
                if(result[0].remaining()!=0 || result[1].remaining()!=0 || oc[0]!=0 || tc[0]!=0 || coverage[0]!=0)
                    throw new AssertionError("empty chunk retained prior output at LOD "+lod);
            }
        }
        // An empty region list deliberately means the unmasked preview field.
        gen.setRegions(List.of());
        var result=gen.generate(0,0,0,32,16,32,8,0,0,0,0,1,128,opaque.clear(),transparent.clear(),
            (b,n,w)->{throw new AssertionError("unexpected growth");},new float[1],new float[1],0,new float[2],new float[1]);
        if(result[0].remaining()==0) throw new AssertionError("unmasked preview wrongly culled");
        // The identical empty list means clear sky when the world requires regions.
        gen.setRegions(List.of(),true);
        float[] oc={99}, tc={99}, coverage={99};
        result=gen.generate(0,0,0,32,16,32,8,0,0,0,0,1,128,opaque.clear(),transparent.clear(),
            (b,n,w)->{throw new AssertionError("empty world allocated geometry");},oc,tc,0,new float[2],coverage);
        if(result[0].remaining()!=0 || result[1].remaining()!=0 || oc[0]!=0 || tc[0]!=0 || coverage[0]!=0)
            throw new AssertionError("empty world fell back to infinite clouds");
        gen.setRegions(List.of(),false);
        result=gen.generate(0,0,0,32,16,32,8,0,0,0,0,1,128,opaque.clear(),transparent.clear(),
            (b,n,w)->{throw new AssertionError("unexpected growth");},oc,tc,0,new float[2],coverage);
        if(result[0].remaining()==0) throw new AssertionError("switching back to SINGLE retained clear sky");
        System.out.println("PASS: empty/halo-only chunks reset output; local Y cap preserves two cloud types at four LODs; unmasked preview retained");
    }
}
