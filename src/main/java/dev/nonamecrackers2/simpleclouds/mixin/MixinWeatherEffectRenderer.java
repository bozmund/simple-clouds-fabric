package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.compat.SimpleCloudsCompatHelper;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.state.level.WeatherRenderState;

/**
 * Holds vanilla's weather back so Simple Clouds can draw it after the clouds.
 *
 * <p>Vanilla's weather slot runs before the mod's cloud pass, and the clouds are opaque, so the
 * rain was painted over wherever a cloud covered it — it was visible on the ground and in the
 * gaps and stopped dead at every cloud edge.
 *
 * <p>Cancelling the slot and calling {@code render} later is not enough on its own: vanilla
 * clears the state once its pass is done, and the mod measured
 * {@code rainColumns=0 snowColumns=0 intensity=0.0 radius=0} by the time it got there. So the
 * columns are copied out here, while they still exist, and
 * {@link SimpleCloudsRenderer} draws from that copy.
 *
 * <p>26.3 also changed the method: the 26.2 descriptor was {@code render(Vec3,
 * WeatherRenderState)} and it is now {@code render(WeatherRenderState, RenderPass)} with a
 * separate {@code renderOit}. Against the old one the injection matched nothing and failed
 * silently, which is how vanilla's snow ended up drawn on top of the mod's own rain.
 */
@Mixin(WeatherEffectRenderer.class)
public class MixinWeatherEffectRenderer
{
	private static boolean simpleclouds$modOwnsWeather()
	{
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null
				&& SimpleCloudsRenderer.canRenderInDimension(mc.level)
				&& (SimpleCloudsCompatHelper.renderCustomRain() || SimpleCloudsRenderer.redrawsVanillaWeather());
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
			at = @At("HEAD"), cancellable = true)
	private void simpleclouds$cancelVanillaRain_render(WeatherRenderState state, RenderPass pass, CallbackInfo ci)
	{
		if (!simpleclouds$modOwnsWeather())
			return;
		if (SimpleCloudsRenderer.redrawsVanillaWeather())
			SimpleCloudsRenderer.captureWeatherState(state);
		ci.cancel();
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
	private void simpleclouds$cancelVanillaRain_renderOit(CallbackInfo ci)
	{
		if (simpleclouds$modOwnsWeather())
			ci.cancel();
	}
}

