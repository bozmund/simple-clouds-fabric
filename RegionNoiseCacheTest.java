import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudGenerationInputs;
import java.nio.*;
import java.util.*;
import java.security.MessageDigest;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;

/** Byte-for-byte regression against the uncached implementation, plus repeat timing. */
public class RegionNoiseCacheTest {
    // Captured before caching from all emitted bytes plus counts/storm coverage.
    // These are compatibility fixtures, not proof of complete original-mod parity.
    private static final String[] EXPECTED = {
        "8a50cdb16c01e6b5cd92f32ec77a3e62f635014b7242c620f720738e6ed59c06",
        "79850750d82d2f63ff3ac684ff6ae4692ce59d86ae71e52b18c87f935d3670c5",
        "688bebc3e020fb088dad17de87b4ea13c9fe5533b3fc6a52289da91b87a6051f",
        "897b413a044931a2341291dcc75f9ac6bd60b292501d9cc0d2f1bc796b55b58c",
        "08c11a340111fe87433ff03fd7ebb9a3140927b149b6f18441b2a30b1472b55f",
        "2d7304fffa919a7d509ca1ae408ce9d3f5aea52c941651f3b978507aa3125e75",
        "15227db46cb974cec7af8fac07bfa6c18bc694c894bd353301dd645d69b1b409",
        "16bfbaf8bdec23895967ffbcee4362c7a48b7461e061b41d44f6067539d75a72"
    };
    public static void main(String[] args) throws Exception {
        var groups = List.of(
            new CloudGenerationInputs.CloudLayerGroup(List.of(
                new CloudGenerationInputs.NoiseLayer(48,.25f,17,13,19,10,0,1),
                new CloudGenerationInputs.NoiseLayer(32,.1f,11,15,9,8,4,.7f)), .4f,true,.7f,4,20),
            new CloudGenerationInputs.CloudLayerGroup(List.of(
                new CloudGenerationInputs.NoiseLayer(48,.2f,19,17,13,12,0,1)), .3f,false,0,0,1));
        var gen = new CpuCloudGenerator(groups);
        var opaque = ByteBuffer.allocateDirect(16*1024*1024).order(ByteOrder.nativeOrder());
        var transparent = ByteBuffer.allocateDirect(16*1024*1024).order(ByteOrder.nativeOrder());
        long[] times = new long[5];
        String[] reference = new String[8];
        for (int repeat=0; repeat<6; repeat++) {
            long start=System.nanoTime();
            int fixture=0;
            for (int lod : new int[]{1,2,4,8}) for (boolean boundary : new boolean[]{false,true}) {
                // Reuse one worker across group, mask, origin and dimension changes.
                int x0=boundary ? -16 : 0;
                gen.setRegions(List.of(
                    new CloudGenerationInputs.RegionMask(boundary?45:0,0,boundary?170:10000,1,.2f,0,1, boundary?1:0),
                    new CloudGenerationInputs.RegionMask(-10000,60,90,1,0,0,1,0)),true);
                float[] oc={0},tc={0},storm={0};
                opaque.clear(); transparent.clear();
                var result=gen.generate(x0,0,-16,x0+32,48,16,8,.375f,-.25f,1.125f,.25f,lod,128,
                    opaque,transparent,(b,n,w)->{throw new AssertionError("fixture overflow");},
                    oc,tc,5,new float[]{0,0},storm);
                var hash=MessageDigest.getInstance("SHA-256");
                if(oc[0]+tc[0]==0) throw new AssertionError("empty fixture "+fixture);
                for(var b:result) hash.update(b.duplicate());
                hash.update(ByteBuffer.allocate(12).putFloat(oc[0]).putFloat(tc[0]).putFloat(storm[0]).array());
                String digest=HexFormat.of().formatHex(hash.digest());
                if(!digest.equals(EXPECTED[fixture])) throw new AssertionError("changed output, fixture "+fixture);
                if(repeat==0) {
                    reference[fixture]=digest;
                    System.out.println("fixture="+fixture+" sha256="+digest+" opaque="+oc[0]+" transparent="+tc[0]);
                } else if(!digest.equals(reference[fixture])) throw new AssertionError("stale worker cache, fixture "+fixture);
                fixture++;
            }
            if(repeat>0) times[repeat-1]=System.nanoTime()-start;
        }
        Arrays.sort(times);
        System.out.printf("medianMs=%.3f%n",times[2]/1e6);
    }
}
