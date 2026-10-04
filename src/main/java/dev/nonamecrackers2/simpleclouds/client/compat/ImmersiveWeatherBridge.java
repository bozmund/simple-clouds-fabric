package dev.nonamecrackers2.simpleclouds.client.compat;

import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/** Preserve native biome selection; sample the actual chosen sound/particle column. */
public final class ImmersiveWeatherBridge {
    private static Method rainData,thunderData;
    private static int nativeSoundSelections;
    private static final boolean probe="1".equals(System.getenv("SIMPLECLOUDS_DEV"))
        && "1".equals(System.getenv("SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER"));
    private ImmersiveWeatherBridge() {}
    public static float cameraRain(Level world,float fallback) {
        if(!SimpleCloudsConfig.CLIENT.biomeStormEffects.get())return 0;
        var mc=Minecraft.getInstance();
        if(mc.level!=world)return fallback;
        var sample=LocalWeatherEffects.at(world,BlockPos.containing(mc.gameRenderer.mainCamera().position()));
        return sample.localAuthority()?sample.rain():fallback;
    }
    public static Object data(Object type,Level world,BlockPos pos,Supplier<Object> fallback) {
        if(!SimpleCloudsConfig.CLIENT.biomeStormEffects.get())return null;
        var sample=LocalWeatherEffects.at(world,pos);
        if(!sample.localAuthority())return fallback.get();
        try {
            if(rainData==null) {
                rainData=type.getClass().getMethod("getRainWeatherData");
                thunderData=type.getClass().getMethod("getThunderWeatherData");
            }
            if(sample.thunder()>0) {
                Object data=thunderData.invoke(type);
                if(data!=null)return data;
            }
            return sample.rain()>0?rainData.invoke(type):null;
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Pinned native weather data contract changed",e);}
    }
    public static Object soundData(Object type,Level world,BlockPos pos,Supplier<Object> fallback) {
        if(probe)nativeSoundSelections++;
        return data(type,world,pos,fallback);
    }
}
