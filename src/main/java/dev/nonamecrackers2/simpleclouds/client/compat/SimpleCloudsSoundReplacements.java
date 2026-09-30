package dev.nonamecrackers2.simpleclouds.client.compat;

import java.util.Map;
import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundEventRegistration;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.apache.logging.log4j.LogManager;

/** Replace only the original vanilla rain files; leave resource-pack additions alone. */
public class SimpleCloudsSoundReplacements {
    public static Weighted<Sound> applyReplacement(Weighted<Sound> current, Identifier event,
            SoundEventRegistration registration, Map<Identifier, Resource> resources) {
        return applyReplacement(current, event, resources, SimpleCloudsCompatHelper.useCustomRainSounds());
    }

    public static Weighted<Sound> applyReplacement(Weighted<Sound> current, Identifier event,
            Map<Identifier, Resource> resources, boolean enabled) {
        if (!(current instanceof Sound sound) || !enabled) return current;
        if (!event.getNamespace().equals("minecraft")) return current;
        int count = switch (event.getPath()) {
            case "weather.rain" -> 8;
            case "weather.rain.above" -> 4;
            default -> 0;
        };
        String path = sound.getLocation().getPath();
        boolean matches = false;
        for (int i = 1; i <= count; i++)
            if (path.equals("ambient/weather/rain" + i)) matches = true;
        if (!matches) return current;
        Sound replacement = new Sound(SimpleCloudsMod.id(path), sound.getVolume(), sound.getPitch(),
                sound.getWeight(), Sound.Type.FILE, false, sound.shouldPreload(), sound.getAttenuationDistance());
        if (!resources.containsKey(replacement.getPath())) {
            LogManager.getLogger("simpleclouds/SoundReplacements").warn("Missing replacement rain sound {}", replacement.getPath());
            return current;
        }
        return replacement;
    }
}
