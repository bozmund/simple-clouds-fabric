package dev.nonamecrackers2.simpleclouds.mixin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Temporary bounded read-only append-record diagnostic, development only. */
@Pseudo
@Mixin(targets="fun.qu_an.minecraft.asyncparticles.client.core.particle.gpu_acceleration.opengl.GlTfParticleRenderer",remap=false)
public abstract class MixinAsyncAppendProbe {
    @Shadow private ByteBuffer mappedBuffer;
    @Shadow private int particleLimit;
    private static int samples;
    private int expectedCount;
    private byte[] expectedBytes;
    private int gpuSamples;
    @Inject(method="extractAppendParticles",at=@At("RETURN"),require=1,remap=false)
    private void simpleclouds$inspect(net.minecraft.world.phys.Vec3 camera,List<?> particles,List<?> batches,
            CallbackInfoReturnable<Integer> ci) {
        if(!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_APPEND_PROBE"))
                || samples>=30 || particles.isEmpty()) return;
        samples++;
        try {
            var format=Class.forName("fun.qu_an.minecraft.asyncparticles.client.core.particle.gpu_acceleration.GpuParticlePipelines")
                    .getField("RAW_PARTICLE").get(null);
            int stride=((com.mojang.renderpearl.api.vertex.VertexFormat)format).getVertexSize();
            if(stride!=68) throw new IllegalStateException("Unexpected diagnostic record stride: "+stride);
            var data=mappedBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            int base=ci.getReturnValue(),count=Math.min(particleLimit,particles.size());
            expectedCount=count;
            expectedBytes=new byte[count*stride];
            for(int i=0;i<expectedBytes.length;i++) expectedBytes[i]=data.get(base+i);
            int saturated=0,invalidUv=0,invalidPos=0;
            StringBuilder colors=new StringBuilder(),ranges=new StringBuilder();
            for(int i=0;i<count;i++) {
                int offset=base+i*stride;
                for(int j=0;j<6;j++) if(!Float.isFinite(data.getFloat(offset+j*4))) invalidPos++;
                for(int j=0;j<4;j++) {float uv=data.getFloat(offset+32+j*4);if(!Float.isFinite(uv)||uv<0||uv>1) invalidUv++;}
                int color=data.getInt(offset+48),r=color&255,g=(color>>8)&255,b=(color>>16)&255;
                int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
                if(max>=110 && max-min>=90 && max>=3*min+30) saturated++;
                if(i<6) colors.append(Integer.toHexString(color)).append(',');
            }
            for(Object batch:batches) {
                var type=batch.getClass();
                ranges.append('[').append(type.getField("tickOffset").getInt(batch)).append('+')
                        .append(type.getField("tickCount").getInt(batch)).append(" append=")
                        .append(type.getField("appendOffset").getInt(batch)).append('+')
                        .append(type.getField("appendCount").getInt(batch)).append(']');
            }
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/AppendProbe").info(
                    "[APPEND-RECORDS] sample={} base={} stride={} limit={} count={} saturated={} invalidUv={} invalidPos={} colors={} ranges={}",
                    samples,base,stride,particleLimit,count,saturated,invalidUv,invalidPos,colors,ranges);
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Pinned append probe contract changed",failure);}
    }
    @Inject(method="compute",at=@At("RETURN"),require=1,remap=false)
    private void simpleclouds$readGpu(net.minecraft.client.Camera camera,float partialTick,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_GPU_READBACK")) || expectedBytes==null || gpuSamples>=12) return;
        gpuSamples++;
        try {
            Class<?> type=Class.forName("fun.qu_an.minecraft.asyncparticles.client.core.particle.gpu_acceleration.opengl.GlTfParticleRenderer");
            var slotField=type.getDeclaredField("renderSrcIdx");slotField.setAccessible(true);
            var sourcesField=type.getDeclaredField("sources");sourcesField.setAccessible(true);
            var targetField=type.getDeclaredField("target");targetField.setAccessible(true);
            int slot=slotField.getInt(this);if(slot<0)return;
            Object source=((Object[])sourcesField.get(this))[slot];
            var vboField=source.getClass().getField("vbo");
            var bytes=org.lwjgl.system.MemoryUtil.memAlloc(expectedBytes.length).order(ByteOrder.nativeOrder());
            var output=org.lwjgl.system.MemoryUtil.memAlloc(44*4).order(ByteOrder.nativeOrder());
            try {
                org.lwjgl.opengl.GL45C.glGetNamedBufferSubData(vboField.getInt(source),(long)particleLimit*68,bytes);
                int mismatches=0;for(int i=0;i<expectedBytes.length;i++)if(bytes.get(i)!=expectedBytes[i])mismatches++;
                Object target=targetField.get(this);
                var countField=type.getDeclaredField("tickCount");countField.setAccessible(true);
                int prefixCount=((int[])countField.get(this))[slot];
                org.lwjgl.opengl.GL45C.glGetNamedBufferSubData(vboField.getInt(target),(long)prefixCount*44*4,output);
                StringBuilder colors=new StringBuilder();
                for(int vertex=0;vertex<4;vertex++) {
                    colors.append('[');for(int c=0;c<4;c++)colors.append(output.getFloat(vertex*44+20+c*4)).append(',');colors.append(']');
                }
                org.apache.logging.log4j.LogManager.getLogger("simpleclouds/AppendProbe").info(
                        "[APPEND-GPU] sample={} slot={} count={} prefixCount={} byteMismatches={} appendedOutputColors={} partialTick={}",
                        gpuSamples,slot,expectedCount,prefixCount,mismatches,colors,partialTick);
            } finally {
                // Consume this expectation once: later computes may use a different
                // published source slot and must not compare against stale records.
                expectedBytes=null;
                org.lwjgl.system.MemoryUtil.memFree(bytes);org.lwjgl.system.MemoryUtil.memFree(output);
            }
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Pinned GPU probe contract changed",failure);}
    }
}
