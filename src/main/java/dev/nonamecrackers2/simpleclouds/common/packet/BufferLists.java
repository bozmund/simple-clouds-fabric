package dev.nonamecrackers2.simpleclouds.common.packet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;

/**
 * The collection helpers Minecraft 26.3 removed from {@link FriendlyByteBuf}
 * ({@code writeCollection}, {@code readList}, {@code readCollection}); vanilla now expects
 * everything to go through {@code StreamCodec}/{@code ByteBufCodecs}.
 *
 * <p>These are byte-for-byte what the old methods did — a var-int element count followed by the
 * elements — so the wire format of Simple Clouds' packets is unchanged and a 26.2 and a 26.3
 * client disagree about nothing. Rewriting the packets as stream codecs would be a bigger change
 * with the same result on the wire.
 */
public final class BufferLists
{
	private BufferLists()
	{
	}

	public static <T> void writeCollection(FriendlyByteBuf buffer, Collection<T> values,
			StreamEncoder<? super FriendlyByteBuf, T> encoder)
	{
		buffer.writeVarInt(values.size());
		for (T value : values)
			encoder.encode(buffer, value);
	}

	public static <T> List<T> readList(FriendlyByteBuf buffer, StreamDecoder<? super FriendlyByteBuf, T> decoder)
	{
		int size = buffer.readVarInt();
		List<T> values = new ArrayList<>(Math.min(size, 1024));
		for (int i = 0; i < size; i++)
			values.add(decoder.decode(buffer));
		return values;
	}
}

