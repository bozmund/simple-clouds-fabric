package dev.nonamecrackers2.simpleclouds.api.common.cloud.spawning;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

public record StaticSpawnInfo(Identifier cloudType, float speed, int radius, int existTicks, int growTicks, float stretchFactor, boolean movesToPlayer, int orderWeight) implements SpawnInfo
{
	@Override
	public float determineSpeed(RandomSource random)
	{
		return this.speed;
	}

	@Override
	public int determineRadius(RandomSource random)
	{
		return this.radius;
	}

	@Override
	public int determineExistTicks(RandomSource random)
	{
		return this.existTicks;
	}

	@Override
	public int determineGrowTicks(RandomSource random)
	{
		return this.growTicks;
	}

	@Override
	public float determineStretchFactor(RandomSource random)
	{
		return this.stretchFactor;
	}
}
