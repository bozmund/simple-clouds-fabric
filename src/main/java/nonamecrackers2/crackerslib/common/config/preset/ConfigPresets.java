package nonamecrackers2.crackerslib.common.config.preset;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import net.minecraftforge.fml.config.ModConfig;

/**
 * Fabric port: presets are registered directly (no Forge mod-bus event generator).
 * The ported mod calls {@link #registerPresets(String, RegisterConfigPresetsEvent)}
 * during initialization instead of relying on ModLoader.runEventGenerator.
 */
public class ConfigPresets
{
	public static @Nullable Map<String, ConfigPresets.Presets> presetsByMod;
	private static final Logger LOGGER = LogManager.getLogger("crackerslib/ConfigPresets");

	public static @Nullable ConfigPresets.Presets getPresetsForModId(String id)
	{
		Objects.requireNonNull(presetsByMod, "Presets have not yet been gathered!");
		return presetsByMod.get(id);
	}

	/**
	 * Directly register a mod's presets (Fabric replacement for the event generator).
	 * Must be called before {@link #gatherPresets()} finalizes the map.
	 */
	public static void registerPresets(String modId, RegisterConfigPresetsEvent event)
	{
		if (presetsByMod != null)
			throw new IllegalStateException("Presets have already been gathered!");
		if (event == null)
			return;
		var presets = event.buildPresets();
		var excluded = event.buildExcludedConfigOptions();
		if (!presets.isEmpty())
			pendingPresets.put(modId, new ConfigPresets.Presets(presets, excluded));
	}

	private static final Map<String, ConfigPresets.Presets> pendingPresets = new java.util.HashMap<>();

	public static void gatherPresets()
	{
		if (presetsByMod != null)
			throw new IllegalStateException("Presets have already been gathered!");
		presetsByMod = ImmutableMap.copyOf(pendingPresets);
		LOGGER.debug("Gathered presets for {} mod(s)", presetsByMod.size());
	}

	public static class Presets
	{
		private final Multimap<ModConfig.Type, ConfigPreset> presetsByType;
		private final List<String> excludedConfigOptions;

		Presets(Multimap<ModConfig.Type, ConfigPreset> presetsByType, List<String> excludedConfigOptions)
		{
			this.presetsByType = presetsByType;
			this.excludedConfigOptions = excludedConfigOptions;
		}

		public Collection<ConfigPreset> getPresetsForType(ModConfig.Type type)
		{
			return this.presetsByType.get(type);
		}

		public List<String> getExcludedConfigOptions()
		{
			return this.excludedConfigOptions;
		}
	}
}
