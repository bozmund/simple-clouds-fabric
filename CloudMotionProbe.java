import java.nio.*;
import java.util.*;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.ChunkGenerationKey;

/** Diagnostic: compare actual emitted faces at the same noise-space coordinates. */
public class CloudMotionProbe {
    static Set<String> mesh(int phase, int lod, boolean boundary, boolean advected) {
        return mesh(phase, lod, boundary, advected, false);
    }
    static Set<String> mesh(int phase, int lod, boolean boundary, boolean advected, boolean productionWiggle) {
        return mesh(phase,lod,boundary,advected,productionWiggle,false);
    }
    static Set<String> mesh(float phase, int lod, boolean boundary, boolean advected,
                            boolean productionWiggle, boolean worldFixed) {
        var layer = new CpuCloudGenerator.NoiseLayer(32, .4f, 16,16,16,8,0,1);
        var gen = new CpuCloudGenerator(List.of(new CpuCloudGenerator.CloudLayerGroup(List.of(layer),0,false,0,0,1)));
        gen.setRegions(List.of(new CpuCloudGenerator.RegionMask((boundary ? 40 : 0)-(advected ? phase : 0),0,boundary ? 100 : 10000,1,0,0,1,0)));
        int shift = worldFixed ? 0 : ChunkGenerationKey.latticeShift(phase,8);
        // Match chunkWorkerLoop's real Wiggle uniform when requested. The original
        // fixed-wiggle control isolates translation, not the complete live field.
        float wiggle = productionWiggle ? phase / 5.0f : 0;
        var result = gen.generate(-32+shift,0,-32,32+shift,32,32,8,phase,0,0,wiggle,lod,128,
            ByteBuffer.allocateDirect(16*1024*1024).order(ByteOrder.nativeOrder()),
            ByteBuffer.allocateDirect(16*1024*1024).order(ByteOrder.nativeOrder()),
            (b,n,w)-> { throw new AssertionError("probe capacity exceeded"); },
            new float[1],new float[1],0,new float[2],new float[1]);
        Set<String> faces = new HashSet<>();
        for (int kind=0; kind<2; kind++) {
            ByteBuffer b=result[kind]; int stride=kind==0?24:28;
            for(int p=0;p<b.limit();p+=stride) {
                float x=b.getFloat(p+4)+(worldFixed ? 0 : phase*8), z=b.getFloat(p+12);
                if (worldFixed) {
                    float radius = b.getFloat(p+16);
                    float y = b.getFloat(p+8)-128;
                    float step = lod*8;
                    for (float center : new float[]{x,y,z})
                        if ((center-radius) % step != 0)
                            throw new AssertionError("noise scroll displaced world voxel lattice");
                }
                if(Math.abs(x)>128 || Math.abs(z)>128) continue;
                // Geometry identity, independent of brightness or transparency alpha.
                faces.add(kind+":"+b.getFloat(p)+":"+x+":"+b.getFloat(p+8)+":"+z+":"+b.getFloat(p+16));
            }
        }
        if(faces.isEmpty()) throw new AssertionError("empty probe");
        return faces;
    }
    public static void main(String[] args) {
        for(int lod:new int[]{1,2,4,8}) for(int mode=0;mode<3;mode++) {
            boolean boundary=mode>0, advected=mode==2;
            var old=mesh(0,lod,boundary,advected); var next=mesh(1,lod,boundary,advected);
            var removed=new HashSet<>(old); removed.removeAll(next);
            var added=new HashSet<>(next); added.removeAll(old);
            System.out.printf("lod=%d boundary=%s advected=%s old=%d new=%d removed=%d added=%d%n",lod,boundary,advected,old.size(),next.size(),removed.size(),added.size());
            if((!boundary || advected) && (!removed.isEmpty() || !added.isEmpty()))
                throw new AssertionError("noise-only geometry jumps across phase replacement");
        }
        int changedLevels = 0;
        for(int lod:new int[]{1,2,4,8}) {
            // Saturated region removes the boundary confounder. Translation cannot
            // reproduce noise evolution even when region edges are absent.
            var old = mesh(0,lod,false,false,true);
            var next = mesh(1,lod,false,false,true);
            var removed = new HashSet<>(old); removed.removeAll(next);
            var added = new HashSet<>(next); added.removeAll(old);
            System.out.printf("production-wiggle lod=%d old=%d new=%d removed=%d added=%d%n",
                lod,old.size(),next.size(),removed.size(),added.size());
            if (!removed.isEmpty() || !added.isEmpty()) changedLevels++;
        }
        if (changedLevels == 0)
            throw new AssertionError("fixture failed to exercise production shape evolution");
        for(int lod:new int[]{1,2,4,8})
            for(float phase:new float[]{-1.25f,0,.125f,.5f,1,1.25f})
                mesh(phase,lod,true,false,true,true);
        System.out.println("PASS: fractional evolving noise retains world-fixed centers at four LODs");
    }
}
