package dev.nonamecrackers2.simpleclouds.common.packet.impl;

import java.util.Map;

import com.google.common.collect.Maps;
import com.google.gson.JsonParser;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeDataManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Fabric 26.2: CustomPacketPayload record.
 */
public record SendCloudTypesPacket(Map<Identifier, CloudType> types, CloudType[] indexed) implements CustomPacketPayload
{
	public static final Type<SendCloudTypesPacket> TYPE = new Type<>(SimpleCloudsMod.id("send_cloud_types"));

	public static final StreamCodec<FriendlyByteBuf, SendCloudTypesPacket> CODEC = StreamCodec.of(
			SendCloudTypesPacket::encode,
			SendCloudTypesPacket::decode);

	public SendCloudTypesPacket(CloudTypeDataManager manager)
	{
		CloudType[] indexed = manager.getIndexedCloudTypes();
		java.util.Map<Identifier, CloudType> map = new java.util.HashMap<>();
		for (CloudType type : indexed)
			map.put(type.id(), type);
		this(map, indexed);
	}

	private static void encode(FriendlyByteBuf buffer, SendCloudTypesPacket packet)
	{
		buffer.writeVarInt(packet.indexed().length);
		for (CloudType type : packet.indexed())
		{
			buffer.writeIdentifier(type.id());
			buffer.writeUtf(type.toJson().toString());
		}
	}

	private static SendCloudTypesPacket decode(FriendlyByteBuf buffer)
	{
		int count = buffer.readVarInt();
		Map<Identifier, CloudType> map = Maps.newHashMap();
		CloudType[] indexed = new CloudType[count];
		for (int i = 0; i < count; i++)
		{
			Identifier id = buffer.readIdentifier();
			CloudType type = CloudType.readFromJson(id, JsonParser.parseString(buffer.readUtf()).getAsJsonObject());
			map.put(id, type);
			indexed[i] = type;
		}
		return new SendCloudTypesPacket(map, indexed);
	}

	@Override
	public Type<? extends CustomPacketPayload> type()
	{
		return TYPE;
	}
}
