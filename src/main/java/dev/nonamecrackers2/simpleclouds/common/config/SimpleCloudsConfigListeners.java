package dev.nonamecrackers2.simpleclouds.common.config;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifyCloudModeUpdatedPacket;
import dev.nonamecrackers2.simpleclouds.common.packet.impl.update.NotifySingleModeCloudTypeUpdatedPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.config.ModConfig;
import nonamecrackers2.crackerslib.common.config.listener.ConfigListener;

public class SimpleCloudsConfigListeners
{
	private static ConfigListener listener;

	public static void registerListener()
	{
		listener = ConfigListener.builder(ModConfig.Type.SERVER, SimpleCloudsMod.MODID)
				.addListener(SimpleCloudsConfig.SERVER.cloudMode, (o, n) -> onCloudModeChanged(n))
				.addListener(SimpleCloudsConfig.SERVER.singleModeCloudType, (o, n) -> onSingleModeCloudTypeChanged(n))
				.buildAndRegister();
	}
	
	public static void onCloudModeChanged(CloudMode newMode)
	{
		executeOnServerThread(() -> sendToAll(new NotifyCloudModeUpdatedPacket(newMode)));
	}
	
	public static void onSingleModeCloudTypeChanged(String newType)
	{
		executeOnServerThread(() -> sendToAll(new NotifySingleModeCloudTypeUpdatedPacket(newType)));
	}
	
	// Bound only for the running server; polling occurs on its tick thread.
	private static volatile MinecraftServer currentServer;

	public static void setCurrentServer(MinecraftServer server)
	{
		currentServer = server;
		if (listener != null)
		{
			if (server == null)
				listener.clearCache();
			else
				listener.resetCache();
		}
	}

	public static void tick(MinecraftServer server)
	{
		if (currentServer == server && listener != null)
			listener.poll();
	}

	private static void sendToAll(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload)
	{
		MinecraftServer server = currentServer;
		if (server != null)
		{
			for (ServerPlayer player : server.getPlayerList().getPlayers())
				ServerPlayNetworking.send(player, payload);
		}
	}

	private static void executeOnServerThread(Runnable runnable)
	{
		MinecraftServer server = currentServer;
		if (server != null)
			server.execute(runnable);
	}
}
