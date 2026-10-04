package dev.nonamecrackers2.simpleclouds.common.compat;

/** Exact beta100 hook already replaced by AsyncParticles' atomic counter adapter. */
public final class ParticleRainCancellationCompatRules {
    private ParticleRainCancellationCompatRules() {}
    public static boolean selectedMixin(String name) {
        return "pigcart.particlerain.mixin.render.ParticleEngineMixin".equals(name);
    }
    public static boolean cancelMethod(String mixin,String method,String descriptor) {
        return selectedMixin(mixin) && "onParticleSpawnCanceled".equals(method)
                && "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;Lnet/minecraft/client/particle/Particle;)V".equals(descriptor);
    }
}
