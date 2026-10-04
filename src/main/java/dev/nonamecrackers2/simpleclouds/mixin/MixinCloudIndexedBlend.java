package dev.nonamecrackers2.simpleclouds.mixin;

import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.IndexedCloudBlend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlRenderPipeline.class)
public abstract class MixinCloudIndexedBlend {
    @Shadow public abstract GlProgram program();
    @Inject(method="bind",at=@At("TAIL"))
    private void simpleclouds$indexedBlend(CallbackInfo ci) {
        if(IndexedCloudBlend.scoped())IndexedCloudBlend.afterBind(this.program().getDebugLabel());
    }
}
