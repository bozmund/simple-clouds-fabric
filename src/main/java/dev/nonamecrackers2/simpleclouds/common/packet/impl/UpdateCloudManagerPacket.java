package dev.nonamecrackers2.simpleclouds.common.packet.impl;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;

/**
 * Fabric 26.2: CustomPacketPayload record (was Forge Packet subclass).
 */
public record UpdateCloudManagerPacket(float speed, float scrollAngle, int cloudHeight) implements CustomPacketPayload
{
	public static final Type<UpdateCloudManagerPacket> TYPE = new Type<>(SimpleCloudsMod.id("update_cloud_manager"));
	public static final StreamCodec<FriendlyByteBuf, UpdateCloudManagerPacket> CODEC = StreamCodec.composite(
			StreamCodec.of(FriendlyByteBuf::writeFloat, FriendlyByteBuf::readFloat), UpdateCloudManagerPacket::speed,
			StreamCodec.of(FriendlyByteBuf::writeFloat, FriendlyByteBuf::readFloat), UpdateCloudManagerPacket::scrollAngle,
			StreamCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt), UpdateCloudManagerPacket::cloudHeight,
			UpdateCloudManagerPacket::new);

	public UpdateCloudManagerPacket(CloudManager<ServerLevel> manager)
	{
		this(manager.getCloudSpeed(), manager.getScrollAngle(), manager.getCloudHeight());
	}

	@Override
	public Type<? extends CustomPacketPayload> type()
	{
		return TYPE;
	}
}
