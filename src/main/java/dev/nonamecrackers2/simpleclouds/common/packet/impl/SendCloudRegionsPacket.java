package dev.nonamecrackers2.simpleclouds.common.packet.impl;

import java.util.List;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fabric 26.2: CustomPacketPayload record.
 */
public record SendCloudRegionsPacket(List<CloudRegion> cloudRegions) implements CustomPacketPayload
{
	public static final Type<SendCloudRegionsPacket> TYPE = new Type<>(SimpleCloudsMod.id("send_cloud_regions"));

	public static final StreamCodec<FriendlyByteBuf, SendCloudRegionsPacket> CODEC = StreamCodec.of(
			(buffer, packet) -> buffer.writeCollection(packet.cloudRegions(), (b, c) -> c.toPacket(b)),
			buffer -> new SendCloudRegionsPacket(buffer.readList(CloudRegion::new)));

	@Override
	public Type<? extends CustomPacketPayload> type()
	{
		return TYPE;
	}
}
