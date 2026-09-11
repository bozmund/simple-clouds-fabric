package nonamecrackers2.crackerslib.client.event.impl;

import java.util.Map;

import com.google.common.collect.Maps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import nonamecrackers2.crackerslib.client.config.ConfigHomeScreenFactory;
import nonamecrackers2.crackerslib.client.event.CrackersLibClientEvents;

/**
 * Fabric port: stores config screen registration. The actual options-menu
 * integration is handled via a mixin (see CrackersLibClientEvents).
 */
public class RegisterConfigScreensEvent
{
	private final String modid;
	private final ConfigHomeScreenFactory factory;
	private final Map<ModConfig.Type, ForgeConfigSpec> specsByType;

	public RegisterConfigScreensEvent(String modid, ConfigHomeScreenFactory factory)
	{
		this.modid = modid;
		this.factory = factory;
		this.specsByType = Maps.newEnumMap(ModConfig.Type.class);
	}

	public RegisterConfigScreensEvent.Builder builder(ConfigHomeScreenFactory factory)
	{
		return new RegisterConfigScreensEvent.Builder(this.modid, factory);
	}

	public String getModId()
	{
		return this.modid;
	}

	public ConfigHomeScreenFactory getFactory()
	{
		return this.factory;
	}

	public Map<ModConfig.Type, ForgeConfigSpec> getSpecsByType()
	{
		return this.specsByType;
	}

	public Screen buildScreen(Screen previous)
	{
		Minecraft mc = Minecraft.getInstance();
		return this.factory.build(this.modid, this.specsByType, mc.level != null, mc.hasSingleplayerServer(), previous);
	}

	public static class Builder
	{
		private final Map<ModConfig.Type, ForgeConfigSpec> specsByType = Maps.newEnumMap(ModConfig.Type.class);
		private final String modid;
		private final ConfigHomeScreenFactory factory;

		private Builder(String modid, ConfigHomeScreenFactory factory)
		{
			this.modid = modid;
			this.factory = factory;
		}

		public Builder addSpec(ModConfig.Type type, ForgeConfigSpec spec)
		{
			if (this.specsByType.containsKey(type))
				throw new IllegalArgumentException("Type is already registered");
			this.specsByType.put(type, spec);
			return this;
		}

		public RegisterConfigScreensEvent build()
		{
			RegisterConfigScreensEvent event = new RegisterConfigScreensEvent(this.modid, this.factory);
			event.specsByType.putAll(this.specsByType);
			return event;
		}

		public void register()
		{
			// Fabric: registration is collected; the mixin reads it to add the options button.
			CrackersLibClientEvents.registerConfigScreenEvent(this.build());
		}
	}
}
