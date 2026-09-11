package nonamecrackers2.crackerslib.client.gui;

import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableMap;

import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;

/**
 * Fabric port: button factories registered directly (no Forge event generator).
 */
public class ConfigMenuButtons
{
	private static @Nullable Map<String, ConfigMenuButtons.Factory> factoriesByModId;
	private static final Logger LOGGER = LogManager.getLogger("crackerslib/ConfigPresets");
	private static final Map<String, ConfigMenuButtons.Factory> pendingFactories = new java.util.HashMap<>();

	public static @Nullable ConfigMenuButtons.Factory getButtonFactory(String modid)
	{
		Objects.requireNonNull(factoriesByModId, "Button factories have not been registered yet!");
		return factoriesByModId.get(modid);
	}

	/**
	 * Directly register a button factory for a mod (Fabric replacement).
	 */
	public static void registerButtonFactory(String modId, ConfigMenuButtons.Factory factory)
	{
		if (factoriesByModId != null)
			throw new IllegalStateException("Config menu button factories have already been gathered!");
		if (factory != null)
			pendingFactories.put(modId, factory);
	}

	public static void gatherButtonFactories()
	{
		if (factoriesByModId != null)
			throw new IllegalStateException("Config menu button factories have already been gathered!");
		factoriesByModId = ImmutableMap.copyOf(pendingFactories);
		LOGGER.debug("Registered {} config menu buttons", factoriesByModId.size());
	}

	@FunctionalInterface
	public static interface Factory
	{
		AbstractButton makeButton(Button.OnPress onButtonPress);
	}
}
