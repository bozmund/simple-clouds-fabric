package net.minecraft.util.random;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.util.RandomSource;

/**
 * Fabric 26.2 compatibility: SimpleWeightedList was removed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 */
public class SimpleWeightedList<T>
{
	private final List<Weighted<T>> entries;

	private SimpleWeightedList(List<Weighted<T>> entries)
	{
		this.entries = entries;
	}

	public static <T> Builder<T> builder()
	{
		return new Builder<>();
	}

	public Optional<T> getRandomValue(RandomSource random)
	{
		if (this.entries.isEmpty())
			return Optional.empty();
		int totalWeight = this.entries.stream().mapToInt(Weighted::weight).sum();
		int randomValue = random.nextInt(totalWeight);
		int currentWeight = 0;
		for (Weighted<T> entry : this.entries)
		{
			currentWeight += entry.weight();
			if (randomValue < currentWeight)
				return Optional.of(entry.value());
		}
		return Optional.of(this.entries.get(this.entries.size() - 1).value());
	}

	public static class Builder<T>
	{
		private final List<Weighted<T>> entries = new ArrayList<>();

		public Builder<T> add(T value, int weight)
		{
			this.entries.add(new Weighted<>(value, weight));
			return this;
		}

		public SimpleWeightedList<T> build()
		{
			return new SimpleWeightedList<>(this.entries);
		}
	}
}
