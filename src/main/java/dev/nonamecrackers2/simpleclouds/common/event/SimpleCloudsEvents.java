package dev.nonamecrackers2.simpleclouds.common.event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeDataManager;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import dev.nonamecrackers2.simpleclouds.common.cloud.spawning.CloudGenerator;
import dev.nonamecrackers2.simpleclouds.common.cloud.spawning.CloudSpawningDataManager;
import dev.nonamecrackers2.simpleclouds.common.command.CloudCommandSource;
import dev.nonamecrackers2.simpleclouds.common.command.CloudCommands;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfigLoader;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudTypesPacket;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import nonamecrackers2.crackerslib.common.command.ConfigCommandBuilder;

/**
 * Fabric 26.2 port: uses Fabric's event system instead of Forge's.
 *
 * Ported:
 * - Command registration (CommandRegistrationCallback)
 * - Reload listeners (SynchronousResourceReloadListener)
 * - Datapack sync (ServerPlayConnectionEvents / ServerLifecycleEvents)
 *
 * Deferred (TODO):
 * - Sleeping during thunder (PlayerSleepEvent equivalent)
 * - Removing storms after sleeping
 */
public class SimpleCloudsEvents
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/SimpleCloudsEvents");

	public static void register()
	{
		// Command registration — DEFERRED in the 26.2 vertical slice.
		// The new command backend serializes every custom Brigadier argument type when
		// sending the command tree to players (ClientboundCommandsPacket →
		// ArgumentTypeInfos.unpack), and only vanilla-bootstrapped types are recognized.
		// Our CloudTypeArgument/EnumArgument would therefore kill player joins. Port the
		// argument types via a bootstrap mixin (or string-arg + manual validation) later;
		// commands are not required for the cloud rendering vertical slice.
		//
		// CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
		// {
		// 	ConfigCommandBuilder.builder(dispatcher, SimpleCloudsMod.MODID)
		// 			.addSpec(ModConfig.Type.SERVER, SimpleCloudsConfig.SERVER_SPEC)
		// 			.addSpec(ModConfig.Type.COMMON, SimpleCloudsConfig.COMMON_SPEC)
		// 			.register();
		// 	CloudCommands.register(dispatcher, "clouds", src -> true, CloudCommandSource.SERVER, CloudTypeDataManager.getServerInstance());
		// });

		// Config loading: Forge's addSpec (deferred with the commands above) used to make
		// Forge load + bind these specs; on Fabric we do it explicitly so isLoaded() works
		// and ConfigValue.get() doesn't throw on player join.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> SimpleCloudsConfigLoader.loadServerConfigs());

		// Reload listeners: register the data managers directly so cloud types + spawning
		// config load on server start and on /reload. The spawning manager depends on the
		// cloud type manager (declared via getFabricDependencies).
		ResourceManagerHelper serverHelper = ResourceManagerHelper.get(net.minecraft.server.packs.PackType.SERVER_DATA);
		serverHelper.registerReloadListener(CloudTypeDataManager.getServerInstance());
		serverHelper.registerReloadListener(CloudSpawningDataManager.getInstance());

		// Datapack sync: send cloud types to players when they join
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
		{
			ServerPlayer player = handler.getPlayer();
			CloudTypeDataManager manager = CloudTypeDataManager.getServerInstance();
			if (manager != null)
				ServerPlayNetworking.send(player, new SendCloudTypesPacket(manager));
		});
	}

	// Deferred: sleeping during thunder
	public static boolean allowSleepingDuringThunderClouds(Player player)
	{
		CloudManager<?> manager = CloudManager.get(player.level());
		if (manager == null)
			return false;
		if (!manager.shouldUseVanillaWeather())
		{
			var cloudType = manager.getCloudTypeAtWorldPos((float) player.getX(), (float) player.getZ());
			if (cloudType != null && cloudType.getLeft().weatherType().includesThunder())
				return true;
		}
		return false;
	}

	// Deferred: removing storms after sleeping
	public static void removeStormsAfterSleeping(LevelAccessor levelAccessor)
	{
		if (levelAccessor instanceof ServerLevel level)
		{
			CloudManager<?> manager = CloudManager.get(level);
			if (manager == null || manager.shouldUseVanillaWeather())
				return;
			CloudGenerator generator = manager.getCloudGenerator();
			for (Player player : level.players())
			{
				CloudRegion region = generator.getCloudAtWorldPosition((float) player.getX(), (float) player.getZ());
				if (region == null)
					continue;
				CloudType type = manager.getCloudTypeForId(region.getCloudTypeId());
				if (type == null)
				{
					LOGGER.warn("Could not find cloud type with ID '{}' for cloud region; this should not happen!", region.getCloudTypeId());
					continue;
				}
				if (type.weatherType().includesThunder())
					generator.removeClouds(r -> r == region);
			}
		}
	}
}
