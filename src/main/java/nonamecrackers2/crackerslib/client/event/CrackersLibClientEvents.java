package nonamecrackers2.crackerslib.client.event;

import java.util.List;

import com.google.common.collect.Lists;

import net.minecraftforge.fml.config.ModConfig;
import nonamecrackers2.crackerslib.CrackersLib;
import nonamecrackers2.crackerslib.client.event.impl.RegisterConfigScreensEvent;
import nonamecrackers2.crackerslib.client.gui.ConfigHomeScreen;
import nonamecrackers2.crackerslib.client.gui.title.TextTitle;
import nonamecrackers2.crackerslib.common.config.CrackersLibConfig;

/**
 * Fabric port: collects config screen registrations. The options-menu button
 * integration is handled via a mixin that reads {@link #registeredEvents}.
 */
public class CrackersLibClientEvents
{
	private static final List<RegisterConfigScreensEvent> registeredEvents = Lists.newArrayList();

	public static void registerConfigScreenEvent(RegisterConfigScreensEvent event)
	{
		registeredEvents.add(event);
	}

	public static List<RegisterConfigScreensEvent> getRegisteredEvents()
	{
		return registeredEvents;
	}

	public static void registerConfigScreen(RegisterConfigScreensEvent event)
	{
		event.builder(ConfigHomeScreen.builder(TextTitle.ofModDisplayName(CrackersLib.MODID))
				.crackersDefault().build()
		).addSpec(ModConfig.Type.CLIENT, CrackersLibConfig.CLIENT_SPEC).register();
	}
}
