package dev.nonamecrackers2.simpleclouds.client.keybind;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

/**
 * 26.2 keybinds. F12 opens the 3D cloud generator previewer (the 1.20.1
 * debug-overlay keybind is not restored yet). Registered by
 * SimpleCloudsClientEvents via KeyMappingHelper.
 */
public class SimpleCloudsKeybinds
{
	public static final KeyMapping OPEN_GEN_PREVIEWER = new KeyMapping("simpleclouds.key.openGenPreviewer", GLFW.GLFW_KEY_F12, KeyMapping.Category.MISC);

	public static void register()
	{
		KeyMappingHelper.registerKeyMapping(OPEN_GEN_PREVIEWER);
	}
}
