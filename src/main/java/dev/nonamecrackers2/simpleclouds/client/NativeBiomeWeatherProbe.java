package dev.nonamecrackers2.simpleclouds.client;

import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Native optional-library gate reproduction, never an audible-output assertion. */
public final class NativeBiomeWeatherProbe {
    private static boolean done;
    private static int ticks;
    private NativeBiomeWeatherProbe() {}
    public static void tick(Minecraft mc) {
        if(done || !"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER")))return;
        if(mc.level==null || mc.getSingleplayerServer()==null || ++ticks<100)return;
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals(
            "/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3")
            || !mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .normalize().getFileName().toString().equals("CodexFullPackTest"))
            throw new IllegalStateException("Native biome fixture requires exact scratch profile/save");
        var sample=LocalWeatherEffects.at(mc.level,BlockPos.containing(mc.gameRenderer.mainCamera().position()));
        if(!sample.localAuthority() || sample.rain()<.7f) {
            if(ticks>600)throw new IllegalStateException("Native biome fixture never entered a strong local storm");
            return;
        }
        done=true;
        float rain=mc.level.getRainLevel(1),thunder=mc.level.getThunderLevel(1);
        boolean enabled=SimpleCloudsConfig.CLIENT.biomeStormEffects.get();
        var oldMode=SimpleCloudsConfig.CLIENT.precipitationRenderer.get();
        int oldBudget=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get();
        var fixtureParticles=new java.util.ArrayList<net.minecraft.client.particle.Particle>();
        try {
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            mc.level.setRainLevel(0);mc.level.setThunderLevel(0);
            check(mc.level.getRainLevel(1)==0,"global rain is clear");
            var type=Class.forName("com.thedeathlycow.immersive.storms.util.WeatherEffectType");
            Object sand=type.getField("SANDSTORM").get(null);
            Object data=type.getMethod("getWeatherData",net.minecraft.world.level.Level.class).invoke(sand,mc.level);
            var sounds=Class.forName("com.thedeathlycow.immersive.storms.world.SandstormSounds");
            Object instance=sounds.getConstructor().newInstance();
            var timer=sounds.getDeclaredField("timer");timer.setAccessible(true);timer.setInt(instance,-100);
            sounds.getMethod("onEndTick",net.minecraft.client.multiplayer.ClientLevel.class).invoke(instance,mc.level);
            check(timer.getInt(instance)==-99,"native wind sound gate advances in local storm with global clear");
            var camera=mc.gameRenderer.mainCamera();
            var pos=BlockPos.containing(camera.position());
            Object localData=dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveWeatherBridge.data(sand,mc.level,pos,()->null);
            check(localData!=null,"native sandstorm data selected from local storm");
            var counter=dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveWeatherBridge.class.getDeclaredField("nativeSoundSelections");
            counter.setAccessible(true);int before=counter.getInt(null);
            var choose=sounds.getDeclaredMethod("chooseSpotForWindSound",net.minecraft.client.multiplayer.ClientLevel.class,net.minecraft.client.Camera.class);
            choose.setAccessible(true);choose.invoke(instance,mc.level,camera);
            check(counter.getInt(null)==before+1,"actual native sound-position call executes local data adapter");
            mc.level.setRainLevel(1);mc.level.setThunderLevel(1);
            Object dry=dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveWeatherBridge.data(sand,mc.level,
                BlockPos.containing(1000000.5,camera.position().y,1000000.5),()->new Object());
            check(dry==null,"dry local column rejects global storm data");
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(false);timer.setInt(instance,-100);
            sounds.getMethod("onEndTick",net.minecraft.client.multiplayer.ClientLevel.class).invoke(instance,mc.level);
            check(timer.getInt(instance)==-100,"root disable stops native sound gate despite global storm");
            check(dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveWeatherBridge.data(sand,mc.level,pos,()->new Object())==null,
                "root disable suppresses local weather data");
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            var options=(net.minecraft.core.particles.ParticleOptions)Class.forName(
                "com.thedeathlycow.immersive.storms.particle.DustGrainParticleEffect")
                .getConstructor(org.joml.Vector3fc.class,float.class).newInstance(new org.joml.Vector3f(.8f,.6f,.4f),1f);
            var dust=mc.particleEngine.createParticle(options,camera.position().x,camera.position().y,camera.position().z,-1,0,0);
            check(dust!=null && dust.isAlive(),"actual native DustGrain provider creates live particle");
            fixtureParticles.add(dust);
            try {
                var access=Class.forName("pigcart.particlerain.mixin.access.ParticleAccessor");
                double vx=((Number)access.getMethod("getXd").invoke(dust)).doubleValue();
                double vz=((Number)access.getMethod("getZd").invoke(dust)).doubleValue();
                var wind=LocalWeatherEffects.at(mc.level,pos);
                check(Math.hypot(wind.windX(),wind.windZ())>1e-6,"nonzero cloud wind prevents vacuous alignment test");
                check(Math.abs(vx*wind.windZ()-vz*wind.windX())<1e-6
                    && vx*wind.windX()+vz*wind.windZ()>0,"actual native dust follows cloud wind direction");
                check(Math.abs(Math.hypot(vx,vz)-1)<1e-6,"preserve native dust horizontal speed");
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(false);
                dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles.tick(mc.level);
                check(!dust.isAlive(),"root disable removes owned native dust");
                org.slf4j.LoggerFactory.getLogger(NativeBiomeWeatherProbe.class).info(
                    "[NATIVE-DUST] actualProvider={} velocityX={} velocityZ={} windX={} windZ={} alignmentError={} aliveAfterRootDisable={}",
                    dust.getClass().getName(),vx,vz,wind.windX(),wind.windZ(),Math.abs(vx*wind.windZ()-vz*wind.windX()),dust.isAlive());
            } finally {dust.remove();}
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(dev.nonamecrackers2.simpleclouds.common.config.PrecipitationRenderer.ORIGINAL);
            var nativeOnly=mc.particleEngine.createParticle(options,camera.position().x,camera.position().y,camera.position().z,-1,0,0);
            check(nativeOnly!=null,"native dust in ORIGINAL precipitation mode");fixtureParticles.add(nativeOnly);
            dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles.tick(mc.level);
            check(nativeOnly.isAlive(),"ORIGINAL precipitation retains independently enabled biome dust");
            nativeOnly.setPos(1000000.5,camera.position().y,1000000.5);
            dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles.tick(mc.level);
            check(!nativeOnly.isAlive(),"dry boundary removes owned native dust");
            var palette=Class.forName("com.thedeathlycow.immersive.storms.world.BiomeWindEffects$ParticleColor");
            var ambientOptionsMethod=palette.getDeclaredMethod("getParticle");ambientOptionsMethod.setAccessible(true);
            var ambientOptions=(net.minecraft.core.particles.ParticleOptions)ambientOptionsMethod.invoke(palette.getEnumConstants()[0]);
            var ambient=mc.particleEngine.createParticle(ambientOptions,1000000.5,camera.position().y,1000000.5,-1,0,0);
            check(ambient!=null && ambient.isAlive(),"actual native ambient palette/provider creates live particle");fixtureParticles.add(ambient);
            check(LocalWeatherEffects.at(mc.level,BlockPos.containing(1000000.5,camera.position().y,1000000.5)).rain()==0,
                "ambient fixture uses actual dry local column");
            dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles.tick(mc.level);
            check(ambient.isAlive(),"native ambient dust survives dry column unlike storm dust");
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(false);
            dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles.tick(mc.level);
            check(!ambient.isAlive(),"biome root still removes owned ambient dust");
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            org.slf4j.LoggerFactory.getLogger(NativeBiomeWeatherProbe.class).info(
                "[NATIVE-AMBIENT] PASS actual upstream memoized palette/provider, dry survival, storm dry removal and biome root cleanup");
            var unrelated=mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                camera.position().x,camera.position().y,camera.position().z,0,0,0);
            check(unrelated!=null,"unrelated vanilla fixture");fixtureParticles.add(unrelated);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(0);
            var capped=mc.particleEngine.createParticle(options,camera.position().x,camera.position().y,camera.position().z,-1,0,0);
            check(capped!=null,"budgeted native provider result");fixtureParticles.add(capped);
            dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles.tick(mc.level);
            check(!capped.isAlive() && unrelated.isAlive(),"zero joint weather budget removes dust, preserves vanilla particle");
            org.slf4j.LoggerFactory.getLogger(NativeBiomeWeatherProbe.class).info(
                "[NATIVE-DUST] PASS actual provider/shared nonzero wind/native speed/root disable/dry boundary/ORIGINAL independence/zero joint budget/unrelated vanilla preservation");
            NativeWeatherMatrixProbe.verify(mc);
            org.slf4j.LoggerFactory.getLogger(NativeBiomeWeatherProbe.class).info(
                "[NATIVE-BIOME] PASS native sound wet/global-clear gate, actual position adapter, dry/global-storm rejection and root disable");
            org.slf4j.LoggerFactory.getLogger(NativeBiomeWeatherProbe.class).info(
                "[NATIVE-BIOME] upstream global data API preserved; initialGlobalDataPresent={} localDataPresent={}",
                data!=null,localData!=null);
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Pinned native biome fixture failed",e);}
        finally {
            for(var particle:fixtureParticles)particle.remove();
            mc.level.setRainLevel(rain);mc.level.setThunderLevel(thunder);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(enabled);
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(oldMode);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(oldBudget);
            dev.nonamecrackers2.simpleclouds.client.compat.OwnedWeatherParticles.tick(mc.level);
        }
        org.slf4j.LoggerFactory.getLogger(NativeBiomeWeatherProbe.class).info(
            "[NATIVE-BIOME] PASS local weather fixture completed and global weather/config restored");
    }
    private static void check(boolean valid,String step) {if(!valid)throw new IllegalStateException(step);}
}
