package dev.nonamecrackers2.simpleclouds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

import dev.nonamecrackers2.simpleclouds.client.event.SimpleCloudsClientEvents;

/**
 * Restores the 1.20.1 CrackersLib options-screen config button for 26.2: adds a
 * "Simple Clouds" button below the last options row that opens the mod's config
 * home screen (the config keybind exists but is unbound by default, so without
 * this the config screen had no entry point). 26.2 layout (probed live): two
 * 150px columns at x=59/217, rows 24px apart from y=29, final centered 200px
 * button — the new button goes below the lowest existing child.
 *
 * 26.2 Screen keeps TWO lists: {@code children} (events) and {@code renderables}
 * (what render() draws); the protected addRenderableWidget adds to both. We
 * touch both directly (accessor for the private field).
 */
@Mixin(OptionsScreen.class)
public class MixinOptionsScreen
{
	@Inject(method = "init()V", at = @At("TAIL"))
	private void simpleclouds$addConfigButton(CallbackInfo ci)
	{
		OptionsScreen self = (OptionsScreen) (Object) this;
		int maxBottom = 0;
		for (var child : self.children())
		{
			if (child instanceof AbstractWidget aw)
				maxBottom = Math.max(maxBottom, aw.getY() + aw.getHeight());
		}
		int x = (self.width - 200) / 2;
		int y = Math.min(maxBottom + 8, self.height - 24);
		Button button = Button.builder(Component.literal("Simple Clouds"), b ->
		{
			Minecraft mc = Minecraft.getInstance();
			mc.setScreenAndShow(SimpleCloudsClientEvents.createConfigScreen(self));
		}).bounds(x, y, 200, 20).build();
		@SuppressWarnings("unchecked")
		java.util.List<net.minecraft.client.gui.components.events.GuiEventListener> widgets =
				(java.util.List<net.minecraft.client.gui.components.events.GuiEventListener>) (java.util.List<?>) self.children();
		@SuppressWarnings("unchecked")
		java.util.List<net.minecraft.client.gui.components.Renderable> renderables =
				(java.util.List<net.minecraft.client.gui.components.Renderable>) (java.util.List<?>) ((MixinScreenRenderablesAccessor) (Object) self).simpleclouds$renderables();
		widgets.add(button);
		renderables.add(button);
	}

}
