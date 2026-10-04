package dev.nonamecrackers2.simpleclouds.client.compat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Restore only native callbacks omitted by upstream's startup Particle Rain detection. */
public final class NativeWeatherRegistration {
    private static boolean registered;
    private NativeWeatherRegistration() {}
    public static synchronized void afterNativeInitialization() {
        if(registered || !FabricLoader.getInstance().isModLoaded("particlerain"))return;
        try {
            var client=Class.forName("com.thedeathlycow.immersive.storms.ImmersiveStormsClient");
            var config=client.getMethod("getConfig").invoke(null);
            var sand=config.getClass().getMethod("getSandstorm").invoke(config);
            if(!(Boolean)sand.getClass().getMethod("isDetectParticleRain").invoke(sand))return;
            for(String name:new String[]{"SandstormParticles","SandstormSounds"}) {
                var type=Class.forName("com.thedeathlycow.immersive.storms.world."+name);
                var callback=(ClientTickEvents.EndLevelTick)type.getConstructor().newInstance();
                ClientTickEvents.END_LEVEL_TICK.register(level -> {
                    // Particle Rain owns its equivalent effects in PARTICLE mode.
                    // Existing native root/config gates remain inside the callback.
                    if(!SimpleCloudsCompatHelper.usesParticleRain())callback.onEndTick(level);
                });
            }
            registered=true;
            org.slf4j.LoggerFactory.getLogger(NativeWeatherRegistration.class).info(
                "[NATIVE-WEATHER-REGISTRATION] restored skipped native sandstorm/sound callbacks for ORIGINAL owner; PARTICLE owner preserved");
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned Immersive Storms registration contract changed",failure);
        }
    }
}
