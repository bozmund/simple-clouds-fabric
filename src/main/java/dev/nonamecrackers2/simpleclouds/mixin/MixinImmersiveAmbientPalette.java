package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.client.compat.NativeDustProvenance;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="com.thedeathlycow.immersive.storms.world.BiomeWindEffects$ParticleColor",remap=false)
public abstract class MixinImmersiveAmbientPalette {
    @Inject(method="getParticle",at=@At("RETURN"),require=1,remap=false)
    private void simpleclouds$ambient(CallbackInfoReturnable<ParticleOptions> ci) {
        NativeDustProvenance.markAmbient(ci.getReturnValue());
    }
}
