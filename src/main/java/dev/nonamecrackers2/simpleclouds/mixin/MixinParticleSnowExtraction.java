package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.client.ParticleSnowRenderProbe;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="pigcart.particlerain.particle.CustomParticle",remap=false)
public abstract class MixinParticleSnowExtraction {
    @Unique private int simpleclouds$snowBefore;
    @Inject(method="extract",at=@At("HEAD"),require=1,remap=false)
    private void simpleclouds$before(QuadParticleRenderState state,Camera camera,float partial,CallbackInfo ci) {
        if(ParticleSnowRenderProbe.ENABLED)simpleclouds$snowBefore=ParticleSnowRenderProbe.count(state);
    }
    @Inject(method="extract",at=@At("TAIL"),require=1,remap=false)
    private void simpleclouds$after(QuadParticleRenderState state,Camera camera,float partial,CallbackInfo ci) {
        if(ParticleSnowRenderProbe.ENABLED)ParticleSnowRenderProbe.extracted(this,state,simpleclouds$snowBefore);
    }
}
