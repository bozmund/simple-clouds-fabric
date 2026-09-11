package dev.nonamecrackers2.simpleclouds.api;

import java.util.Objects;

import org.apache.maven.artifact.versioning.ArtifactVersion;

import dev.nonamecrackers2.simpleclouds.api.common.ScAPIHooks;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.region.ScAPICloudRegion;
import dev.nonamecrackers2.simpleclouds.api.common.world.ScAPICloudManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;

public interface SimpleCloudsAPI
{
	static SimpleCloudsAPI getApi()
	{
		return Objects.requireNonNull(ScAPIInternal.instance, "API is not available");
	}
	
	default ArtifactVersion getApiVersion()
	{
		return ScAPIInternal.version;
	}
	
	ArtifactVersion getSimpleCloudsVersion();
	
	ScAPICloudManager getCloudManager(Level level);
	
	ScAPIHooks getHooks();
	
	ScAPICloudRegion createCloudRegion(Identifier cloudTypeId, Vec2 movementDirection, float maxSpeed, float accelerationFactor, float posX, float posZ, float radius, float rotation, float stretchFactor, int existsForTicks, int growTicks, int orderWeight);
}
