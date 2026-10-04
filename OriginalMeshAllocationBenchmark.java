import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nonamecrackers2.simpleclouds.client.shader.compute.ComputeShader;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.*;
import org.lwjgl.sdl.*;
import java.util.*;

/** Interleaved GPU comparison at identical input and cadence, no game/profile.
 * Blocking query reads are confined to this benchmark, never production. */
public class OriginalMeshAllocationBenchmark extends OriginalMeshAllocationParityTest {
    static final int VOXELS=32*32*32;
    static void largeBuffers(ComputeShader shader) {
        allocate(shader,true,false);
        shader.getShaderStorageBuffer("SideInfoBuffer").allocateBuffer(VOXELS*6*24);
        shader.getShaderStorageBuffer("TransparentCubeInfoBuffer").allocateBuffer(VOXELS*24);
    }
    static double sample(ComputeShader shader,float offset,int[] counts,int[] queries) {
        prepare(shader,true,false,1,offset,1,true,-1);
        shader.getShaderStorageBuffer("NoiseLayers").writeData(b->{
            for(int i=0;i<2;i++) b.putFloat(32).putFloat(offset+(i==0?0:.17f)).putFloat(8+i)
                .putFloat(9).putFloat(10+i).putFloat(2).putFloat(0).putFloat(1.2f);
        },64,true);
        GL11.glFinish(); // Isolated benchmark only: exclude earlier uploads from wall time.
        long wallStart=System.nanoTime();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,queries[0]);
        shader.dispatchAndWait(4,4,4);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        GL11.glFinish();
        double wallMs=(System.nanoTime()-wallStart)/1_000_000.0;
        long gpuNs=GL33.glGetQueryObjectui64(queries[0],GL15.GL_QUERY_RESULT);
        counts[0]=count(shader,"TotalSides",0);counts[1]=count(shader,"TotalTransparentCubes",0);
        check(counts[0]>=0 && counts[0]<=VOXELS*6 && counts[1]>=0 && counts[1]<=VOXELS,"Invalid benchmark counts");
        checkGl("benchmark dispatch");
        System.out.printf(Locale.ROOT,"SAMPLE wallMs=%.6f gpuMs=%.6f%n",wallMs,gpuNs/1_000_000.0);
        return wallMs;
    }
    static double mean(double[] values){double sum=0;for(double v:values)sum+=v;return sum/values.length;}
    static double median(double[] values){var copy=values.clone();Arrays.sort(copy);return (copy[15]+copy[16])/2;}
    static void benchmark() throws Exception {
        var options=ImmutableMap.of("TYPE","1","FADE_NEAR_ORIGIN","0","STYLE","1","TRANSPARENCY","1","FIXED_SECTION_SIZE","0");
        int[] queries={GL15.glGenQueries(),GL15.glGenQueries()};
        try(var old=ComputeShader.loadShader(Identifier.parse("simpleclouds:original_cube_mesh"),resources(true),8,8,8,options);
            var current=ComputeShader.loadShader(Identifier.parse("simpleclouds:original_cube_mesh"),resources(false),8,8,8,options)) {
            largeBuffers(old);largeBuffers(current);
            for(float offset:new float[]{0,6}) {
                int[] before=new int[2],after=new int[2];
                double[] originalMs=new double[32],optimizedMs=new double[32];
                for(int i=-8;i<32;i++) {
                    double a,b;
                    if((i&1)==0){a=sample(old,offset,before,queries);b=sample(current,offset,after,queries);}
                    else {b=sample(current,offset,after,queries);a=sample(old,offset,before,queries);}
                    check(Arrays.equals(before,after),"Benchmark changed counts");
                    if(i>=0){originalMs[i]=a;optimizedMs[i]=b;}
                }
                System.out.printf(Locale.ROOT,"SYNC-WALL-BENCH offset=%.1f workgroups=4x4x4 samples=32 faces=%d transparent=%d oldMeanMs=%.6f newMeanMs=%.6f oldMedianMs=%.6f newMedianMs=%.6f ratio=%.4f%n",
                    offset,before[0],before[1],mean(originalMs),mean(optimizedMs),median(originalMs),median(optimizedMs),mean(originalMs)/mean(optimizedMs));
            }
        } finally {for(int query:queries)GL15.glDeleteQueries(query);}
    }
    public static void main(String[] args) throws Exception {
        RenderSystem.initRenderThread();check(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO),SDLError.SDL_GetError());
        long window=0,context=0;
        try {
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION,4);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION,5);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            window=SDLVideo.SDL_CreateWindow("Mesh allocation benchmark",32,32,SDLVideo.SDL_WINDOW_HIDDEN|SDLVideo.SDL_WINDOW_OPENGL);
            check(window!=0,SDLError.SDL_GetError());context=SDLVideo.SDL_GL_CreateContext(window);check(context!=0,SDLError.SDL_GetError());
            check(SDLVideo.SDL_GL_MakeCurrent(window,context),SDLError.SDL_GetError());GL.createCapabilities();
            String renderer=GL11.glGetString(GL11.GL_RENDERER);System.out.println("GL renderer="+renderer);
            check(renderer.toLowerCase(Locale.ROOT).contains("intel"),"Must benchmark on Intel");checkGl("context setup");benchmark();
        } finally {
            GL.setCapabilities(null);if(context!=0)SDLVideo.SDL_GL_DestroyContext(context);
            if(window!=0)SDLVideo.SDL_DestroyWindow(window);SDLInit.SDL_Quit();
        }
    }
}
