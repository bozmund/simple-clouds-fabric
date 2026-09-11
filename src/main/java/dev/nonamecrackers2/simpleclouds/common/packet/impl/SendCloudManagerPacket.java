package dev.nonamecrackers2.simpleclouds.common.packet.impl;

import java.util.List;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;

/**
 * Fabric 26.2: CustomPacketPayload record (was Forge Packet subclass extending UpdateCloudManagerPacket).
 * Standalone record (records cannot extend); includes all fields.
 */
public record SendCloudManagerPacket(float speed, float scrollAngle, int cloudHeight, List<CloudRegion> cloudRegions, long seed)
		implements CustomPacketPayload
{
	public static final Type<SendCloudManagerPacket> TYPE = new Type<>(SimpleCloudsMod.id("send_cloud_manager"));

	public static final StreamCodec<FriendlyByteBuf, SendCloudManagerPacket> CODEC = StreamCodec.of(
			SendCloudManagerPacket::encode,
			SendCloudManagerPacket::decode);

	public SendCloudManagerPacket(CloudManager<ServerLevel> manager)
	{
		this(manager.getCloudSpeed(), manager.getScrollAngle(), manager.getCloudHeight(),
				manager.getClouds(), manager.getSeed());
	}

	private static void encode(FriendlyByteBuf buffer, SendCloudManagerPacket packet)
	{
		buffer.writeFloat(packet.speed());
		buffer.writeFloat(packet.scrollAngle());
		buffer.writeVarInt(packet.cloudHeight());
		buffer.writeCollection(packet.cloudRegions(), (b, c) -> c.toPacket(b));
		buffer.writeLong(packet.seed());
	}

	private static SendCloudManagerPacket decode(FriendlyByteBuf buffer)
	{
		float speed = buffer.readFloat();
		float scrollAngle = buffer.readFloat();
		int cloudHeight = buffer.readVarInt();
		List<CloudRegion> cloudRegions = buffer.readList(CloudRegion::new);
		long seed = buffer.readLong();
		return new SendCloudManagerPacket(speed, scrollAngle, cloudHeight, cloudRegions, seed);
	}

	@Override
	public Type<? extends CustomPacketPayload> type()
	{
		return TYPE;
	}
}
