package dev.nonamecrackers2.simpleclouds.client.renderer;

import org.joml.Matrix4f;
import org.joml.Vector2f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 26.2 vertical-slice stub. Atmospheric clouds are not ported yet; all methods are
 * no-ops. Port incrementally.
 */
public class AtmosphericCloudsRenderHandler
{
	private final Minecraft mc;

	public AtmosphericCloudsRenderHandler(Minecraft mc)
	{
		this.mc = mc;
	}

	public void setWindDirection(Vector2f direction)
	{
	}

	public void tick()
	{
	}

	public void render(PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, float r, float g, float b)
	{
	}

	public void init(ResourceManager manager)
	{
	}

	public void onResize(int width, int height)
	{
	}

	public void close()
	{
	}
}
