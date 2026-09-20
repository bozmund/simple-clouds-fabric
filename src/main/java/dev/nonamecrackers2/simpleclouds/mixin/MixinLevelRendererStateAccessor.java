package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/** Reaches {@code LevelRenderer.levelRenderState}, which carries the weather render state. */
@Mixin(LevelRenderer.class)
public interface MixinLevelRendererStateAccessor
{
	@Accessor("levelRenderState")
	LevelRenderState simpleclouds$levelRenderState();
}
