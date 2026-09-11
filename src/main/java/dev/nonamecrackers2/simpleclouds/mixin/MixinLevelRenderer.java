package dev.nonamecrackers2.simpleclouds.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * 26.2 re-hook. The old LevelRenderer methods (renderLevel, renderClouds, renderSky,
 * renderSnowAndRain, tick, tickRain) are gone — 26.2 uses a pass-based frame graph.
 * For the vertical slice we hook the single entry point {@code render(...)} and draw
 * the opaque clouds there. The proper long-term integration is a custom pass added to
 * the {@code FrameGraphBuilder} (see PORTING.md).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer
{
	private static final Logger LOGGER = LogManager.getLogger("simpleclouds/MixinLevelRenderer");
	private static boolean loggedError;

	/**
	 * 26.2 3D previewer: while the previewer screen is open, draw the preview into
	 * its dedicated offscreen target HERE (the vanilla Projection uniform still holds
	 * the world's perspective projection; in the GUI phase it is the 2D GUI
	 * projection and would clip the cubes). The screen blits the target fullscreen
	 * in its GUI phase, which draws on top of the world. The normal cloud pass is
	 * skipped for the frame.
	 */
	@Inject(
			method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
			at = @At("TAIL"))
	public void simpleclouds$renderClouds_render(CallbackInfo ci)
	{
		try
		{
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			if (SimpleCloudsRenderer.getOptionalInstance().isEmpty() || !SimpleCloudsRenderer.canRenderInDimension(level))
				return;

			var camera = mc.gameRenderer.mainCamera();
			var pos = camera.position();
			float partialTick = mc.getDeltaTracker() != null ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) : 0.0F;

			SimpleCloudsRenderer.getInstance().renderBeforeLevel(null, null, partialTick, pos.x, pos.y, pos.z);
			// Dev-only deterministic screenshot for dev-relaunch.sh (no-op without run/devshot.request).
			dev.nonamecrackers2.simpleclouds.client.DevShot.onWorldFrame();
		}
		catch (Throwable t)
		{
			// Never take down the game over a cloud-pass failure; log once.
			if (!loggedError)
			{
				LOGGER.error("Simple Clouds: cloud render pass failed (further failures suppressed)", t);
				loggedError = true;
			}
		}
	}
}
