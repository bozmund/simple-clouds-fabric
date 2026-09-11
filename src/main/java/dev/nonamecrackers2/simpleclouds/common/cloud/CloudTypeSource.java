package dev.nonamecrackers2.simpleclouds.common.cloud;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.Identifier;

public interface CloudTypeSource
{
	@Nullable CloudType getCloudTypeForId(Identifier id);
	
	CloudType[] getIndexedCloudTypes();
	
	default boolean doesCloudTypeExist(Identifier id)
	{
		return this.getCloudTypeForId(id) != null;
	}
	
	default Optional<CloudType> getCloudTypeFromRawId(String id)
	{
		Identifier loc = Identifier.tryParse(id);
		if (id != null)
			return Optional.ofNullable(this.getCloudTypeForId(loc));
		return Optional.empty();
	}
}
