package dev.nonamecrackers2.simpleclouds.common.command;

import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.api.SimpleCloudsAPI;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeSource;
import dev.nonamecrackers2.simpleclouds.common.command.argument.CloudTypeArgument;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;

//TODO: Docs, including with API
public class CloudCommands
{
	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, String baseName, Predicate<FabricClientCommandSource> requirement, CloudCommandSource<?, ?> source, CloudTypeSource cloudTypeSource)
	{
		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal(SimpleCloudsMod.MODID);
		
		root.then(ClientCommands.literal(baseName).requires(requirement)
				.then(ClientCommands.literal("clear")
						.then(ClientCommands.literal("all")
								.executes(ctx -> source.clearClouds(ctx, CloudCommandSource.ALL))
						)
						.then(ClientCommands.literal("storms")
								.executes(ctx -> source.clearClouds(ctx, CloudCommandSource.storms(cloudTypeSource)))
						)
				)
		);
		
		root.then(ClientCommands.literal(baseName).requires(requirement)
				.then(ClientCommands.literal("spawn")
						.then(ClientCommands.argument("type", CloudTypeArgument.type(cloudTypeSource))
								.then(ClientCommands.argument("position", Vec2Argument.vec2())
										.then(ClientCommands.argument("radius", FloatArgumentType.floatArg(0.0F))
												.then(ClientCommands.argument("stretchFactor", FloatArgumentType.floatArg(0.01F))
														.then(ClientCommands.argument("rotation", FloatArgumentType.floatArg())
																.then(ClientCommands.argument("lifeTime", TimeArgument.time(0))
																		.then(ClientCommands.argument("growTime", TimeArgument.time(0))
																				.then(ClientCommands.argument("direction", Vec2Argument.vec2(false))
																						.then(ClientCommands.argument("maxSpeed", FloatArgumentType.floatArg(0.0F))
																								.then(ClientCommands.argument("accelerationFactor", FloatArgumentType.floatArg(0.0F))
																										.executes(source::spawnCloud)
																								)
																						)
																				)
																		)
																)
														)
												)
										)
								)
								.then(ClientCommands.literal("extreme")
										.executes(ctx -> source.spawnModifiedCloud(ctx, CloudCommandSource.EXTREME_CLOUD_INFO))
								)
								.then(ClientCommands.literal("temperate")
										.executes(ctx -> source.spawnModifiedCloud(ctx, CloudCommandSource.TEMPERATE_CLOUD_INFO))
								)
								.then(ClientCommands.literal("random")
										.executes(ctx -> source.spawnModifiedCloud(ctx, i -> i))
								)
						)
						.then(ClientCommands.literal("random")
								.executes(source::spawnRandomCloud)
						)
				)
		);
		
		root.then(ClientCommands.literal(baseName).requires(requirement)
				.then(ClientCommands.literal("get")
						.then(ClientCommands.literal("at")
								.then(ClientCommands.argument("position", Vec2Argument.vec2())
										.executes(source::getCloudTypeAt)
								)
						)
						.then(ClientCommands.literal("count")
								.then(ClientCommands.argument("position", Vec2Argument.vec2())
										.then(ClientCommands.argument("radius", IntegerArgumentType.integer(0))
												.executes(ctx -> source.getCloudTypeCount(ctx, true, true))
										)
										.executes(ctx -> source.getCloudTypeCount(ctx, true, false))
								)
								.executes(ctx -> source.getCloudTypeCount(ctx, false, false))
						)
				)
		);
		
		if (!SimpleCloudsAPI.getApi().getHooks().isExternalWeatherControlEnabled())
		{
			root.then(ClientCommands.literal(baseName).requires(requirement)
					.then(ClientCommands.literal("refresh")
							.executes(source::refreshClouds)
							)
					);
		}
		
		root.then(ClientCommands.literal(baseName).requires(requirement)
				.then(ClientCommands.literal("speed")
						.then(ClientCommands.literal("get")
								.executes(source::getSpeed)
						)
						.then(ClientCommands.literal("set")
								.then(ClientCommands.argument("amount", FloatArgumentType.floatArg(0.0F))
										.executes(source::setSpeed)
								)
						)
				)
		);
		
		root.then(ClientCommands.literal(baseName).requires(requirement)
				.then(ClientCommands.literal("seed")
						.then(ClientCommands.literal("get")
								.executes(source::getSeed)
						)
				)
		);
		
		root.then(ClientCommands.literal(baseName).requires(requirement)
				.then(ClientCommands.literal("height")
						.then(ClientCommands.literal("get")
								.executes(source::getCloudHeight)
						)
						.then(ClientCommands.literal("set")
								.then(ClientCommands.argument("height", IntegerArgumentType.integer(CloudManager.CLOUD_HEIGHT_MIN, CloudManager.CLOUD_HEIGHT_MAX))
										.executes(source::setCloudHeight)
								)
						)
				)
		);
		
		dispatcher.register(root);
	}
}
