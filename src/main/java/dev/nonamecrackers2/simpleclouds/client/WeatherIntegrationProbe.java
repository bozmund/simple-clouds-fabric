package dev.nonamecrackers2.simpleclouds.client;

import java.util.Map;
import dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles;
import dev.nonamecrackers2.simpleclouds.client.compat.WeatherLibraryControl;
import dev.nonamecrackers2.simpleclouds.common.config.PrecipitationRenderer;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;

/** One-shot functional fixture; never active in normal development or user worlds. */
public final class WeatherIntegrationProbe {
    private static boolean done;
    private static int readyTicks;
    private WeatherIntegrationProbe() {}
    public static void tick(Minecraft mc) {
        if(done || !"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_WEATHER_LIBRARIES"))) return;
        if(mc.level==null || mc.player==null) return;
        String directory=mc.gameDirectory.toPath().toAbsolutePath().normalize().toString();
        boolean isolated=directory.equals("/home/jan/simple-clouds-fabric/run");
        boolean fullPack=directory.equals("/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3")
            && mc.getSingleplayerServer()!=null && mc.getSingleplayerServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize()
                .getFileName().toString().equals("CodexFullPackTest");
        if(!isolated && !fullPack)throw new IllegalStateException("Weather fixture requires isolated run or exact full-pack scratch save");
        var pos=mc.player.blockPosition();
        if(!mc.level.hasChunkAt(pos)) return;
        var ground=mc.level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,pos).below();
        if(ground.getY()<mc.level.getMinY() || mc.level.getBlockState(ground).isAir()) return;
        var sample=LocalWeatherEffects.at(mc.level,pos);
        if(!sample.localAuthority() || sample.rain()<=0 || sample.thunder()<=0) {
            if(++readyTicks>600)throw new IllegalStateException("Weather fixture never received its required local thunderstorm");
            return;
        }
        done=true;
        var oldMode=SimpleCloudsConfig.CLIENT.precipitationRenderer.get();
        var oldBudget=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get();
        var oldBiomes=SimpleCloudsConfig.CLIENT.biomeStormEffects.get();
        try {
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.PARTICLE);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(1500);
            WeatherLibraryControl.tick();
            var dataType=Class.forName("pigcart.particlerain.config.ParticleData");
            var weather=dataType.getField("weather");
            var particles=(Map<?,?>)Class.forName("pigcart.particlerain.ParticleLoader").getField("particles").get(null);
            Object rain=particles.values().stream().filter(data -> {
                try {return ((Enum<?>)weather.get(data)).name().equals("DURING_WEATHER")
                        && ((Enum<?>)dataType.getField("particleStyle").get(data)).name().equals("CUSTOM");}
                catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
            }).findFirst().orElseThrow();
            var constructor=Class.forName("pigcart.particlerain.particle.CustomParticle").getConstructor(
                    net.minecraft.client.multiplayer.ClientLevel.class,double.class,double.class,double.class,dataType);
            var counter=activeParticleCounter();
            Particle vanilla=mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,0,0,0);
            check(vanilla!=null && vanilla.isAlive(),"independent vanilla fixture particle");
            int before=counter.getAsInt();
            Particle particle=(Particle)constructor.newInstance(mc.level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,rain);
            check(particle.isAlive() && counter.getAsInt()==before+1,"constructor/counter registration");
            OwnedWeatherParticles.tick(mc.level);
            check(particle.isAlive(),"wet local coverage retains particle");
            particle.setPos(1000000,pos.getY()+.5,1000000);
            check(LocalWeatherEffects.at(mc.level,BlockPos.containing(1000000,pos.getY()+.5,1000000)).rain()==0,"dry fixture outside local storm");
            OwnedWeatherParticles.tick(mc.level);
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WeatherProbe").info(
                "[WEATHER-DRY-DIAG] particleAlive={} initialCounter={} actualCounter={} ownedSize={}",
                particle.isAlive(),before,counter.getAsInt(),OwnedWeatherParticles.size());
            check(!particle.isAlive() && counter.getAsInt()==before,"dry boundary cleanup/counter");
            particle=(Particle)constructor.newInstance(mc.level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,rain);
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.ORIGINAL);
            WeatherLibraryControl.tick();OwnedWeatherParticles.tick(mc.level);
            check(!particle.isAlive() && counter.getAsInt()==0 && OwnedWeatherParticles.size()==0,
                    "ORIGINAL handoff clears all upstream-owned effects/counter");
            check(vanilla.isAlive(),"ORIGINAL handoff must preserve unrelated vanilla particles");
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.PARTICLE);
            particle=(Particle)constructor.newInstance(mc.level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,rain);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(0);
            OwnedWeatherParticles.tick(mc.level);
            check(!particle.isAlive() && counter.getAsInt()==0 && OwnedWeatherParticles.size()==0,"zero budget cleanup/counter");
            check(vanilla.isAlive(),"zero budget must preserve unrelated vanilla particles");
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(1500);
            Particle registered=mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,0,0,0);
            Class.forName("pigcart.particlerain.WindManager").getMethod("track",Particle.class,dataType).invoke(null,registered,rain);
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.ORIGINAL);
            OwnedWeatherParticles.tick(mc.level);
            check(!registered.isAlive() && vanilla.isAlive(),"registered native effect is owned; ordinary native effect is not");
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.PARTICLE);
            particle=(Particle)constructor.newInstance(mc.level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,rain);
            OwnedWeatherParticles.tick(null);
            check(particle.isAlive() && counter.getAsInt()==1 && OwnedWeatherParticles.size()==0,
                    "disconnect must release references without decrementing old-world counters");
            particle.remove();vanilla.remove();
            check(counter.getAsInt()==0,"fixture particles explicitly released");
            verifyMixedBudget(mc,pos,constructor,rain,counter);
            verifyWind(mc,pos,dataType);
            verifyFog(mc);
            var weatherEffects=Class.forName("com.thedeathlycow.immersive.storms.util.WeatherEffects")
                .getDeclaredMethod("isWeatherAffected",net.minecraft.world.level.Level.class,BlockPos.class,boolean.class);
            weatherEffects.setAccessible(true);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            check((Boolean)weatherEffects.invoke(null,mc.level,pos,false),"biome weather follows local wet column");
            check(!(Boolean)weatherEffects.invoke(null,mc.level,BlockPos.containing(1000000,pos.getY(),1000000),false),
                "biome weather rejects dry local column despite camera weather");
            var underground=ground;
            check(!mc.level.canSeeSky(underground),"roof fixture is an actual loaded solid ground block");
            check(!(Boolean)weatherEffects.invoke(null,mc.level,underground,true),"upstream roof restriction remains active");
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(false);
            check(!(Boolean)weatherEffects.invoke(null,mc.level,pos,false),"biome root switch disables weather effects");
            var fog=Class.forName("com.thedeathlycow.immersive.storms.world.StormFogModifier")
                .getMethod("shouldApply",net.minecraft.world.level.Level.class);
            check(!(Boolean)fog.invoke(null,mc.level),"biome root switch disables upstream storm fog");
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WeatherProbe").info(
                "[WEATHER-INTEGRATION] actual CustomParticle/native REGISTERED ownership/local wet retention/dry boundary/ORIGINAL handoff/zero budget/unrelated vanilla preservation/disconnect counters PASS");
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WeatherProbe").info(
                "[BIOME-WEATHER] actual upstream wet/dry local gates/roof restriction/root disable/fog disable PASS");
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Actual upstream weather fixture failed",e);}
        finally {
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(oldMode);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(oldBudget);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(oldBiomes);
            WeatherLibraryControl.tick();
        }
    }
    private static void verifyWind(Minecraft mc,BlockPos pos,Class<?> dataType) throws ReflectiveOperationException {
        Object data=dataType.getConstructor().newInstance();
        dataType.getField("windStrength").set(data,.125f);
        dataType.getField("stormWindStrength").set(data,.75f);
        var windClass=Class.forName("pigcart.particlerain.WindManager");
        var windMethod=Class.forName("pigcart.particlerain.ParticleRain").getMethod("getWind",double.class,double.class,double.class);
        var access=Class.forName("pigcart.particlerain.mixin.access.ParticleAccessor");
        var xd=access.getMethod("getXd");var zd=access.getMethod("getZd");
        var setXd=access.getMethod("setXd",double.class);var setZd=access.getMethod("setZd",double.class);
        Particle fixture=mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,0,0,0);
        check(fixture!=null,"wind fixture exists");
        try {
            for(boolean wet:new boolean[]{true,false}) {
                double x=wet?pos.getX()+.5:1000000.5,z=wet?pos.getZ()+.5:1000000.5,y=pos.getY()+.5;
                fixture.setPos(x,y,z);
                var sample=LocalWeatherEffects.at(mc.level,BlockPos.containing(x,y,z));
                check(sample.localAuthority() && (wet?sample.thunder()>0:sample.thunder()==0),"wind fixture local wet/dry authority");
                var wind=(org.joml.Vector3f)windMethod.invoke(null,x,y,z);
                check(Math.hypot(wind.x,wind.z)>1e-8,"nonzero wind prevents vacuous velocity check");
                check(Math.abs(wind.x*sample.windZ()-wind.z*sample.windX())<1e-6,"shared horizontal wind direction");
                float strength=wet?.75f:.125f;
                for(String method:new String[]{"applyWind","applySpawnWind"}) {
                    setXd.invoke(fixture,.31d);setZd.invoke(fixture,-.27d);
                    windClass.getMethod(method,Particle.class,dataType,net.minecraft.client.multiplayer.ClientLevel.class)
                            .invoke(null,fixture,data,mc.level);
                    float factor=strength*(method.equals("applySpawnWind")?10:1);
                    // Match upstream float multiplication before addition to double velocity.
                    check(Math.abs(((Number)xd.invoke(fixture)).doubleValue()-(.31d+wind.x*factor))<1e-6,
                            method+" local "+(wet?"storm":"normal")+" X velocity");
                    check(Math.abs(((Number)zd.invoke(fixture)).doubleValue()-(-.27d+wind.z*factor))<1e-6,
                            method+" local "+(wet?"storm":"normal")+" Z velocity");
                }
            }
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WeatherProbe").info(
                    "[WEATHER-WIND] actual nonzero shared direction/local storm and dry-column strengths/spawn and per-tick velocity PASS");
        } finally {fixture.remove();}
    }
    private static void check(boolean valid,String step) {if(!valid) throw new IllegalStateException("Weather integration: "+step);}

    private static java.util.function.IntSupplier activeParticleCounter() throws ReflectiveOperationException {
        if(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("asyncparticles")) {
            var atomic=(java.util.concurrent.atomic.AtomicInteger)Class.forName(
                "fun.qu_an.minecraft.asyncparticles.client.compat.particlerain.ParticleRainCompat")
                .getField("particleCount").get(null);
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WeatherProbe").info(
                "[WEATHER-COUNTER] actual AsyncParticles atomic lifetime counter");
            return atomic::get;
        }
        var field=Class.forName("pigcart.particlerain.ParticleSpawner").getField("particleCount");
        return () -> {
            try {return field.getInt(null);}
            catch(IllegalAccessException failure) {throw new IllegalStateException("Weather counter unavailable",failure);}
        };
    }

    private static void verifyMixedBudget(Minecraft mc,BlockPos pos,java.lang.reflect.Constructor<?> rainConstructor,
            Object rain,java.util.function.IntSupplier counter) throws ReflectiveOperationException {
        var oldMode=SimpleCloudsConfig.CLIENT.precipitationRenderer.get();
        int oldBudget=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get();
        boolean oldBiomes=SimpleCloudsConfig.CLIENT.biomeStormEffects.get();
        var created=new java.util.ArrayList<Particle>();
        int initialCounter=counter.getAsInt();
        try {
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.PARTICLE);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(2);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            WeatherLibraryControl.tick();OwnedWeatherParticles.tick(mc.level);
            check(OwnedWeatherParticles.size()==0,"mixed fixture begins with no live owned particles");
            var options=(net.minecraft.core.particles.ParticleOptions)Class.forName(
                "com.thedeathlycow.immersive.storms.particle.DustGrainParticleEffect")
                .getConstructor(org.joml.Vector3fc.class,float.class).newInstance(new org.joml.Vector3f(.8f,.6f,.4f),1f);
            Particle d1=mc.particleEngine.createParticle(options,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,-1,0,0);
            check(d1!=null,"first native dust provider");created.add(d1);
            Particle r1=(Particle)rainConstructor.newInstance(mc.level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,rain);created.add(r1);
            check(d1.isAlive() && r1.isAlive() && OwnedWeatherParticles.size()==2,"both producers share two slots");
            Particle d2=mc.particleEngine.createParticle(options,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,-1,0,0);
            check(d2!=null,"second native dust provider");created.add(d2);
            check(!d1.isAlive() && r1.isAlive() && d2.isAlive() && OwnedWeatherParticles.size()==2,
                "native creation evicts oldest weather particle, not a separate native cap");
            Particle r2=(Particle)rainConstructor.newInstance(mc.level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,rain);created.add(r2);
            check(!r1.isAlive() && d2.isAlive() && r2.isAlive() && OwnedWeatherParticles.size()==2,
                "rain creation evicts oldest rain once, remains within combined cap");
            check(counter.getAsInt()==initialCounter+1,"evicted native/rain objects preserve exact upstream counter");
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(false);OwnedWeatherParticles.tick(mc.level);
            check(!d2.isAlive() && r2.isAlive() && OwnedWeatherParticles.size()==1,
                "biome root disables dust, preserves independently enabled rain");
            check(counter.getAsInt()==initialCounter+1,"biome disable does not decrement rain counter");
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            Particle d3=mc.particleEngine.createParticle(options,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,-1,0,0);
            check(d3!=null,"third native provider");created.add(d3);
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.ORIGINAL);
            WeatherLibraryControl.tick();OwnedWeatherParticles.tick(mc.level);
            check(!r2.isAlive() && d3.isAlive() && OwnedWeatherParticles.size()==1,
                "ORIGINAL handoff clears rain but preserves biome dust");
            check(counter.getAsInt()==initialCounter,"handoff restores upstream rain counter exactly");
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WeatherProbe").info(
                "[WEATHER-MIXED-BUDGET] PASS actual native dust+CustomParticle cap2, alternating eviction, exact upstream counter and independent root/mode handoffs");
        } finally {
            for(var particle:created)particle.remove();
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(oldMode);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(oldBudget);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(oldBiomes);
            WeatherLibraryControl.tick();OwnedWeatherParticles.tick(mc.level);
        }
    }

    private static void verifyFog(Minecraft mc) throws ReflectiveOperationException {
        var type=Class.forName("com.thedeathlycow.immersive.storms.world.StormFogModifier");
        var shouldApply=type.getMethod("shouldApply",net.minecraft.world.level.Level.class);
        var color=type.getMethod("sampleWeatherFogColor",net.minecraft.client.multiplayer.ClientLevel.class,
                net.minecraft.world.phys.Vec3.class,float.class,org.joml.Vector3fc.class);
        var pos=mc.gameRenderer.mainCamera().position();
        var wet=LocalWeatherEffects.at(mc.level,BlockPos.containing(pos));
        check(wet.localAuthority() && wet.rain()>0,"fog camera is in local rain");
        float oldRain=mc.level.getRainLevel(1),oldThunder=mc.level.getThunderLevel(1);
        boolean oldBiomes=SimpleCloudsConfig.CLIENT.biomeStormEffects.get();
        try {
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            mc.level.setRainLevel(0);mc.level.setThunderLevel(0);
            check(mc.level.getRainLevel(1)==0,"fog test has no global rain");
            check((Boolean)shouldApply.invoke(null,mc.level),"local fog eligibility despite globally clear weather");
            var dry=new net.minecraft.world.phys.Vec3(1000000.5,pos.y,1000000.5);
            check(LocalWeatherEffects.at(mc.level,BlockPos.containing(dry)).rain()==0,"fog dry sample is outside local storm");
            mc.level.setRainLevel(1);mc.level.setThunderLevel(1);
            var original=new org.joml.Vector3f(.8f,.7f,.6f);
            var actual=(org.joml.Vector3fc)color.invoke(null,mc.level,dry,1f,original);
            check(original.distance(actual)<1e-5,"dry fog color unchanged despite globally stormy weather");
            var config=Class.forName("com.thedeathlycow.immersive.storms.ImmersiveStormsClient")
                    .getMethod("getConfig").invoke(null);
            check(((Number)config.getClass().getMethod("getFogDistanceMultiplier").invoke(config)).floatValue()==1f,
                    "fog distance fixture uses upstream identity multiplier");
            var data=new net.minecraft.client.renderer.fog.FogData();
            data.environmentalStart=8;data.environmentalEnd=128;data.skyEnd=256;data.cloudEnd=192;
            type.getMethod("applyStartEndModifier",net.minecraft.client.renderer.fog.FogData.class,
                    net.minecraft.world.phys.Vec3.class,net.minecraft.client.multiplayer.ClientLevel.class,
                    net.minecraft.client.DeltaTracker.class).invoke(null,data,dry,mc.level,mc.getDeltaTracker());
            check(data.environmentalEnd==128 && data.skyEnd==256 && data.cloudEnd==192,
                    "dry fog distances unchanged despite globally stormy weather");
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/WeatherProbe").info(
                    "[WEATHER-FOG] actual local eligibility with global clear/dry color and distances with global storm PASS");
        } finally {
            mc.level.setRainLevel(oldRain);mc.level.setThunderLevel(oldThunder);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(oldBiomes);
        }
    }
}
