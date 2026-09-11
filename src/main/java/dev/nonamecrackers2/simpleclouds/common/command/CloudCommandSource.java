package dev.nonamecrackers2.simpleclouds.common.command;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;


import dev.nonamecrackers2.simpleclouds.common.api.SimpleCloudsHooks;
import dev.nonamecrackers2.simpleclouds.api.SimpleCloudsAPI;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.spawning.SpawnInfo;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.spawning.StaticSpawnInfo;

import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeSource;
import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import dev.nonamecrackers2.simpleclouds.common.cloud.region.CloudRegion;
import dev.nonamecrackers2.simpleclouds.common.cloud.spawning.CloudGenerator;
import dev.nonamecrackers2.simpleclouds.common.cloud.spawning.CloudSpawningConfig;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.ServerCloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.SpawnRegion;
import dev.nonamecrackers2.simpleclouds.common.world.SyncType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;

public interface CloudCommandSource<S extends Level, T extends CloudManager<S>>
{
	/**
	 * 26.2: works for both the server source (CommandSourceStack) and the
	 * Fabric client source (FabricClientCommandSource), so the same command
	 * tree can be registered on either side.
	 */
	static <C> void sendSuccess(C source, Component message, boolean broadcast)
	{
		if (source instanceof CommandSourceStack stack)
			stack.sendSuccess(() -> message, broadcast);
		else if (source instanceof FabricClientCommandSource client)
			client.sendFeedback(message);
		else
			throw new UnsupportedOperationException("Unsupported command source: " + source);
	}

	
	/** 26.2: vanilla Vec2 getters are bound to CommandSourceStack; client contexts resolve via the same path. */
	static <C> Vec2 getVec2Arg(CommandContext<C> context, String name) throws CommandSyntaxException
	{
		if (context.getSource() instanceof CommandSourceStack)
			return Vec2Argument.getVec2((CommandContext<CommandSourceStack>)(CommandContext<?>)context, name);
		throw new UnsupportedOperationException("Vec2 arguments require a server command source");
	}

	static <C> void sendError(C source, Component message)
	{
		if (source instanceof CommandSourceStack stack)
			stack.sendFailure(message);
		else if (source instanceof FabricClientCommandSource client)
			client.sendError(message);
		else
			throw new UnsupportedOperationException("Unsupported command source: " + source);
	}

	static <C> Level getLevelArg(C source)
	{
		if (source instanceof CommandSourceStack stack)
			return stack.getLevel();
		if (source instanceof FabricClientCommandSource client)
			return client.getLevel();
		throw new UnsupportedOperationException("Unsupported command source: " + source);
	}

	CloudCommandSource<ServerLevel, ServerCloudManager> SERVER = new CloudCommandSource<>()
	{
		@Override
		public <C> Player getPlayer(CommandContext<C> context) throws CommandSyntaxException
		{
			if (context.getSource() instanceof CommandSourceStack stack)
				return stack.getPlayerOrException();
			throw new IllegalArgumentException("Server command source required");
		}
		
		@Override
		public <C> ServerCloudManager getCloudManager(CommandContext<C> context) throws CommandSyntaxException
		{
			if (context.getSource() instanceof CommandSourceStack stack)
				return (ServerCloudManager)CloudManager.get(stack.getLevel());
			throw new IllegalArgumentException("Server command source required");
		}
		
		public void onValueUpdated(ServerCloudManager cloudManager, SyncType sync)
		{
			cloudManager.queueSync(sync);
		}
	};
	
	Predicate<CloudRegion> ALL = r -> true;
	
	Function<CloudSpawningConfig.Info, SpawnInfo> EXTREME_CLOUD_INFO = info -> 
	{
		return new StaticSpawnInfo(
				info.cloudType(),
				info.speed().max(),
				info.radius().maxInclusive(),
				info.existTicks().maxInclusive(),
				info.growTicks().maxInclusive(),
				info.stretchFactor().min(), //Not a bug. Smaller values makes the stretch bigger
				info.movesToPlayer(),
				info.orderWeight()
		);
	};
	Function<CloudSpawningConfig.Info, SpawnInfo> TEMPERATE_CLOUD_INFO = info -> 
	{
		return new StaticSpawnInfo(
				info.cloudType(),
				info.speed().min(),
				info.radius().minInclusive(),
				info.existTicks().minInclusive(),
				info.growTicks().minInclusive(),
				info.stretchFactor().max(), //Not a bug. Larger values makes the stretch smaller
				info.movesToPlayer(),
				info.orderWeight()
		);
	};
	
