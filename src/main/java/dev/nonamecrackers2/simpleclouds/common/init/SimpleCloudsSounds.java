package dev.nonamecrackers2.simpleclouds.common.init;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.minecraft.sounds.SoundEvent;

/**
 * 26.2 vertical-slice: the custom thunder sound events are created directly (the Forge
 * DeferredRegister/registry-object pattern is gone). They are not yet wired to a sound
 * registry for the new sound system; kept for API compatibility.
 */
public class SimpleCloudsSounds
{
	public static final SoundEvent DISTANT_THUNDER = SoundEvent.createVariableRangeEvent(SimpleCloudsMod.id("distant_thunder"));
	public static final SoundEvent CLOSE_THUNDER = SoundEvent.createVariableRangeEvent(SimpleCloudsMod.id("close_thunder"));

	public static void register(net.minecraftforge.eventbus.api.IEventBus modBus)
	{
		// TODO(26.2): register the thunder sound events with the new sound system.
	}
}
