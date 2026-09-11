package dev.nonamecrackers2.simpleclouds.client.gui;

import net.minecraft.network.chat.Component;

/**
 * 26.2 vertical-slice stub. The startup notice screen (e.g. Vivecraft warning) is not
 * ported yet; this keeps the constructor so the startup mixin compiles. Port incrementally.
 */
public class SimpleCloudsNoticeScreen extends SimpleCloudsInfoScreen
{
	public SimpleCloudsNoticeScreen(Component text)
	{
		super(text, 2);
	}
}
