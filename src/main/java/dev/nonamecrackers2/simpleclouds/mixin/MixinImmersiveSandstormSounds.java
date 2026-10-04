package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveWeatherBridge;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@Pseudo
@Mixin(targets="com.thedeathlycow.immersive.storms.world.SandstormSounds",remap=false)
public abstract class MixinImmersiveSandstormSounds {
    @WrapOperation(method="lambda$onEndTick$0",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"),require=1,remap=false)
    private static void simpleclouds$observeSound(ClientLevel level,BlockPos pos,net.minecraft.sounds.SoundEvent event,
        net.minecraft.sounds.SoundSource source,float volume,float pitch,boolean delayed,Operation<Void> original) {
        dev.nonamecrackers2.simpleclouds.client.NativeWeatherMatrixProbe.soundSubmitted(event);
        original.call(level,pos,event,source,volume,pitch,delayed);
    }
    @Inject(method="onEndTick",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void simpleclouds$enabled(CallbackInfo ci) {
        if(!SimpleCloudsConfig.CLIENT.biomeStormEffects.get())ci.cancel();
    }
    @Redirect(method="onEndTick",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),require=1,remap=false)
    private float simpleclouds$rain(ClientLevel level,float partial) {
        return ImmersiveWeatherBridge.cameraRain(level,level.getRainLevel(partial));
    }
    @Coerce
    @WrapOperation(method="chooseSpotForWindSound",at=@At(value="INVOKE",target="Lcom/thedeathlycow/immersive/storms/util/WeatherEffectType;getWeatherData(Lnet/minecraft/world/level/Level;)Lcom/thedeathlycow/immersive/storms/util/WeatherEffectType$WeatherData;"),require=1,remap=false)
    private Object simpleclouds$data(@Coerce Object type,Level world,Operation<Object> original,@Local(ordinal=1) BlockPos pos) {
        return ImmersiveWeatherBridge.soundData(type,world,pos,()->original.call(type,world));
    }
}
