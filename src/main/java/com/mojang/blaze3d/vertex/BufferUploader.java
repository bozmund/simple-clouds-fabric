package com.mojang.blaze3d.vertex;

/**
 * Fabric 26.2 compatibility: BufferUploader was removed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 */
public class BufferUploader
{
	public static void draw(BufferBuilder bufferBuilder)
	{
		// Placeholder
	}

	public static void drawWithShader(BufferBuilder bufferBuilder)
	{
		// Placeholder
	}
}
