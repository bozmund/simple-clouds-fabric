package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;

@Pseudo
@Mixin(targets="com.thedeathlycow.immersive.storms.world.BiomeWindEffects",remap=false)
public abstract class MixinImmersiveWindControl {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="addParticleAndSound",
        at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"),require=1,remap=false)
    private static void simpleclouds$ambientWind(net.minecraft.client.multiplayer.ClientLevel level,
            net.minecraft.core.particles.ParticleOptions type,double x,double y,double z,double dx,double dy,double dz,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var sample=dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects.at(level,net.minecraft.core.BlockPos.containing(x,y,z));
        if(sample.localAuthority()) {
            double speed=Math.hypot(dx,dz);dx=sample.windX()*speed;dz=sample.windZ()*speed;
        }
        original.call(level,type,x,y,z,dx,dy,dz);
    }
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="addParticleAndSound",
        at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;isRaining()Z"),require=1,remap=false)
    private static boolean simpleclouds$ambientSoundWeather(net.minecraft.client.multiplayer.ClientLevel level,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original,
            @com.llamalad7.mixinextras.sugar.Local(argsOnly=true) net.minecraft.core.BlockPos.MutableBlockPos pos) {
        var sample=dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects.at(level,pos);
        return sample.localAuthority()?sample.rain()>0:original.call(level);
    }
    @Inject(method="onEndTick",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void simpleclouds$biomeWindEnabled(CallbackInfo ci) {
        if(!SimpleCloudsConfig.CLIENT.biomeStormEffects.get()) ci.cancel();
    }
}
