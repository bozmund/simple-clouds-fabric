package dev.nonamecrackers2.simpleclouds.mixin;

import dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="pigcart.particlerain.WindManager",remap=false)
public abstract class MixinRegisteredParticleLifetime {
    private static java.lang.reflect.Field simpleclouds$windStrength,simpleclouds$stormStrength;
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method={"applyWind","applySpawnWind"},
        at=@At(value="INVOKE",target="Lpigcart/particlerain/WindManager;windMultiplier(Lnet/minecraft/client/multiplayer/ClientLevel;Lpigcart/particlerain/config/ParticleData;)F"),
        require=2,remap=false)
    private static float simpleclouds$localWindStrength(net.minecraft.client.multiplayer.ClientLevel level,
            @Coerce Object data,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Float> original,
            @com.llamalad7.mixinextras.sugar.Local(argsOnly=true) Particle particle) {
        if(level==null) return original.call(level,data);
        var position=(MixinParticlePositionAccessor)particle;
        var sample=dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects.at(level,
            net.minecraft.core.BlockPos.containing(position.simpleclouds$x(),position.simpleclouds$y(),position.simpleclouds$z()));
        if(!sample.localAuthority()) return original.call(level,data);
        try {
            if(simpleclouds$windStrength==null) {
                simpleclouds$windStrength=data.getClass().getField("windStrength");
                simpleclouds$stormStrength=data.getClass().getField("stormWindStrength");
            }
            return ((Number)(sample.thunder()>0?simpleclouds$stormStrength:simpleclouds$windStrength).get(data)).floatValue();
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Pinned wind strength contract changed",failure);}
    }
    @Inject(method="track",at=@At("HEAD"),require=1,remap=false)
    private static void simpleclouds$track(Particle particle,@Coerce Object data,CallbackInfo ci) {
        OwnedWeatherParticles.register(particle,Minecraft.getInstance().level,data);
    }
}
