package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveWeatherBridge;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;

@Pseudo
@Mixin(targets="com.thedeathlycow.immersive.storms.world.SandstormParticles",remap=false)
public abstract class MixinImmersiveSandstormParticles {
    @Inject(method="onEndTick",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void simpleclouds$enabled(CallbackInfo ci) {
        if(!SimpleCloudsConfig.CLIENT.biomeStormEffects.get())ci.cancel();
    }
    @Redirect(method="onEndTick",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;isRaining()Z"),require=1,remap=false)
    private boolean simpleclouds$rain(ClientLevel level) {
        return ImmersiveWeatherBridge.cameraRain(level,level.isRaining()?1:0)>0;
    }
    @Inject(method="addParticle",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private static void simpleclouds$column(ClientLevel level,ParticleOptions particle,BlockPos pos,float rarity,CallbackInfo ci) {
        var sample=LocalWeatherEffects.at(level,pos);
        if(!SimpleCloudsConfig.CLIENT.biomeStormEffects.get() || sample.localAuthority() && sample.rain()<=0)ci.cancel();
    }
}
