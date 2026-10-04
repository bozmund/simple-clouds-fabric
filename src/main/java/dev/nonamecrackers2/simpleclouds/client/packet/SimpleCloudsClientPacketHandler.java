package dev.nonamecrackers2.simpleclouds.client.packet;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.config.SimpleCloudsClientConfigListeners;
import dev.nonamecrackers2.simpleclouds.client.config.ClientServerConfig;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendServerConfigPacket;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.MultiRegionCloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.world.ClientCloudManager;
import dev.nonamecrackers2.simpleclouds.client.world.CloudScrollSmoother;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudRegionsPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SendCloudTypesPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.SpawnLightningPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.UpdateCloudManagerPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifyCloudModeUpdatedPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifySingleModeCloudTypeUpdatedPacket;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public class SimpleCloudsClientPacketHandler
{
	private static final Logger LOGGER = LogManager.getLogger();
	private static final boolean TRACE_VISUAL_CHURN = "1".equals(System.getenv("SIMPLECLOUDS_TRACE_VISUAL_CHURN"));

	private static long clientTick()
	{
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? -1L : level.getGameTime();
	}

	private static String regionsForTrace(List<CloudRegion> regions)
	{
		StringBuilder result = new StringBuilder().append(regions.size()).append('[');
		for (CloudRegion region : regions)
		{
			if (result.charAt(result.length() - 1) != '[')
				result.append(';');
			result.append(region.getCloudTypeId()).append('@')
					.append(region.getPosX()).append(',').append(region.getPosZ())
					.append("/r").append(region.getRadius())
					.append("/s").append(region.getStretch())
					.append("/a").append(region.getRotation());
		}
		return result.append(']').toString();
	}

	private static void traceFormations(String source, CloudManager<ClientLevel> manager, List<CloudRegion> incoming)
	{
		if (TRACE_VISUAL_CHURN)
			LOGGER.info("[CLOUD-PACKET] {} tick={} before={} incoming={}", source, clientTick(),
					regionsForTrace(manager.getCloudGenerator().getClouds()), regionsForTrace(incoming));
	}

	public static void handleUpdateCloudManagerPacket(UpdateCloudManagerPacket packet)
	{
		Minecraft mc = Minecraft.getInstance();
		CloudManager<ClientLevel> manager = CloudManager.get(mc.level);
		handleUpdateCloudManagerPacket(packet, manager);
		//LOGGER.debug("Updating client-side cloud manager");
	}

	public static void handleSendServerConfigPacket(SendServerConfigPacket packet)
	{
		var previous = ClientServerConfig.get();
		ClientServerConfig.receive(packet.config());
		LOGGER.debug("Received server cloud config: mode={} dimensions={} blacklist={}",
				packet.config().cloudMode(), packet.config().dimensionWhitelist().size(), packet.config().whitelistAsBlacklist());
		// Initial config may precede manager sync. Its full-sync handler handles
		// reinitialization then; later mode changes also need a different generator.
		if (previous != null && previous.cloudMode() != packet.config().cloudMode())
			SimpleCloudsRenderer.getOptionalInstance().ifPresent(SimpleCloudsRenderer::requestReload);
		else if (previous == null && ClientCloudManager.isAvailableServerSide())
			SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer -> {
				if (renderer.needsReinitialization()) renderer.requestReload();
			});
	}

	public static void handleUpdateCloudManagerPacket(UpdateCloudManagerPacket packet, CloudManager<ClientLevel> manager)
	{
		if (TRACE_VISUAL_CHURN)
			LOGGER.info("[CLOUD-PACKET] movement tick={} localAngle={} serverAngle={} localSpeed={} serverSpeed={}",
					clientTick(), manager.getScrollAngle(), packet.scrollAngle(), manager.getCloudSpeed(), packet.speed());
		if (manager instanceof ClientCloudManager clientManager)
			clientManager.acceptServerScrollAngle(packet.scrollAngle());
		else
			manager.setScrollAngle(packet.scrollAngle());
		manager.setCloudSpeed(packet.speed());
		manager.setCloudHeight(packet.cloudHeight());
		if (manager instanceof ClientCloudManager clientManager)
			clientManager.setReceivedSync();
	}

	public static void handleSendCloudManagerPacket(SendCloudManagerPacket packet)
	{
		Minecraft mc = Minecraft.getInstance();
		CloudManager<ClientLevel> manager = CloudManager.get(mc.level);
		traceFormations("full", manager, packet.cloudRegions());
		if (TRACE_VISUAL_CHURN)
			LOGGER.info("[CLOUD-PACKET] full tick={} localAngle={} serverAngle={} localSeed={} serverSeed={}",
					clientTick(), manager.getScrollAngle(), packet.scrollAngle(), manager.getSeed(), packet.seed());
		if (manager instanceof ClientCloudManager clientManager
			&& CloudScrollSmoother.shouldSmoothFullSync(clientManager.hasReceivedSync(), manager.getSeed(), packet.seed()))
		{
			// A same-world full resend can arrive after the client has already
			// rendered clouds. Its clock correction must not move the whole sky.
			LOGGER.info("[CLOUD-SYNC] full same-seed cloud manager resend");
			clientManager.acceptServerScrollAngle(packet.scrollAngle());
		}
		else
		{
			// A new world or first join needs the server phase immediately.
			manager.setScrollAngle(packet.scrollAngle());
		}
		manager.setCloudSpeed(packet.speed());
		manager.setCloudHeight(packet.cloudHeight());
		if (manager instanceof ClientCloudManager clientManager)
			clientManager.setReceivedSync();
		manager.setSeed(packet.seed());
		manager.getCloudGenerator().setClouds(packet.cloudRegions());
		SimpleCloudsRenderer renderer = SimpleCloudsRenderer.getInstance();
		if (ClientServerConfig.get() != null || SimpleCloudsConfig.SERVER_SPEC.isLoaded())
		{
			if (renderer.needsReinitialization())
			{
				LOGGER.debug("Looks like the server cloud mode or region generator does not match with the client. Requesting a reload...");
				renderer.requestReload();
			}
		}
		else
		{
			LOGGER.warn("Server configuration has not been synchronized");
		}
		LOGGER.debug("Received cloud manager info");
	}

	public static void handleSendCloudRegionsPacket(SendCloudRegionsPacket packet)
	{
		Minecraft mc = Minecraft.getInstance();
		CloudManager<ClientLevel> manager = CloudManager.get(mc.level);
		traceFormations("formations", manager, packet.cloudRegions());
		manager.getCloudGenerator().setClouds(packet.cloudRegions());
	}

	public static void handleCloudTypesPacket(SendCloudTypesPacket packet)
	{
		LOGGER.debug("Received {} synced cloud types", packet.types().size());
		ClientSideCloudTypeManager.getInstance().receiveSynced(packet.types(), packet.indexed());
		if (SimpleCloudsRenderer.getInstance().getMeshGenerator() instanceof MultiRegionCloudMeshGenerator meshGenerator)
		{
			if (packet.types().size() > MultiRegionCloudMeshGenerator.MAX_CLOUD_TYPES)
				LOGGER.warn("The amount of loaded cloud types exceeds the maximum of {}. Please be aware that not all cloud types loaded will be used.", MultiRegionCloudMeshGenerator.MAX_CLOUD_TYPES);
			else
				meshGenerator.updateCloudTypes();
		}
	}

	public static void handleSpawnLightningPacket(SpawnLightningPacket packet)
	{
		SimpleCloudsRenderer.getInstance().getWorldEffectsManager().spawnLightning(packet.pos(), packet.onlySound(), packet.seed(), packet.maxDepth(), packet.branchCount(), packet.maxBranchLength(), packet.maxWidth(), packet.minimumPitch(), packet.maximumPitch());
	}

	public static void handleNotifyCloudModeUpdatedPacket(NotifyCloudModeUpdatedPacket packet)
	{
		SimpleCloudsClientConfigListeners.onCloudModeUpdatedFromServer(packet.newMode());
	}

	public static void handleNotifySingleModeCloudTypeUpdatedPacket(NotifySingleModeCloudTypeUpdatedPacket packet)
	{
		SimpleCloudsClientConfigListeners.onSingleModeCloudTypeUpdatedFromServer(packet.newType());
	}
}
