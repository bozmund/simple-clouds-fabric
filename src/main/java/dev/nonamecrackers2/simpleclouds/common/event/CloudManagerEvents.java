package dev.nonamecrackers2.simpleclouds.common.event;

import java.util.List;

import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudRegionsPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.UpdateCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.ServerCloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.SpawnRegion;
import dev.nonamecrackers2.simpleclouds.common.world.SyncType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Fabric 26.2 port: uses Fabric's event system instead of Forge's.
 *
 * Ported:
 * - Server tick for cloud manager (ServerTickEvents)
 * - Player join for cloud sync (ServerPlayConnectionEvents.JOIN)
 *
 * Deferred (TODO):
 * - Player dimension change
 * - Player respawn
 */
public class CloudManagerEvents
{
	public static void register()
	{
		// Server tick: tick all cloud managers
		ServerTickEvents.END_SERVER_TICK.register(server ->
		{
			for (ServerLevel level : server.getAllLevels())
			{
				CloudManager<?> manager = CloudManager.get(level);
				if (manager == null)
					continue;
				manager.tick();
				if (manager instanceof ServerCloudManager serverManager)
				{
					SyncType syncType = serverManager.fetchNextSyncOperation();
					if (syncType != null)
					{
						switch (syncType)
						{
						case BASE_PROPERTIES:
							sendToDimension(level, new SendCloudManagerPacket(serverManager));
							break;
						case MOVEMENT:
							sendToDimension(level, new UpdateCloudManagerPacket(serverManager));
							break;
						case CLOUD_FORMATIONS:
							for (ServerPlayer player : level.players())
								sendCloudRegionsToPlayer(player);
							break;
						default:
							throw new IllegalArgumentException("Unexpected value: " + syncType);
						}
					}
					else if (manager.getTickCount() % CloudManager.UPDATE_INTERVAL == 0)
					{
						sendToDimension(level, new UpdateCloudManagerPacket(serverManager));
					}
				}
			}
		});

		// Player join: sync clouds
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
		{
			ServerPlayer player = handler.getPlayer();
			CloudManager<?> manager = CloudManager.get(player.level());
			if (manager != null)
				manager.onPlayerJoin(player);
			update(player);
		});
	}

	private static void sendToDimension(ServerLevel level, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload)
	{
		for (ServerPlayer player : level.players())
			ServerPlayNetworking.send(player, payload);
	}

	private static void update(ServerPlayer player)
	{
		CloudManager<ServerLevel> manager = CloudManager.get(player.level());
		if (manager == null)
			return;
		ServerPlayNetworking.send(player, new SendCloudManagerPacket(manager));
		sendCloudRegionsToPlayer(player);
	}

	private static void sendCloudRegionsToPlayer(ServerPlayer player)
	{
		CloudManager<ServerLevel> manager = CloudManager.get(player.level());
		if (manager == null)
			return;
		SpawnRegion region = new SpawnRegion(player.getBlockX(), player.getBlockZ(), SimpleCloudsConstants.SPAWN_RADIUS);
		List<CloudRegion> formationsForPlayer = manager.getCloudGenerator().getCloudsInRegion(region);
		ServerPlayNetworking.send(player, new SendCloudRegionsPacket(formationsForPlayer));
	}
}
