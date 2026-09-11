package dev.nonamecrackers2.simpleclouds.common.packet.impl.update;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fabric 26.2: CustomPacketPayload record.
 */
public record NotifySingleModeCloudTypeUpdatedPacket(String newType) implements CustomPacketPayload
{
	public static final Type<NotifySingleModeCloudTypeUpdatedPacket> TYPE = new Type<>(SimpleCloudsMod.id("notify_single_mode_cloud_type_updated"));

	public static final StreamCodec<FriendlyByteBuf, NotifySingleModeCloudTypeUpdatedPacket> CODEC = StreamCodec.of(
			(buffer, packet) -> buffer.writeUtf(packet.newType()),
			buffer -> new NotifySingleModeCloudTypeUpdatedPacket(buffer.readUtf()));

	@Override
	public Type<? extends CustomPacketPayload> type()
	{
		return TYPE;
	}
}
