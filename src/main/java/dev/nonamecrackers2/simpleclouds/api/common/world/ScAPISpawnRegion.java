package dev.nonamecrackers2.simpleclouds.api.common.world;

public interface ScAPISpawnRegion
{
	int x();
	
	int z();
	
	int radius();
	
	boolean includesPoint(int x, int z);
	
	boolean intersectsCircle(float x, float z, float radius);
	
	int getMinX();
	
	int getMaxX();
	
	int getMinZ();
	
	int getMaxZ();
}
