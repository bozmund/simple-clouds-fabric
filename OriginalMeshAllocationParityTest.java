import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nonamecrackers2.simpleclouds.client.shader.compute.ComputeShader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.*;
import org.lwjgl.sdl.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Actual Intel compute comparison. Canonicalizes records because atomic output
 * ordering is unspecified; compares every emitted byte, not just face counts. */
public class OriginalMeshAllocationParityTest {
    static void check(boolean value,String message) {if(!value) throw new AssertionError(message);}
    static void checkGl(String stage) {
        int error=GL11.glGetError();
        check(error==GL11.GL_NO_ERROR,"GL error=0x"+Integer.toHexString(error)+" at "+stage);
    }
    static ResourceManager resources(boolean baseline) {
        return (ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
            new Class<?>[]{ResourceManager.class},(proxy,method,args)->{
                if(!method.getName().equals("getResourceOrThrow")) throw new UnsupportedOperationException(method.getName());
                Identifier id=(Identifier)args[0];
                Path path=baseline && id.getPath().equals("shaders/compute/original_cube_mesh.comp")
                    ?Path.of("test-fixtures/original_cube_mesh-allocation-baseline.comp")
                    :Path.of("src/main/resources/assets",id.getNamespace(),id.getPath());
                return new Resource(null,()->Files.newInputStream(path));
            });
    }
    static void uniform(ComputeShader shader,String name,float... values) {
        shader.forUniform(name,(p,l)->{
            switch(values.length) {
                case 1 -> GL41.glProgramUniform1f(p,l,values[0]);
                case 2 -> GL41.glProgramUniform2f(p,l,values[0],values[1]);
                case 3 -> GL41.glProgramUniform3f(p,l,values[0],values[1],values[2]);
                default -> throw new AssertionError();
            }
        });
    }
    static void integer(ComputeShader shader,String name,int value) {
        shader.forUniform(name,(p,l)->GL41.glProgramUniform1i(p,l,value));
    }
    static void allocate(ComputeShader shader,boolean transparent,boolean fixed) {
        if(!fixed) shader.createAndBindSSBO("TotalSides",GL15.GL_DYNAMIC_COPY).allocateBuffer(4);
        shader.createAndBindSSBO("SidesPerChunk",GL15.GL_DYNAMIC_COPY).allocateBuffer(8);
        shader.createAndBindSSBO("SideInfoBuffer",GL15.GL_DYNAMIC_COPY).allocateBuffer(6144*24);
        if(transparent) {
            if(!fixed) shader.createAndBindSSBO("TotalTransparentCubes",GL15.GL_DYNAMIC_COPY).allocateBuffer(4);
            shader.createAndBindSSBO("TransparentCubesPerChunk",GL15.GL_DYNAMIC_COPY).allocateBuffer(8);
            shader.createAndBindSSBO("TransparentCubeInfoBuffer",GL15.GL_DYNAMIC_COPY).allocateBuffer(1024*24);
        }
        shader.createAndBindSSBO("NoiseLayers",GL15.GL_DYNAMIC_DRAW).allocateBuffer(64);
        shader.createAndBindSSBO("LayerGroupings",GL15.GL_DYNAMIC_DRAW).allocateBuffer(48);
        integer(shader,"ChunkIndex",1);
        integer(shader,"OpaqueMeshDataOffset",3072);
        integer(shader,"TransparentMeshDataOffset",512);
        uniform(shader,"FadeStart",10000);uniform(shader,"FadeEnd",20000);
        integer(shader,"TransparencyDistance",10000);
        uniform(shader,"Scroll",2.5f,0,-1.75f);uniform(shader,"Wiggle",1.25f);
        uniform(shader,"Origin",-2,3,-2);uniform(shader,"RenderOffset",0,0,0);
    }
    static void prepare(ComputeShader shader,boolean transparent,boolean fixed,int type,float offset,float scale,boolean away,int border) {
        for(String name:List.of("SidesPerChunk","TransparentCubesPerChunk","TotalSides","TotalTransparentCubes")) {
            if(name.startsWith("Transparent") && !transparent || name.equals("TotalTransparentCubes") && !transparent
                || name.startsWith("Total") && fixed) continue;
            int bytes=name.endsWith("PerChunk")?8:4;
            shader.getShaderStorageBuffer(name).writeData(b->{for(int n=0;n<bytes;n+=4)b.putInt(n,0);},bytes,true);
        }
        shader.getShaderStorageBuffer("NoiseLayers").writeData(b->{
            for(int i=0;i<2;i++) b.putFloat(6).putFloat(offset+(i==0?0:.17f)).putFloat(3+i)
                .putFloat(4).putFloat(5+i).putFloat(1).putFloat(i).putFloat(1.2f);
        },64,true);
        shader.getShaderStorageBuffer("LayerGroupings").writeData(b->{
            b.putInt(0).putInt(type==0?1:2).putFloat(.7f).putFloat(1).putFloat(4).putFloat(.9f);
            b.putInt(1).putInt(2).putFloat(.2f).putFloat(2).putFloat(3).putFloat(.4f);
        },48,true);
        uniform(shader,"Scale",scale);integer(shader,"TestFacesFacingAway",away?1:0);
        integer(shader,"DoNotOccludeSide",border);integer(shader,"LodLevel",0);
    }
    static int count(ComputeShader shader,String name,int index) {
        int[] result={0};
        shader.getShaderStorageBuffer(name).readData(b->result[0]=b.getInt(index*4),(index+1)*4);
        return result[0];
    }
    static void verifyCounterSnapshot(ComputeShader shader,boolean transparent) {
        try(var readback=new dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalCounterReadback(8,transparent)) {
            readback.capture(shader.getShaderStorageBuffer("SidesPerChunk").getId(),
                transparent?shader.getShaderStorageBuffer("TransparentCubesPerChunk").getId():0);
            readback.await();readback.await(); // Re-reading a completed snapshot is safe.
            for(int index=0;index<2;index++) {
                check(readback.counts(false).getInt(index*4)==count(shader,"SidesPerChunk",index),"Opaque snapshot mismatch");
                if(transparent)check(readback.counts(true).getInt(index*4)==count(shader,"TransparentCubesPerChunk",index),"Transparent snapshot mismatch");
            }
            checkGl("persistent counter snapshot");
        }
    }
    static void reject(Runnable operation,String label) {
        try {operation.run();} catch(IllegalStateException expected){return;}
        throw new AssertionError("Missing lifecycle rejection: "+label);
    }
    static void verifyCounterLifecycle() {
        int opaque=GL45.glCreateBuffers(),transparent=GL45.glCreateBuffers();
        try {
            GL45.glNamedBufferData(opaque,16,GL15.GL_DYNAMIC_COPY);
            GL45.glNamedBufferData(transparent,16,GL15.GL_DYNAMIC_COPY);
            for(boolean transparency:new boolean[]{false,true}) {
                var readback=new dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalCounterReadback(16,transparency);
                try {
                    reject(()->readback.counts(false),"read before capture");
                    reject(readback::await,"wait before capture");
                    for(int cycle=0;cycle<20;cycle++) {
                        GL45.glClearNamedBufferData(opaque,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,new int[]{0});
                        GL45.glClearNamedBufferData(transparent,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,new int[]{0});
                        int[] a=cycle%2==0?new int[]{0,0,0,0}:new int[]{cycle,cycle*2,17,8192};
                        int[] b=cycle%2==0?new int[]{0,0,0,0}:new int[]{1,cycle,512,7};
                        GL45.glNamedBufferSubData(opaque,0,a);GL45.glNamedBufferSubData(transparent,0,b);
                        readback.capture(opaque,transparent);
                        reject(()->readback.capture(opaque,transparent),"overwrite pending snapshot");
                        reject(()->readback.counts(false),"read before completed fence");
                        readback.await();readback.await();
                        for(int i=0;i<4;i++) {
                            check(readback.counts(false).getInt(i*4)==a[i],"Stale opaque snapshot cycle="+cycle);
                            if(transparency)check(readback.counts(true).getInt(i*4)==b[i],"Stale transparent snapshot cycle="+cycle);
                        }
                        if(!transparency)reject(()->readback.counts(true),"missing transparency");
                        check(readback.counts(false).isReadOnly(),"Counter snapshot must not mutate GPU staging");
                        checkGl("counter reset/capture/reuse cycle="+cycle);
                    }
                    readback.capture(opaque,transparent); // Dispose with pending GPU work.
                } finally {readback.close();readback.close();}
                reject(()->readback.capture(opaque,transparent),"capture after close");
                reject(()->readback.counts(false),"read after close");
                reject(readback::await,"wait after close");
            }
            checkGl("counter lifecycle cleanup");
            System.out.println("PASS actual GPU counter lifecycle: 40 reset/capture/reuse cycles, zero/nonzero transitions, pending overwrite/read rejection, pending close and repeated close");
        } finally {GL15.glDeleteBuffers(opaque);GL15.glDeleteBuffers(transparent);}
    }
    static List<String> records(ComputeShader shader,String name,int count,int first) {
        check(count>=0 && count<=3072,"Invalid emitted count: "+count);
        var result=new ArrayList<String>();if(count==0)return result;
        shader.getShaderStorageBuffer(name).readData(b->{
            for(int n=0;n<count;n++) {
                var row=new StringBuilder();
                for(int k=0;k<6;k++) row.append(String.format("%08x",b.getInt((first+n)*24+k*4)));
                result.add(row.toString());
            }
        },(first+count)*24);
        Collections.sort(result);return result;
    }
    static List<List<String>> snapshot(ComputeShader shader,boolean transparent,boolean fixed) {
        int faces=count(shader,"SidesPerChunk",1);
        check(count(shader,"SidesPerChunk",0)==0,"Unrelated chunk counter modified");
        if(!fixed)check(count(shader,"TotalSides",0)==faces,"Opaque total/per-chunk mismatch");
        var opaque=records(shader,"SideInfoBuffer",faces,fixed?3072:0);
        List<String> translucent=List.of();
        if(transparent) {
            int cubes=count(shader,"TransparentCubesPerChunk",1);
            check(cubes<=512,"Transparent buffer overflow");
            check(count(shader,"TransparentCubesPerChunk",0)==0,"Unrelated transparent counter modified");
            if(!fixed)check(count(shader,"TotalTransparentCubes",0)==cubes,"Transparent total/per-chunk mismatch");
            translucent=records(shader,"TransparentCubeInfoBuffer",cubes,fixed?512:0);
        }
        return List.of(opaque,translucent);
    }
    static void run() throws Exception {
        verifyCounterLifecycle();
        int texture=GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
        GL45.glTextureStorage3D(texture,1,GL30.GL_RGBA32F,8,8,1);
        GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        float[] regions=new float[8*8*4];
        for(int z=0;z<8;z++)for(int x=0;x<8;x++){int n=(z*8+x)*4;regions[n]=x<4?0:1;regions[n+1]=x<4?1:.97f;}
        GL45.glTextureSubImage3D(texture,0,0,0,0,8,8,1,GL11.GL_RGBA,GL11.GL_FLOAT,regions);
        checkGl("region texture");
        int cases=0,totalFaces=0,totalCubes=0;
        try {
            for(int[] local:new int[][]{{8,8,8},{4,4,8}})
            for(int type=0;type<2;type++)for(int nearFade=0;nearFade<(type==0?2:1);nearFade++)for(int style=0;style<2;style++)
                for(boolean fixed:new boolean[]{false,true})for(boolean trans:new boolean[]{false,true}) {
                    var options=ImmutableMap.of("TYPE",""+type,"FADE_NEAR_ORIGIN",""+nearFade,"STYLE",""+style,
                        "TRANSPARENCY",trans?"1":"0","FIXED_SECTION_SIZE",fixed?"1":"0");
                    try(var old=ComputeShader.loadShader(Identifier.parse("simpleclouds:original_cube_mesh"),resources(true),local[0],local[1],local[2],options);
                        var current=ComputeShader.loadShader(Identifier.parse("simpleclouds:original_cube_mesh"),resources(false),local[0],local[1],local[2],options)) {
                        for(var shader:List.of(old,current)) {
                            allocate(shader,trans,fixed);
                            if(type==0){shader.setSampler2DArray("RegionsSampler",texture,0);integer(shader,"RegionsTexSize",8);uniform(shader,"RegionSampleOffset",0,0);}
                            checkGl("allocate "+options);
                        }
                        for(float renderY:new float[]{0,-.17f})
                        for(float offset:new float[]{-10,-.5f,0,10})for(float scale:new float[]{.7f,1,2,4,1.3f})
                            for(boolean away:new boolean[]{false,true})for(int border:new int[]{-1,1}) {
                                List<List<String>> expected=null;
                                for(var shader:List.of(old,current)) {
                                    prepare(shader,trans,fixed,type,offset,scale,away,border);
                                    uniform(shader,"RenderOffset",0,renderY,0);
                                    if(type==0 && nearFade==1){uniform(shader,"FadeStart",0);uniform(shader,"FadeEnd",20);}
                                    checkGl("prepare "+options);
                                    shader.dispatchAndWait(1,local[1]==4?2:1,1);
                                    checkGl("dispatch "+options);
                                    if(renderY==0 && offset==0 && scale==1 && away && border==-1)
                                        verifyCounterSnapshot(shader,trans);
                                    GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                                    var actual=snapshot(shader,trans,fixed);
                                    checkGl("readback "+options);
                                    if(expected==null) expected=actual;
                                    else check(expected.equals(actual),"Geometry changed: "+options+" local="+Arrays.toString(local)+" renderY="+renderY+" offset="+offset+" scale="+scale+" away="+away+" border="+border);
                                }
                                cases++;totalFaces+=expected.get(0).size();totalCubes+=expected.get(1).size();
                            }
                    }
                }
            check(totalFaces>0 && totalCubes>0,"Vacuous geometry comparison");
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL error during comparison");
            System.out.println("PASS actual GPU exact multiset parity cases="+cases+" opaqueRecords="+totalFaces+" transparentRecords="+totalCubes);
        } finally {GL11.glDeleteTextures(texture);}
    }
    public static void main(String[] args) throws Exception {
        RenderSystem.initRenderThread();check(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO),SDLError.SDL_GetError());
        long window=0,context=0;
        try {
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION,4);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION,5);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            window=SDLVideo.SDL_CreateWindow("Mesh allocation parity",32,32,SDLVideo.SDL_WINDOW_HIDDEN|SDLVideo.SDL_WINDOW_OPENGL);
            check(window!=0,SDLError.SDL_GetError());context=SDLVideo.SDL_GL_CreateContext(window);check(context!=0,SDLError.SDL_GetError());
            check(SDLVideo.SDL_GL_MakeCurrent(window,context),SDLError.SDL_GetError());GL.createCapabilities();
            String renderer=GL11.glGetString(GL11.GL_RENDERER);System.out.println("GL renderer="+renderer);
            check(renderer.toLowerCase(Locale.ROOT).contains("intel"),"Must test on Intel");checkGl("context setup");run();
        } finally {
            GL.setCapabilities(null);if(context!=0)SDLVideo.SDL_GL_DestroyContext(context);
            if(window!=0)SDLVideo.SDL_DestroyWindow(window);SDLInit.SDL_Quit();
        }
    }
}
