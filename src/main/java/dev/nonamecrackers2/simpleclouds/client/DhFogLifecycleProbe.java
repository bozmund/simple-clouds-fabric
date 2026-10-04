package dev.nonamecrackers2.simpleclouds.client;

import java.lang.reflect.Method;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraft.client.Minecraft;

/** Actual DH API-state fixture. Only the named scratch save and explicit opt-in. */
public final class DhFogLifecycleProbe {
    private static boolean done;
    private static int ticks;
    private record ReloadState(Object[] slots,Object[] previous,Method get,Method set,Method clear,
        java.util.concurrent.CompletableFuture<Void> future,Object world,boolean enabled) {}
    private static ReloadState reloadState;
    private static boolean reloadRestored;
    private DhFogLifecycleProbe() {}
    public static void tick(Minecraft mc) {
        if(!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_DH_FOG_LIFECYCLE")))return;
        if(reloadState!=null) { finishReload(mc);return; }
        if(done)return;
        if(mc.level==null || mc.getSingleplayerServer()==null || ++ticks<100)return;
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals(
                "/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3")
                || !mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .normalize().getFileName().toString().equals("CodexFullPackTest"))
            throw new IllegalStateException("DH fog fixture requires exact scratch profile/save");
        try {
            Object config=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
            if(config==null) {
                if(ticks>600)throw new IllegalStateException("DH API was not initialized");
                return;
            }
            done=true;
            Object graphics=call(config,"graphics"),fog=call(graphics,"fog"),far=call(fog,"farFog");
            Object[] values={call(far,"farFogMinThickness"),call(far,"farFogMaxThickness"),call(fog,"enableVanillaFog")};
            Class<?> api=Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
            Method get=api.getMethod("getApiValue"),set=api.getMethod("setValue",Object.class),clear=api.getMethod("clearValue");
            Object[] previous=new Object[3];
            for(int i=0;i<3;i++)previous[i]=get.invoke(values[i]);
            boolean enabled=SimpleCloudsConfig.CLIENT.biomeStormEffects.get();
            var modifier=Class.forName("com.thedeathlycow.immersive.storms.world.StormFogModifier");
            var apply=modifier.getDeclaredMethod("setFogDistanceForDistantHorizons",float.class);
            apply.setAccessible(true);
            try {
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
                apply.invoke(null,.5f);
                check(get.invoke(values[0])!=null && get.invoke(values[1])!=null
                    && Boolean.TRUE.equals(get.invoke(values[2])),"actual storm overrides installed");
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(false);
                boolean eligible=(Boolean)modifier.getMethod("shouldApply",net.minecraft.world.level.Level.class).invoke(null,mc.level);
                check(!eligible,"root-disabled storm fog is ineligible");
                boolean retained=get.invoke(values[0])!=null && get.invoke(values[1])!=null
                    && Boolean.TRUE.equals(get.invoke(values[2]));
                org.slf4j.LoggerFactory.getLogger(DhFogLifecycleProbe.class).info(
                    "[DH-FOG-LIFECYCLE] root-disabled eligible={} retainedOverrides={} outcome={}",
                    eligible,retained,retained?"REPRODUCED_STALE_OVERRIDE":"CLEARED");
                for(int i=0;i<3;i++)check(java.util.Objects.equals(get.invoke(values[i]),previous[i]),"root disable restores prior override "+i);
                Object[] prior={.25f,.5f,Boolean.FALSE};
                for(int i=0;i<3;i++)check((Boolean)set.invoke(values[i],prior[i]),"seed prior override "+i);
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
                apply.invoke(null,.5f);
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(false);
                modifier.getMethod("shouldApply",net.minecraft.world.level.Level.class).invoke(null,mc.level);
                for(int i=0;i<3;i++)check(java.util.Objects.equals(get.invoke(values[i]),prior[i]),"preserve preexisting override "+i);
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
                apply.invoke(null,.5f);
                var camera=mc.gameRenderer.mainCamera();
                var cameraPosition=camera.position();
                check(dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects.at(mc.level,
                    net.minecraft.core.BlockPos.containing(cameraPosition)).rain()>0,"camera starts inside local rain");
                check((Boolean)modifier.getMethod("shouldApply",net.minecraft.world.level.Level.class).invoke(null,mc.level),
                    "wet camera starts eligible for storm fog");
                var move=net.minecraft.client.Camera.class.getDeclaredMethod("setPosition",net.minecraft.world.phys.Vec3.class);
                move.setAccessible(true);
                try {
                    move.invoke(camera,new net.minecraft.world.phys.Vec3(1000000.5,cameraPosition.y,1000000.5));
                    check(dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects.at(mc.level,
                        camera.blockPosition()).rain()==0,"camera reaches a dry local column");
                    check(!(Boolean)modifier.getMethod("shouldApply",net.minecraft.world.level.Level.class).invoke(null,mc.level),
                        "dry camera has no local fog eligibility");
                    for(int i=0;i<3;i++)check(java.util.Objects.equals(get.invoke(values[i]),prior[i]),"dry camera restores override "+i);
                } finally {move.invoke(camera,cameraPosition);}
                org.slf4j.LoggerFactory.getLogger(DhFogLifecycleProbe.class).info(
                    "[DH-FOG-LIFECYCLE] PASS actual dry-camera eligibility and owned override release");
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
                apply.invoke(null,.5f);
                check((Boolean)set.invoke(values[0],.33f),"simulate newer unrelated override");
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(false);
                modifier.getMethod("shouldApply",net.minecraft.world.level.Level.class).invoke(null,mc.level);
                check(java.util.Objects.equals(get.invoke(values[0]),.33f),"newer unrelated override preserved");
                check(java.util.Objects.equals(get.invoke(values[1]),prior[1])
                    && java.util.Objects.equals(get.invoke(values[2]),prior[2]),"remaining owned overrides restored");
                org.slf4j.LoggerFactory.getLogger(DhFogLifecycleProbe.class).info(
                    "[DH-FOG-LIFECYCLE] PASS root disable/preexisting overrides/newer unrelated override ownership");
            } finally {
                for(int i=0;i<3;i++) {
                    boolean restored=(Boolean)(previous[i]==null?clear.invoke(values[i]):set.invoke(values[i],previous[i]));
                    check(restored && java.util.Objects.equals(get.invoke(values[i]),previous[i]),"restore prior DH override "+i);
                }
                SimpleCloudsConfig.CLIENT.biomeStormEffects.set(enabled);
            }
            org.slf4j.LoggerFactory.getLogger(DhFogLifecycleProbe.class).info(
                "[DH-FOG-LIFECYCLE] PASS fixture completed and all prior API overrides restored");
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
            apply.invoke(null,.5f);
            reloadRestored=false;
            reloadState=new ReloadState(values,previous,get,set,clear,mc.reloadResourcePacks(),mc.level,enabled);
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Pinned DH fog fixture contract changed",e); }
    }
    /** Called after renderer resource application; dormant outside the fixture. */
    public static void resourcesReloaded() {
        var state=reloadState;if(state==null)return;
        try {
            reloadRestored=true;
            for(int i=0;i<3;i++)reloadRestored &= java.util.Objects.equals(state.get.invoke(state.slots[i]),state.previous[i]);
            org.slf4j.LoggerFactory.getLogger(DhFogLifecycleProbe.class).info(
                "[DH-FOG-LIFECYCLE] actual resource application restoredOverrides={}",reloadRestored);
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("DH resource reload fixture failed",e);}
    }
    private static void finishReload(Minecraft mc) {
        var state=reloadState;
        if(!state.future.isDone()) {
            if(++ticks>600)throw new IllegalStateException("DH resource reload fixture timed out");
            return;
        }
        try {
            state.future.join();
            check(mc.level==state.world,"resource reload preserves world identity");
            check(reloadRestored,"resource application releases active DH overrides");
            org.slf4j.LoggerFactory.getLogger(DhFogLifecycleProbe.class).info(
                "[DH-FOG-LIFECYCLE] PASS actual resource reload and active override cleanup");
        } finally {
            dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveDhFogLifecycle.release();
            try {
                for(int i=0;i<3;i++) {
                    if(state.previous[i]==null)state.clear.invoke(state.slots[i]);
                    else state.set.invoke(state.slots[i],state.previous[i]);
                    check(java.util.Objects.equals(state.get.invoke(state.slots[i]),state.previous[i]),"post-reload restore "+i);
                }
            } catch(ReflectiveOperationException e) {throw new IllegalStateException("Cannot restore DH reload fixture",e);}
            finally {SimpleCloudsConfig.CLIENT.biomeStormEffects.set(state.enabled);reloadState=null;}
        }
    }
    private static Object call(Object target,String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }
    private static void check(boolean valid,String step) {
        if(!valid)throw new IllegalStateException("DH fog lifecycle: "+step);
    }
}
