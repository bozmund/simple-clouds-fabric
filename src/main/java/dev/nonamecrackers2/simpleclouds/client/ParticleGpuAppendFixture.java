package dev.nonamecrackers2.simpleclouds.client;

import net.minecraft.client.Minecraft;
/** Explicit opt-in fixture: ensure the GPU append path really runs. No world edits. */
public final class ParticleGpuAppendFixture {
    private static int ticks;
    private static Object owner;
    private ParticleGpuAppendFixture() {}
    public static void tick(Minecraft mc) {
        if(!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_PARTICLE_GPU"))) return;
        if(mc.level==null||mc.player==null||mc.isPaused()) return;
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().toString()
                .equals("/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3"))
            throw new IllegalStateException("GPU append fixture requires owned fullpack test profile");
        if(owner!=mc.level) {owner=mc.level;ticks=0;}
        if(ticks++>=160)return;
        var camera=mc.gameRenderer.mainCamera();var pos=camera.position();
        var particle=mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.CLOUD,
                pos.x()+1,pos.y()+4,pos.z()+3,0,0,0);
        if(particle==null) throw new IllegalStateException("GPU append fixture particle missing");
        try {
            var behavior=Class.forName("fun.qu_an.minecraft.asyncparticles.client.core.particle.gpu_acceleration.GpuParticleBehavior");
            Object instance=behavior.getMethod("getInstance").invoke(null);
            boolean fast=(Boolean)behavior.getMethod("canRenderFast",net.minecraft.client.particle.Particle.class)
                    .invoke(instance,particle);
            if(!fast) throw new IllegalStateException("Fixture particle does not use GPU path: "+particle.getClass());
            if(ticks==1||ticks==160) org.apache.logging.log4j.LogManager.getLogger("simpleclouds/AppendFixture").info(
                    "[APPEND-FIXTURE] tick={} native={} GPU eligible=true",ticks,particle.getClass().getName());
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("GPU fixture contract changed",failure);}
    }
}
