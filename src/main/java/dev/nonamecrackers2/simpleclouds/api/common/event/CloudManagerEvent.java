package dev.nonamecrackers2.simpleclouds.api.common.event;

import dev.nonamecrackers2.simpleclouds.api.common.world.ScAPICloudManager;
import net.minecraft.world.level.Level;
import dev.nonamecrackers2.simpleclouds.api.Event;

public abstract class CloudManagerEvent extends Event
{
	protected final Level level;
	protected final ScAPICloudManager manager;
	
	protected CloudManagerEvent(Level level, ScAPICloudManager manager)
	{
		this.level = level;
		this.manager = manager;
	}
	
	public Level getLevel()
	{
		return this.level;
	}
	
	public ScAPICloudManager getCloudManager()
	{
		return this.manager;
	}
}
