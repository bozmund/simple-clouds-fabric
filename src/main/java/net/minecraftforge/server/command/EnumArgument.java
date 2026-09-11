package net.minecraftforge.server.command;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.network.chat.Component;

/**
 * Fabric 26.2 compatibility: EnumArgument from Forge, adapted to the 26.2 Brigadier API.
 */
public class EnumArgument<T extends Enum<T>> implements ArgumentType<T>
{
	private static final SimpleCommandExceptionType INVALID = new SimpleCommandExceptionType(Component.translatable("commands.generic.invalid"));
	private final Class<T> enumClass;
	private final T[] allowedValues;

	public EnumArgument(Class<T> enumClass, T... allowedValues)
	{
		this.enumClass = enumClass;
		this.allowedValues = allowedValues;
	}

	public static <T extends Enum<T>> EnumArgument<T> enumArgument(Class<T> enumClass)
	{
		return new EnumArgument<>(enumClass, enumClass.getEnumConstants());
	}

	@Override
	public T parse(StringReader reader) throws CommandSyntaxException
	{
		String s = reader.readString();
		for (T value : this.allowedValues)
		{
			if (value.name().equalsIgnoreCase(s))
			{
				return value;
			}
		}
		throw INVALID.create();
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder)
	{
		for (T value : this.allowedValues)
			builder.suggest(value.name());
		return builder.buildFuture();
	}

	@Override
	public Collection<String> getExamples()
	{
		return Arrays.stream(this.allowedValues).map(Enum::name).toList();
	}
}
