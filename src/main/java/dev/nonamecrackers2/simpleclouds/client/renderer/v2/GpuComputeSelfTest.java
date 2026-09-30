package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.*;
import java.util.*;
import org.lwjgl.opengl.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;

/** Explicit dev-only real-context check, never replaces the active cloud field. */
public final class GpuComputeSelfTest {
    private GpuComputeSelfTest() {}
    public static void run() {
        if (!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))) return;
        if (!GpuCloudGeneration.isOpenGLBackend() || !GL.getCapabilities().OpenGL45)
            throw new IllegalStateException("GPUCHECK requires OpenGL 4.5; no raw GL calls on other backends");
        var outer=state();
        int sentinel=GL45.glCreateBuffers();
        int sentinelTexture=GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
        int alignment=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        int[] pattern={12345,67890,13579,24680};
        try {
            GL45.glNamedBufferData(sentinel,alignment+64L,GL15.GL_DYNAMIC_DRAW);
            GL45.glNamedBufferSubData(sentinel,alignment,pattern);
            GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,0,sentinel,alignment,16);
            GL45.glTextureStorage3D(sentinelTexture,1,GL30.GL_RG32F,2,2,1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,sentinelTexture);
            GL13.glActiveTexture(outer.get(2 + 3 * GpuCloudGeneration.STORAGE_BINDING_COUNT).intValue());
            runCases();
            int[] actual=new int[4];
            GL45.glGetNamedBufferSubData(sentinel,alignment,actual);
            if(!Arrays.equals(pattern,actual)) throw new AssertionError("Compute overwrote another owner's SSBO");
        } finally {
            restore(outer);
            GL15.glDeleteBuffers(sentinel);
            GL11.glDeleteTextures(sentinelTexture);
        }
        if(!outer.equals(state())) throw new AssertionError("Self-test did not restore caller state");
        int error=GL11.glGetError();
        if(error!=GL11.GL_NO_ERROR) throw new AssertionError("Self-test left GL error 0x"+Integer.toHexString(error));
        GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] PASS: real dispatch/readback and counts-only completion, opaque and transparent GPU-to-GPU draw copies, exact voxel/face IDs, solid/empty/noise parity, bounded dispatch, nonzero SSBO range/data preservation and cleanup");
    }
    private static void runCases() {
        var before=state();
        try (var gpu=new GpuCloudGeneration()) {
            if(!gpu.init()) throw new IllegalStateException("GPU test unavailable on " + GpuCloudGeneration.backendClassName());
            for(float offset:new float[]{10,-10,10,.4f}) {
                var layer=new CpuCloudGenerator.NoiseLayer(32,offset,16,16,16,8,0,1);
                int count=gpu.generate(-8,0,-8,8,32,8,0,0,0,10000,20000,List.of(layer),0);
                var cpu=new CpuCloudGenerator(List.of(new CpuCloudGenerator.CloudLayerGroup(List.of(layer),0,false,0,0,1)));
                var result=cpu.generate(-8,0,-8,8,32,8,8,0,0,0,0,1,0,
                    ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder()),
                    ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder()),
                    (b,n,w)->{throw new AssertionError("CPU fixture overflow");},
                    new float[1],new float[1],0,new float[2],new float[1]);
                var expected=faces(result[0]);
                var actual=faces(gpu.instanceData());
                if(offset==10 && expected.size()!=512) throw new AssertionError("Invalid solid fixture: "+expected.size());
                if(offset==-10 && (!actual.isEmpty() || count!=0)) throw new AssertionError("Empty field retained stale data");
                if(!actual.equals(expected)) {
                    var missing=new HashSet<>(expected); missing.removeAll(actual);
                    var extra=new HashSet<>(actual); extra.removeAll(expected);
                    throw new AssertionError("CPU/GPU mismatch offset="+offset+" count="+count+" missing="+missing.size()+" extra="+extra.size());
                }
                if (offset == 10) {
                    long bytes = (long)count * GpuCloudGeneration.BYTES_PER_INSTANCE;
                    try (GpuBuffer draw = RenderSystem.getDevice().createBuffer(
                            () -> "simpleclouds.gpuCopySelfTest",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,bytes)) {
                        gpu.copyOpaqueTo(draw);
                        ByteBuffer copied = ByteBuffer.allocateDirect((int)bytes).order(ByteOrder.nativeOrder());
                        GL45.glGetNamedBufferSubData(((GlBuffer)draw).handle(),0,copied);
                        if (!copied.equals(gpu.instanceData().duplicate()))
                            throw new AssertionError("GPU-to-GPU opaque copy differs from source geometry");
                    }
                    int[] ids = assertFaceIds(gpu,-8,0,-8,8,32,8,1,0);
                    try (GpuBuffer idCopy = RenderSystem.getDevice().createBuffer(
                            () -> "simpleclouds.gpuFaceIdsSelfTest",GpuBuffer.USAGE_COPY_DST,(long)ids.length * Integer.BYTES)) {
                        gpu.copyFaceIdsTo(idCopy);
                        int[] copiedIds = new int[ids.length];
                        GL45.glGetNamedBufferSubData(((GlBuffer)idCopy).handle(),0,copiedIds);
                        if (!Arrays.equals(ids,copiedIds))
                            throw new AssertionError("GPU-to-GPU face ID copy differs from generated IDs");
                    }
                }
                if(!before.equals(state())) throw new AssertionError("Compute changed render state");
                GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] offset={} faces={} exact CPU match, dispatch+readback={} us",
                    offset,count,gpu.lastGenerateNanos()/1000);
            }
            var deltaGroup = new CpuCloudGenerator.CloudLayerGroup(List.of(
                new CpuCloudGenerator.NoiseLayer(32,.4f,16,16,16,8,0,1)),0,false,0,0,1);
            gpu.generate(-8,0,-8,8,32,8,0,0,0,10000,20000,deltaGroup,
                new GpuCloudGeneration.Sampling(1,0,0,0,0,0));
            int oldCount = gpu.instanceCount();
            if (oldCount == 0) throw new AssertionError("GPU face delta fixture has no original faces");
            byte[] previousFaces = new byte[oldCount * GpuCloudGeneration.BYTES_PER_INSTANCE];
            gpu.instanceData().duplicate().get(previousFaces);
            try (GpuBuffer oldIds = RenderSystem.getDevice().createBuffer(
                    () -> "simpleclouds.deltaOldIds",GpuBuffer.USAGE_COPY_DST,(long)oldCount * Integer.BYTES);
                 GpuBuffer oldGeometry = RenderSystem.getDevice().createBuffer(
                    () -> "simpleclouds.deltaOldFaces",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    (long)oldCount * GpuCloudGeneration.BYTES_PER_INSTANCE)) {
                gpu.copyFaceIdsTo(oldIds);
                gpu.copyOpaqueTo(oldGeometry);
                gpu.generate(-8,0,-8,8,32,8,0,0,0,10000,20000,deltaGroup,
                    new GpuCloudGeneration.Sampling(1,0,.375f,-.25f,1.125f,.25f));
                int newCount = gpu.instanceCount();
                if (newCount == 0) throw new AssertionError("GPU face delta fixture has no new faces");
                try (GpuBuffer newIds = RenderSystem.getDevice().createBuffer(
                        () -> "simpleclouds.deltaNewIds",GpuBuffer.USAGE_COPY_DST,(long)newCount * Integer.BYTES);
                     GpuBuffer newGeometry = RenderSystem.getDevice().createBuffer(
                        () -> "simpleclouds.deltaNewFaces",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                        (long)newCount * GpuCloudGeneration.BYTES_PER_INSTANCE)) {
                    gpu.copyFaceIdsTo(newIds);
                    gpu.copyOpaqueTo(newGeometry);
                    var expectedDelta = CloudFaceDelta.split(previousFaces,gpu.instanceData(),
                        GpuCloudGeneration.BYTES_PER_INSTANCE);
                    try (var delta = new GpuFaceDelta(16 * 32 * 16 * 6)) {
                        var same = delta.compare(oldIds,oldGeometry,oldCount,oldIds,oldGeometry,oldCount);
                        if (same.stable()!=oldCount || same.added()!=0 || same.removed()!=0)
                            throw new AssertionError("Identical GPU face sets did not remain stable");
                        var actualDelta = delta.compare(oldIds,oldGeometry,oldCount,
                            newIds,newGeometry,newCount);
                        if (actualDelta.stable()!=expectedDelta.stableCount(24)
                                || actualDelta.added()!=expectedDelta.addedCount(24)
                                || actualDelta.removed()!=expectedDelta.removedCount(24))
                            throw new AssertionError("GPU face delta counts differ from CPU face-local transition: "
                                + actualDelta);
                        byte[][] split = delta.readSplitForTest();
                        assertFaceBag(expectedDelta.stable(),split[0],"stable");
                        assertFaceBag(expectedDelta.added(),split[1],"added");
                        assertFaceBag(expectedDelta.removed(),split[2],"removed");
                        try (GpuBuffer stableDraw = RenderSystem.getDevice().createBuffer(
                                    () -> "simpleclouds.deltaStableDraw",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                                    split[0].length);
                             GpuBuffer addedDraw = RenderSystem.getDevice().createBuffer(
                                    () -> "simpleclouds.deltaAddedDraw",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                                    split[1].length);
                             GpuBuffer removedDraw = RenderSystem.getDevice().createBuffer(
                                    () -> "simpleclouds.deltaRemovedDraw",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                                    split[2].length)) {
                            delta.copySplitTo(stableDraw,addedDraw,removedDraw);
                            assertFaceBag(expectedDelta.stable(),readDrawBytes(stableDraw,split[0].length),"stable draw");
                            assertFaceBag(expectedDelta.added(),readDrawBytes(addedDraw,split[1].length),"added draw");
                            assertFaceBag(expectedDelta.removed(),readDrawBytes(removedDraw,split[2].length),"removed draw");
                        }
                        var first = delta.compare(null,null,0,newIds,newGeometry,newCount);
                        if (first.stable()!=0 || first.added()!=newCount || first.removed()!=0)
                            throw new AssertionError("GPU face delta first generation is not all added");
                        GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] GPU face delta stable={} added={} removed={} exact CPU match",
                            actualDelta.stable(),actualDelta.added(),actualDelta.removed());
                    }
                }
            }
            if(!before.equals(state())) throw new AssertionError("GPU face delta changed caller state");
            assertTransparentDelta(gpu);
            if(!before.equals(state())) throw new AssertionError("GPU transparent delta changed caller state");
            boolean refused=false;
            for(int lod:new int[]{1,2,4,8}) for(int phase=0;phase<2;phase++) {
                var layers=List.of(
                    new CpuCloudGenerator.NoiseLayer(64,.35f,17,19,13,12,0,1),
                    new CpuCloudGenerator.NoiseLayer(32,.1f,11,13,19,8,8,.4f));
                var group=new CpuCloudGenerator.CloudLayerGroup(layers,.4f,true,.7f,8,24);
                float sx=phase==0?.375f:-2.125f, sy=phase==0?-.25f:.75f, sz=phase==0?1.125f:-1.625f;
                float wiggle=(sx+sy+sz)/5, base=phase==0?128:-32;
                int x0=-8*lod,x1=8*lod;
                gpu.generate(x0,0,x0,x1,64,x1,0,0,0,10000,20000,group,
                    new GpuCloudGeneration.Sampling(lod,base,sx,sy,sz,wiggle));
                var cpu=new CpuCloudGenerator(List.of(group));
                var result=cpu.generate(x0,0,x0,x1,64,x1,8,sx,sy,sz,wiggle,lod,base,
                    ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                    ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                    (b,n,w)->{throw new AssertionError("LOD fixture overflow");},
                    new float[1],new float[1],0,new float[2],new float[1]);
                compareShaded(result[0],gpu.instanceData());
                compareTransparent(result[1],gpu.transparentData());
                assertFaceIds(gpu,x0,0,x0,x1,64,x1,lod,base);
                assertTransparentCubeIds(gpu,x0,0,x0,x1,64,x1,lod,base);
                if (lod == 1 && phase == 0)
                    assertTransparentExpansion(gpu,base);
                if(!before.equals(state())) throw new AssertionError("LOD compute changed render state");
                GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] LOD={} phase={} opaque={} transparent={} scroll/height/storm/alpha parity",
                    lod,phase,gpu.instanceCount(),gpu.transparentInstanceCount());
            }
            // The world renderer must pass its configured cutoff to BOTH backends.
            // Zero is a strong boundary fixture: opaque faces stay unchanged while
            // transparent edge cubes disappear entirely.
            var cutoffGroup=new CpuCloudGenerator.CloudLayerGroup(List.of(
                new CpuCloudGenerator.NoiseLayer(64,.35f,17,19,13,12,0,1),
                new CpuCloudGenerator.NoiseLayer(32,.1f,11,13,19,8,8,.4f)),
                .4f,true,.7f,8,24);
            gpu.generate(-8,0,-8,8,64,8,0,0,0,10000,20000,cutoffGroup,
                new GpuCloudGeneration.Sampling(1,128,.375f,-.25f,1.125f,.25f,0));
            var cutoffCpu=new CpuCloudGenerator(List.of(cutoffGroup));
            var cutoffResult=cutoffCpu.generate(-8,0,-8,8,64,8,8,.375f,-.25f,1.125f,.25f,1,128,
                ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                (b,n,w)->{throw new AssertionError("Cutoff fixture overflow");},
                new float[1],new float[1],0,new float[2],new float[1],0);
            compareShaded(cutoffResult[0],gpu.instanceData());
            if(cutoffResult[1].remaining()!=0 || gpu.transparentInstanceCount()!=0)
                throw new AssertionError("Zero transparency cutoff emitted edge cubes");
            if(!before.equals(state())) throw new AssertionError("Cutoff compute changed render state");
            for(int lod:new int[]{1,2,4}) {
                var groups=List.of(
                    new CpuCloudGenerator.CloudLayerGroup(List.of(new CpuCloudGenerator.NoiseLayer(64,10,16,16,16,8,0,1)),0,false,0,0,1),
                    new CpuCloudGenerator.CloudLayerGroup(List.of(new CpuCloudGenerator.NoiseLayer(64,10,16,16,16,8,0,1)),0,true,.6f,0,64));
                var regions=List.of(
                    new CpuCloudGenerator.RegionMask(-4*lod,0,4*lod,1,0,0,1,0),
                    new CpuCloudGenerator.RegionMask(4*lod,0,4*lod,1,0,0,1,1));
                int x0=-8*lod,x1=8*lod,z0=-8*lod,z1=8*lod;
                gpu.generateRegions(x0,0,z0,x1,32,z1,0,0,0,groups,regions,
                    new GpuCloudGeneration.Sampling(lod,128,0,0,0,0));
                var cpu=new CpuCloudGenerator(groups);
                cpu.setRegions(regions,true);
                var result=cpu.generate(x0,0,z0,x1,32,z1,8,0,0,0,0,lod,128,
                    ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                    ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                    (b,n,w)->{throw new AssertionError("Region fixture overflow");},
                    new float[1],new float[1],0,new float[2],new float[1]);
                GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] region LOD={} CPU faces={} GPU faces={}",
                    lod,result[0].remaining()/24,gpu.instanceCount());
                compareShaded(result[0],gpu.instanceData());
                assertFaceIds(gpu,x0,0,z0,x1,32,z1,lod,128);
                if(!before.equals(state())) throw new AssertionError("Region compute changed SSBO/program state");
                GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] region LOD={} faces={} exact two-group geometry and storm parity",lod,gpu.instanceCount());
                if(lod==1) {
                    gpu.submitRegions(x0,0,z0,x1,32,z1,0,0,0,groups,regions,
                        new GpuCloudGeneration.Sampling(lod,128,0,0,0,0));
                    boolean refusedOverlap=false;
                    try { gpu.submitRegions(x0,0,z0,x1,32,z1,0,0,0,groups,regions,
                        new GpuCloudGeneration.Sampling(lod,128,0,0,0,0)); }
                    catch(IllegalStateException expected) { refusedOverlap=true; }
                    if(!refusedOverlap) throw new AssertionError("Overlapping GPU dispatch was accepted");
                    long deadline=System.nanoTime()+2_000_000_000L;
                    boolean ready=false;
                    while(System.nanoTime()<deadline && !(ready=gpu.pollRegions())) Thread.onSpinWait();
                    if(!ready) throw new AssertionError("Region GPU fence did not signal within two seconds");
                    compareShaded(result[0],gpu.instanceData());
                    assertFaceIds(gpu,x0,0,z0,x1,32,z1,lod,128);
                    if(!before.equals(state())) throw new AssertionError("Async region readback changed caller state");
                    GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] async region LOD=1 faces={} exact CPU match, total={} us",
                        gpu.instanceCount(),gpu.lastGenerateNanos()/1000);
                    gpu.submitRegions(x0,0,z0,x1,32,z1,0,0,0,groups,regions,
                        new GpuCloudGeneration.Sampling(lod,128,0,0,0,0));
                    deadline=System.nanoTime()+2_000_000_000L;
                    ready=false;
                    while(System.nanoTime()<deadline && !(ready=gpu.pollRegionsCountsOnly())) Thread.onSpinWait();
                    if(!ready) throw new AssertionError("Counts-only GPU fence did not signal within two seconds");
                    if(gpu.instanceData()!=null || gpu.transparentData()!=null
                            || gpu.instanceCount()!=result[0].remaining()/CloudVertexFormat.BYTES_PER_INSTANCE
                            || gpu.transparentInstanceCount()!=result[1].remaining()/CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)
                        throw new AssertionError("Counts-only dispatch downloaded geometry or returned wrong counts");
                    try (GpuBuffer draw = RenderSystem.getDevice().createBuffer(
                            () -> "simpleclouds.countsOnlyOpaque",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                            (long)gpu.instanceCount()*CloudVertexFormat.BYTES_PER_INSTANCE)) {
                        gpu.copyOpaqueTo(draw);
                        byte[] copied=readDrawBytes(draw,gpu.instanceCount()*CloudVertexFormat.BYTES_PER_INSTANCE);
                        compareShaded(result[0],ByteBuffer.wrap(copied).order(ByteOrder.nativeOrder()));
                        assertFaceIds(gpu,x0,0,z0,x1,32,z1,lod,128,
                            ByteBuffer.wrap(copied).order(ByteOrder.nativeOrder()));
                    }
                    if(!before.equals(state())) throw new AssertionError("Counts-only completion changed caller state");
                    GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] counts-only region LOD=1 faces={} without geometry readback",gpu.instanceCount());
                }
            }
            var emptyGroup=new CpuCloudGenerator.CloudLayerGroup(
                List.of(new CpuCloudGenerator.NoiseLayer(64,10,16,16,16,8,0,1)),0,false,0,0,1);
            if(gpu.generateRegions(-8,0,-8,8,32,8,0,0,0,List.of(emptyGroup),List.of(),
                    new GpuCloudGeneration.Sampling(1,128,0,0,0,0))!=0)
                throw new AssertionError("Empty masked world rendered clouds");
            if(!before.equals(state())) throw new AssertionError("Region compute changed texture or pixel-store state");
            var partialRegions=List.of(new CpuCloudGenerator.RegionMask(0,0,300,1,0,0,1,0));
            gpu.generateRegions(-8,0,-8,9,12,11,0,0,0,List.of(emptyGroup),partialRegions,
                new GpuCloudGeneration.Sampling(1,128,0,0,0,0));
            var partialCpu=new CpuCloudGenerator(List.of(emptyGroup));
            partialCpu.setRegions(partialRegions,true);
            var partialExpected=partialCpu.generate(-8,0,-8,9,12,11,8,0,0,0,0,1,128,
                ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(4*1024*1024).order(ByteOrder.nativeOrder()),
                (b,n,w)->{throw new AssertionError("Partial workgroup fixture overflow");},
                new float[1],new float[1],0,new float[2],new float[1]);
            compareShaded(partialExpected[0],gpu.instanceData());
            if(!before.equals(state())) throw new AssertionError("Partial workgroup changed caller state");
            GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] partial workgroups 17x12x19 faces={} exact CPU parity",gpu.instanceCount());
            var weatherGroups=List.of(
                new CpuCloudGenerator.CloudLayerGroup(List.of(
                    new CpuCloudGenerator.NoiseLayer(64,.4f,17,19,13,12,0,1),
                    new CpuCloudGenerator.NoiseLayer(32,.1f,11,13,19,8,8,.4f)),.4f,true,.7f,8,24),
                new CpuCloudGenerator.CloudLayerGroup(List.of(
                    new CpuCloudGenerator.NoiseLayer(64,.6f,15,17,21,10,0,1)),.3f,false,.4f,4,20));
            var weatherRegions=List.of(
                new CpuCloudGenerator.RegionMask(-3,0,280,32,8,-8,32,0),
                new CpuCloudGenerator.RegionMask(3,0,280,28,-10,10,28,1));
            for(int lod:new int[]{1,2,4}) {
                var sample=new GpuCloudGeneration.Sampling(lod,128,.375f,-.25f,1.125f,.25f);
                gpu.generateRegions(-8*lod,0,-8*lod,8*lod,64,8*lod,0,0,0,
                    weatherGroups,weatherRegions,sample);
                var cpu=new CpuCloudGenerator(weatherGroups);
                cpu.setRegions(weatherRegions,true);
                float[] cpuStormCoverage=new float[1];
                var result=cpu.generate(-8*lod,0,-8*lod,8*lod,64,8*lod,8,
                    sample.scrollX(),sample.scrollY(),sample.scrollZ(),sample.wiggle(),lod,sample.worldBaseY(),
                    ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                    ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                    (b,n,w)->{throw new AssertionError("Weather fixture overflow");},
                    new float[1],new float[1],0,new float[2],cpuStormCoverage);
                compareShaded(result[0],gpu.instanceData());
                compareTransparent(result[1],gpu.transparentData());
                assertTransparentCubeIds(gpu,-8*lod,0,-8*lod,8*lod,64,8*lod,lod,sample.worldBaseY());
                var gpuStorm=GpuStormColumns.fromFaces(gpu.instanceData(),-8*lod,-8*lod,8*lod,8*lod,lod,
                    sample.worldBaseY(),0,0,0,weatherGroups,weatherRegions);
                try (var bits = new GpuStormColumnBits(16 * 16)) {
                    var reduced = bits.fromFaceIds(gpu,-8*lod,0,-8*lod,8*lod,64,8*lod,lod,
                        0,0,0,weatherGroups,weatherRegions);
                    if (!Arrays.equals(gpuStorm.columns(),reduced.columns())
                            || Math.abs(gpuStorm.coverage()-reduced.coverage())>0.000001f)
                        throw new AssertionError("GPU storm bitset differs from full-face reconstruction at LOD="+lod);
                    long marked = java.util.stream.IntStream.range(0,reduced.columns().length)
                        .filter(i -> reduced.columns()[i] != 0).count();
                    GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] storm bitset LOD={} marked={} coverage={}",
                        lod,marked,reduced.coverage());
                }
                if(!Arrays.equals(cpu.copyStormColumns(),gpuStorm.columns())
                        || Math.abs(cpuStormCoverage[0]-gpuStorm.coverage())>0.000001f)
                    throw new AssertionError("GPU storm columns differ from CPU at LOD="+lod);
                for(int cameraY:new int[]{20,40}) {
                    float[] raisedCoverage=new float[1];
                    cpu.generate(-8*lod,0,-8*lod,8*lod,64,8*lod,8,
                        sample.scrollX(),sample.scrollY(),sample.scrollZ(),sample.wiggle(),lod,sample.worldBaseY(),
                        ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                        ByteBuffer.allocateDirect(8*1024*1024).order(ByteOrder.nativeOrder()),
                        (b,n,w)->{throw new AssertionError("Raised camera fixture overflow");},
                        new float[1],new float[1],cameraY,new float[2],raisedCoverage);
                    var raisedGpu=GpuStormColumns.fromFaces(gpu.instanceData(),-8*lod,-8*lod,8*lod,8*lod,lod,
                        sample.worldBaseY(),cameraY,0,0,weatherGroups,weatherRegions);
                    try (var bits = new GpuStormColumnBits(16 * 16)) {
                        var raisedBits = bits.fromFaceIds(gpu,-8*lod,0,-8*lod,8*lod,64,8*lod,lod,
                            cameraY,0,0,weatherGroups,weatherRegions);
                        if (!Arrays.equals(raisedGpu.columns(),raisedBits.columns())
                                || Math.abs(raisedGpu.coverage()-raisedBits.coverage())>0.000001f)
                            throw new AssertionError("GPU storm bitset differs above cameraY="+cameraY+" LOD="+lod);
                    }
                    if(!Arrays.equals(cpu.copyStormColumns(),raisedGpu.columns())
                            || Math.abs(raisedCoverage[0]-raisedGpu.coverage())>0.000001f)
                        throw new AssertionError("GPU storm columns differ above cameraY="+cameraY+" LOD="+lod);
                }
                if(!before.equals(state())) throw new AssertionError("Weather GPU dispatch changed caller state");
                GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] transformed overlap LOD={} opaque={} transparent={} CPU parity",
                    lod,gpu.instanceCount(),gpu.transparentInstanceCount());
            }
            var benchmarkSample=new GpuCloudGeneration.Sampling(1,128,.375f,-.25f,1.125f,.25f);
            var benchmarkCpu=new CpuCloudGenerator(weatherGroups);
            benchmarkCpu.setRegions(weatherRegions,true);
            long cpuStarted=System.nanoTime();
            var benchmarkExpected=benchmarkCpu.generate(-16,0,-16,16,64,16,8,
                benchmarkSample.scrollX(),benchmarkSample.scrollY(),benchmarkSample.scrollZ(),benchmarkSample.wiggle(),1,
                benchmarkSample.worldBaseY(),
                ByteBuffer.allocateDirect(16*1024*1024).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(16*1024*1024).order(ByteOrder.nativeOrder()),
                (b,n,w)->{throw new AssertionError("Full-chunk fixture overflow");},
                new float[1],new float[1],0,new float[2],new float[1]);
            long cpuNanos=System.nanoTime()-cpuStarted;
            long submitStarted=System.nanoTime();
            gpu.submitRegions(-16,0,-16,16,64,16,0,0,0,weatherGroups,weatherRegions,benchmarkSample);
            long submitNanos=System.nanoTime()-submitStarted;
            int polls=0;
            long deadline=System.nanoTime()+3_000_000_000L;
            boolean ready=false;
            while(System.nanoTime()<deadline && !(ready=gpu.pollRegions())) { polls++; Thread.onSpinWait(); }
            if(!ready) throw new AssertionError("Full-chunk GPU dispatch did not complete within three seconds");
            compareShaded(benchmarkExpected[0],gpu.instanceData());
            compareTransparent(benchmarkExpected[1],gpu.transparentData());
            if(!before.equals(state())) throw new AssertionError("Full-chunk GPU dispatch changed caller state");
            GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] full chunk 32x64x32 CPU={} us GPU submit={} us GPU completion+readback={} us polls={} opaque={} transparent={} (single fixture, not steady-state FPS)",
                cpuNanos/1000,submitNanos/1000,gpu.lastGenerateNanos()/1000,polls,gpu.instanceCount(),gpu.transparentInstanceCount());
            gpu.submitRegions(-16,0,-16,16,64,16,0,0,0,weatherGroups,weatherRegions,benchmarkSample);
            deadline=System.nanoTime()+3_000_000_000L;
            ready=false;
            while(System.nanoTime()<deadline && !(ready=gpu.pollRegionsCountsOnly())) Thread.onSpinWait();
            if(!ready) throw new AssertionError("Full-chunk counts-only GPU dispatch timed out");
            if(gpu.instanceData()!=null || gpu.transparentData()!=null
                    || gpu.instanceCount()!=benchmarkExpected[0].remaining()/CloudVertexFormat.BYTES_PER_INSTANCE
                    || gpu.transparentInstanceCount()!=benchmarkExpected[1].remaining()/CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA)
                throw new AssertionError("Full-chunk counts-only geometry/count mismatch");
            try (var expansion=new GpuTransparentExpansion(32*64*32)) {
                int expanded=expansion.expandFrom(gpu,benchmarkSample.worldBaseY());
                if(expanded!=gpu.transparentInstanceCount())
                    throw new AssertionError("Counts-only transparent expansion count differs");
                compareTransparent(benchmarkExpected[1],expansion.readFacesForTest());
            }
            if(!before.equals(state())) throw new AssertionError("Counts-only full chunk changed caller state");
            GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] counts-only full chunk opaque={} transparent={} exact CPU parity without geometry readback",
                gpu.instanceCount(),gpu.transparentInstanceCount());
            var tallGroup=new CpuCloudGenerator.CloudLayerGroup(List.of(
                new CpuCloudGenerator.NoiseLayer(32,10,16,16,16,8,0,1)),0,false,0,0,1);
            var tallRegions=List.of(new CpuCloudGenerator.RegionMask(0,0,300,1,0,0,1,0));
            gpu.generateRegions(-16,0,-16,16,256,16,0,0,0,List.of(tallGroup),tallRegions,
                new GpuCloudGeneration.Sampling(1,128,0,0,0,0));
            var tallCpu=new CpuCloudGenerator(List.of(tallGroup));
            tallCpu.setRegions(tallRegions,true);
            var tallExpected=tallCpu.generate(-16,0,-16,16,256,16,8,0,0,0,0,1,128,
                ByteBuffer.allocateDirect(16*1024*1024).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(16*1024*1024).order(ByteOrder.nativeOrder()),
                (b,n,w)->{throw new AssertionError("Tall fixture overflow");},
                new float[1],new float[1],0,new float[2],new float[1]);
            compareShaded(tallExpected[0],gpu.instanceData());
            assertFaceIds(gpu,-16,0,-16,16,256,16,1,128);
            if(!before.equals(state())) throw new AssertionError("Tall GPU dispatch changed caller state");
            GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] tall 32x256x32 cells opaque={} exact CPU parity",gpu.instanceCount());
            try { gpu.generate(0,0,0,1024,1024,1024,0,0,0,0,1,List.of(new CpuCloudGenerator.NoiseLayer(32,1,16,16,16,8,0,1)),0); }
            catch(IllegalArgumentException expected) { refused=true; }
            if(!refused) throw new AssertionError("Unsafe volume accepted");
        }
        if(!before.equals(state())) throw new AssertionError("Compute close changed render state");
        int error=GL11.glGetError();
        if(error!=GL11.GL_NO_ERROR) throw new AssertionError("Compute left GL error 0x"+Integer.toHexString(error));
    }
    private static byte[] readDrawBytes(GpuBuffer buffer,int length) {
        ByteBuffer bytes=ByteBuffer.allocateDirect(length);
        GL45.glGetNamedBufferSubData(((GlBuffer)buffer).handle(),0,bytes);
        byte[] result=new byte[length];
        bytes.get(result);
        return result;
    }

    private static void assertFaceBag(byte[] expected,byte[] actual,String name) {
        if(expected.length%GpuCloudGeneration.BYTES_PER_INSTANCE!=0
                || actual.length%GpuCloudGeneration.BYTES_PER_INSTANCE!=0)
            throw new AssertionError("Invalid " + name + " face stride");
        Map<String,Integer> expectedBag=new HashMap<>(), actualBag=new HashMap<>();
        for(int at=0;at<expected.length;at+=GpuCloudGeneration.BYTES_PER_INSTANCE)
            expectedBag.merge(Base64.getEncoder().encodeToString(Arrays.copyOfRange(expected,
                at,at+GpuCloudGeneration.BYTES_PER_INSTANCE)),1,Integer::sum);
        for(int at=0;at<actual.length;at+=GpuCloudGeneration.BYTES_PER_INSTANCE)
            actualBag.merge(Base64.getEncoder().encodeToString(Arrays.copyOfRange(actual,
                at,at+GpuCloudGeneration.BYTES_PER_INSTANCE)),1,Integer::sum);
        if(!expectedBag.equals(actualBag))
            throw new AssertionError(name + " GPU face records differ from CPU face-local split");
    }

    private static int[] assertFaceIds(GpuCloudGeneration gpu,int x0,int y0,int z0,
            int x1,int y1,int z1,int lod,float worldBaseY) {
        return assertFaceIds(gpu,x0,y0,z0,x1,y1,z1,lod,worldBaseY,gpu.instanceData());
    }

    private static int[] assertFaceIds(GpuCloudGeneration gpu,int x0,int y0,int z0,
            int x1,int y1,int z1,int lod,float worldBaseY,ByteBuffer geometryData) {
        int dx=(x1-x0)/lod, dy=(y1-y0)/lod, dz=(z1-z0)/lod;
        int[] ids=gpu.faceIds();
        if(ids.length!=gpu.instanceCount()) throw new AssertionError("Face ID count differs from geometry");
        Set<Integer> unique=new HashSet<>();
        ByteBuffer geometry=geometryData==null ? null
            : geometryData.duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<ids.length;i++) {
            int id=ids[i];
            if(id<0 || id>=(long)dx*dy*dz*6 || !unique.add(id))
                throw new AssertionError("Invalid or duplicate GPU face ID " + id);
            int side=id%6, cell=id/6;
            int x=cell%dx, y=cell/dx%dy, z=cell/(dx*dy);
            int at=i*GpuCloudGeneration.BYTES_PER_INSTANCE;
            float radius=lod*.5f;
            if(geometry==null || geometry.getFloat(at)!=side
                    || geometry.getFloat(at+4)!=(x0+x*lod+radius)*8
                    || geometry.getFloat(at+8)!=(y0+y*lod+radius)*8+worldBaseY
                    || geometry.getFloat(at+12)!=(z0+z*lod+radius)*8
                    || geometry.getFloat(at+16)!=radius*8)
                throw new AssertionError("GPU face ID does not identify its geometry at " + i);
        }
        return ids;
    }

    private static void assertTransparentCubeIds(GpuCloudGeneration gpu,int x0,int y0,int z0,
            int x1,int y1,int z1,int lod,float worldBaseY) {
        int dx=(x1-x0)/lod, dy=(y1-y0)/lod, dz=(z1-z0)/lod;
        int[] ids=gpu.transparentCubeIds();
        if(ids.length*6!=gpu.transparentInstanceCount())
            throw new AssertionError("Transparent cube ID count differs from six-face geometry");
        Set<Integer> unique=new HashSet<>();
        ByteBuffer geometry=gpu.transparentData()==null ? null
            : gpu.transparentData().duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<ids.length;i++) {
            int cell=ids[i];
            if(cell<0 || cell>=(long)dx*dy*dz || !unique.add(cell))
                throw new AssertionError("Invalid or duplicate GPU transparent cube ID " + cell);
            int x=cell%dx, y=cell/dx%dy, z=cell/(dx*dy);
            float radius=lod*.5f;
            for(int side=0;side<6;side++) {
                int at=(i*6+side)*28;
                if(geometry==null || geometry.getFloat(at)!=side
                        || geometry.getFloat(at+4)!=(x0+x*lod+radius)*8
                        || geometry.getFloat(at+8)!=(y0+y*lod+radius)*8+worldBaseY
                        || geometry.getFloat(at+12)!=(z0+z*lod+radius)*8
                        || geometry.getFloat(at+16)!=radius*8)
                    throw new AssertionError("GPU transparent cube ID does not identify face " + side + " at " + i);
            }
        }
    }

    private static void assertTransparentExpansion(GpuCloudGeneration gpu,float worldBaseY) {
        try (var expansion = new GpuTransparentExpansion(16 * 64 * 16)) {
            int count = expansion.expandFrom(gpu,worldBaseY);
            if (count != gpu.transparentInstanceCount())
                throw new AssertionError("GPU transparent expansion face count differs from CPU expansion");
            ByteBuffer expanded = expansion.readFacesForTest();
            compareTransparent(gpu.transparentData(),expanded);
            int[] cubes = gpu.transparentCubeIds();
            int[] faceIds = expansion.readFaceIdsForTest();
            for (int i=0;i<faceIds.length;i++)
                if (faceIds[i] != cubes[i/6]*6+i%6)
                    throw new AssertionError("GPU transparent face ID differs at " + i);
            long faceBytes=(long)count*CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
            try (GpuBuffer faces = RenderSystem.getDevice().createBuffer(
                    () -> "simpleclouds.transparentExpansionFaces",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,faceBytes);
                 GpuBuffer ids = RenderSystem.getDevice().createBuffer(
                    () -> "simpleclouds.transparentExpansionIds",GpuBuffer.USAGE_COPY_DST,(long)count*Integer.BYTES)) {
                expansion.copyFacesTo(faces);
                expansion.copyFaceIdsTo(ids);
                ByteBuffer copied = ByteBuffer.allocateDirect((int)faceBytes).order(ByteOrder.nativeOrder());
                GL45.glGetNamedBufferSubData(((GlBuffer)faces).handle(),0,copied);
                if (!copied.equals(expanded))
                    throw new AssertionError("GPU transparent draw copy differs from expanded faces");
                int[] copiedIds = new int[count];
                GL45.glGetNamedBufferSubData(((GlBuffer)ids).handle(),0,copiedIds);
                if (!Arrays.equals(copiedIds,faceIds))
                    throw new AssertionError("GPU transparent ID copy differs from expanded IDs");
            }
        }
        GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] transparent GPU cube-to-face expansion, voxel IDs and draw copies match CPU");
    }

    private static void assertTransparentDelta(GpuCloudGeneration gpu) {
        var group = new CpuCloudGenerator.CloudLayerGroup(List.of(
            new CpuCloudGenerator.NoiseLayer(64,.35f,17,19,13,12,0,1),
            new CpuCloudGenerator.NoiseLayer(32,.1f,11,13,19,8,8,.4f)),.4f,true,.7f,8,24);
        int stride=CloudVertexFormat.BYTES_PER_INSTANCE_ALPHA;
        gpu.generate(-8,0,-8,8,64,8,0,0,0,10000,20000,group,
            new GpuCloudGeneration.Sampling(1,128,.375f,-.25f,1.125f,.25f));
        int oldCount=gpu.transparentInstanceCount();
        if (oldCount==0) throw new AssertionError("Transparent GPU delta fixture has no original faces");
        byte[] oldCpu=new byte[oldCount*stride];
        gpu.transparentData().duplicate().get(oldCpu);
        try (var expansion = new GpuTransparentExpansion(16*64*16);
             GpuBuffer oldFaces = RenderSystem.getDevice().createBuffer(
                () -> "simpleclouds.transparentDeltaOldFaces",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,(long)oldCount*stride);
             GpuBuffer oldIds = RenderSystem.getDevice().createBuffer(
                () -> "simpleclouds.transparentDeltaOldIds",GpuBuffer.USAGE_COPY_DST,(long)oldCount*Integer.BYTES)) {
            expansion.expandFrom(gpu,128);
            expansion.copyFacesTo(oldFaces);
            expansion.copyFaceIdsTo(oldIds);
            gpu.generate(-8,0,-8,8,64,8,0,0,0,10000,20000,group,
                new GpuCloudGeneration.Sampling(1,128,.5f,-.25f,1.25f,.3f));
            int newCount=gpu.transparentInstanceCount();
            if (newCount==0) throw new AssertionError("Transparent GPU delta fixture has no new faces");
            try (GpuBuffer newFaces = RenderSystem.getDevice().createBuffer(
                    () -> "simpleclouds.transparentDeltaNewFaces",GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,(long)newCount*stride);
                 GpuBuffer newIds = RenderSystem.getDevice().createBuffer(
                    () -> "simpleclouds.transparentDeltaNewIds",GpuBuffer.USAGE_COPY_DST,(long)newCount*Integer.BYTES);
                 var delta = new GpuFaceDelta(16*64*16*6,stride)) {
                expansion.expandFrom(gpu,128);
                expansion.copyFacesTo(newFaces);
                expansion.copyFaceIdsTo(newIds);
                var expected=CloudFaceDelta.split(oldCpu,gpu.transparentData(),stride);
                var actual=delta.compare(oldIds,oldFaces,oldCount,newIds,newFaces,newCount);
                if (actual.stable()!=expected.stableCount(stride)
                        || actual.added()!=expected.addedCount(stride)
                        || actual.removed()!=expected.removedCount(stride))
                    throw new AssertionError("Transparent GPU delta counts differ from CPU: " + actual);
                byte[][] split=delta.readSplitForTest();
                assertTransparentClass(expected.stable(),split[0],"stable transparent");
                assertTransparentClass(expected.added(),split[1],"added transparent");
                assertTransparentClass(expected.removed(),split[2],"removed transparent");
                GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] transparent GPU face delta stable={} added={} removed={} exact CPU class match",
                    actual.stable(),actual.added(),actual.removed());
            }
        }
    }

    private static void assertTransparentClass(byte[] expected,byte[] actual,String name) {
        var left=transparentFaces(ByteBuffer.wrap(expected).order(ByteOrder.nativeOrder()));
        var right=transparentFaces(ByteBuffer.wrap(actual).order(ByteOrder.nativeOrder()));
        if (!left.keySet().equals(right.keySet()))
            throw new AssertionError(name + " geometry differs from CPU class");
        for (var entry:left.entrySet()) {
            float[] a=entry.getValue(), b=right.get(entry.getKey());
            if (Math.abs(a[0]-b[0])>0.00001f || Math.abs(a[1]-b[1])>0.0001f)
                throw new AssertionError(name + " brightness/alpha differs at " + entry.getKey());
        }
    }

    private static void restore(List<Long> state) {
        for(int i=0;i<GpuCloudGeneration.STORAGE_BINDING_COUNT;i++) {
            int buffer=state.get(2+3*i).intValue();
            long offset=state.get(3+3*i), size=state.get(4+3*i);
            if(buffer!=0 && size>0) GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,buffer,offset,size);
            else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,buffer);
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,state.get(1).intValue());
        GL20.glUseProgram(state.get(0).intValue());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int extras = 2 + 3 * GpuCloudGeneration.STORAGE_BINDING_COUNT;
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,state.get(extras + 1).intValue());
        GL13.glActiveTexture(state.get(extras).intValue());
        int[] keys={GL11.GL_UNPACK_ALIGNMENT,GL12.GL_UNPACK_ROW_LENGTH,GL12.GL_UNPACK_IMAGE_HEIGHT,
            GL12.GL_UNPACK_SKIP_PIXELS,GL12.GL_UNPACK_SKIP_ROWS,GL12.GL_UNPACK_SKIP_IMAGES,
            GL11.GL_UNPACK_SWAP_BYTES,GL11.GL_UNPACK_LSB_FIRST};
        for(int i=0;i<keys.length;i++) GL11.glPixelStorei(keys[i],state.get(extras + 2 + i).intValue());
    }
    private static Set<String> faces(ByteBuffer buffer) {
        Set<String> result=new HashSet<>();
        if(buffer==null) return result;
        if(buffer.remaining()%24!=0) throw new AssertionError("Invalid instance stride");
        for(int i=buffer.position();i<buffer.limit();i+=24) {
            StringBuilder face=new StringBuilder();
            for(int f=0;f<6;f++) {
                float value=buffer.getFloat(i+4*f);
                if(!Float.isFinite(value)) throw new AssertionError("Nonfinite GPU data");
                face.append(value).append(':');
            }
            if(!result.add(face.toString())) throw new AssertionError("Duplicate face");
        }
        return result;
    }
    private static void compareShaded(ByteBuffer expectedBuffer,ByteBuffer actualBuffer) {
        var expected=shadedFaces(expectedBuffer); var actual=shadedFaces(actualBuffer);
        if(expected.isEmpty()) throw new AssertionError("Empty shaded fixture");
        if(!expected.keySet().equals(actual.keySet())) {
            var missing=new HashSet<>(expected.keySet()); missing.removeAll(actual.keySet());
            var extra=new HashSet<>(actual.keySet()); extra.removeAll(expected.keySet());
            throw new AssertionError("Shaded geometry mismatch missing="+missing.size()+" extra="+extra.size());
        }
        for(var entry:expected.entrySet())
            if(Math.abs(entry.getValue()-actual.get(entry.getKey()))>0.00001f)
                throw new AssertionError("Storm brightness mismatch at "+entry.getKey());
    }
    private static void compareTransparent(ByteBuffer expectedBuffer,ByteBuffer actualBuffer) {
        var expected=transparentFaces(expectedBuffer); var actual=transparentFaces(actualBuffer);
        if(expected.isEmpty()) throw new AssertionError("Empty transparency fixture");
        if(!expected.keySet().equals(actual.keySet())) {
            var missing=new HashSet<>(expected.keySet()); missing.removeAll(actual.keySet());
            var extra=new HashSet<>(actual.keySet()); extra.removeAll(expected.keySet());
            for(String key:missing.stream().limit(6).toList())
                GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] missing transparent face={} CPU brightness/alpha={}",key,Arrays.toString(expected.get(key)));
            for(String key:extra.stream().limit(6).toList())
                GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] extra transparent face={} GPU brightness/alpha={}",key,Arrays.toString(actual.get(key)));
            // The two noise implementations may straddle -TransparencyFade.
            // An absent face is equivalent to alpha zero only within the same
            // explicit alpha-error bound, never for a visibly opaque edge.
            for(String key:missing) if(expected.get(key)[1]>0.0001f)
                throw new AssertionError("Visible transparent face missing: "+key);
            for(String key:extra) if(actual.get(key)[1]>0.0001f)
                throw new AssertionError("Visible transparent face added: "+key);
        }
        float[] largest={0,0};
        for(var entry:expected.entrySet()) {
            float[] observed=actual.get(entry.getKey());
            if(observed==null) largest[1]=Math.max(largest[1],entry.getValue()[1]);
            else for(int f=0;f<2;f++) largest[f]=Math.max(largest[f],Math.abs(entry.getValue()[f]-observed[f]));
        }
        for(var entry:actual.entrySet()) if(!expected.containsKey(entry.getKey()))
            largest[1]=Math.max(largest[1],entry.getValue()[1]);
        GpuCloudGeneration.LOGGER.info("[GPU-SELFTEST] transparency max absolute brightness error={}, alpha error={}",largest[0],largest[1]);
        // GLSL noise uses native float trig; Java uses Math sin/cos then casts.
        // Keep geometry exact. Alpha may differ by <=0.0001 absolute (under
        // 1/39 of an 8-bit alpha step); log the measured error on every fixture.
        if(largest[0]>0.00001f || largest[1]>0.0001f)
            throw new AssertionError("Transparent brightness/alpha error "+Arrays.toString(largest));
    }
    private static Map<String,float[]> transparentFaces(ByteBuffer b) {
        Map<String,float[]> result=new HashMap<>();
        if(b==null) return result;
        if(b.remaining()%28!=0) throw new AssertionError("Bad transparent stride");
        for(int p=b.position();p<b.limit();p+=28) {
            StringBuilder key=new StringBuilder();
            for(int f=0;f<7;f++) {
                float value=b.getFloat(p+f*4);
                if(!Float.isFinite(value)) throw new AssertionError("Nonfinite transparent data");
                if(f<5) key.append(value).append(':');
            }
            float alpha=b.getFloat(p+24);
            if(alpha<0 || alpha>1) throw new AssertionError("Invalid transparency alpha");
            if(result.put(key.toString(),new float[]{b.getFloat(p+20),alpha})!=null) throw new AssertionError("Duplicate transparent face");
        }
        return result;
    }
    private static Map<String,Float> shadedFaces(ByteBuffer b) {
        Map<String,Float> result=new HashMap<>();
        if(b==null) return result;
        if(b.remaining()%24!=0) throw new AssertionError("Bad shaded stride");
        for(int p=b.position();p<b.limit();p+=24) {
            StringBuilder key=new StringBuilder();
            for(int f=0;f<6;f++) {
                float value=b.getFloat(p+f*4);
                if(!Float.isFinite(value)) throw new AssertionError("Nonfinite shaded data");
                if(f<5) key.append(value).append(':');
            }
            if(result.put(key.toString(),b.getFloat(p+20))!=null) throw new AssertionError("Duplicate shaded face");
        }
        return result;
    }
    private static List<Long> state() {
        List<Long> state=new ArrayList<>();
        state.add((long)GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        state.add((long)GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING));
        for(int i=0;i<GpuCloudGeneration.STORAGE_BINDING_COUNT;i++) {
            state.add((long)GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i));
            state.add(GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i));
            state.add(GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i));
        }
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int texture=GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        GL13.glActiveTexture(active);
        state.add((long)active);
        state.add((long)texture);
        int[] keys={GL11.GL_UNPACK_ALIGNMENT,GL12.GL_UNPACK_ROW_LENGTH,GL12.GL_UNPACK_IMAGE_HEIGHT,
            GL12.GL_UNPACK_SKIP_PIXELS,GL12.GL_UNPACK_SKIP_ROWS,GL12.GL_UNPACK_SKIP_IMAGES,
            GL11.GL_UNPACK_SWAP_BYTES,GL11.GL_UNPACK_LSB_FIRST};
        for(int key:keys) state.add((long)GL11.glGetInteger(key));
        return state;
    }
}
