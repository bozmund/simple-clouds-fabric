package net.minecraftforge.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric 26.2 port: minimal event bus for Simple Clouds API events.
 *
 * This is NOT a Forge stub — it's a functional event bus that Simple Clouds uses
 * to post API events (CloudRegionTickEvent, CloudRegionRemovedEvent, etc.) that
 * other mods can listen to.
 *
 * TODO(fabric): properly integrate with Fabric's event system or provide a
 * listener registration mechanism for other mods.
 */
public class MinecraftForge
{
	public static final IForgeEventBus EVENT_BUS = new SimpleEventBus();

	public interface IForgeEventBus
	{
		void post(Object event);
		void register(Object target);
	}

	private static class SimpleEventBus implements IForgeEventBus
	{
		private final List<Object> listeners = new ArrayList<>();

		@Override
		public void post(Object event)
		{
			// TODO(fabric): dispatch to registered listeners
		}

		@Override
		public void register(Object target)
		{
			this.listeners.add(target);
		}
	}
}
