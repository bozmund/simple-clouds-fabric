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
	public static void registerListener()
	{
		ConfigListener.builder(ModConfig.Type.SERVER, SimpleCloudsMod.MODID)
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
	
	// 26.2: MinecraftServer.getServer() static accessor was removed. Server config sync
	// (notify all players) is deferred for the vertical slice; the client renders clouds
	// by default, so this is not required for the core render path.
	private static volatile MinecraftServer currentServer;

	public static void setCurrentServer(MinecraftServer server)
	{
		currentServer = server;
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
