package dev.nonamecrackers2.simpleclouds.client;

import java.lang.reflect.Field;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.world.level.biome.Biome;

/** Read-only opt-in evidence for naturally spawned Particle Rain snow render submissions. */
public final class ParticleSnowRenderProbe {
    public static final boolean ENABLED="1".equals(System.getenv("SIMPLECLOUDS_DEV"))
        && "1".equals(System.getenv("SIMPLECLOUDS_TEST_PARTICLE_SNOW"));
    private static final WeakHashMap<QuadParticleRenderState,Integer> snow=new WeakHashMap<>();
    private static Field count,dataField,precipitation,sprites;
    private static boolean reported;
    private ParticleSnowRenderProbe() {}
    public static int count(QuadParticleRenderState state) {
        if(!ENABLED)return 0;
        try {
            if(count==null) {count=QuadParticleRenderState.class.getDeclaredField("particleCount");count.setAccessible(true);}
            return count.getInt(state);
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Snow quad state contract changed",failure);}
    }
    public static synchronized void extracted(Object particle,QuadParticleRenderState state,int before) {
        if(!ENABLED)return;
        int added=count(state)-before;if(added<=0)return;
        try {
            if(dataField==null)dataField=particle.getClass().getField("data");
            Object data=dataField.get(particle);
            if(precipitation==null) {
                precipitation=data.getClass().getField("precipitation");sprites=data.getClass().getField("spriteLocations");
            }
            var types=(java.util.Collection<?>)precipitation.get(data);
            var textures=(java.util.Collection<?>)sprites.get(data);
            if(types.contains(Biome.Precipitation.SNOW)
                && textures.stream().anyMatch(texture->texture.toString().toLowerCase(java.util.Locale.ROOT).contains("snow")))
                snow.merge(state,added,Integer::sum);
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Pinned snow particle contract changed",failure);}
    }
    public static synchronized void clear(QuadParticleRenderState state) {
        if(ENABLED)snow.remove(state);
    }
    public static synchronized void submitted(QuadParticleRenderState state) {
        if(!ENABLED)return;
        Integer quads=snow.remove(state);
        if(quads!=null && quads>0 && !state.isEmpty() && !reported) {
            if(!dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper.usesParticleRain())
                throw new IllegalStateException("Particle snow test submitted without PARTICLE ownership");
            reported=true;
            org.slf4j.LoggerFactory.getLogger(ParticleSnowRenderProbe.class).info(
                "[PARTICLE-SNOW-RENDER] PASS actual naturally spawned SNOW data/snow textures/extractedQuads={} submittedLayers={}",
                quads,state.layers().size());
        }
    }
}
