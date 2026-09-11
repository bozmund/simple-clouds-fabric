package dev.nonamecrackers2.simpleclouds.api.common.world;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.joml.Vector2f;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.ScAPICloudType;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.region.ScAPICloudRegion;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.spawning.ScAPICloudGenerator;
import net.minecraft.resources.Identifier;

public interface ScAPICloudManager
{
	ScAPICloudGenerator getCloudGenerator();
	
	List<? extends ScAPICloudRegion> getClouds();
	
	ScAPICloudType getCloudTypeForId(Identifier id);
	
	ScAPICloudType[] getIndexedCloudTypes();
	
	boolean isCloudGeneratorActive();
	
	boolean shouldUseVanillaWeather();
	
	Pair<? extends ScAPICloudType, Float> getCloudTypeAtPosition(float x, float z);
	
	float getRainLevel(float x, float y, float z);
	
	int getCloudHeight();
	
	void setCloudHeight(int height);
	
	CloudMode getCloudMode();
	
	String getSingleModeCloudTypeRawId();
	
	void spawnLightning(int x, int z, boolean soundOnly);
	
	Vector2f calculateWindDirection();
	
	int getTickCount();
	
	long getSeed();
	
	float getCloudSpeed();
	
	void setCloudSpeed(float speed);
	
	float getScrollAngle();
	
	void setScrollAngle(float angle);
	
	float getScrollX();
	
	float getScrollY();
	
	float getScrollZ();
	
	float getScrollX(float partialTick);
	
	float getScrollY(float partialTick);
	
	float getScrollZ(float partialTick);
}
