package dev.nonamecrackers2.simpleclouds.common.packet.impl;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fabric 26.2: CustomPacketPayload record.
 */
public record SpawnLightningPacket(BlockPos pos, boolean onlySound, int seed, int maxDepth, int branchCount,
		float maxBranchLength, float maxWidth, float minimumPitch, float maximumPitch)
		implements CustomPacketPayload
{
	public static final Type<SpawnLightningPacket> TYPE = new Type<>(SimpleCloudsMod.id("spawn_lightning"));

	public static final StreamCodec<FriendlyByteBuf, SpawnLightningPacket> CODEC = StreamCodec.composite(
			StreamCodec.of((buf, pos) -> buf.writeBlockPos(pos), buf -> buf.readBlockPos()), SpawnLightningPacket::pos,
			StreamCodec.of(FriendlyByteBuf::writeBoolean, FriendlyByteBuf::readBoolean), SpawnLightningPacket::onlySound,
			StreamCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt), SpawnLightningPacket::seed,
			StreamCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt), SpawnLightningPacket::maxDepth,
			StreamCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt), SpawnLightningPacket::branchCount,
			StreamCodec.of(FriendlyByteBuf::writeFloat, FriendlyByteBuf::readFloat), SpawnLightningPacket::maxBranchLength,
			StreamCodec.of(FriendlyByteBuf::writeFloat, FriendlyByteBuf::readFloat), SpawnLightningPacket::maxWidth,
			StreamCodec.of(FriendlyByteBuf::writeFloat, FriendlyByteBuf::readFloat), SpawnLightningPacket::minimumPitch,
			StreamCodec.of(FriendlyByteBuf::writeFloat, FriendlyByteBuf::readFloat), SpawnLightningPacket::maximumPitch,
			SpawnLightningPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type()
	{
		return TYPE;
	}
}
