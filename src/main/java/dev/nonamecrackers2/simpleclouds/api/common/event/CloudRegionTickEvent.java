package dev.nonamecrackers2.simpleclouds.api.common.event;

import org.jetbrains.annotations.Nullable;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.region.ScAPICloudRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import dev.nonamecrackers2.simpleclouds.api.Event;

public class CloudRegionTickEvent extends Event
{
	private final Level level;
	private final ScAPICloudRegion region;
	private @Nullable Vec2 modifiedMovementDirection;
	private float modifiedMaxSpeed = -1.0F;
	private float modifiedAccelerationFactor = -1.0F;
	
	public CloudRegionTickEvent(Level level, ScAPICloudRegion region)
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
	
	public @Nullable Vec2 getModifiedMovementDirection()
	{
		return this.modifiedMovementDirection;
	}
	
	public void setModifiedMovementDirection(Vec2 direction)
	{
		if (direction.length() <= 0.001F)
			throw new IllegalArgumentException("Direction vector must be non-zero");
		this.modifiedMovementDirection = direction.normalized();
	}
	
	public float getModifiedMaxSpeed()
	{
		return this.modifiedMaxSpeed;
	}
	
	public void setModifiedMaxSpeed(float speed)
	{
		if (speed <= 0.0F)
			throw new IllegalArgumentException("Max speed must be >= 0.0");
		this.modifiedMaxSpeed = speed;
	}
	
	public float getModifiedAccelerationFactor()
	{
		return this.modifiedAccelerationFactor;
	}
	
	public void setModifiedAccelerationFactor(float factor)
	{
		if (factor <= 0.0F)
			throw new IllegalArgumentException("Acceleration factor must be >= 0.0");
		this.modifiedAccelerationFactor = factor;
	}
}
