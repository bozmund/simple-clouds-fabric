package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;

/** Original compute shader adapter, optionally used by the experimental world backend.
 * TYPE=1 opaque and transparent output. DSA avoids cached generic buffer bindings.
 * Every touched indexed binding/range and current program is restored.
 */
public final class GpuCloudGeneration implements AutoCloseable {
    public static final Logger LOGGER = LogManager.getLogger("simpleclouds/GpuCompute");
    private static final String[] BLOCKS = {"TotalSides", "SideInfoBuffer", "SidesPerChunk", "NoiseLayers", "LayerGroupings",
        "TotalTransparentCubes", "TransparentCubeInfoBuffer", "TransparentCubesPerChunk", "FaceIds", "TransparentCubeIds"};
    public static final int STORAGE_BINDING_COUNT = BLOCKS.length;
    private static final int LOCAL_SIZE = 8;
    // Tall cumulonimbus can extend to 256 cloud cells. Keep one bounded full
    // chunk allocation for every selected cloud type, even before it spawns.
    private static final int MAX_CELLS = 32 * 256 * 32;
    private static final int MAX_INSTANCES = MAX_CELLS * 6;
    private static final int MAX_LAYERS = 32;
    private static final int MAX_GROUPS = 32;
    private static final int MAX_REGION_TEXELS = 65536;
    public static final int BYTES_PER_INSTANCE = 24;
    private final ChunkBufferPool readbackPool;
    private final int[] buffers = new int[BLOCKS.length];
    private static final boolean MAPPED_COUNTERS = "1".equals(System.getenv("SIMPLECLOUDS_GPU_MAPPED_COUNTERS"));
    private static final boolean SHARED_OCCUPANCY = "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
        && "1".equals(System.getenv("SIMPLECLOUDS_GPU_SHARED_OCCUPANCY"));
    // Benchmark the original Forge camera-facing predicate without changing
    // the port's production face contract or CPU/GPU parity tests.
    private static final boolean CULL_AWAY_FACES = "1".equals(System.getenv("SIMPLECLOUDS_DEV"))
        && "1".equals(System.getenv("SIMPLECLOUDS_GPU_CULL_AWAY_FACES"));
    private final ByteBuffer[] mappedCounters = new ByteBuffer[2];
    private int program = -1;
    private int regionProgram = -1;
    private int regionTexture;
    private int regionTextureWidth;
    private int regionTextureHeight;
    private int instanceCount;
    private ByteBuffer instanceData;
    private ByteBuffer transparentData;
    private int transparentInstanceCount;
    private int completedCellCount;
    private long lastGenerateNanos;
    private long pendingFence;
    private long pendingCellCount;
    private Sampling pendingSampling;
    private long pendingStartedNanos;

    public GpuCloudGeneration() { this(null); }

    /** The world renderer supplies its bounded pool; isolated adapter tests can omit it. */
    public GpuCloudGeneration(ChunkBufferPool readbackPool) {
        this.readbackPool = readbackPool;
    }

    private ByteBuffer acquire(int bytes) {
        ByteBuffer buffer = readbackPool == null
            ? ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            : readbackPool.borrow(bytes);
        buffer.position(0);
        buffer.limit(bytes);
        return buffer;
    }

    private void releaseTemporary(ByteBuffer buffer) {
        if (readbackPool != null) readbackPool.release(buffer);
    }

    /** Cloud-unit sampling coordinates; worldBaseY is in world blocks. */
    public record Sampling(int lodScale, float worldBaseY, float scrollX, float scrollY, float scrollZ, float wiggle,
                           int transparencyDistance) {
        public Sampling(int lodScale, float worldBaseY, float scrollX, float scrollY, float scrollZ, float wiggle) {
            this(lodScale, worldBaseY, scrollX, scrollY, scrollZ, wiggle,
                (int)CpuCloudGenerator.TRANSPARENCY_DISTANCE);
        }
        public Sampling {
            if (lodScale<=0 || (lodScale & (lodScale-1))!=0 || lodScale>64
                    || !Float.isFinite(worldBaseY) || !Float.isFinite(scrollX) || !Float.isFinite(scrollY)
                    || !Float.isFinite(scrollZ) || !Float.isFinite(wiggle) || transparencyDistance<0)
                throw new IllegalArgumentException("Invalid cloud sampling parameters");
        }
    }

