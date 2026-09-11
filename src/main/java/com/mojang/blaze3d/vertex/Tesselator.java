package com.mojang.blaze3d.vertex;

/**
 * Fabric 26.2 compatibility: Tesselator was removed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 *
 * TODO(26.2-render): properly port rendering code to the new BufferBuilder API.
 */
public class Tesselator
{
	private static final Tesselator INSTANCE = new Tesselator();

	public static Tesselator getInstance()
	{
		return INSTANCE;
	}

	public BufferBuilder getBuilder()
	{
		// In 26.2, BufferBuilder requires ByteBufferBuilder, PrimitiveTopology, and VertexFormat.
		// This is a placeholder; rendering code needs to be properly ported.
		throw new UnsupportedOperationException("Tesselator.getBuilder() is not supported in 26.2. Port rendering code to the new BufferBuilder API.");
	}

	public void end()
	{
		// Placeholder
	}
}
