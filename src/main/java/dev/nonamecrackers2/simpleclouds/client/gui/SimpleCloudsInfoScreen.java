package dev.nonamecrackers2.simpleclouds.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 26.2 vertical-slice stub. The info/error text screens (OpenGL version, links, crash
 * report) are not ported yet; this keeps the base class + constructor so the error
 * screen and startup mixin compile. Port incrementally.
 */
public abstract class SimpleCloudsInfoScreen extends Screen
{
	protected SimpleCloudsInfoScreen(Component title, int buttonCount)
	{
		super(title);
	}
}
