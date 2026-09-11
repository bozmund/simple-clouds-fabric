package dev.nonamecrackers2.simpleclouds.client.gui;

import dev.nonamecrackers2.simpleclouds.client.mesh.RendererInitializeResult;
import net.minecraft.network.chat.Component;

/**
 * 26.2 vertical-slice stub. The renderer initialization error screen is not ported yet;
 * this keeps the constructor so the startup mixin compiles. Port incrementally.
 */
public class SimpleCloudsErrorScreen extends SimpleCloudsInfoScreen
{
	public SimpleCloudsErrorScreen(RendererInitializeResult result)
	{
		super(Component.translatable("gui.simpleclouds.error_screen.title"), 3);
	}
}
