package net.minecraft.client.renderer;

import java.io.IOException;

import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Fabric 26.2 compatibility: ShaderInstance was removed in 26.2.
 * This is a minimal replacement to allow old rendering code to compile.
 *
 * TODO(26.2-render): properly port rendering code to the new RenderPipeline API.
 */
public class ShaderInstance
{
	private final Identifier location;
	private final VertexFormat format;

	public ShaderInstance(net.minecraft.server.packs.resources.ResourceManager resourceManager, Identifier location, VertexFormat format) throws IOException
	{
		this.location = location;
		this.format = format;
	}

	public Identifier getLocation()
	{
		return this.location;
	}

	public void render(float partialTick)
	{
		// Placeholder
	}

	public void compile()
	{
		// Placeholder
	}
}
