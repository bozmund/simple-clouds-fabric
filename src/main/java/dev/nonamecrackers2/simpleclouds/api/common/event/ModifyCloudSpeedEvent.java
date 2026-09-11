package dev.nonamecrackers2.simpleclouds.api.common.event;

import dev.nonamecrackers2.simpleclouds.api.common.world.ScAPICloudManager;
import net.minecraft.world.level.Level;

public class ModifyCloudSpeedEvent extends CloudManagerEvent
{
	private final float originalSpeed;
	private float speed;
	
	public ModifyCloudSpeedEvent(Level level, ScAPICloudManager manager, float speed)
	{
		super(level, manager);
		this.originalSpeed = speed;
		this.speed = speed;
	}
	
	public float getOriginalSpeed()
	{
		return this.originalSpeed;
	}
	
	public float getCurrentSpeed()
	{
		return this.speed;
	}
	
	public void setSpeed(float speed)
	{
		this.speed = speed;
	}
}
