package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.nonamecrackers2.simpleclouds.client.FogColorCapturer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;

/**
 * Captures the vanilla fog (sky) color each frame so the mod's cloud fog can match
 * it (VISUAL-PARITY-PLAN step 3: "the fog color is the vanilla fog (sky) color").
 * The 26.2 FogRenderer computes the color into a FogData and has no public getter,
 * so we grab the return value of setupFog at the tail.
 */
@Mixin(FogRenderer.class)
public class MixinFogRenderer
{
	@Inject(method = "setupFog", at = @At("TAIL"))
	private void simpleclouds$captureFogColor(Camera camera, int tick, DeltaTracker deltaTracker, float partialTick, ClientLevel level, CallbackInfoReturnable<FogData> ci)
	{
		FogData data = ci.getReturnValue();
		if (data != null && data.color != null)
		{
			if (camera.getFluidInCamera() == net.minecraft.world.level.material.FogType.NONE
					&& dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig.CLIENT.fogMode.get()
						!= dev.nonamecrackers2.simpleclouds.client.world.FogRenderMode.OFF)
				dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer -> {
					var color = renderer.getWorldEffectsManager().calculateFogColor(data.color.x, data.color.y, data.color.z,
						deltaTracker.getGameTimeDeltaPartialTick(false));
					data.color.set(color.getRed()/255.0F, color.getGreen()/255.0F, color.getBlue()/255.0F, data.color.w);
				});
			FogColorCapturer.set(data.color.x, data.color.y, data.color.z);
		}
	}
}
