package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="pigcart.particlerain.particle.CustomParticle",remap=false)
public abstract class MixinCustomParticleLifetime {
    @Inject(method="<init>",at=@At("RETURN"),require=1,remap=false)
    private void simpleclouds$rule(ClientLevel level,double x,double y,double z,@Coerce Object data,CallbackInfo ci) {
        OwnedWeatherParticles.register((Particle)(Object)this,level,data);
        dev.nonamecrackers2.simpleclouds.client.NativeRainRoofProbe.spawned((Particle)(Object)this,y);
    }
}
