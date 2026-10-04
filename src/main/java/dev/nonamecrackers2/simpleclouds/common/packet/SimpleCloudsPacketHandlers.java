package dev.nonamecrackers2.simpleclouds.common.packet;

import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudRegionsPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudTypesPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SpawnLightningPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.UpdateCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifyCloudModeUpdatedPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifySingleModeCloudTypeUpdatedPacket;
import nonamecrackers2.crackerslib.common.packet.PacketUtil;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendServerConfigPacket;

/**
 * Fabric 26.2 packet registration.
 * Replaces Forge's SimpleChannel with per-payload-type registration.
 */
public class SimpleCloudsPacketHandlers
{
	public static final String VERSION = "1.1";

	public static void register()
	{
		PacketUtil.registerClientboundType(SendServerConfigPacket.TYPE, SendServerConfigPacket.CODEC);
		PacketUtil.registerClientboundType(UpdateCloudManagerPacket.TYPE, UpdateCloudManagerPacket.CODEC);

		PacketUtil.registerClientboundType(SendCloudManagerPacket.TYPE, SendCloudManagerPacket.CODEC);

		PacketUtil.registerClientboundType(SendCloudRegionsPacket.TYPE, SendCloudRegionsPacket.CODEC);

		PacketUtil.registerClientboundType(SendCloudTypesPacket.TYPE, SendCloudTypesPacket.CODEC);

		PacketUtil.registerClientboundType(SpawnLightningPacket.TYPE, SpawnLightningPacket.CODEC);

		PacketUtil.registerClientboundType(NotifyCloudModeUpdatedPacket.TYPE, NotifyCloudModeUpdatedPacket.CODEC);

		PacketUtil.registerClientboundType(NotifySingleModeCloudTypeUpdatedPacket.TYPE, NotifySingleModeCloudTypeUpdatedPacket.CODEC);
	}
}
