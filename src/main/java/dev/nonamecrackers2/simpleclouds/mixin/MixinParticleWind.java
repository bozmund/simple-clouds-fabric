package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.joml.Vector3f;

@Pseudo
@Mixin(targets="pigcart.particlerain.ParticleRain",remap=false)
public abstract class MixinParticleWind {
    @Inject(method="getWind(DDD)Lorg/joml/Vector3f;",at=@At("RETURN"),cancellable=true,require=1,remap=false)
    private static void simpleclouds$sharedWind(double x,double y,double z,CallbackInfoReturnable<Vector3f> ci) {
        var level=Minecraft.getInstance().level;
        if(level==null) return;
        var sample=LocalWeatherEffects.at(level,BlockPos.containing(x,y,z));
        if(!sample.localAuthority()) return;
        var nativeWind=ci.getReturnValue();
        float magnitude=(float)Math.hypot(nativeWind.x,nativeWind.z);
        if(!Float.isFinite(magnitude)) magnitude=0;
        ci.setReturnValue(new Vector3f(sample.windX()*magnitude,nativeWind.y,sample.windZ()*magnitude));
    }
}
