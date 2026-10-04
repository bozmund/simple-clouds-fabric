package dev.nonamecrackers2.simpleclouds.client.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraftforge.fml.ModList;

/** Optional pinned-library config contract, resolved once; no upstream class linkage in base builds. */
public final class WeatherLibraryControl {
    private static Method getConfig;
    private static Field compat,perf,renderDefault,maxParticles;
    private static Object currentPerf;
    private static int upstreamMax,lastApplied=-1;
    private static Boolean lastOwner;
    private static final org.apache.logging.log4j.Logger LOG=
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WeatherLibraryControl");
    private WeatherLibraryControl() {}
    public static void tick() {
        if(!ModList.get().isLoaded("particlerain")) return;
        try {
            if(getConfig==null) {
                getConfig=Class.forName("pigcart.particlerain.config.ConfigManager").getMethod("getConfig");
                var type=getConfig.getReturnType();
                compat=type.getField("compat");perf=type.getField("perf");
                renderDefault=compat.getType().getField("renderDefaultWeather");
                maxParticles=perf.getType().getField("maxParticleAmount");
            }
            var config=getConfig.invoke(null);
            var performance=perf.get(config);
            int observed=maxParticles.getInt(performance);
            if(performance!=currentPerf || observed!=lastApplied) {
                currentPerf=performance;upstreamMax=Math.max(0,observed);
            }
            boolean particle=SimpleCloudsCompatHelper.usesParticleRain();
            renderDefault.setBoolean(compat.get(config),!particle);
            lastApplied=Math.min(upstreamMax,SimpleCloudsConfig.CLIENT.weatherParticleBudget.get());
            maxParticles.setInt(performance,lastApplied);
            if(lastOwner==null || lastOwner!=particle) {
                lastOwner=particle;
                LOG.info("[WEATHER-OWNER] precipitation={} particleBudget={} biomeEffects={}",
                        particle?"PARTICLE":"ORIGINAL",lastApplied,SimpleCloudsConfig.CLIENT.biomeStormEffects.get());
            }
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned Particle Rain config contract changed; cannot guarantee one precipitation owner",failure);
        }
    }
}
