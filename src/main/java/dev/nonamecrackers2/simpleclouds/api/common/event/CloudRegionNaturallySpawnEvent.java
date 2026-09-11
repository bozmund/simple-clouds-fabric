package dev.nonamecrackers2.simpleclouds.api.common.event;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.region.ScAPICloudRegion;
import net.minecraft.world.level.Level;
import dev.nonamecrackers2.simpleclouds.api.Event;

public class CloudRegionNaturallySpawnEvent extends Event
{
	private final Level level;
	private final ScAPICloudRegion region;
	
	public CloudRegionNaturallySpawnEvent(Level level, ScAPICloudRegion region)
	{
		this.level = level;
		this.region = region;
	}
	
	public Level getLevel()
	{
		return this.level;
	}
	
	public ScAPICloudRegion getCloudRegion()
	{
		return this.region;
	}
}