    public static String backendClassName() {
        return RenderSystem.getDevice().getDeviceInfo().backendName();
    }
    public static boolean isOpenGLBackend() {
        return "OpenGL".equalsIgnoreCase(backendClassName());
    }
    public boolean init() {
        RenderSystem.assertOnRenderThread();
        if (program != -1 || regionProgram != -1) throw new IllegalStateException("Already initialized");
        if (!isOpenGLBackend() || !GL.getCapabilities().OpenGL45) return false;
        if (GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) < BLOCKS.length) return false;
        int shader = 0;
        try {
            checkGl("before compute initialization");
            String source = readResource("assets/simpleclouds/shaders/compute/cube_mesh.comp")
                .replace("#moj_import <simpleclouds:psrdnoise.glsl>", readResource("assets/simpleclouds/shaders/include/psrdnoise.glsl"));
            String common = source.replace("${FADE_NEAR_ORIGIN}", "0")
                .replace("#define FACE_IDS 0", "#define FACE_IDS 1")
                .replace("#define SHARED_OCCUPANCY 0", "#define SHARED_OCCUPANCY " + (SHARED_OCCUPANCY ? "1" : "0"))
                .replace("${STYLE}", "0").replace("${TRANSPARENCY}", "1")
                .replace("${FIXED_SECTION_SIZE}", "0")
                .replace("${LOCAL_SIZE_X}", "8").replace("${LOCAL_SIZE_Y}", "8").replace("${LOCAL_SIZE_Z}", "8");
            String comp = common.replace("${TYPE}", "1");
            shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            GL20.glShaderSource(shader, comp);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, shader);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            GL20.glShaderSource(shader, common.replace("${TYPE}", "0"));
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            regionProgram = GL20.glCreateProgram();
            GL20.glAttachShader(regionProgram, shader);
            GL20.glLinkProgram(regionProgram);
            if (GL20.glGetProgrami(regionProgram, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(regionProgram));
            // Declaration order does not assign bindings; all default to zero.
            long[] sizes = {4, (long)MAX_INSTANCES * BYTES_PER_INSTANCE, 4, MAX_LAYERS * 32L, MAX_GROUPS * 24L,
                4, MAX_CELLS * 24L, 4, (long)MAX_INSTANCES * Integer.BYTES,
                (long)MAX_CELLS * Integer.BYTES};
            for (int i=0; i<buffers.length; i++) {
                for(int p:new int[]{program,regionProgram}) {
                    int block = GL43.glGetProgramResourceIndex(p, GL43.GL_SHADER_STORAGE_BLOCK, BLOCKS[i]);
                    if (block == GL43.GL_INVALID_INDEX) throw new IllegalStateException("Missing block " + BLOCKS[i]);
                    GL43.glShaderStorageBlockBinding(p, block, i);
                }
                // Create an object, not merely a reserved glGenBuffers name.
                buffers[i] = GL45.glCreateBuffers();
                if (MAPPED_COUNTERS && (i == 0 || i == 5)) {
                    int flags = GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT
                        | GL44.GL_MAP_COHERENT_BIT | GL44.GL_DYNAMIC_STORAGE_BIT;
                    GL45.glNamedBufferStorage(buffers[i], sizes[i], flags);
                    ByteBuffer mapped = GL45.glMapNamedBufferRange(buffers[i], 0, sizes[i],
                        GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT, null);
                    if (mapped == null) throw new IllegalStateException("Could not map " + BLOCKS[i] + " counter");
                    mappedCounters[i == 0 ? 0 : 1] = mapped.order(ByteOrder.nativeOrder());
                } else {
                    GL45.glNamedBufferData(buffers[i], sizes[i], GL15.GL_DYNAMIC_DRAW);
                }
                if (GL45.glGetNamedBufferParameteri64(buffers[i], GL15.GL_BUFFER_SIZE) != sizes[i])
                    throw new IllegalStateException("Wrong allocation size for " + BLOCKS[i]);
            }
            checkGl("compute initialization");
            return true;
        } catch (Exception e) {
            LOGGER.error("Compute initialization failed", e);
            close();
            return false;
        } finally { if (shader != 0) GL20.glDeleteShader(shader); }
    }

    /** Validated bounded dispatch; shader guards partial workgroups. */
    public int generate(int x0, int y0, int z0, int x1, int y1, int z1,
            float centerX, float centerY, float centerZ, float fadeStart, float fadeEnd,
            List<CloudGenerationInputs.NoiseLayer> layers, float transparencyFade) {
        return generate(x0,y0,z0,x1,y1,z1,centerX,centerY,centerZ,fadeStart,fadeEnd,
            new CloudGenerationInputs.CloudLayerGroup(layers,transparencyFade,false,0,0,1),new Sampling(1,0,0,0,0,0));
    }

    public int generate(int x0, int y0, int z0, int x1, int y1, int z1,
            float centerX, float centerY, float centerZ, float fadeStart, float fadeEnd,
            CloudGenerationInputs.CloudLayerGroup group, Sampling sampling) {
        return generateInternal(x0,y0,z0,x1,y1,z1,centerX,centerY,centerZ,fadeStart,fadeEnd,
            List.of(group),List.of(),false,sampling,false);
    }

    /** Opt-in region compute path. No world renderer calls this until parity and scheduling are validated. */
    public int generateRegions(int x0,int y0,int z0,int x1,int y1,int z1,
            float centerX,float centerY,float centerZ,
            List<CloudGenerationInputs.CloudLayerGroup> groups,List<CloudGenerationInputs.RegionMask> regions,Sampling sampling) {
        return generateInternal(x0,y0,z0,x1,y1,z1,centerX,centerY,centerZ,0,1,
            groups,regions,true,sampling,false);
    }

    /** Starts one bounded region dispatch without reading back its output this frame. */
    public void submitRegions(int x0,int y0,int z0,int x1,int y1,int z1,
            float centerX,float centerY,float centerZ,
            List<CloudGenerationInputs.CloudLayerGroup> groups,List<CloudGenerationInputs.RegionMask> regions,Sampling sampling) {
        generateInternal(x0,y0,z0,x1,y1,z1,centerX,centerY,centerZ,0,1,
            groups,regions,true,sampling,true);
    }

    /** Returns false without waiting when the submitted dispatch is still running. */
    public boolean pollRegions() {
        return pollRegions(true);
    }

    /** Completes a fenced dispatch by reading validated counters only. The face
     * buffers remain on the GPU for direct rendering and storm reduction. */
    public boolean pollRegionsCountsOnly() {
        return pollRegions(false);
    }

    private boolean pollRegions(boolean readGeometry) {
        RenderSystem.assertOnRenderThread();
        if(pendingFence==0) throw new IllegalStateException("No pending region dispatch");
        int status=GL32.glClientWaitSync(pendingFence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
        if(status==GL32.GL_TIMEOUT_EXPIRED) return false;
        if(status==GL32.GL_WAIT_FAILED) throw new IllegalStateException("GPU region fence wait failed");
        try {
            readResults(pendingCellCount,pendingSampling,readGeometry);
            checkGl("async region readback");
            return true;
        } finally {
            GL32.glDeleteSync(pendingFence);
            pendingFence=0;
            pendingCellCount=0;
            pendingSampling=null;
            lastGenerateNanos=System.nanoTime()-pendingStartedNanos;
            pendingStartedNanos=0;
        }
    }

    private int generateInternal(int x0, int y0, int z0, int x1, int y1, int z1,
            float centerX, float centerY, float centerZ, float fadeStart, float fadeEnd,
            List<CloudGenerationInputs.CloudLayerGroup> groups,List<CloudGenerationInputs.RegionMask> regions,
            boolean regionMode, Sampling sampling, boolean deferReadback) {
        RenderSystem.assertOnRenderThread();
        if(pendingFence!=0) throw new IllegalStateException("Previous GPU dispatch is still pending");
        instanceCount = 0;
        completedCellCount = 0;
        instanceData = null;
        transparentData = null;
        transparentInstanceCount = 0;
        if (program == -1 || regionProgram == -1) throw new IllegalStateException("Compute not initialized");
        long dx=(long)x1-x0, dy=(long)y1-y0, dz=(long)z1-z0;
        int lod=sampling.lodScale();
        if(dx%lod!=0 || dy%lod!=0 || dz%lod!=0)
            throw new IllegalArgumentException("Volume must align to its LOD cells");
        dx/=lod; dy/=lod; dz/=lod;
        int layerCount=groups.stream().mapToInt(g->g.layers().size()).sum();
        if (dx<=0 || dy<=0 || dz<=0 || dx>MAX_CELLS || dy>MAX_CELLS || dz>MAX_CELLS
                || dx*dy*dz>MAX_CELLS || groups.isEmpty() || groups.size()>MAX_GROUPS
                || layerCount==0 || layerCount>MAX_LAYERS || groups.stream().anyMatch(g->g.layers().isEmpty())
                || (regionMode && (dx+2)*(dz+2)>MAX_REGION_TEXELS)
                || !Float.isFinite(fadeStart) || !Float.isFinite(fadeEnd) || fadeEnd<=fadeStart)
            throw new IllegalArgumentException("Unsupported compute volume/layers/fade: cells="+dx+"x"+dy+"x"+dz
                +" groups="+groups.size()+" layers="+layerCount+" regionTexels="+((dx+2)*(dz+2))
                +" fade="+fadeStart+".."+fadeEnd+"; refusing unsafe dispatch");
        if(regionMode) for(var r:regions) if(r.groupIndex()<0 || r.groupIndex()>=groups.size()
                || !Float.isFinite(r.x()) || !Float.isFinite(r.z()) || !Float.isFinite(r.radius()) || r.radius()<=0
                || !Float.isFinite(r.m00()) || !Float.isFinite(r.m01())
                || !Float.isFinite(r.m10()) || !Float.isFinite(r.m11()))
            throw new IllegalArgumentException("Invalid GPU cloud region x="+r.x()+" z="+r.z()
                +" radius="+r.radius()+" transform=["+r.m00()+","+r.m01()+","+r.m10()+","+r.m11()
                +"] group="+r.groupIndex()+" of "+groups.size());
        for (int axis=0; axis<3; axis++) {
            long count = ((axis==0 ? dx : axis==1 ? dy : dz)+LOCAL_SIZE-1)/LOCAL_SIZE;
            if (count>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,axis))
                throw new IllegalArgumentException("Dispatch exceeds device limit");
        }
        long started=System.nanoTime();
        int previousProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int generic=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        int previousActiveTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousRegionTexture=GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        GL13.glActiveTexture(previousActiveTexture);
        int[] bound=new int[buffers.length];
        long[] offsets=new long[buffers.length], sizes=new long[buffers.length];
        for(int i=0;i<buffers.length;i++) {
            bound[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);
            offsets[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);
            sizes[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);
        }
        try {
            checkGl("before compute dispatch");
            var noise=ByteBuffer.allocateDirect(layerCount*32).order(ByteOrder.nativeOrder());
            var grouping=ByteBuffer.allocateDirect(groups.size()*24).order(ByteOrder.nativeOrder());
            int layerStart=0;
            for(var group:groups) {
                for(var l:group.layers()) noise.putFloat(l.height()).putFloat(l.valueOffset()).putFloat(l.scaleX())
                    .putFloat(l.scaleY()).putFloat(l.scaleZ()).putFloat(l.fadeDistance()).putFloat(l.heightOffset()).putFloat(l.valueScale());
                grouping.putInt(layerStart).putInt(layerStart+group.layers().size()).putFloat(group.storminess()).putFloat(group.stormStart())
                    .putFloat(group.stormFadeDistance()).putFloat(group.transparencyFade());
                layerStart+=group.layers().size();
            }
            noise.flip();
            GL45.glNamedBufferSubData(buffers[3],0,noise);
            grouping.flip();
            GL45.glNamedBufferSubData(buffers[4],0,grouping);
            if(regionMode) uploadRegions(x0,z0,(int)dx,(int)dz,lod,regions);
            GL45.glNamedBufferSubData(buffers[0],0,new int[]{0});
            GL45.glNamedBufferSubData(buffers[2],0,new int[]{0});
            GL45.glNamedBufferSubData(buffers[5],0,new int[]{0});
            GL45.glNamedBufferSubData(buffers[7],0,new int[]{0});
            int activeProgram=regionMode?regionProgram:program;
            GL20.glUseProgram(activeProgram);
            for(int i=0;i<buffers.length;i++) GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,buffers[i]);
            if(regionMode) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,regionTexture);
                GL13.glActiveTexture(previousActiveTexture);
                uniform1i(activeProgram,"RegionsSampler",0);
                uniform1i(activeProgram,"LodLevel",0);
                uniform1i(activeProgram,"RegionsTexSize",(int)dx+2);
                GL20.glUniform2f(GL20.glGetUniformLocation(activeProgram,"RegionSampleOffset"),1,1);
            }
            uniform1f(activeProgram,"Scale",lod); uniform3f(activeProgram,"RenderOffset",x0,y0,z0);
            GL20.glUniform3i(GL20.glGetUniformLocation(activeProgram,"DispatchSize"),(int)dx,(int)dy,(int)dz);
            uniform1f(activeProgram,"WorldBaseY",sampling.worldBaseY());
            uniform3f(activeProgram,"Origin",centerX,centerY,centerZ);
            if(!regionMode) { uniform1f(activeProgram,"FadeStart",fadeStart); uniform1f(activeProgram,"FadeEnd",fadeEnd); }
            uniform3f(activeProgram,"Scroll",sampling.scrollX(),sampling.scrollY(),sampling.scrollZ()); uniform1f(activeProgram,"Wiggle",sampling.wiggle());
            uniform1i(activeProgram,"ChunkIndex",0); uniform1i(activeProgram,"DoNotOccludeSide",-1);
            uniform1i(activeProgram,"TransparencyDistance",sampling.transparencyDistance());
            // Shader predicate: true retains all faces without camera culling.
            uniform1i(activeProgram,"TestFacesFacingAway",CULL_AWAY_FACES ? 0 : 1);
            GL43.glDispatchCompute((int)((dx+LOCAL_SIZE-1)/LOCAL_SIZE),
                (int)((dy+LOCAL_SIZE-1)/LOCAL_SIZE),(int)((dz+LOCAL_SIZE-1)/LOCAL_SIZE));
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT
                | (MAPPED_COUNTERS ? GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT : 0));
            if(deferReadback) {
                pendingFence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                if(pendingFence==0) throw new IllegalStateException("Could not create GPU region fence");
                pendingCellCount=dx*dy*dz;
                pendingSampling=sampling;
                pendingStartedNanos=started;
                return 0;
            }
            return readResults(dx*dy*dz,sampling,true);
        } finally {
            for(int i=0;i<buffers.length;i++) {
                if(bound[i]!=0 && sizes[i]>0) GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,bound[i],offsets[i],sizes[i]);
                else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,bound[i]);
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);
            GL20.glUseProgram(previousProgram);
            if(regionMode) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,previousRegionTexture);
                GL13.glActiveTexture(previousActiveTexture);
            }
            if(!deferReadback) lastGenerateNanos=System.nanoTime()-started;
        }
    }

    private int readResults(long cellCount,Sampling sampling,boolean readGeometry) {
            completedCellCount = (int)cellCount;
            int[] counter={0};
            if (MAPPED_COUNTERS && pendingFence != 0)
                counter[0] = mappedCounters[0].getInt(0);
            else
                GL45.glGetNamedBufferSubData(buffers[0],0,counter);
            checkGl("compute counter readback");
            if (counter[0]<0 || counter[0]>cellCount*6 || counter[0]>MAX_INSTANCES)
                throw new IllegalStateException("Invalid GPU face count " + counter[0]);
            instanceCount=counter[0];
            if(readGeometry && instanceCount>0) {
                instanceData=acquire(instanceCount*BYTES_PER_INSTANCE);
                GL45.glGetNamedBufferSubData(buffers[1],0,instanceData);
                checkGl("compute geometry readback");
            }
            int[] cubes={0};
            if (MAPPED_COUNTERS && pendingFence != 0)
                cubes[0] = mappedCounters[1].getInt(0);
            else
                GL45.glGetNamedBufferSubData(buffers[5],0,cubes);
            checkGl("transparent counter readback");
            if(cubes[0]<0 || cubes[0]>cellCount || cubes[0]>MAX_CELLS)
                throw new IllegalStateException("Invalid GPU transparent count "+cubes[0]);
            transparentInstanceCount=cubes[0]*6;
            if(readGeometry && cubes[0]>0) {
                var data=acquire(cubes[0]*24);
                try {
                    GL45.glGetNamedBufferSubData(buffers[6],0,data);
                    checkGl("transparent cube readback");
                    transparentData=acquire(transparentInstanceCount*28);
                    // Original GPU shader emits one compact cube; the current draw
                    // pipeline consumes six side instances in world-block units.
                    for(int p=0;p<data.limit();p+=24) for(int side=0;side<6;side++) {
                        transparentData.putFloat(side).putFloat(data.getFloat(p)*8)
                            .putFloat(data.getFloat(p+4)*8+sampling.worldBaseY()).putFloat(data.getFloat(p+8)*8)
                            .putFloat(data.getFloat(p+20)*8).putFloat(data.getFloat(p+12)).putFloat(data.getFloat(p+16));
                    }
                    transparentData.flip();
                } finally {
                    releaseTemporary(data);
                }
            }
            return instanceCount;
    }

    private void uploadRegions(int x0,int z0,int dx,int dz,int lod,List<CloudGenerationInputs.RegionMask> regions) {
        int width=dx+2,height=dz+2;
        if(regionTexture==0 || regionTextureWidth!=width || regionTextureHeight!=height) {
            if(regionTexture!=0) GL11.glDeleteTextures(regionTexture);
            regionTexture=GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
            GL45.glTextureStorage3D(regionTexture,1,GL30.GL_RG32F,width,height,1);
            GL45.glTextureParameteri(regionTexture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL45.glTextureParameteri(regionTexture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            regionTextureWidth=width;regionTextureHeight=height;
        }
        var mask=acquire(width*height*8);
        float edge=200.0f;
        for(int z=0;z<height;z++) for(int x=0;x<width;x++) {
            float wx=x0+(x-1)*lod+lod*.5f,wz=z0+(z-1)*lod+lod*.5f;
            int best=-1;float coverage=0;
            for(var r:regions) {
                float ox=wx-r.x(),oz=wz-r.z();
                float tx=r.m00()*ox+r.m01()*oz,tz=r.m10()*ox+r.m11()*oz;
                float distance=(float)Math.sqrt(tx*tx+tz*tz);
                if(distance>r.radius()+edge) continue;
                if(distance<r.radius()) {
                    if(best<0) { best=r.groupIndex();coverage=Math.min((r.radius()-distance)*.005f,1); }
                } else if(best>=0) coverage*=Math.min((distance-r.radius())*.005f,1);
            }
            mask.putFloat(best).putFloat(coverage);
        }
        mask.flip();
        // DSA still observes caller pixel-store state. Minecraft uses a nonzero
        // UNPACK_ROW_LENGTH/IMAGE_HEIGHT; without normalizing these the upload
        // silently repeats the first (empty) region column across the texture.
        int[] keys={GL11.GL_UNPACK_ALIGNMENT,GL12.GL_UNPACK_ROW_LENGTH,GL12.GL_UNPACK_IMAGE_HEIGHT,
            GL12.GL_UNPACK_SKIP_PIXELS,GL12.GL_UNPACK_SKIP_ROWS,GL12.GL_UNPACK_SKIP_IMAGES,
            GL11.GL_UNPACK_SWAP_BYTES,GL11.GL_UNPACK_LSB_FIRST};
        int[] previous=new int[keys.length];
        for(int i=0;i<keys.length;i++) previous[i]=GL11.glGetInteger(keys[i]);
        try {
            for(int i=0;i<keys.length;i++) GL11.glPixelStorei(keys[i],i==0?4:0);
            GL45.glTextureSubImage3D(regionTexture,0,0,0,0,width,height,1,GL30.GL_RG,GL11.GL_FLOAT,mask);
        } finally {
            for(int i=0;i<keys.length;i++) GL11.glPixelStorei(keys[i],previous[i]);
            releaseTemporary(mask);
        }
        checkGl("region texture upload");
    }
    private void uniform1f(int p,String name,float v) { GL20.glUniform1f(GL20.glGetUniformLocation(p,name),v); }
    private void uniform1i(int p,String name,int v) { GL20.glUniform1i(GL20.glGetUniformLocation(p,name),v); }
    private void uniform3f(int p,String name,float x,float y,float z) { GL20.glUniform3f(GL20.glGetUniformLocation(p,name),x,y,z); }
    private static void checkGl(String where) {
        int error=GL11.glGetError();
        if(error!=GL11.GL_NO_ERROR) throw new IllegalStateException(where+": GL 0x"+Integer.toHexString(error));
    }
    private static String readResource(String path) throws java.io.IOException {
        try(InputStream in=GpuCloudGeneration.class.getClassLoader().getResourceAsStream(path)) {
            if(in==null) throw new java.io.FileNotFoundException(path);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    public int instanceCount() { return instanceCount; }
    public ByteBuffer instanceData() { return instanceData; }
    public ByteBuffer transparentData() { return transparentData; }
    public int transparentInstanceCount() { return transparentInstanceCount; }
    int transparentCubeCount() {
        if (pendingFence != 0 || completedCellCount <= 0)
            throw new IllegalStateException("Transparent cubes are not ready");
        return transparentInstanceCount / 6;
    }
    int completedCellCount() { return completedCellCount; }
    int completedOpaqueFaceCount() {
        if (pendingFence != 0 || completedCellCount <= 0)
            throw new IllegalStateException("Opaque face IDs are not ready");
        return instanceCount;
    }
    int opaqueFaceIdBufferHandle() { return buffers[8]; }
    int compactTransparentBufferHandle() { return buffers[6]; }
    int transparentCubeIdBufferHandle() { return buffers[9]; }
    public long lastGenerateNanos() { return lastGenerateNanos; }

    /** Development verification of each opaque face's voxel/side identity. */
    public int[] faceIds() {
        RenderSystem.assertOnRenderThread();
        if (pendingFence != 0) throw new IllegalStateException("Face IDs are not ready");
        int[] ids = new int[instanceCount];
        if (ids.length != 0) GL45.glGetNamedBufferSubData(buffers[8],0,ids);
        checkGl("face ID readback");
        return ids;
    }

    /** Development verification of compact transparent cube voxel identities. */
    public int[] transparentCubeIds() {
        RenderSystem.assertOnRenderThread();
        if (pendingFence != 0) throw new IllegalStateException("Transparent cube IDs are not ready");
        int cubes = transparentInstanceCount / 6;
        int[] ids = new int[cubes];
        if (cubes != 0) GL45.glGetNamedBufferSubData(buffers[9],0,ids);
        checkGl("transparent cube ID readback");
        return ids;
    }

    /** Copy generated opaque instances directly into a renderable buffer after
     * either full or counts-only completion. */
    public void copyOpaqueTo(GpuBuffer target) {
        RenderSystem.assertOnRenderThread();
        if (pendingFence != 0 || completedCellCount <= 0 || instanceCount <= 0)
            throw new IllegalStateException("No completed opaque geometry to copy");
        if (!(target instanceof GlBuffer glTarget) || target.isClosed()
                || (target.usage() & GpuBuffer.USAGE_COPY_DST) == 0)
            throw new IllegalArgumentException("Opaque copy needs a live OpenGL COPY_DST buffer");
        long bytes = (long)instanceCount * BYTES_PER_INSTANCE;
        if (target.size() < bytes) throw new IllegalArgumentException("Opaque destination is too small");
        GL45.glCopyNamedBufferSubData(buffers[1],glTarget.handle(),0,0,bytes);
        checkGl("opaque GPU-to-GPU copy");
    }

    public void copyFaceIdsTo(GpuBuffer target) {
        RenderSystem.assertOnRenderThread();
        if (pendingFence != 0 || completedCellCount <= 0 || instanceCount <= 0)
            throw new IllegalStateException("No completed face IDs to copy");
        if (!(target instanceof GlBuffer glTarget) || target.isClosed()
                || (target.usage() & GpuBuffer.USAGE_COPY_DST) == 0)
            throw new IllegalArgumentException("Face ID copy needs a live OpenGL COPY_DST buffer");
        long bytes = (long)instanceCount * Integer.BYTES;
        if (target.size() < bytes) throw new IllegalArgumentException("Face ID destination is too small");
        GL45.glCopyNamedBufferSubData(buffers[8],glTarget.handle(),0,0,bytes);
        checkGl("face ID GPU-to-GPU copy");
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if(pendingFence!=0) { GL32.glDeleteSync(pendingFence); pendingFence=0; }
        pendingSampling=null; pendingCellCount=0; pendingStartedNanos=0;
        if(program!=-1) { GL20.glDeleteProgram(program); program=-1; }
        if(regionProgram!=-1) { GL20.glDeleteProgram(regionProgram); regionProgram=-1; }
        if(regionTexture!=0) { GL11.glDeleteTextures(regionTexture); regionTexture=0; }
        for(int i=0;i<buffers.length;i++) if(buffers[i]!=0) {
            if (MAPPED_COUNTERS && (i == 0 || i == 5) && mappedCounters[i == 0 ? 0 : 1] != null) {
                GL45.glUnmapNamedBuffer(buffers[i]);
                mappedCounters[i == 0 ? 0 : 1] = null;
            }
            GL15.glDeleteBuffers(buffers[i]); buffers[i]=0;
        }
        instanceCount=0; instanceData=null; transparentInstanceCount=0; transparentData=null; completedCellCount=0;
    }
}
