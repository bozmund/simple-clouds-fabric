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
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;

//TODO: Docs, including with API
public class CloudCommands
{
    private static final class CommandNodes<C> {
        LiteralArgumentBuilder<C> literal(String name) {
            return LiteralArgumentBuilder.literal(name);
        }
        <T> com.mojang.brigadier.builder.RequiredArgumentBuilder<C,T> argument(
                String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
            return com.mojang.brigadier.builder.RequiredArgumentBuilder.argument(name,type);
        }
    }
	public static <C> void register(CommandDispatcher<C> dispatcher, String baseName, Predicate<C> requirement, CloudCommandSource<?, ?> source, CloudTypeSource cloudTypeSource)
	{
		CommandNodes<C> commands = new CommandNodes<>();
		LiteralArgumentBuilder<C> root = commands.literal(SimpleCloudsMod.MODID);
		
		root.then(commands.literal(baseName).requires(requirement)
				.then(commands.literal("clear")
						.then(commands.literal("all")
								.executes(ctx -> source.clearClouds(ctx, CloudCommandSource.ALL))
						)
						.then(commands.literal("storms")
								.executes(ctx -> source.clearClouds(ctx, CloudCommandSource.storms(cloudTypeSource)))
						)
				)
		);
		
		root.then(commands.literal(baseName).requires(requirement)
				.then(commands.literal("spawn")
						.then(commands.argument("type", CloudTypeArgument.type(cloudTypeSource))
								.then(commands.argument("position", Vec2Argument.vec2())
										.then(commands.argument("radius", FloatArgumentType.floatArg(0.0F))
												.then(commands.argument("stretchFactor", FloatArgumentType.floatArg(0.01F))
														.then(commands.argument("rotation", FloatArgumentType.floatArg())
																.then(commands.argument("lifeTime", TimeArgument.time(0))
																		.then(commands.argument("growTime", TimeArgument.time(0))
																				.then(commands.argument("direction", Vec2Argument.vec2(false))
																						.then(commands.argument("maxSpeed", FloatArgumentType.floatArg(0.0F))
																								.then(commands.argument("accelerationFactor", FloatArgumentType.floatArg(0.0F))
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
								.then(commands.literal("extreme")
										.executes(ctx -> source.spawnModifiedCloud(ctx, CloudCommandSource.EXTREME_CLOUD_INFO))
								)
								.then(commands.literal("temperate")
										.executes(ctx -> source.spawnModifiedCloud(ctx, CloudCommandSource.TEMPERATE_CLOUD_INFO))
								)
								.then(commands.literal("random")
										.executes(ctx -> source.spawnModifiedCloud(ctx, i -> i))
								)
						)
						.then(commands.literal("random")
								.executes(source::spawnRandomCloud)
						)
				)
		);
		
		root.then(commands.literal(baseName).requires(requirement)
				.then(commands.literal("get")
						.then(commands.literal("at")
								.then(commands.argument("position", Vec2Argument.vec2())
										.executes(source::getCloudTypeAt)
								)
						)
						.then(commands.literal("count")
								.then(commands.argument("position", Vec2Argument.vec2())
										.then(commands.argument("radius", IntegerArgumentType.integer(0))
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
			root.then(commands.literal(baseName).requires(requirement)
					.then(commands.literal("refresh")
							.executes(source::refreshClouds)
							)
					);
		}
		
		root.then(commands.literal(baseName).requires(requirement)
				.then(commands.literal("speed")
						.then(commands.literal("get")
								.executes(source::getSpeed)
						)
						.then(commands.literal("set")
								.then(commands.argument("amount", FloatArgumentType.floatArg(0.0F))
										.executes(source::setSpeed)
								)
						)
				)
		);
		
		root.then(commands.literal(baseName).requires(requirement)
				.then(commands.literal("seed")
						.then(commands.literal("get")
								.executes(source::getSeed)
						)
				)
		);
		
		root.then(commands.literal(baseName).requires(requirement)
				.then(commands.literal("height")
						.then(commands.literal("get")
								.executes(source::getCloudHeight)
						)
						.then(commands.literal("set")
								.then(commands.argument("height", IntegerArgumentType.integer(CloudManager.CLOUD_HEIGHT_MIN, CloudManager.CLOUD_HEIGHT_MAX))
										.executes(source::setCloudHeight)
								)
						)
				)
		);
		
		dispatcher.register(root);
	}
}
