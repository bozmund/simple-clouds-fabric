package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import dev.nonamecrackers2.simpleclouds.client.accessor.PostPassAccessor;
import net.minecraft.client.renderer.PostPass;

/**
 * 26.2 vertical-slice stub. The RenderTarget.clear redirect is gone from the new
 * backend; the out-clear disable is a no-op for now. Port with the post-processing path.
 */
@Mixin(PostPass.class)
public abstract class MixinPostPass implements PostPassAccessor
{
	@Unique
	private boolean outClearDisabled;

	@Override
	public void disableOutClear()
	{
		this.outClearDisabled = true;
	}
}
