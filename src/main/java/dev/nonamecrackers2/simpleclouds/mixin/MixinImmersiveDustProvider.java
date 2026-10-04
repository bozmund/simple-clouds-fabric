package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

@Pseudo
@Mixin(targets="com.thedeathlycow.immersive.storms.particle.DustGrainParticle$Provider",remap=false)
public abstract class MixinImmersiveDustProvider {
    @Inject(method="createParticle(Lcom/thedeathlycow/immersive/storms/particle/DustGrainParticleEffect;Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDDLnet/minecraft/util/RandomSource;)Lnet/minecraft/client/particle/Particle;",
        at=@At("RETURN"),require=1,remap=false)
    private void simpleclouds$own(@Coerce Object options,ClientLevel level,double x,double y,double z,
        double vx,double vy,double vz,RandomSource random,CallbackInfoReturnable<Particle> ci) {
        var particle=ci.getReturnValue();if(particle==null)return;
        var position=(MixinParticlePositionAccessor)particle;
        var sample=LocalWeatherEffects.at(level,BlockPos.containing(position.simpleclouds$x(),position.simpleclouds$y(),position.simpleclouds$z()));
        double length=Math.hypot(sample.windX(),sample.windZ());
        if(sample.localAuthority() && length>1e-9) {
            double speed=Math.hypot(position.simpleclouds$xd(),position.simpleclouds$zd());
            position.simpleclouds$xd(sample.windX()/length*speed);
            position.simpleclouds$zd(sample.windZ()/length*speed);
        }
        OwnedWeatherParticles.registerBiome(particle,level,
            dev.nonamecrackers2.simpleclouds.client.compat.NativeDustProvenance.isAmbient(
                (net.minecraft.core.particles.ParticleOptions)options));
        dev.nonamecrackers2.simpleclouds.client.NativeWeatherMatrixProbe.dustCreated(
            (net.minecraft.core.particles.ParticleOptions)options,particle);
    }
}
