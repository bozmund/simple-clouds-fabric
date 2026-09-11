package nonamecrackers2.crackerslib.client.event.impl;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import nonamecrackers2.crackerslib.client.gui.ConfigMenuButtons;

/**
 * Fabric port: plain data holder (no Forge bus event).
 */
public class ConfigMenuButtonEvent
{
	private final String modid;
	private @Nullable ConfigMenuButtons.Factory factory;

	public ConfigMenuButtonEvent(String modid)
	{
		this.modid = modid;
	}

	public void registerFactory(ConfigMenuButtons.Factory factory)
	{
		this.factory = factory;
	}

	public void defaultButtonWithSingleCharacter(char character, int color)
	{
		this.factory = onPress ->
		{
			// 26.2: no setFGColor; button color is theme-driven. Color param ignored for now.
			return Button.builder(Component.literal(String.valueOf(character)), onPress).build();
		};
	}

	public String getModId()
	{
		return this.modid;
	}

	public @Nullable ConfigMenuButtons.Factory getFactory()
	{
		return this.factory;
	}
}
