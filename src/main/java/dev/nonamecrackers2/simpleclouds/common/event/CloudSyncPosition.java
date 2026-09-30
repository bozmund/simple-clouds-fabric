package dev.nonamecrackers2.simpleclouds.common.event;

/** Last X/Z from a full cloud sync, without 32-bit squared-distance overflow. */
public record CloudSyncPosition(int x, int z)
{
	public boolean isFarFrom(int nextX, int nextZ, int maxDistance)
	{
		if (maxDistance < 0)
			throw new IllegalArgumentException("maxDistance must be non-negative");
		long dx = (long)nextX - this.x;
		long dz = (long)nextZ - this.z;
		if (Math.abs(dx) > maxDistance || Math.abs(dz) > maxDistance)
			return true;
		long radius = maxDistance;
		return dx * dx + dz * dz > radius * radius;
	}
}
