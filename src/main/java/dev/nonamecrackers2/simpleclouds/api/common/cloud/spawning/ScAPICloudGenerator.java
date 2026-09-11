package dev.nonamecrackers2.simpleclouds.api.common.cloud.spawning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.region.ScAPICloudRegion;
import dev.nonamecrackers2.simpleclouds.api.common.world.ScAPISpawnRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

public interface ScAPICloudGenerator
{
	List<? extends ScAPICloudRegion> getClouds();
	
	List<? extends ScAPISpawnRegion> getSpawnRegions();
	
	List<? extends ScAPICloudRegion> api_getCloudsInRegion(ScAPISpawnRegion region);
	
	List<? extends ScAPISpawnRegion> api_getRegionsThatOccupyCloud(ScAPICloudRegion cloud);
	
	@Nullable ScAPICloudRegion getCloudAtPosition(float x, float z);
	
	@Nullable ScAPICloudRegion getCloudAtWorldPosition(float worldX, float worldZ);
	
	Optional<? extends ScAPICloudRegion> createRegion(SpawnInfo info, float playerX, float playerZ, float x, float z, RandomSource random, boolean growTime);
	
	Optional<? extends ScAPICloudRegion> spawnCloud(Supplier<SpawnInfo> infoGetter, int nextSpawnInterval, int maxRegions, Level level);
	
	Optional<? extends ScAPICloudRegion> spawnCloud(Supplier<SpawnInfo> infoGetter, int nextSpawnInterval, int maxRegions, Level level, CreateRegionFunction regionFunc);
	
	int getTotalCloudRegions();
	
	void api_setClouds(Collection<? extends ScAPICloudRegion> clouds);
	
	boolean removeAllClouds();
	
	boolean api_removeClouds(Predicate<? extends ScAPICloudRegion> predicate);
	
	int api_removeCloudsCount(Predicate<? extends ScAPICloudRegion> predicate);
	
	boolean addCloudToTop(ScAPICloudRegion region);
	
	boolean addCloudToBottom(ScAPICloudRegion region);
	
	boolean addCloudUsingWeight(ScAPICloudRegion region);
	
	static enum ApiOrder
	{
		TOP,
		BOTTOM,
		USE_WEIGHT;
	}
}
