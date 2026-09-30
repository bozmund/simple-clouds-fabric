package dev.nonamecrackers2.simpleclouds.common.init;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/**
 * Custom thunder sound events used by the weather renderer and sounds.json.
 */
public class SimpleCloudsSounds
{
	public static final SoundEvent DISTANT_THUNDER = SoundEvent.createVariableRangeEvent(SimpleCloudsMod.id("distant_thunder"));
	public static final SoundEvent CLOSE_THUNDER = SoundEvent.createVariableRangeEvent(SimpleCloudsMod.id("close_thunder"));

	public static void register()
	{
		Registry.register(BuiltInRegistries.SOUND_EVENT, SimpleCloudsMod.id("distant_thunder"), DISTANT_THUNDER);
		Registry.register(BuiltInRegistries.SOUND_EVENT, SimpleCloudsMod.id("close_thunder"), CLOSE_THUNDER);
	}
}
