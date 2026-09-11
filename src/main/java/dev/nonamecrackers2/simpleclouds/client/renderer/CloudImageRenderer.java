package dev.nonamecrackers2.simpleclouds.client.renderer;

import java.io.File;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.pipeline.RenderTarget;

import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 26.2 vertical-slice stub. Cloud image export/preview rendering is not ported yet; this
 * keeps the public API so dependent code compiles. Port incrementally.
 */
public class CloudImageRenderer
{
	public CloudImageRenderer(Minecraft mc, File path, float rotX, float rotY, float zoom, CloudMeshGenerator generator)
	{
	}

	public static CloudImageRenderer basicIsometric(File path, CloudMeshGenerator generator)
	{
		return new CloudImageRenderer(null, path, 0.0F, 0.0F, 1.0F, generator);
	}

	public @Nullable RenderTarget getFrameBuffer()
	{
		return null;
	}

	public void setRotX(float rot)
	{
	}

	public void setRotY(float rotY)
	{
	}

	public void setZoom(float zoom)
	{
	}

	public void setBgCol(float r, float g, float b)
	{
	}

	public void initialize()
	{
	}

	public void render()
	{
	}

	public void exportToRenderedImage(Consumer<Component> messageAcceptor)
	{
	}

	public void finalize()
	{
	}

	public void close()
	{
	}
}
