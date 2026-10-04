package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in diagnostic: preserve bindings around upstream raw-GL transform feedback. */
@Pseudo
@Mixin(targets="fun.qu_an.minecraft.asyncparticles.client.core.particle.gpu_acceleration.opengl.GlTfParticleRenderer", remap=false)
public abstract class MixinAsyncGlBindingRestore {
    @Unique private int simpleclouds$incomingVao;
    @Unique private int simpleclouds$incomingArrayBuffer;
    @Unique private boolean simpleclouds$restoreBindings;
    @Unique private int simpleclouds$restoreLogs;

    @Inject(method="compute", at=@At("HEAD"), require=1, remap=false)
    private void simpleclouds$captureBindings(net.minecraft.client.Camera camera, float partialTick, CallbackInfo ci) {
        simpleclouds$restoreBindings="1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                && "1".equals(System.getenv("SIMPLECLOUDS_TEST_ASYNC_GL_RESTORE"));
        if(!simpleclouds$restoreBindings) return;
        simpleclouds$incomingVao=org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING);
        simpleclouds$incomingArrayBuffer=org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER_BINDING);
    }

    @Inject(method="compute", at=@At("RETURN"), require=1, remap=false)
    private void simpleclouds$restoreBindings(net.minecraft.client.Camera camera, float partialTick, CallbackInfo ci) {
        if(!simpleclouds$restoreBindings) return;
        int actual=org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING);
        org.lwjgl.opengl.GL30C.glBindVertexArray(simpleclouds$incomingVao);
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER,simpleclouds$incomingArrayBuffer);
        if(actual!=simpleclouds$incomingVao && simpleclouds$restoreLogs++<4)
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/AsyncGlRestore").info(
                    "[ASYNC-GL-RESTORE] vao {} -> {}, arrayBuffer={}", actual,simpleclouds$incomingVao,simpleclouds$incomingArrayBuffer);
    }
}
