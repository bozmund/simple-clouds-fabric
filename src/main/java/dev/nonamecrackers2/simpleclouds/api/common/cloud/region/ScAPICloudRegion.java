package dev.nonamecrackers2.simpleclouds.api.common.cloud.region;

import org.joml.Matrix2f;

import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec2;

public interface ScAPICloudRegion
{
	Identifier getCloudTypeId();
	
	int getOrderWeight();
	
	boolean isDead();
	
	Vec2 getMovementDirection();
	
	void setMovementDirection(Vec2 direction);
	
	float getMaxSpeed();
	
	void setMaxSpeed(float speed);
	
	float getAccelerationFactor();
	
	void setAccelerationFactor(float factor);
	
	float getPosX(float partialTick);
	
	float getPosX();
	
	float getWorldX();
	
	float getPosZ(float partialTick);
	
	float getPosZ();
	
	float getWorldZ();
	
	void moveTo(float x, float z);
	
	void moveToWorldPos(float x, float z);
	
	float getInitialRadius();
	
	float getInitialWorldRadius();
	
	float getRadius(float partialTick);
	
	float getRadius();
	
	float getWorldRadius();
	
	void setRadius(float radius);
	
	void setWorldRadius(float radius);
	
	float getStretch(float partialTick);
	
	float getStretch();
	
	void setStretchFactor(float factor);
	
	float getRotation(float partialTick);
	
	float getRotation();
	
	void setRotation(float rotation);
	
	boolean wasPriorVisible();
	
	Matrix2f createTransform(float partialTick);
}
