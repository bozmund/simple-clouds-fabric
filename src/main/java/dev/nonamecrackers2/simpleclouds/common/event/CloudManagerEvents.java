package dev.nonamecrackers2.simpleclouds.common.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudRegionsPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.UpdateCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.world.CloudData;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Fabric 26.2 port: uses Fabric's event system instead of Forge's.
 *
 * Ported:
 * - Server tick for cloud manager (ServerTickEvents)
 * - Player join for cloud sync (ServerPlayConnectionEvents.JOIN)
 * - Player dimension change + respawn resync: fabric-entity-events 5.0.5 has no
 *   change-dimension/respawn events and 26.2 moved dimension changes to the
 *   TeleportTransition system, so this polls per-player (dimension key or a
 *   >1024 block position jump) in the tick handler and resends the full sync.
 */
public class CloudManagerEvents
{
	/** Per-player last-synced dimension/position for change-detection resyncs. */
	private static final Map<UUID, ResourceKey<Level>> LAST_SYNCED_DIMENSION = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_SYNCED_POSITION = new ConcurrentHashMap<>();
	/** Resync a player who moved more than this far since the last full sync. */
	private static final int RESYNC_DISTANCE_BLOCKS = 1024;

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
				// Keep the per-level persistence file in sync with the ticking manager.
				CloudData cloudData = level.getDataStorage().get(CloudData.TYPE);
				if (cloudData != null)
					cloudData.markChanged();
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

				// Resync players whose dimension changed or who respawned far away
				// (the 1.20.1 LivingChangeDimensionEvent / PlayerRespawnEvent, polled).
				for (ServerPlayer player : level.players())
					checkPlayerResync(player);
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
			rememberSync(player);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
		{
			LAST_SYNCED_DIMENSION.remove(handler.getPlayer().getUUID());
			LAST_SYNCED_POSITION.remove(handler.getPlayer().getUUID());
		});
	}

	private static void checkPlayerResync(ServerPlayer player)
	{
		UUID id = player.getUUID();
		ResourceKey<Level> dim = player.level().dimension();
		long here = (player.blockPosition().getX() & 0xFFFFFFFFL) << 32 | (player.blockPosition().getZ() & 0xFFFFFFFFL);
		ResourceKey<Level> lastDim = LAST_SYNCED_DIMENSION.get(id);
		Long lastPos = LAST_SYNCED_POSITION.get(id);
		if (lastDim == null)
		{
			rememberSync(player);
			return;
		}
		if (lastDim.equals(dim) && lastPos != null)
		{
			int dx = (int) (player.blockPosition().getX() - ((lastPos >> 32) & 0xFFFFFFFFL));
			int dz = (int) (player.blockPosition().getZ() - (lastPos & 0xFFFFFFFFL));
			if (dx * dx + dz * dz <= RESYNC_DISTANCE_BLOCKS * RESYNC_DISTANCE_BLOCKS)
				return;
		}
		update(player);
		rememberSync(player);
	}

	private static void rememberSync(ServerPlayer player)
	{
		LAST_SYNCED_DIMENSION.put(player.getUUID(), player.level().dimension());
		long x = player.blockPosition().getX() & 0xFFFFFFFFL;
		long z = player.blockPosition().getZ() & 0xFFFFFFFFL;
		LAST_SYNCED_POSITION.put(player.getUUID(), x << 32 | z);
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
