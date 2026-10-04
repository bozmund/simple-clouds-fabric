package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.client.ParticleSnowRenderProbe;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuadParticleRenderState.class)
public abstract class MixinParticleSnowSubmission {
    @Inject(method="submit",at=@At("TAIL"),require=1)
    private void simpleclouds$submitted(CallbackInfo ci) {
        if(ParticleSnowRenderProbe.ENABLED)ParticleSnowRenderProbe.submitted((QuadParticleRenderState)(Object)this);
    }
    @Inject(method="clear",at=@At("HEAD"),require=1)
    private void simpleclouds$clear(CallbackInfo ci) {
        if(ParticleSnowRenderProbe.ENABLED)ParticleSnowRenderProbe.clear((QuadParticleRenderState)(Object)this);
    }
}
