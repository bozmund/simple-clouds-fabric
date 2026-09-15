import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;

public class CpuChunkSeamTest
{
    public static void main(String[] args)
    {
        var layer = new CpuCloudGenerator.NoiseLayer(16, 3, 16,16,16,1,0,0);
        var gen = new CpuCloudGenerator(List.of(new CpuCloudGenerator.CloudLayerGroup(List.of(layer),0,false,0,0,1)));
        gen.setRegions(List.of(new CpuCloudGenerator.RegionMask(0,0,10000,1,0,0,1,0)));
        for (int lod : new int[] {1,2,4,8})
        {
            var opaque = ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder());
            var trans = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
            var out = gen.generate(0,0,0,32*lod,16,32*lod,8,0,0,0,0,lod,128,opaque,trans,
                    (b,n,w) -> { throw new AssertionError("unexpected growth"); },new float[1],new float[1],0,new float[2],new float[1]);
            if (out[0].remaining()==0) throw new AssertionError("empty mesh");
            for (int p=0;p<out[0].limit();p+=24)
            {
                int side=(int)out[0].getFloat(p);
                if (side!=2 && side!=3) throw new AssertionError("interior wall at LOD " + lod + ", side " + side);
            }
        }
        System.out.println("PASS: saturated formation has no internal X/Z chunk walls at LOD 1/2/4/8");
    }
}
