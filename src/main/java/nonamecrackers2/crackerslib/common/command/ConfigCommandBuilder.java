package nonamecrackers2.crackerslib.common.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ValueSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.server.command.EnumArgument;
import nonamecrackers2.crackerslib.common.command.argument.ConfigArgument;
import nonamecrackers2.crackerslib.common.config.ConfigHelper;
import nonamecrackers2.crackerslib.common.event.impl.OnConfigOptionSaved;

/**
 * Creates config commands for modifying config options in game
 */
public class ConfigCommandBuilder
{
	private final String modid;
	private final Map<ModConfig.Type, ForgeConfigSpec> specs = Maps.newEnumMap(ModConfig.Type.class);
	private final LiteralArgumentBuilder<FabricClientCommandSource> argumentBuilder;
	private final CommandDispatcher<FabricClientCommandSource> dispatcher;
	
	public ConfigCommandBuilder(String modid, LiteralArgumentBuilder<FabricClientCommandSource> argumentBuilder, CommandDispatcher<FabricClientCommandSource> dispatcher)
	{
		this.modid = modid;
		this.argumentBuilder = argumentBuilder;
		this.dispatcher = dispatcher;
	}
	
	/**
	 * Please use the other constructor taking in a mod ID 
	 */
	@Deprecated
	public ConfigCommandBuilder(LiteralArgumentBuilder<FabricClientCommandSource> argumentBuilder, CommandDispatcher<FabricClientCommandSource> dispatcher)
	{
		this("UNKNOWN", argumentBuilder, dispatcher);
	}
	
	public static ConfigCommandBuilder builder(CommandDispatcher<FabricClientCommandSource> dispatcher, String modid)
	{
		return new ConfigCommandBuilder(modid, ClientCommands.literal(modid), dispatcher);
	}
	
	public ConfigCommandBuilder addSpec(ModConfig.Type type, ForgeConfigSpec spec)
	{
		if (this.specs.containsKey(type))
			throw new IllegalArgumentException("Spec '" + type + "' already registered.");
		this.specs.put(type, spec);
		return this;
	}
	
	public void register()
	{
		var root = ClientCommands.literal("config");
		for (var entry : this.specs.entrySet())
		{
			ModConfig.Type type = entry.getKey();
			ForgeConfigSpec spec = entry.getValue();
			var specArgument = ClientCommands.literal(type.extension());
			if (type != ModConfig.Type.CLIENT)
				specArgument.requires(src -> true);
			addArgumentsForSpec(spec, this.modid, type, specArgument);
			root.then(specArgument);
		}
		this.argumentBuilder.then(root);
		this.dispatcher.register(this.argumentBuilder);
	}
	
