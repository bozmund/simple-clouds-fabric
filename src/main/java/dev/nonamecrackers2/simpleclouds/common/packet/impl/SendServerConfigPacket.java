package dev.nonamecrackers2.simpleclouds.common.packet.impl;

import java.util.ArrayList;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.common.config.ServerConfigSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Fabric replacement for Forge's automatic SERVER-config client synchronization. */
public record SendServerConfigPacket(ServerConfigSnapshot config) implements CustomPacketPayload
{
    public static final Type<SendServerConfigPacket> TYPE = new Type<>(SimpleCloudsMod.id("send_server_config"));
    // Bound the allocation from untrusted wire input before reading strings. This is
    // a transport limit, not a renderer/dimension policy. 4096 identifiers fit the
    // normal configuration use case well below the clientbound payload ceiling.
    public static final int MAX_DIMENSIONS = 4096;
    public static final StreamCodec<FriendlyByteBuf, SendServerConfigPacket> CODEC = StreamCodec.of(
            SendServerConfigPacket::encode, SendServerConfigPacket::decode);

    public SendServerConfigPacket
    {
        java.util.Objects.requireNonNull(config);
        if (config.dimensionWhitelist().size() > MAX_DIMENSIONS)
            throw new IllegalArgumentException("Server dimension list exceeds wire limit " + MAX_DIMENSIONS);
    }

    private static void encode(FriendlyByteBuf buffer, SendServerConfigPacket packet)
    {
        var config = packet.config;
        buffer.writeEnum(config.cloudMode());
        buffer.writeUtf(config.singleModeCloudType());
        buffer.writeVarInt(config.dimensionWhitelist().size());
        for (String dimension : config.dimensionWhitelist()) buffer.writeUtf(dimension);
        buffer.writeBoolean(config.whitelistAsBlacklist());
    }

    private static SendServerConfigPacket decode(FriendlyByteBuf buffer)
    {
        CloudMode mode = buffer.readEnum(CloudMode.class);
        String type = buffer.readUtf();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_DIMENSIONS)
            throw new IllegalArgumentException("Invalid server dimension count " + count);
        var dimensions = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) dimensions.add(buffer.readUtf());
        return new SendServerConfigPacket(new ServerConfigSnapshot(mode, type, dimensions, buffer.readBoolean()));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
