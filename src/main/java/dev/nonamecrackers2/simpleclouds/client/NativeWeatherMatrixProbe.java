package dev.nonamecrackers2.simpleclouds.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import dev.nonamecrackers2.simpleclouds.client.compat.*;
import dev.nonamecrackers2.simpleclouds.common.config.*;

/** Exact scratch-profile fixture, called only by the opt-in native biome probe. */
public final class NativeWeatherMatrixProbe {
    private static volatile boolean active;
    private static int stormDust;
    private static final List<Particle> particles=new ArrayList<>();
    private static final List<SoundEvent> sounds=new ArrayList<>();
    private NativeWeatherMatrixProbe() {}
    public static void dustCreated(ParticleOptions options,Particle particle) {
        if(!active || particle==null || !particle.isAlive())return;
        synchronized(NativeWeatherMatrixProbe.class) {
            if(!active)return;
            particles.add(particle);
            if(!NativeDustProvenance.isAmbient(options))stormDust++;
        }
    }
    public static void soundSubmitted(SoundEvent sound) {
        if(!active)return;
        synchronized(NativeWeatherMatrixProbe.class) {if(active)sounds.add(sound);}
    }
    private record Snapshot(LevelChunk chunk,Map<Long,Holder<Biome>> biomes) {}
    private static java.lang.reflect.Field field(Object object,String name) throws ReflectiveOperationException {
        var result=object.getClass().getDeclaredField(name);result.setAccessible(true);return result;
    }
    public static void verify(Minecraft mc) throws ReflectiveOperationException {
        if(!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
            || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER")))return;
        check(mc!=null && mc.level!=null && mc.getSingleplayerServer()!=null
            && mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals(
                "/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3")
            && mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .normalize().getFileName().toString().equals("CodexFullPackTest"),"exact scratch profile/save required");
        var level=mc.level;
        var pos=BlockPos.containing(mc.gameRenderer.mainCamera().position());
        var snapshots=new ArrayList<Snapshot>();
        var config=Class.forName("com.thedeathlycow.immersive.storms.ImmersiveStormsClient")
            .getMethod("getConfig").invoke(null);
        var sand=config.getClass().getMethod("getSandstorm").invoke(config);
        var radius=field(sand,"sandstormParticleRenderDistance");var density=field(sand,"sandstormParticleDensityMultiplier");
        var enable=field(sand,"enableSandstormParticles");var strong=field(config,"enableStrongWindSounds");
        Object oldRadius=radius.get(sand),oldDensity=density.get(sand),oldEnable=enable.get(sand),oldStrong=strong.get(config);
        int oldBudget=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get();
        boolean oldRoot=SimpleCloudsConfig.CLIENT.biomeStormEffects.get();
        var oldMode=SimpleCloudsConfig.CLIENT.precipitationRenderer.get();
        float oldRain=level.getRainLevel(1),oldThunder=level.getThunderLevel(1);
        try {
            // Real client biome palettes, not a mocked getCurrentType return.
            for(int cx=(pos.getX()>>4)-1;cx<=(pos.getX()>>4)+1;cx++)
                for(int cz=(pos.getZ()>>4)-1;cz<=(pos.getZ()>>4)+1;cz++) {
                    check(level.hasChunk(cx,cz),"matrix neighborhood is actually loaded");
                    var chunk=level.getChunk(cx,cz);var original=new HashMap<Long,Holder<Biome>>();
                    for(int x=cx*4;x<cx*4+4;x++)for(int z=cz*4;z<cz*4+4;z++)
                        for(int y=Math.floorDiv(level.getMinY(),4);y<=Math.floorDiv(level.getMaxY(),4);y++)
                            original.put(BlockPos.asLong(x,y,z),chunk.getNoiseBiome(x,y,z));
                    snapshots.add(new Snapshot(chunk,original));
                }
            radius.setInt(sand,2);density.setFloat(sand,60);enable.setBoolean(sand,true);strong.setBoolean(config,true);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(1500);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.ORIGINAL);
            WeatherLibraryControl.tick();OwnedWeatherParticles.tick(level);
            level.setRainLevel(0);level.setThunderLevel(0);
            var registry=level.registryAccess().lookupOrThrow(Registries.BIOME);
            var effects=Class.forName("com.thedeathlycow.immersive.storms.util.WeatherEffects");
            var type=Class.forName("com.thedeathlycow.immersive.storms.util.WeatherEffectType");
            var inclusion=Class.forName("com.thedeathlycow.immersive.storms.util.WeatherEffectsClient")
                .getMethod("typeAffectsBiome",type,Holder.class);
            java.util.function.BiPredicate<Object,Holder<Biome>> predicate=(kind,biome)->{
                try {return (Boolean)inclusion.invoke(null,kind,biome);}
                catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
            };
            var current=effects.getMethod("getCurrentType",net.minecraft.world.level.Level.class,BlockPos.class,
                boolean.class,java.util.function.BiPredicate.class);
            var biomes=List.of(Biomes.DESERT,Biomes.SNOWY_PLAINS,Biomes.PLAINS);
            var expected=List.of("SANDSTORM","BLIZZARD","NONE");
            for(int i=0;i<biomes.size();i++) {
                var holder=registry.getOrThrow(biomes.get(i));
                for(var snapshot:snapshots)snapshot.chunk().fillBiomesFromNoise((x,y,z)->holder);
                Object selected=current.invoke(null,level,pos,true,predicate);
                check(((Enum<?>)selected).name().equals(expected.get(i)),"actual biome selection "+expected.get(i));
                stormDust=0;sounds.clear();active=true;
                // Dispatch the actual registered event chain, including recovered
                // native callbacks. No direct replacement native instance.
                for(int tick=0;tick<12;tick++)
                    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_LEVEL_TICK.invoker().onEndTick(level);
                active=false;
                Object weather=ImmersiveWeatherBridge.data(selected,level,pos,()->null);
                SoundEvent expectedSound=weather==null?null:(SoundEvent)weather.getClass().getMethod("windSound").invoke(weather);
                check(i==0?stormDust>0:stormDust==0,"actual storm dust emission "+expected.get(i));
                check(expectedSound==null?sounds.isEmpty():sounds.contains(expectedSound),
                    "actual native sound submission "+expected.get(i));
                org.slf4j.LoggerFactory.getLogger(NativeWeatherMatrixProbe.class).info(
                    "[NATIVE-MATRIX] biome={} type={} actualStormDust={} actualWeatherSoundRequests={}",
                    biomes.get(i).identifier(),expected.get(i),stormDust,sounds.size());
                for(var particle:particles)particle.remove();particles.clear();OwnedWeatherParticles.tick(level);
            }
            org.slf4j.LoggerFactory.getLogger(NativeWeatherMatrixProbe.class).info(
                "[NATIVE-MATRIX] PASS actual registered callbacks/desert dust/snowy blizzard sound/plain exclusion with global clear");
        } finally {
            active=false;
            for(var particle:particles)particle.remove();particles.clear();sounds.clear();
            for(var snapshot:snapshots)snapshot.chunk().fillBiomesFromNoise((x,y,z)->{
                var original=snapshot.biomes().get(BlockPos.asLong(x,y,z));
                if(original==null)throw new IllegalStateException("Missing original biome palette entry");
                return original;
            });
            radius.set(sand,oldRadius);density.set(sand,oldDensity);enable.set(sand,oldEnable);strong.set(config,oldStrong);
            level.setRainLevel(oldRain);level.setThunderLevel(oldThunder);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(oldBudget);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(oldRoot);
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(oldMode);
            WeatherLibraryControl.tick();OwnedWeatherParticles.tick(level);
        }
    }
    private static void check(boolean valid,String message) {
        if(!valid)throw new IllegalStateException("Native automatic matrix: "+message);
    }
}
