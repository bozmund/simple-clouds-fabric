package dev.nonamecrackers2.simpleclouds.client.compat;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import dev.nonamecrackers2.simpleclouds.mixin.MixinParticlePositionAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;

/** Tracks explicit weather producers only, never the entire vanilla particle engine. */
public final class OwnedWeatherParticles {
    private record Tracked(LocalWeatherEffects.Condition condition,boolean biome) {}
    private static final LinkedHashMap<Particle,Tracked> live=new LinkedHashMap<>();
    private static ClientLevel owner;
    private static Field weatherRule;
    private OwnedWeatherParticles() {}

    public static synchronized void register(Particle particle,ClientLevel level,Object data) {
        // A late old-world producer must not alter a new world's counters.
        if(level==null || Minecraft.getInstance().level!=level) return;
        if(owner!=level) { live.clear();owner=level; }
        LocalWeatherEffects.Condition condition=LocalWeatherEffects.Condition.ALWAYS;
        if(data!=null) {
            try {
                if(weatherRule==null) weatherRule=data.getClass().getField("weather");
                condition=LocalWeatherEffects.Condition.valueOf(((Enum<?>)weatherRule.get(data)).name());
            } catch(ReflectiveOperationException failure) {
                throw new IllegalStateException("Pinned Particle Rain weather rule contract changed",failure);
            }
        }
        if(!SimpleCloudsCompatHelper.usesParticleRain()) {
            particle.remove();return;
        }
        put(particle,condition,false);
    }

    public static synchronized void registerBiome(Particle particle,ClientLevel level) {
        registerBiome(particle,level,false);
    }

    public static synchronized void registerBiome(Particle particle,ClientLevel level,boolean ambient) {
        if(level==null || Minecraft.getInstance().level!=level)return;
        if(owner!=level) {live.clear();owner=level;}
        if(!SimpleCloudsConfig.CLIENT.biomeStormEffects.get()) {particle.remove();return;}
        put(particle,ambient?LocalWeatherEffects.Condition.ALWAYS:LocalWeatherEffects.Condition.DURING_WEATHER,true);
    }

    private static void put(Particle particle,LocalWeatherEffects.Condition condition,boolean biome) {
        live.put(particle,new Tracked(condition,biome));
        // Includes native REGISTERED effects, which upstream does not include in
        // its custom-particle counter. Base + subclass registrations replace one
        // entry instead of consuming two slots. Removal remains idempotent.
        int budget=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get();
        while(live.size()>budget) {
            var oldest=live.entrySet().iterator();
            var removed=oldest.next().getKey();oldest.remove();
            if(removed.isAlive()) removed.remove();
        }
    }

    public static synchronized void tick(ClientLevel level) {
        if(owner!=level || level==null) {
            // ParticleEngine has already cleared/reset upstream counters on a
            // world change. Drop references, do not remove old-world objects.
            live.clear();owner=level;return;
        }
        boolean precipitationEnabled=SimpleCloudsCompatHelper.usesParticleRain();
        int budget=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get();
        var entries=live.entrySet().iterator();
        while(entries.hasNext()) {
            var entry=entries.next();var particle=entry.getKey();
            var tracked=entry.getValue();
            boolean enabled=tracked.biome()?SimpleCloudsConfig.CLIENT.biomeStormEffects.get():precipitationEnabled;
            if(!particle.isAlive()) {entries.remove();continue;}
            var position=(MixinParticlePositionAccessor)particle;
            var pos=BlockPos.containing(position.simpleclouds$x(),position.simpleclouds$y(),position.simpleclouds$z());
            var sample=LocalWeatherEffects.at(level,pos);
            boolean valid=sample.localAuthority()?ParticleWeatherBridge.matches(level,pos,tracked.condition(),sample)
                : !tracked.biome() || tracked.condition()==LocalWeatherEffects.Condition.ALWAYS || level.isRaining();
            if(!enabled || live.size()>budget || !valid) {
                particle.remove();entries.remove();
            }
        }
    }
    public static synchronized int size() {return live.size();}
}
