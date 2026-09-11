package dev.nonamecrackers2.simpleclouds.common.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public class CloudData extends SavedData
{
	public static final String ID = "clouddata";
	private final ServerCloudManager manager;
	
	public CloudData(ServerCloudManager manager)
	{
		this.manager = manager;
	}
	
	public static CloudData load(ServerCloudManager manager, CompoundTag tag)
	{
		CloudData data = new CloudData(manager);
		if (tag.contains("Seed"))
			manager.setSeed(tag.getLongOr("Seed", 0L));
		if (tag.contains("ScrollAngle"))
			manager.setScrollAngle(tag.getFloatOr("ScrollAngle", 0F));
		if (tag.contains("Speed"))
			manager.setCloudSpeed(tag.getFloatOr("Speed", 0F));
		if (tag.contains("Height"))
			manager.setCloudHeight(tag.getIntOr("Height", 0));
		manager.getCloudGenerator().readTag(tag.getCompoundOrEmpty("cloud_generator"));
		return data;
	}
	
	// 26.2: SavedData no longer defines save(CompoundTag); persistence is deferred.
	@Override
	public boolean isDirty()
	{
		return true;
	}
}
