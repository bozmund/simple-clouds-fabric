package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.client.gui.screens.Screen;

/**
 * Exposes {@code Screen.renderables} (26.2: the list {@code Screen.render()}
 * actually draws — distinct from {@code children()}, which only carries GUI
 * events). MixinOptionsScreen adds its config button to BOTH lists.
 */
@Mixin(Screen.class)
public interface MixinScreenRenderablesAccessor
{
	@Accessor("renderables")
	java.util.List<net.minecraft.client.gui.components.Renderable> simpleclouds$renderables();
}
