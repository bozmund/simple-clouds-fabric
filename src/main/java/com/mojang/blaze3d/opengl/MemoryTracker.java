package com.mojang.blaze3d.opengl;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

/**
 * Fabric 26.2 compatibility: the original 1.20 MemoryTracker returned a tracked
 * direct {@link ByteBuffer}. 26.2 removed it; this minimal replacement allocates a
 * direct buffer so the (legacy, unused-by-the-26.2-pipeline) SSBO/mesh code compiles.
 */
public class MemoryTracker
{
	public static ByteBuffer create(int size)
	{
		return MemoryUtil.memAlloc(size);
	}

	public static void free(ByteBuffer buffer)
	{
		if (buffer != null)
			MemoryUtil.memFree(buffer);
	}
}
