package dev.nonamecrackers2.simpleclouds.api.common.cloud.spawning;

import java.util.Optional;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.ScAPICloudType;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.region.ScAPICloudRegion;
import net.minecraft.util.RandomSource;

@FunctionalInterface
public interface CreateRegionFunction
{
	Optional<? extends ScAPICloudRegion> create(@NotNull SpawnInfo info, float playerX, float playerZ, float x, float z, RandomSource random, boolean growTime);
}
