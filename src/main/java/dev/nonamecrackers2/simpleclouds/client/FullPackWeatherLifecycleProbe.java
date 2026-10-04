package dev.nonamecrackers2.simpleclouds.client;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveDhFogLifecycle;
import dev.nonamecrackers2.simpleclouds.common.config.*;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;

/** Owned full-modpack scratch world; actual respawns, save/unload and normal reopen flow. */
public final class FullPackWeatherLifecycleProbe {
    private static final boolean ENABLED="1".equals(System.getenv("SIMPLECLOUDS_DEV"))
        && "1".equals(System.getenv("SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE"));
    private static final boolean SUSTAINED=ENABLED && "1".equals(System.getenv("SIMPLECLOUDS_TEST_WEATHER_SUSTAINED"));
    private static final int RESIDENCE_TICKS=SUSTAINED?6000:400;
    private static int stage,ticks,residence;
    private static long seed;
    private static Object[] slots,previous;
    private static Method get,apply;
    private static CompletableFuture<Void> transfer;
    private static PrecipitationRenderer mode;
    private static int budget;
    private static boolean root;
    private FullPackWeatherLifecycleProbe() {}
    private static void check(boolean valid,String message) {if(!valid)throw new IllegalStateException("Full-pack weather lifetime: "+message);}
    private static Object call(Object object,String name) throws ReflectiveOperationException {
        return object.getClass().getMethod(name).invoke(object);
    }
    private static void arm(Minecraft mc) throws ReflectiveOperationException {
        WeatherConnectionProbe.armBeforeDisconnect(mc);
        SimpleCloudsConfig.CLIENT.biomeStormEffects.set(true);
        // Non-vacuous lease from the actual native setter; no manually cleared
        // lease after the subsequent world transition/unload.
        ImmersiveDhFogLifecycle.release();
        Object configs=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
        check(configs!=null,"actual DH configs missing");
        Object fog=call(call(configs,"graphics"),"fog"),far=call(fog,"farFog");
        slots=new Object[]{call(far,"farFogMinThickness"),call(far,"farFogMaxThickness"),call(fog,"enableVanillaFog")};
        get=Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue").getMethod("getApiValue");
        previous=new Object[3];for(int i=0;i<3;i++)previous[i]=get.invoke(slots[i]);
        apply=Class.forName("com.thedeathlycow.immersive.storms.world.StormFogModifier")
            .getDeclaredMethod("setFogDistanceForDistantHorizons",float.class);apply.setAccessible(true);
        apply.invoke(null,.5f);
        boolean changed=false;for(int i=0;i<3;i++)changed|=!Objects.equals(previous[i],get.invoke(slots[i]));
        check(changed,"DH lease fixture was vacuous");
    }
    private static void restored() throws ReflectiveOperationException {
        for(int i=0;i<3;i++)check(Objects.equals(previous[i],get.invoke(slots[i])),"old-world DH override retained slot="+i);
    }
    private static void move(Minecraft mc,net.minecraft.resources.ResourceKey<Level> dimension) {
        move(mc,dimension,0);
    }
    private static void move(Minecraft mc,net.minecraft.resources.ResourceKey<Level> dimension,double x) {
        var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
        transfer=new CompletableFuture<>();
        server.execute(()->{
            try {
                var player=server.getPlayerList().getPlayer(id);check(player!=null,"scratch player missing");
                player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                var target=server.getLevel(dimension);check(target!=null,"target dimension missing");
                check(player.teleportTo(target,x,100,0,Set.of(),0,0,false),"scratch teleport rejected");transfer.complete(null);
            } catch(Throwable failure) {transfer.completeExceptionally(failure);}
        });
    }
    public static void tick(Minecraft mc) {
        if(!ENABLED || stage==99)return;
        try {
            check(mc!=null && mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals(
                "/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3"),"exact owned profile required");
            if(stage==0) {
                if(mc.level==null || mc.player==null || mc.getSingleplayerServer()==null)return;
                check(mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .normalize().getFileName().toString().equals("CodexFullPackTest"),"exact scratch save required");
                if(++ticks%20!=0)return;
                try(var files=java.nio.file.Files.list(mc.gameDirectory.toPath().resolve("screenshots"))) {
                    if(files.filter(path->path.getFileName().toString().startsWith("devshot-SHAKE-")).count()!=81)return;
                }
                mode=SimpleCloudsConfig.CLIENT.precipitationRenderer.get();budget=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get();
                root=SimpleCloudsConfig.CLIENT.biomeStormEffects.get();seed=CloudManager.get(mc.level).getSeed();
                arm(mc);move(mc,Level.NETHER);stage=1;ticks=0;
                log("armed actual weather/history/DH; transferring to Nether");return;
            }
            if(++ticks>(stage==5?RESIDENCE_TICKS+1800:1800))throw new IllegalStateException("Full-pack lifecycle timeout stage="+stage);
            if(transfer!=null && transfer.isCompletedExceptionally())transfer.join();
            if(stage==1 && mc.level!=null && mc.level.dimension().equals(Level.NETHER)) {
                WeatherConnectionProbe.changedDimension(mc);restored();
                log("Nether actual AsyncParticles counter/history and DH restoration PASS");
                arm(mc);move(mc,Level.OVERWORLD);stage=2;ticks=0;
            } else if(stage==2 && mc.level!=null && mc.level.dimension().equals(Level.OVERWORLD)
                    && CloudManager.get(mc.level).getSeed()==seed) {
                WeatherConnectionProbe.changedDimension(mc);restored();
                log("Overworld return original seed and DH restoration PASS");
                arm(mc);stage=3;ticks=0;
                mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Full-pack weather lifecycle test"));
            } else if(stage==3 && mc.level==null) {
                WeatherConnectionProbe.disconnected(mc);restored();
                log("actual first unload cleared AsyncParticles/history/DH PASS");stage=4;ticks=0;
            } else if(stage==4 && mc.level==null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
                mc.createWorldOpenFlows().openWorld("CodexFullPackTest",()->{});stage=5;ticks=0;
            } else if(stage==5 && mc.level!=null && mc.player!=null && mc.getSingleplayerServer()!=null
                    && CloudManager.get(mc.level).getSeed()==seed) {
                if(residence++==0) {WeatherConnectionProbe.connected(mc);log("same scratch save reopened with original seed");}
                WeatherConnectionProbe.currentBounds(mc);
                if(SUSTAINED && residence%500==0 && residence<RESIDENCE_TICKS) {
                    check(transfer==null || transfer.isDone(),"previous travel still pending");
                    double x=residence%1000==0?-2048:2048;
                    move(mc,Level.OVERWORLD,x);
                    log("same-level scratch travel requested x="+x+" residence="+residence);
                }
                if(residence%100==0)log("bounded residence ticks="+residence+" heapMiB="+
                    java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()/1048576);
                if(residence>=RESIDENCE_TICKS) {
                    arm(mc);stage=6;ticks=0;
                    mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Full-pack weather lifecycle test complete"));
                }
            } else if(stage==6 && mc.level==null) {
                WeatherConnectionProbe.disconnected(mc);restored();restoreConfig();stage=99;
                log("PASS actual Nether/back/two save-unloads/reopen/"+RESIDENCE_TICKS+"tick bounded residence/AsyncParticles counter/history/DH lease cleanup");
            }
        } catch(Throwable failure) {
            restoreConfig();stage=99;
            org.slf4j.LoggerFactory.getLogger(FullPackWeatherLifecycleProbe.class).error(
                "Simple Clouds ERROR: full-pack weather lifecycle failed",failure);
        }
    }
    private static void restoreConfig() {
        if(mode!=null) {
            SimpleCloudsConfig.CLIENT.precipitationRenderer.set(mode);
            SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(budget);
            SimpleCloudsConfig.CLIENT.biomeStormEffects.set(root);
        }
    }
    private static void log(String message) {
        org.slf4j.LoggerFactory.getLogger(FullPackWeatherLifecycleProbe.class).info("[FULLPACK-WEATHER-LIFETIME] {}",message);
    }
}
