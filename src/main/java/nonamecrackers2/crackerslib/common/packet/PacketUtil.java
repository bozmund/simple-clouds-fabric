package nonamecrackers2.crackerslib.common.packet;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric networking layer (replaces Forge's SimpleChannel + PacketUtil).
 *
 * Concrete packets are records implementing {@link CustomPacketPayload} with their own
 * {@link CustomPacketPayload.Type} and {@link StreamCodec}. This class centralizes
 * registration and sending.
 */
public class PacketUtil
{
	/**
	 * Register a play-to-client payload type and its handler.
	 */
	public static <T extends CustomPacketPayload> void registerToClient(CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf, T> codec, ClientPlayNetworking.PlayPayloadHandler<T> handler)
	{
		PayloadTypeRegistry.clientboundPlay().register(type, codec);
		ClientPlayNetworking.registerGlobalReceiver(type, handler);
	}

	/**
	 * Register a play-to-server payload type and its handler.
	 */
	public static <T extends CustomPacketPayload> void registerToServer(CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf, T> codec, ServerPlayNetworking.PlayPayloadHandler<T> handler)
	{
		PayloadTypeRegistry.serverboundPlay().register(type, codec);
		ServerPlayNetworking.registerGlobalReceiver(type, handler);
	}

	/**
	 * Send a payload to a specific player (server -> client).
	 */
	public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload)
	{
		ServerPlayNetworking.send(player, payload);
	}

	/**
	 * Send a payload to all players in a level (server -> client).
	 */
	public static void sendToAll(ServerLevel level, CustomPacketPayload payload)
	{
		for (ServerPlayer player : level.players())
			ServerPlayNetworking.send(player, payload);
	}

	/**
	 * Helper to build a payload type id from a mod id and path.
	 */
	public static Identifier id(String modId, String path)
	{
		return Identifier.fromNamespaceAndPath(modId, path);
	}
}
