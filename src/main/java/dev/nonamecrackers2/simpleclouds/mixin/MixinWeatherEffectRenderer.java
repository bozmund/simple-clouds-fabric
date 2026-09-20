package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;

/**
 * Step 5 (original parity): when the mod owns the weather and renders its own rain
 * (renderCustomRain), cancel the vanilla rain/snow pass so it does not double up with the mod's
 * custom drops. This is the equivalent of the 1.20.1 original's
 * {@code MixinLevelRenderer#simpleclouds$overrideRainRendering_renderSnowAndRain}
 * (which cancelled {@code LevelRenderer.renderSnowAndRain}). Only the weather is cancelled; the
 * world border (a separate call in the same pass) is untouched.
 *
 * <p>26.3 changed both entry points. The 26.2 method was
 * {@code render(Vec3, WeatherRenderState)}; it is now {@code render(WeatherRenderState,
 * RenderPass)} plus a separate {@code renderOit(...)} for the order-independent-transparency
 * pass. Against the old descriptor the injection matched nothing and **failed silently**, so
 * vanilla kept drawing its own weather on top of the mod's — Jan saw the mod's rain at the camera
 * and vanilla's snow through the clouds at the same time.
 */
@Mixin(WeatherEffectRenderer.class)
public class MixinWeatherEffectRenderer
{
	private static boolean simpleclouds$modOwnsWeather()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !SimpleCloudsRenderer.canRenderInDimension(mc.level))
			return false;
		// Either the mod draws its own drops instead, or it re-draws vanilla's weather itself
		// after the clouds (SimpleCloudsRenderer.drawWeatherAfterClouds). Vanilla's own slot runs
		// BEFORE the cloud pass, and the mod's clouds are opaque and drawn later, so weather left
		// in that slot is simply painted over wherever a cloud covers it - rain that stopped dead
		// at every cloud edge.
		return SimpleCloudsCompatHelper.renderCustomRain();
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
			at = @At("HEAD"), cancellable = true)
	private void simpleclouds$cancelVanillaRain_render(CallbackInfo ci)
	{
		if (simpleclouds$modOwnsWeather())
			ci.cancel();
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
	private void simpleclouds$cancelVanillaRain_renderOit(CallbackInfo ci)
	{
		if (simpleclouds$modOwnsWeather())
			ci.cancel();
	}
}

