package dev.nonamecrackers2.simpleclouds.common.packet.impl.update;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fabric 26.2: CustomPacketPayload record.
 */
public record NotifyCloudModeUpdatedPacket(CloudMode newMode) implements CustomPacketPayload
{
	public static final Type<NotifyCloudModeUpdatedPacket> TYPE = new Type<>(SimpleCloudsMod.id("notify_cloud_mode_updated"));

	public static final StreamCodec<FriendlyByteBuf, NotifyCloudModeUpdatedPacket> CODEC = StreamCodec.of(
			(buffer, packet) -> buffer.writeEnum(packet.newMode()),
			buffer -> new NotifyCloudModeUpdatedPacket(buffer.readEnum(CloudMode.class)));

	@Override
	public Type<? extends CustomPacketPayload> type()
	{
		return TYPE;
	}
}