	static Predicate<CloudRegion> storms(CloudTypeSource source)
	{
		return r -> {
			CloudType type = source.getCloudTypeForId(r.getCloudTypeId());
			if (type != null)
				return type.weatherType().causesDarkening();
			return false;
		};
	}
	
	<C> T getCloudManager(CommandContext<C> context)  throws CommandSyntaxException;
	
	<C> Player getPlayer(CommandContext<C> context) throws CommandSyntaxException;
	
	void onValueUpdated(T cloudManager, SyncType sync);
	
	default <C> int getScrollAmount(CommandContext<C> context) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.scroll.get", manager.getScrollX(), manager.getScrollY(), manager.getScrollZ()), false);
		return 0;
	}
	
	default <C> int getSpeed(CommandContext<C> context) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.speed.get", manager.getCloudSpeed()), false);
		return 0;
	}
	
	default <C> int setSpeed(CommandContext<C> context) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		float speed = context.getArgument("amount", Float.class);
		manager.setCloudSpeed(speed);
		this.onValueUpdated(manager, SyncType.MOVEMENT);
		return 0;
	}
	
	default <C> int getSeed(CommandContext<C> context) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.seed.get", ComponentUtils.copyOnClickText(String.valueOf(manager.getSeed()))), true);
		return 0;
	} 
	
	default <C> int getCloudHeight(CommandContext<C> context) throws CommandSyntaxException
	{
		int height = this.getCloudManager(context).getCloudHeight();
		sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.height.get", height), false);
		return height;
	}
	
	default <C> int setCloudHeight(CommandContext<C> context) throws CommandSyntaxException
	{
		int height = context.getArgument("height", Integer.class);
		T manager = this.getCloudManager(context);
		manager.setCloudHeight(height);
		this.onValueUpdated(manager, SyncType.MOVEMENT);
		sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.height.set", height), true);
		return height;
	}
	
	default <C> int spawnCloud(CommandContext<C> context) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		CloudGenerator generator = manager.getCloudGenerator();
		Identifier id = context.getArgument("type", Identifier.class);
		Vec2 pos = getVec2Arg(context, "position");
		float radius = context.getArgument("radius", Float.class) / SimpleCloudsConstants.CLOUD_SCALE;
		float stretchFactor = context.getArgument("stretchFactor", Float.class);
		float rotation = (float)Math.PI / 180.0F * (context.getArgument("rotation", Float.class) % 360.0F);
		int lifeTime = context.getArgument("lifeTime", Integer.class);
		int growTime = context.getArgument("growTime", Integer.class);
		Vec2 direction = getVec2Arg(context, "direction");
		float maxSpeed = context.getArgument("maxSpeed", Float.class);
		float accelerationFactor = context.getArgument("accelerationFactor", Float.class);
		if (generator.addCloud(new CloudRegion(id, direction, maxSpeed, accelerationFactor, pos.x / SimpleCloudsConstants.CLOUD_SCALE, pos.y / SimpleCloudsConstants.CLOUD_SCALE, radius, rotation, stretchFactor, lifeTime, growTime, Integer.MAX_VALUE), CloudGenerator.Order.TOP))
		{
			sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.clouds.spawn", id, pos.x, pos.y), true);
			return 1;
		}
		else
		{
			sendError(context.getSource(), Component.translatable("command.simpleclouds.clouds.spawn.fail"));
			return 0;
		}
	}
	
	default <C> int spawnRandomCloud(CommandContext<C> context) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		CloudGenerator generator = manager.getCloudGenerator();
		CloudRegion region = generator.spawnCloud(generator.getSpawnConfig().get(), getLevelArg(context.getSource())).orElse(null);
		if (region != null)
		{
			sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.clouds.spawn", region.getCloudTypeId(), region.getWorldX(), region.getWorldZ()), true);
			return 1;
		}
		else
		{
			sendError(context.getSource(), Component.translatable("command.simpleclouds.clouds.spawn.fail"));
			return 0;
		}
	}
	
	default int spawnModifiedCloud(CommandContext<FabricClientCommandSource> context, Function<CloudSpawningConfig.Info, SpawnInfo> func) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		CloudGenerator generator = manager.getCloudGenerator();
		
		Identifier id = context.getArgument("type", Identifier.class);
		CloudSpawningConfig config = generator.getSpawnConfig().get();
		CloudSpawningConfig.Info info = config.getWeightInfo(id);
		
		if (info == null)
		{
			sendError(context.getSource(), Component.translatable("commands.simpleclouds.cloudType.notFound", id.toString()));
			return 0;
		}
		
		RandomSource random = RandomSource.create();
		CloudRegion region = generator.spawnCloud(() -> func.apply(info), config.getSpawnInterval().sample(random), config.getMaxRegions(), getLevelArg(context.getSource())).orElse(null);
		
		if (region != null)
		{
			sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.clouds.spawn", region.getCloudTypeId(), region.getWorldX(), region.getWorldZ()), true);
			return 2;
		}
		else
		{
			sendError(context.getSource(), Component.translatable("command.simpleclouds.clouds.spawn.fail"));
			return 1;
		}
	}
	
	//TODO: Explain results
	default <C> int getCloudTypeAt(CommandContext<C> context) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		CloudGenerator generator = manager.getCloudGenerator();
		Vec2 pos = getVec2Arg(context, "position");
		
		CloudRegion region = generator.getCloudAtWorldPosition(pos.x, pos.y);
		if (region == null)
		{
			sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.clouds.get.empty"), false);
			return 0;
		}
		
		CloudType type = manager.getCloudTypeForId(region.getCloudTypeId());
		if (type == null)
		{
			sendError(context.getSource(), Component.translatable("commands.simpleclouds.cloudType.notFound", region.getCloudTypeId().toString()));
			return 0;
		}
		sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.clouds.get", type.id().toString(), region.getWorldX(), region.getWorldZ(), type.weatherType().getSerializedName()), false);
		
		return type.weatherType().ordinal() + 1;
	}
	
	default int getCloudTypeCount(CommandContext<FabricClientCommandSource> context, boolean inRegion, boolean withRadius) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		CloudGenerator generator = manager.getCloudGenerator();
		
		List<CloudRegion> regions = Lists.newArrayList();
		
		if (inRegion)
		{
			Vec2 pos = getVec2Arg(context, "position");
			int radius = SimpleCloudsConstants.SPAWN_RADIUS;
			if (withRadius)
				radius = context.getArgument("radius", Integer.class);
			SpawnRegion region = new SpawnRegion(Mth.floor(pos.y) / SimpleCloudsConstants.CLOUD_SCALE, Mth.floor(pos.y) / SimpleCloudsConstants.CLOUD_SCALE, radius);
			regions.addAll(generator.getCloudsInRegion(region));
		}
		else
		{
			regions.addAll(generator.getClouds());
		}
		
		int size = regions.size();
		String types = regions.stream().map(t -> t.getCloudTypeId().toString()).distinct().collect(Collectors.joining(", "));
		sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.clouds.count", size, types), false);
		return size;
	}
	
	default int clearClouds(CommandContext<FabricClientCommandSource> context, Predicate<CloudRegion> region) throws CommandSyntaxException
	{
		T manager = this.getCloudManager(context);
		CloudGenerator generator = manager.getCloudGenerator();
		int amount = generator.removeCloudsCount(region);
		if (amount > 0)
			sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.clouds.clear", amount), true);
		else
			sendError(context.getSource(), Component.translatable("command.simpleclouds.clouds.clear.fail"));
		return amount;
	}
	
	default <C> int refreshClouds(CommandContext<C> context) throws CommandSyntaxException
	{
		if (SimpleCloudsAPI.getApi().getHooks().isExternalWeatherControlEnabled())
			return 0;
		T manager = this.getCloudManager(context);
		CloudGenerator generator = manager.getCloudGenerator();
		generator.removeAllClouds();
		for (SpawnRegion region : generator.getSpawnRegions())
			generator.doInitialGen(region.x(), region.z(), getLevelArg(context.getSource()), true);
		sendSuccess(context.getSource(), Component.translatable("command.simpleclouds.clouds.refresh"), true);
		return 1;
	}
}
