package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;

/**
 * Suppresses the vanilla cloud sheet while Simple Clouds draws its own.
 *
 * <p>Up to 26.2 this was an inject into {@code LevelRenderer.addCloudsPass}, the private
 * frame-graph pass. Minecraft 26.3 removed that method and gave {@code LevelRenderer} a
 * {@link CloudRenderer} of its own, so the cancel moved here — same intent as the 1.20.1
 * original's {@code renderClouds} cancel: when Simple Clouds renders, the vanilla sheet is a
 * second set of clouds.
 *
 * <p>Both entry points are cancelled: {@code render} for the ordinary pass and
 * {@code renderOit} for the order-independent-transparency pass 26.3 added.
 */
@Mixin(CloudRenderer.class)
public class MixinCloudRenderer
{
	private static boolean simpleclouds$active()
	{
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null
				&& SimpleCloudsRenderer.getOptionalInstance().isPresent()
				&& SimpleCloudsRenderer.canRenderInDimension(mc.level);
	}

	@Inject(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
			at = @At("HEAD"), cancellable = true)
	private void simpleclouds$skipVanillaClouds(CallbackInfo ci)
	{
		if (simpleclouds$active())
			ci.cancel();
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
	private void simpleclouds$skipVanillaCloudsOit(CallbackInfo ci)
	{
		if (simpleclouds$active())
			ci.cancel();
	}
}

