package dev.nonamecrackers2.simpleclouds.client;

import java.util.ArrayList;
import java.util.Map;
import dev.nonamecrackers2.simpleclouds.common.config.PrecipitationRenderer;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.client.compat.WeatherLibraryControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Explicit scratch fixture exercising the installed native spawner and collision code. */
public final class NativeRainRoofProbe {
    private static final boolean ENABLED="1".equals(System.getenv("SIMPLECLOUDS_DEV"))
        && "1".equals(System.getenv("SIMPLECLOUDS_TEST_NATIVE_RAIN_ROOF"));
    private record Spawn(Particle particle,double y) {}
    private static ArrayList<Spawn> capture;
    private static Thread captureOwner;
    private record Pending(int roofY,boolean covered,String phase,long since) {}
    private static Pending pending;
    private NativeRainRoofProbe() {}
    public static boolean enabled() {return ENABLED;}
    public static boolean resume(Minecraft mc) {
        if(!ENABLED || pending==null)return true;
        var request=pending;
        if(mc.level==null || mc.level.getGameTime()-request.since()>200)
            throw new IllegalStateException("Native roof cache did not settle within200 actual world ticks");
        verify(mc,request.roofY(),request.covered(),request.phase());
        return pending==null;
    }
    public static void spawned(Particle particle,double y) {
        if(!ENABLED || capture==null || captureOwner!=Thread.currentThread())return;
        if(capture.size()>=4096)throw new IllegalStateException("Native roof fixture spawn bound exceeded");
        capture.add(new Spawn(particle,y));
    }
    public static void verify(Minecraft mc,int roofY,boolean covered,String phase) {
        if(!ENABLED)return;
        if(mc.level==null || mc.player==null || mc.getSingleplayerServer()==null
            || !mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals(
                "/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3")
            || !mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .normalize().getFileName().toString().equals("CodexGlassRoof20261001"))
            throw new IllegalStateException("Native rain roof probe requires exact fullpack glass-roof scratch save");
        var oldMode=SimpleCloudsConfig.CLIENT.precipitationRenderer.get();
        int oldBudget=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get();
        Object perf=null;java.lang.reflect.Field distance=null,idle=null;
        Object oldDistance=null;int oldIdle=0;
        var spawned=new ArrayList<Spawn>();
        Particle collisionParticle=null;
        try {
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.PARTICLE);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(1500);
            WeatherLibraryControl.tick();
            var spawner=Class.forName("pigcart.particlerain.ParticleSpawner");
            var cfg=Class.forName("pigcart.particlerain.config.ConfigManager").getMethod("getConfig").invoke(null);
            perf=cfg.getClass().getField("perf").get(cfg);
            distance=perf.getClass().getField("particleDistance");oldDistance=distance.get(perf);
            distance.set(perf,2);
            idle=spawner.getField("ticksUntilSkyFXIdle");oldIdle=idle.getInt(null);
            int x=mc.player.blockPosition().getX(),z=mc.player.blockPosition().getZ();
            int height=(Integer)spawner.getMethod("getHeight",net.minecraft.client.multiplayer.ClientLevel.class,int.class,int.class)
                .invoke(null,mc.level,x,z);
            int calculated=(Integer)spawner.getMethod("calculateHeight",net.minecraft.client.multiplayer.ClientLevel.class,int.class,int.class)
                .invoke(null,mc.level,x,z);
            check(covered?calculated==roofY+1:calculated<68,"fresh native shelter height: "+calculated);
            if(height!=calculated) {
                if(pending==null) {
                    pending=new Pending(roofY,covered,phase,mc.level.getGameTime());
                    org.slf4j.LoggerFactory.getLogger(NativeRainRoofProbe.class).info(
                        "[NATIVE-RAIN-ROOF] {} waiting for upstream80tick cache cached={} fresh={} worldTick={}",phase,height,calculated,mc.level.getGameTime());
                }
                return; // Subsequent real frames call getHeight; no forced invalidation/time jump.
            }
            if(pending!=null)org.slf4j.LoggerFactory.getLogger(NativeRainRoofProbe.class).info(
                "[NATIVE-RAIN-ROOF] {} actual cache settled after={} world ticks",phase,mc.level.getGameTime()-pending.since());
            pending=null;
            check(covered?height==roofY+1:height<68,"actual native shelter height: "+height);
            var tick=spawner.getMethod("tickSkyFX",net.minecraft.client.multiplayer.ClientLevel.class,Vec3.class);
            capture=spawned;captureOwner=Thread.currentThread();
            // Every candidate is within two blocks of y68, safely below the y74 roof.
            for(int i=0;i<64;i++) {idle.setInt(null,0);tick.invoke(null,mc.level,new Vec3(x+.5,68,z+.5));}
            capture=null;captureOwner=null;
            check(covered?spawned.isEmpty():!spawned.isEmpty(),"native below-roof sky spawn count="+spawned.size());
            for(var entry:spawned)check(entry.y()<roofY,"fixture unexpectedly sampled above roof");
            if(covered) {
                var dataType=Class.forName("pigcart.particlerain.config.ParticleData");
                var particles=(Map<?,?>)Class.forName("pigcart.particlerain.ParticleLoader").getField("particles").get(null);
                Object rain=particles.values().stream().filter(data -> {
                    try {return "rain".equals(dataType.getField("id").get(data));}
                    catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
                }).findFirst().orElseThrow();
                var custom=Class.forName("pigcart.particlerain.particle.CustomParticle");
                collisionParticle=(Particle)custom.getConstructor(net.minecraft.client.multiplayer.ClientLevel.class,
                    double.class,double.class,double.class,dataType).newInstance(mc.level,x+.5,roofY+1.001,z+.5,rain);
                check(collisionParticle.isAlive(),"actual rain collision fixture must be alive");
                custom.getMethod("tickCollisions").invoke(collisionParticle);
                var collision=custom.getDeclaredField("collision");collision.setAccessible(true);
                var hit=(BlockHitResult)collision.get(collisionParticle);
                check(hit!=null && hit.getBlockPos().getY()==roofY
                    && mc.level.getBlockState(hit.getBlockPos()).is(net.minecraft.world.level.block.Blocks.GLASS),
                    "actual native rain collision must hit glass roof");
            }
            org.slf4j.LoggerFactory.getLogger(NativeRainRoofProbe.class).info(
                "[NATIVE-RAIN-ROOF] {} PASS actual sky spawner belowRoof={} height={} glassCollision={} columnX={}",
                phase,spawned.size(),height,covered,x);
        } catch(ReflectiveOperationException error){throw new IllegalStateException("Native rain roof fixture failed",error);}
        finally {
            capture=null;captureOwner=null;
            for(var entry:spawned)entry.particle().remove();
            if(collisionParticle!=null)collisionParticle.remove();
            try {if(distance!=null && oldDistance!=null)distance.set(perf,oldDistance);if(idle!=null)idle.setInt(null,oldIdle);}
            catch(ReflectiveOperationException error){throw new IllegalStateException("Native roof config restoration failed",error);}
            finally {
                SimpleCloudsConfig.CLIENT.precipitationRenderer.set(oldMode);
                SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(oldBudget);
                WeatherLibraryControl.tick();
            }
        }
    }
    private static void check(boolean condition,String message) {
        if(!condition)throw new IllegalStateException("Native rain roof: "+message);
    }
}
