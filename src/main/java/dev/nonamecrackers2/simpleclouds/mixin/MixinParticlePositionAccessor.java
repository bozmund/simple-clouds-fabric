package dev.nonamecrackers2.simpleclouds.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface MixinParticlePositionAccessor {
    @Accessor("x") double simpleclouds$x();
    @Accessor("y") double simpleclouds$y();
    @Accessor("z") double simpleclouds$z();
    @Accessor("xd") double simpleclouds$xd();
    @Accessor("zd") double simpleclouds$zd();
    @Accessor("xd") void simpleclouds$xd(double value);
    @Accessor("zd") void simpleclouds$zd(double value);
}
