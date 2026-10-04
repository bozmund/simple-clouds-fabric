package dev.nonamecrackers2.simpleclouds.client.config;

import com.google.common.base.Joiner;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraftforge.fml.config.ModConfig;
import nonamecrackers2.crackerslib.client.gui.Popup;
import nonamecrackers2.crackerslib.common.config.listener.ConfigListener;

public class SimpleCloudsClientConfigListeners
{
	private static ConfigListener listener;
	private static boolean initialized;

	public static void registerListener()
	{
		if (listener != null)
			return;
		listener = ConfigListener.builder(ModConfig.Type.CLIENT, SimpleCloudsMod.MODID)
				.addListener(SimpleCloudsConfig.CLIENT.cloudMode, (o, n) -> requestReload(true))
				.addListener(SimpleCloudsConfig.CLIENT.shadedClouds, (o, n) -> requestReload(false))
				.addListener(SimpleCloudsConfig.CLIENT.transparency, (o, n) -> requestReload(false))
				.addListener(SimpleCloudsConfig.CLIENT.levelOfDetail, (o, n) -> requestReload(false))
				.addListener(SimpleCloudsConfig.CLIENT.distantShadows, (o, n) -> requestReload(false))
				.addListener(SimpleCloudsConfig.CLIENT.shadowDistance, (o, n) -> requestReload(false))
				.addListener(SimpleCloudsConfig.CLIENT.concurrentComputeDispatches, (o, n) -> requestReload(false))
				.addListener(SimpleCloudsConfig.CLIENT.singleModeCloudType, (o, n) -> onSingleModeCloudTypeUpdated(n))
				.addListener(SimpleCloudsConfig.CLIENT.customRainSounds, (o, n) -> reloadResources())
				.buildAndRegister();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!SimpleCloudsConfig.CLIENT_SPEC.isLoaded())
				return;
			if (!initialized)
			{
				listener.resetCache();
				initialized = true;
			}
			else
				listener.poll();
		});
	}
	
	/**
	 * Updates the connection-scoped server copy without mutating the world-owned spec.
	 * After called, this method will then request a reload from the cloud renderer, which
	 * will reinitialize the mesh generator so the change in the config value is applied.
	 */
	public static void onCloudModeUpdatedFromServer(CloudMode mode)
	{
		ClientServerConfig.updateCloudMode(mode);
		Popup.createInfoPopup(null, 300, Component.translatable("gui.simpleclouds.reload_confirmation.server.info"), () -> {
			SimpleCloudsRenderer.getInstance().requestReload();
		});
	}
	
	/**
	 * Updates the connection-scoped server copy without mutating the world-owned spec.
     * After called, this method will then update the single mode cloud type for the single mode cloud mesh
     * generator.
	 */
	public static void onSingleModeCloudTypeUpdatedFromServer(String type)
	{
		ClientServerConfig.updateSingleModeCloudType(type);
		// The active renderer reads this selection and invalidates changed mesh groups.
		// getMeshGenerator() is a legacy adapter, not the 26.3 render path.
	}
	
	public static void onSingleModeCloudTypeUpdated(String type)
	{
		Minecraft.getInstance().execute(() -> 
		{
			if (ClientCloudManager.isAvailableServerSide())
				return;
			
			Identifier loc = Identifier.tryParse(type);
			var types = ClientSideCloudTypeManager.getInstance().getCloudTypes();
			if (loc != null && types.containsKey(loc) && ClientSideCloudTypeManager.isValidClientSideSingleModeCloudType(types.get(loc)))
			{
				// Selection is read directly by the active renderer on the next frame.
			}
			else
			{
				Component valid = Component.literal(Joiner.on(", ").join(types.values().stream().filter(t -> {
					return ClientSideCloudTypeManager.isValidClientSideSingleModeCloudType(t);
				}).map(t -> t.id().toString()).iterator())).withStyle(ChatFormatting.YELLOW);
				Popup.createInfoPopup(null, 300, Component.translatable("gui.simpleclouds.unknown_or_invalid_client_side_cloud_type.info", loc == null ? type : loc.toString(), valid));
			}
		});
	}
	
	public static void requestReload(boolean skipIfServerAvailable)
	{
		Minecraft.getInstance().execute(() -> 
		{
			if (skipIfServerAvailable && ClientCloudManager.isAvailableServerSide())
				return;
			Popup.createYesNoPopup(null, () -> {
				SimpleCloudsRenderer.getInstance().requestReload();
			}, 300, Component.translatable("gui.simpleclouds.requires_reload.info"));
			// Keep queued dialogs: when another Popup is open, the new reload
			// request is queued too. Clearing here silently discards that request.
		});
	}
	
	public static void reloadResources()
	{
		Minecraft.getInstance().execute(() -> {
			Popup.createYesNoPopup(null, () -> {
				Minecraft.getInstance().reloadResourcePacks();
			}, 300, Component.translatable("gui.simpleclouds.requires_reload_resource_packs.info"));
		});
	}
}
