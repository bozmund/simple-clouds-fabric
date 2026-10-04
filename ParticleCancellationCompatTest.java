import dev.nonamecrackers2.simpleclouds.common.compat.ParticleRainCancellationCompatRules;

public class ParticleCancellationCompatTest {
    public static void main(String[] args) {
        String mixin="pigcart.particlerain.mixin.render.ParticleEngineMixin";
        String descriptor="(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;Lnet/minecraft/client/particle/Particle;)V";
        check(ParticleRainCancellationCompatRules.cancelMethod(mixin,"onParticleSpawnCanceled",descriptor),"exact duplicate hook");
        for(String method:new String[]{"clearParticles","tick","onParticleSpawnCancelled","onParticleSpawnCanceled2","",null})
            check(!ParticleRainCancellationCompatRules.cancelMethod(mixin,method,descriptor),"preserve other member "+method);
        for(String owner:new String[]{"other.ParticleEngineMixin","pigcart.particlerain.mixin.render.OtherMixin","",null})
            check(!ParticleRainCancellationCompatRules.cancelMethod(owner,"onParticleSpawnCanceled",descriptor),"preserve other owner "+owner);
        for(String signature:new String[]{"()V",descriptor+"X","",null})
            check(!ParticleRainCancellationCompatRules.cancelMethod(mixin,"onParticleSpawnCanceled",signature),"preserve other signature "+signature);
        System.out.println("PASS: exact optional cancellation selection; clear, other methods/mixins/signatures preserved");
    }
    private static void check(boolean valid,String step) {if(!valid) throw new AssertionError(step);}
}
