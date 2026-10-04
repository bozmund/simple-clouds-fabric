package dev.nonamecrackers2.simpleclouds.client;

import java.lang.ref.WeakReference;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import dev.nonamecrackers2.simpleclouds.client.compat.*;
import dev.nonamecrackers2.simpleclouds.common.config.*;
import dev.nonamecrackers2.simpleclouds.common.world.RecentWetColumns;

/** Real network disconnect/rejoin fixture, not a synthetic tick(null) test. */
public final class WeatherConnectionProbe {
    private static final boolean ENABLED="1".equals(System.getenv("SIMPLECLOUDS_DEV"))
        && ("1".equals(System.getenv("SIMPLECLOUDS_TEST_DEDICATED"))
            && "1".equals(System.getenv("SIMPLECLOUDS_TEST_WEATHER_CONNECTION"))
            || "1".equals(System.getenv("SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE")));
    private static WeakReference<ClientLevel> previous=new WeakReference<>(null);
    private static int clears;
    private WeatherConnectionProbe() {}
    public static boolean enabled() {return ENABLED;}
    private static void require(boolean valid,String message) {if(!valid)throw new AssertionError("Weather connection: "+message);}
    private static java.lang.reflect.Field field(Class<?> type,String name) throws ReflectiveOperationException {
        var result=type.getDeclaredField(name);result.setAccessible(true);return result;
    }
    private static RecentWetColumns history() throws ReflectiveOperationException {
        return (RecentWetColumns)field(ParticleWeatherBridge.class,"recentWet").get(null);
    }
    private static int count() throws ReflectiveOperationException {
        if(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("asyncparticles"))
            return ((java.util.concurrent.atomic.AtomicInteger)Class.forName(
                "fun.qu_an.minecraft.asyncparticles.client.compat.particlerain.ParticleRainCompat")
                .getField("particleCount").get(null)).get();
        return Class.forName("pigcart.particlerain.ParticleSpawner").getField("particleCount").getInt(null);
    }
    public static void connected(Minecraft mc) throws ReflectiveOperationException {
        if(!ENABLED)return;
        String path=mc.gameDirectory.toPath().toAbsolutePath().normalize().toString();
        boolean dedicated=mc.getSingleplayerServer()==null && path
                .matches("/home/jan/simple-clouds-fabric/build/dedicated-client-[AB]")
            && mc.getCurrentServer()!=null && mc.getCurrentServer().ip.equals("127.0.0.1:25575");
        boolean fullPack="1".equals(System.getenv("SIMPLECLOUDS_TEST_FULLPACK_WEATHER_LIFECYCLE"))
            && path.equals("/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3")
            && mc.getSingleplayerServer()!=null && mc.getSingleplayerServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize()
                .getFileName().toString().equals("CodexFullPackTest");
        require(mc.level!=null && (dedicated || fullPack),"exact owned endpoint/scratch save required");
        if(clears>0)require(mc.level!=previous.get(),"rejoin reused previous ClientLevel");
        org.slf4j.LoggerFactory.getLogger(WeatherConnectionProbe.class).info(
            "[WEATHER-CONNECTION] connected freshLevel={} completedDisconnects={}",clears==0 || mc.level!=previous.get(),clears);
    }
    public static void armBeforeDisconnect(Minecraft mc) throws ReflectiveOperationException {
        if(!ENABLED)return;
        connected(mc);
        SimpleCloudsConfig.CLIENT.precipitationRenderer.set(PrecipitationRenderer.PARTICLE);
        SimpleCloudsConfig.CLIENT.weatherParticleBudget.set(1500);WeatherLibraryControl.tick();
        var dataType=Class.forName("pigcart.particlerain.config.ParticleData");
        var weather=dataType.getField("weather");var style=dataType.getField("particleStyle");
        var all=(Map<?,?>)Class.forName("pigcart.particlerain.ParticleLoader").getField("particles").get(null);
        Object data=all.values().stream().filter(candidate->{
            try {return ((Enum<?>)style.get(candidate)).name().equals("CUSTOM");}
            catch(ReflectiveOperationException failure) {throw new IllegalStateException(failure);}
        }).findFirst().orElseThrow();
        var constructor=Class.forName("pigcart.particlerain.particle.CustomParticle").getConstructor(
            ClientLevel.class,double.class,double.class,double.class,dataType);
        var pos=mc.player.position();int before=count();
        Particle particle=(Particle)constructor.newInstance(mc.level,pos.x,pos.y+2,pos.z,data);
        boolean async=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("asyncparticles");
        require(particle.isAlive() && (async?count()>0:count()==before+1),"actual constructor/counter registration failed");
        require(field(OwnedWeatherParticles.class,"owner").get(null)==mc.level && OwnedWeatherParticles.size()>0,
            "actual old-world owned map not populated");
        // Explicitly seed after-weather history through its real API so cleanup
        // is non-vacuous even when the dedicated fixture starts in clear weather.
        history().observe(mc.level,mc.level.getGameTime(),0,true);
        require(history().size()>0,"history fixture not populated");
        previous=new WeakReference<>(mc.level);
        org.slf4j.LoggerFactory.getLogger(WeatherConnectionProbe.class).info(
            "[WEATHER-CONNECTION] armed actual native CustomParticle owner/counter and explicit old-world history before real disconnect rule={}",
            weather.get(data));
    }
    public static void disconnected(Minecraft mc) throws ReflectiveOperationException {
        if(!ENABLED)return;
        require(mc.level==null,"world still connected");
        require(OwnedWeatherParticles.size()==0 && field(OwnedWeatherParticles.class,"owner").get(null)==null,
            "old-world particles/owner leaked after real disconnect");
        require(history().size()==0 && field(RecentWetColumns.class,"owner").get(history())==null,
            "old-world after-weather history/owner leaked after real disconnect");
        require(count()==0,"actual upstream particle counter not reset by ParticleEngine world clear");
        clears++;
        org.slf4j.LoggerFactory.getLogger(WeatherConnectionProbe.class).info(
            "[WEATHER-CONNECTION] PASS real disconnect={} ownedEntries=0 ownedWorld=null historyEntries=0 historyWorld=null activeCounter=0",
            clears);
    }
    public static void changedDimension(Minecraft mc) throws ReflectiveOperationException {
        if(!ENABLED)return;
        require(mc.level!=null && mc.level!=previous.get(),"dimension did not replace ClientLevel");
        Object owned=field(OwnedWeatherParticles.class,"owner").get(null);
        Object historyOwner=field(RecentWetColumns.class,"owner").get(history());
        require(owned==null || owned==mc.level,"owned particles retained previous dimension");
        require(historyOwner==null || historyOwner==mc.level,"after-weather history retained previous dimension");
        if(mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER))
            require(history().size()==0,"clear Nether inherited after-weather columns");
        require(count()>=0,"dimension transition made upstream particle counter negative");
        ClientLevel old=previous.get();
        if(old!=null) {
            require(!ParticleWeatherBridge.matches(old,net.minecraft.core.BlockPos.ZERO,
                dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects.Condition.ALWAYS,null),
                "late old-world query was accepted");
            require(field(RecentWetColumns.class,"owner").get(history())==historyOwner,
                "late old-world query rebound current history");
            org.slf4j.LoggerFactory.getLogger(WeatherConnectionProbe.class).info(
                "[WEATHER-DIMENSION] late old-world query rejected without history mutation");
        }
        previous=new WeakReference<>(mc.level);
        org.slf4j.LoggerFactory.getLogger(WeatherConnectionProbe.class).info(
            "[WEATHER-DIMENSION] PASS dimension={} freshClientLevel=true noOldParticleOwner=true noOldHistoryOwner=true historyEntries={} activeCounter={}",
            mc.level.dimension().identifier(),history().size(),count());
    }
    public static void currentBounds(Minecraft mc) throws ReflectiveOperationException {
        if(!ENABLED)return;
        require(mc.level!=null,"residence sample without world");
        Object owned=field(OwnedWeatherParticles.class,"owner").get(null);
        Object wetOwner=field(RecentWetColumns.class,"owner").get(history());
        require(owned==null || owned==mc.level,"residence retained old particle world");
        require(wetOwner==null || wetOwner==mc.level,"residence retained old history world");
        require(OwnedWeatherParticles.size()<=SimpleCloudsConfig.CLIENT.weatherParticleBudget.get(),"residence particle budget overflow");
        require(history().size()<=4096 && count()>=0,"residence history/counter bounds failed");
    }
}
