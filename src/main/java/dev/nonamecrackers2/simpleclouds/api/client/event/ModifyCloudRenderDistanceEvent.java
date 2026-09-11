package dev.nonamecrackers2.simpleclouds.api.client.event;

import dev.nonamecrackers2.simpleclouds.api.Event;

public class ModifyCloudRenderDistanceEvent extends Event
{
	private float renderDistance;
	
	public ModifyCloudRenderDistanceEvent(float renderDistance)
	{
		this.renderDistance = renderDistance;
	}
	
	public float getRenderDistance()
	{
		return this.renderDistance;
	}
	
	public void setRenderDistance(float distance)
	{
		this.renderDistance = distance;
	}
}
