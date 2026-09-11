package dev.nonamecrackers2.simpleclouds.client.dh.pipeline;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.CloudsRenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;

/**
 * 26.2 Distant Horizons pipeline slot. The v2 26.2 renderer draws its finite,
 * camera-following cloud field into the MAIN framebuffer during the world phase,
 * so this pipeline is NOT the active one when DH is loaded -- the active pipeline
 * stays {@link CloudsRenderPipeline#DEFAULT} and the world-phase draw is used
 * unchanged. The DH-specific value in 26.2 (disabling DH's own cloud rendering)
 * lives in {@link dev.nonamecrackers2.simpleclouds.client.dh.SimpleCloudsDhCompatHandler}.
 * This class is kept as the reserved slot for a future far-field LOD pass (all
 * methods are no-ops); see PORTING.md for the 26.2 far-field limitation.
 */
public class DhSupportPipeline implements CloudsRenderPipeline
{
	public static final DhSupportPipeline INSTANCE = new DhSupportPipeline();

	@Override
	public void prepare(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
	}

	@Override
	public void afterSky(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
	}

	@Override
	public void beforeWeather(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
	}

	@Override
	public void afterLevel(Minecraft mc, SimpleCloudsRenderer renderer, PoseStack stack, Matrix4f projMat, float partialTick, double camX, double camY, double camZ, Frustum frustum)
	{
	}
}
