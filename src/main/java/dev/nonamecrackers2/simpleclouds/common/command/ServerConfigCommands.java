package dev.nonamecrackers2.simpleclouds.common.command;

import java.util.Map;
import java.util.Objects;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.config.ModConfig;
import nonamecrackers2.crackerslib.common.config.ConfigHelper;
import nonamecrackers2.crackerslib.common.event.impl.OnConfigOptionSaved;

/** Original server/common config command syntax, without client class linkage.
 * Vanilla wire arguments replace Forge's config/enum argument serializers.
 * Value type, allowed paths and ranges remain authoritative in the config spec. */
public final class ServerConfigCommands {
    private static final SimpleCommandExceptionType INVALID = new SimpleCommandExceptionType(
            Component.translatable("commands.generic.invalid"));
    private ServerConfigCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var config = Commands.literal("config").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (var entry : Map.of(ModConfig.Type.SERVER, SimpleCloudsConfig.SERVER_SPEC,
                ModConfig.Type.COMMON, SimpleCloudsConfig.COMMON_SPEC).entrySet()) {
            var type=entry.getKey(); var spec=entry.getValue();
            var paths=ConfigHelper.getAllSpecs(spec);
            var scope=Commands.literal(type.extension());
            scope.then(Commands.literal("get").then(Commands.argument("option", StringArgumentType.word())
                    .suggests((ctx,b) -> SharedSuggestionProvider.suggest(paths.keySet(),b))
                    .executes(ctx -> {
                        var value=option(spec,paths,StringArgumentType.getString(ctx,"option"));
                        Object current=value.get();
                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.crackerslib.getConfig.get",
                                String.join(".",value.getPath()),current),false);
                        return current instanceof Integer n ? n : current instanceof Boolean b ? (b?1:0)
                                : current instanceof Double d ? (int)(d*10) : current instanceof Enum<?> e ? e.ordinal() : -1;
                    })));
            scope.then(Commands.literal("set").then(Commands.argument("option", StringArgumentType.word())
                    .suggests((ctx,b) -> SharedSuggestionProvider.suggest(paths.entrySet().stream()
                            .filter(e -> supported(e.getValue().getDefault())).map(Map.Entry::getKey),b))
                    .then(Commands.literal("default").executes(ctx -> {
                        var value=option(spec,paths,StringArgumentType.getString(ctx,"option"));
                        if(!supported(value.getDefault())) throw INVALID.create();
                        return set(ctx.getSource(),spec,type,value,value.getDefault(),true);
                    }))
                    .then(Commands.argument("value",StringArgumentType.greedyString())
                            .suggests((ctx,b) -> {
                                var value=option(spec,paths,StringArgumentType.getString(ctx,"option"));
                                Object def=value.getDefault();
                                if(def instanceof Enum<?> e) return SharedSuggestionProvider.suggest(
                                        java.util.Arrays.stream(e.getDeclaringClass().getEnumConstants()).map(Enum::name),b);
                                if(def instanceof Boolean) return SharedSuggestionProvider.suggest(java.util.List.of("true","false"),b);
                                return b.buildFuture();
                            })
                            .executes(ctx -> {
                                var value=option(spec,paths,StringArgumentType.getString(ctx,"option"));
                                return set(ctx.getSource(),spec,type,value,
                                        parseValue(value.getDefault(),StringArgumentType.getString(ctx,"value")),false);
                            }))));
            config.then(scope);
        }
        dispatcher.register(Commands.literal(SimpleCloudsMod.MODID).then(config));
    }

    private static ForgeConfigSpec.ConfigValue<Object> option(ForgeConfigSpec spec,
            Map<String,ForgeConfigSpec.ValueSpec> paths,String path) throws CommandSyntaxException {
        if(!spec.isLoaded() || !paths.containsKey(path)) throw INVALID.create();
        return spec.getValues().get(path);
    }
    private static boolean supported(Object value) {
        return value instanceof Integer || value instanceof Double || value instanceof Boolean
                || value instanceof String || value instanceof Enum<?>;
    }
    public static Object parseValue(Object defaultValue,String input) throws CommandSyntaxException {
        if(defaultValue instanceof String) return input;
        if(defaultValue instanceof Enum<?> e) {
            for(var value : e.getDeclaringClass().getEnumConstants())
                if(value.name().equalsIgnoreCase(input)) return value;
            throw INVALID.create();
        }
        StringReader reader=new StringReader(input);
        Object value;
        if(defaultValue instanceof Integer) value=IntegerArgumentType.integer().parse(reader);
        else if(defaultValue instanceof Double) value=DoubleArgumentType.doubleArg().parse(reader);
        else if(defaultValue instanceof Boolean) value=BoolArgumentType.bool().parse(reader);
        else throw INVALID.create();
        if(reader.canRead()) throw INVALID.create();
        return value;
    }
    private static int set(CommandSourceStack source,ForgeConfigSpec spec,ModConfig.Type type,
            ForgeConfigSpec.ConfigValue<Object> config,Object proposed,boolean reset) throws CommandSyntaxException {
        ForgeConfigSpec.ValueSpec valueSpec=spec.getRaw(config.getPath());
        if(!valueSpec.test(proposed)) throw INVALID.create();
        Object old=config.get();
        var event=new OnConfigOptionSaved<>(SimpleCloudsMod.MODID,type,OnConfigOptionSaved.Source.COMMAND,
                config,proposed,!Objects.equals(old,proposed));
        MinecraftForge.EVENT_BUS.post(event);
        Object value=event.getOverrideValue()!=null ? event.getOverrideValue() : proposed;
        if(!valueSpec.test(value)) throw INVALID.create();
        if(Objects.equals(old,value)) {
            source.sendFailure(Component.translatable("commands.crackerslib.setConfig.set.fail")); return 0;
        }
        config.set(value);
        try { config.save(); }
        catch(RuntimeException failure) {
            config.set(old);
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/ConfigCommand").error("Config save failed; restored in-memory value",failure);
            throw INVALID.create();
        }
        String path=String.join(".",config.getPath());
        source.sendSuccess(() -> Component.translatable(reset ? "commands.crackerslib.setDefault.success"
                : "commands.crackerslib.setConfig.set.success",path,value),true);
        if(valueSpec.needsWorldRestart()) {
            source.sendSuccess(() -> Component.translatable("commands.crackerslib.setConfig.set.note",path),false);
            return 2;
        }
        return 1;
    }
}
