package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Keep upstream biome/roof checks; replace only its global weather predicate. */
@Pseudo
@Mixin(targets="com.thedeathlycow.immersive.storms.util.WeatherEffects",remap=false)
public abstract class MixinImmersiveLocalWeather {
    @Redirect(method="getCurrentType(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;ZLjava/util/function/BiPredicate;)Lcom/thedeathlycow/immersive/storms/util/WeatherEffectType;",
        at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;precipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"),require=1,remap=false)
    private static net.minecraft.world.level.biome.Biome.Precipitation simpleclouds$precipitation(Level world,BlockPos pos) {
        var sample=LocalWeatherEffects.at(world,pos);
        if(!sample.localAuthority())return world.precipitationAt(pos);
        if(sample.rain()<=0 || !world.canSeeSky(pos)
            || world.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,pos).getY()>pos.getY())
            return net.minecraft.world.level.biome.Biome.Precipitation.NONE;
        return world.getBiome(pos).value().getPrecipitationAt(pos,world.getSeaLevel());
    }
    @Redirect(method="isWeatherAffected(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Z)Z",
        at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;isRaining()Z"),require=1,remap=false)
    private static boolean simpleclouds$localWeather(Level receiver,Level world,BlockPos pos,boolean aboveSurface) {
        if(world.isClientSide() && !dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig.CLIENT.biomeStormEffects.get()) return false;
        var sample=LocalWeatherEffects.at(world,pos);
        return sample.localAuthority()?sample.rain()>0:receiver.isRaining();
    }
}
