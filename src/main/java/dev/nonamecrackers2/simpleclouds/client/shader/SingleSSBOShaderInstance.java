package dev.nonamecrackers2.simpleclouds.client.shader;

import java.io.IOException;

import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * 26.2 vertical-slice stub. The legacy SSBO shader instance (GL43 shader storage blocks)
 * is unused by the new GPU pipeline; this keeps the type so dependent code compiles.
 * Port (as part of the full renderer) incrementally.
 */
public class SingleSSBOShaderInstance
{
	private int binding = -1;

	public SingleSSBOShaderInstance(ResourceProvider provider, Identifier shaderLocation, VertexFormat format, String ssboName) throws IOException
	{
		// TODO(26.2): not used by the new pipeline.
	}

	public int getShaderStorageBinding()
	{
		return this.binding;
	}

	public void close()
	{
		this.binding = -1;
	}
}
