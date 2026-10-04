package dev.nonamecrackers2.simpleclouds.client.packet;

import dev.nonamecrackers2.simpleclouds.common.packet.impl.UpdateCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudRegionsPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudTypesPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SpawnLightningPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifyCloudModeUpdatedPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifySingleModeCloudTypeUpdatedPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import dev.nonamecrackers2.simpleclouds.client.config.ClientServerConfig;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendServerConfigPacket;

/** Client receivers, installed only after shared payload codec registration. */
public final class SimpleCloudsClientPacketRegistrations
{
	private SimpleCloudsClientPacketRegistrations() {}

	private static void clearConnectionState()
	{
		ClientServerConfig.clear();
		dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager.getInstance().clearSynced();
	}

	public static void register()
	{
		ClientPlayConnectionEvents.INIT.register((handler, client) -> clearConnectionState());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearConnectionState());
		ClientPlayNetworking.registerGlobalReceiver(SendServerConfigPacket.TYPE,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleSendServerConfigPacket(payload));
		ClientPlayNetworking.registerGlobalReceiver(UpdateCloudManagerPacket.TYPE,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleUpdateCloudManagerPacket(payload));
		ClientPlayNetworking.registerGlobalReceiver(SendCloudManagerPacket.TYPE,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleSendCloudManagerPacket(payload));
		ClientPlayNetworking.registerGlobalReceiver(SendCloudRegionsPacket.TYPE,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleSendCloudRegionsPacket(payload));
		ClientPlayNetworking.registerGlobalReceiver(SendCloudTypesPacket.TYPE,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleCloudTypesPacket(payload));
		ClientPlayNetworking.registerGlobalReceiver(SpawnLightningPacket.TYPE,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleSpawnLightningPacket(payload));
		ClientPlayNetworking.registerGlobalReceiver(NotifyCloudModeUpdatedPacket.TYPE,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleNotifyCloudModeUpdatedPacket(payload));
		ClientPlayNetworking.registerGlobalReceiver(NotifySingleModeCloudTypeUpdatedPacket.TYPE,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleNotifySingleModeCloudTypeUpdatedPacket(payload));
	}
}