	private static void addArgumentsForSpec(ForgeConfigSpec spec, String modid, ModConfig.Type type, LiteralArgumentBuilder<FabricClientCommandSource> specArgument)
	{
		Map<String, ForgeConfigSpec.ValueSpec> allValues = ConfigHelper.getAllSpecs(spec);
		var setArg = ClientCommands.literal("set")
				.then(
						ClientCommands.argument("double", ConfigArgument.arg(allValues, Double.class))
						.then(
								ClientCommands.argument("value", DoubleArgumentType.doubleArg())
								.executes(ctx -> set(ctx, "double", DoubleArgumentType::getDouble, spec, modid, type))
						)
						.then(
								ClientCommands.literal("default")
								.executes(ctx -> setDefault(ctx, "double", spec, modid, type))
						)
				)
				.then(
						ClientCommands.argument("boolean", ConfigArgument.arg(allValues, Boolean.class))
						.then(
								ClientCommands.argument("value", BoolArgumentType.bool())
								.executes(ctx -> set(ctx, "boolean", BoolArgumentType::getBool, spec, modid, type))
						)
						.then(
								ClientCommands.literal("default")
								.executes(ctx -> setDefault(ctx, "boolean", spec, modid, type))
						)
				)
				.then(
						ClientCommands.argument("integer", ConfigArgument.arg(allValues, Integer.class))
						.then(
								ClientCommands.argument("value", IntegerArgumentType.integer())
								.executes(ctx -> set(ctx, "integer", IntegerArgumentType::getInteger, spec, modid, type))
						)
						.then(
								ClientCommands.literal("default")
								.executes(ctx -> setDefault(ctx, "integer", spec, modid, type))
						)
				)
				.then(
						ClientCommands.argument("string", ConfigArgument.arg(allValues, String.class))
						.then(
								ClientCommands.argument("value", StringArgumentType.greedyString())
								.executes(ctx -> set(ctx, "string", StringArgumentType::getString, spec, modid, type))
						)
						.then(
								ClientCommands.literal("default")
								.executes(ctx -> setDefault(ctx, "string", spec, modid, type))
						)
				);
		//Auto register the command arguments for custom enums (really hacky)
		for (@SuppressWarnings("rawtypes") Class<Enum> clazz : gatherEnumValueClasses(allValues))
		{
			String name = clazz.getSimpleName();
			setArg.then(
					ClientCommands.argument(name, ConfigArgument.arg(allValues, clazz))
					.then(
							ClientCommands.argument("value", EnumArgument.enumArgument(clazz))
							.executes(ctx -> set(ctx, name, (ctx1, arg) -> ctx1.getArgument(arg, clazz), spec, modid, type))
					)
					.then(
							ClientCommands.literal("default")
							.executes(ctx -> setDefault(ctx, name, spec, modid, type))
					)
			);
		}
		specArgument.then(
				ClientCommands.literal("get")
				.then(
						ClientCommands.argument("value", ConfigArgument.any(allValues))
						.executes(ctx -> get(ctx, spec))
				)
		);
		specArgument.then(setArg);
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static List<Class<Enum>> gatherEnumValueClasses(Map<String, ForgeConfigSpec.ValueSpec> allValues)
	{
		List<Class<Enum>> list = Lists.newArrayList();
		for (var value : allValues.values())
		{
			Object obj = value.getDefault();
			if (obj instanceof Enum enu && !list.contains(enu.getDeclaringClass()))
				list.add(enu.getDeclaringClass());
		}
		return list;
	}
	
	private static <T> int set(CommandContext<FabricClientCommandSource> context, String arg, BiFunction<CommandContext<FabricClientCommandSource>, String, T> valueGetter, ForgeConfigSpec spec, String modid, ModConfig.Type type) throws CommandSyntaxException
	{
		FabricClientCommandSource source = context.getSource();
		ForgeConfigSpec.ConfigValue<T> config = ConfigArgument.get(context, arg, spec);
		T value = valueGetter.apply(context, "value");
		ValueSpec valueSpec = spec.getRaw(config.getPath());
		if (!valueSpec.test(value))
			return 0;
		OnConfigOptionSaved<T> event = new OnConfigOptionSaved<>(modid, type, OnConfigOptionSaved.Source.COMMAND, config, value, !Objects.equals(config.get(), value));
		MinecraftForge.EVENT_BUS.post(event);
		if (event.getOverrideValue() != null)
			value = event.getOverrideValue();
		if (!Objects.equals(config.get(), value) && valueSpec.test(value))
		{
			config.set(value);
			String joinedPath = ConfigHelper.DOT_JOINER.join(config.getPath());
			Component result = Component.translatable("commands.crackerslib.setConfig.set.success", joinedPath, value);
			source.sendFeedback(result);
			if (valueSpec.needsWorldRestart())
			{
				source.sendFeedback(Component.translatable("commands.crackerslib.setConfig.set.note", joinedPath).withStyle(ChatFormatting.GRAY));
				return 2;
			}
			else
			{
				return 1;
			}
		}
		else
		{
			source.sendError(Component.translatable("commands.crackerslib.setConfig.set.fail"));
			return 0;
		}
	}
	
	private static int get(CommandContext<FabricClientCommandSource> context, ForgeConfigSpec spec)
	{
		ForgeConfigSpec.ConfigValue<Object> config = ConfigArgument.get(context, "value", spec);
		Object val = config.get();
		context.getSource().sendFeedback(Component.translatable("commands.crackerslib.getConfig.get", ConfigHelper.DOT_JOINER.join(config.getPath()), config.get()));
		if (val instanceof Integer integer)
			return integer;
		else if (val instanceof Boolean bool)
			return bool ? 1 : 0;
		else if (val instanceof Double decimal)
			return (int)(decimal * 10.0D);
		else if (val instanceof Enum<?> enu)
			return enu.ordinal();
		else
			return -1;
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static int setDefault(CommandContext<FabricClientCommandSource> context, String arg, ForgeConfigSpec spec, String modid, ModConfig.Type type)
	{
		FabricClientCommandSource source = context.getSource();
		ForgeConfigSpec.ConfigValue<Object> config = ConfigArgument.get(context, arg, spec);
		ValueSpec valueSpec = spec.getRaw(config.getPath());
		boolean flag = !Objects.equals(config.get(), config.getDefault());
		MinecraftForge.EVENT_BUS.post(new OnConfigOptionSaved(modid, type, OnConfigOptionSaved.Source.COMMAND, config, config.getDefault(), flag));
		if (flag)
		{
			config.set(config.getDefault());
			String name = ConfigHelper.DOT_JOINER.join(config.getPath());
			source.sendFeedback(Component.translatable("commands.crackerslib.setDefault.success", name, config.get()));
			if (valueSpec.needsWorldRestart())
			{
				source.sendFeedback(Component.translatable("commands.crackerslib.setConfig.set.note", name).withStyle(ChatFormatting.GRAY));
				return 2;
			}
			else
			{
				return 1;
			}
		}
		else
		{
			source.sendError(Component.translatable("commands.crackerslib.setConfig.set.fail"));
			return 0;
		}
	}
}
