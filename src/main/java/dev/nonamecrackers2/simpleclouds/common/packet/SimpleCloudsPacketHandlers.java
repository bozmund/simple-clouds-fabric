package dev.nonamecrackers2.simpleclouds.common.packet;

import dev.nonamecrackers2.simpleclouds.client.packet.SimpleCloudsClientPacketHandler;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudRegionsPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudTypesPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SpawnLightningPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.UpdateCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifyCloudModeUpdatedPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifySingleModeCloudTypeUpdatedPacket;
import nonamecrackers2.crackerslib.common.packet.PacketUtil;

/**
 * Fabric 26.2 packet registration.
 * Replaces Forge's SimpleChannel with per-payload-type registration.
 */
public class SimpleCloudsPacketHandlers
{
	public static final String VERSION = "1.1";

	public static void register()
	{
		PacketUtil.registerToClient(
				UpdateCloudManagerPacket.TYPE, UpdateCloudManagerPacket.CODEC,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleUpdateCloudManagerPacket(payload));

		PacketUtil.registerToClient(
				SendCloudManagerPacket.TYPE, SendCloudManagerPacket.CODEC,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleSendCloudManagerPacket(payload));

		PacketUtil.registerToClient(
				SendCloudRegionsPacket.TYPE, SendCloudRegionsPacket.CODEC,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleSendCloudRegionsPacket(payload));

		PacketUtil.registerToClient(
				SendCloudTypesPacket.TYPE, SendCloudTypesPacket.CODEC,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleCloudTypesPacket(payload));

		PacketUtil.registerToClient(
				SpawnLightningPacket.TYPE, SpawnLightningPacket.CODEC,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleSpawnLightningPacket(payload));

		PacketUtil.registerToClient(
				NotifyCloudModeUpdatedPacket.TYPE, NotifyCloudModeUpdatedPacket.CODEC,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleNotifyCloudModeUpdatedPacket(payload));

		PacketUtil.registerToClient(
				NotifySingleModeCloudTypeUpdatedPacket.TYPE, NotifySingleModeCloudTypeUpdatedPacket.CODEC,
				(payload, context) -> SimpleCloudsClientPacketHandler.handleNotifySingleModeCloudTypeUpdatedPacket(payload));
	}
}
