package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import dev.nonamecrackers2.simpleclouds.client.compat.ParticleWeatherBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Exact beta100 spawn sites. Preserve upstream biome, roof, collision and budget rules. */
@Pseudo
@Mixin(targets="pigcart.particlerain.ParticleSpawner",remap=false)
public abstract class MixinParticleLocalWeather {
	@Inject(method={"tickSkyFX","tickSurfaceFX","tickBlockFX"},at=@At("HEAD"),cancellable=true,require=3,remap=false)
	private static void simpleclouds$singleOwner(CallbackInfo ci) {
		if(!dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper.usesParticleRain()
				|| dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig.CLIENT.weatherParticleBudget.get()==0) ci.cancel();
	}
    @Shadow @Final private static BlockPos.MutableBlockPos pos;
    private static final String RULE="Lpigcart/particlerain/config/ParticleData$Weather;isCurrent(Lnet/minecraft/client/multiplayer/ClientLevel;)Ljava/lang/Boolean;";
    @WrapOperation(method="tickSkyFX",at=@At(value="INVOKE",target=RULE),require=1,remap=false)
    private static Boolean simpleclouds$sky(@Coerce Object rule,ClientLevel level,Operation<Boolean> original) {
        return simpleclouds$matches(rule,level,pos,original);
    }
    @WrapOperation(method="tickSurfaceFX",at=@At(value="INVOKE",target=RULE),require=1,remap=false)
    private static Boolean simpleclouds$surface(@Coerce Object rule,ClientLevel level,Operation<Boolean> original,
            @Local(ordinal=0) double x,@Local(ordinal=1) double z,@Local(ordinal=2) double y) {
        return simpleclouds$matches(rule,level,BlockPos.containing(x,y,z),original);
    }
    @WrapOperation(method="tickBlockFX",at=@At(value="INVOKE",target=RULE),require=1,remap=false)
    private static Boolean simpleclouds$block(@Coerce Object rule,ClientLevel level,Operation<Boolean> original,
            @Local(argsOnly=true) BlockPos.MutableBlockPos sourcePos) {
        return simpleclouds$matches(rule,level,sourcePos,original);
    }
    private static Boolean simpleclouds$matches(Object rule,ClientLevel level,BlockPos where,Operation<Boolean> original) {
        var sample=LocalWeatherEffects.at(level,where);
        return sample.localAuthority()?ParticleWeatherBridge.matches(level,where,rule,sample):original.call(rule,level);
    }
}
