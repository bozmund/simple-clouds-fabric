package dev.nonamecrackers2.simpleclouds.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Captures the view rotation <em>including view bobbing</em> for the cloud pass.
 *
 * <p>The cloud pass runs at the {@code LevelRenderer.render} TAIL hook, where the level's
 * model-view stack has already been popped, so {@code SimpleCloudsRenderer} rebuilds the view
 * matrix from {@code camera.getViewRotationMatrix()}. That matrix does not contain the bob:
 * {@code GameRenderer.renderLevel} reads it first and applies {@code bobView} to the pose stack
 * afterwards (checked in the 26.3 bytecode). The terrain therefore bobbed while the clouds did
 * not, and the clouds visibly swam against the world whenever the player walked.
 *
 * <p>Injecting at the tail of {@code bobView} takes the pose after the bob has been multiplied in
 * — view rotation composed with bob, which is exactly what the cloud pass needs before it applies
 * its own {@code translate(-cameraPos)}. When bobbing is off or the camera is not first person,
 * vanilla never calls this and the renderer falls back to the plain view rotation.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer
{
	@Inject(method = "bobView", at = @At("TAIL"))
	private void simpleclouds$captureBobbedView(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci)
	{
		SimpleCloudsRenderer.setBobbedViewRotation(new Matrix4f(poseStack.last().pose()));
	}
}

