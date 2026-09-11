package dev.nonamecrackers2.simpleclouds.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;

import nonamecrackers2.crackerslib.client.gui.Screen3D;

/**
 * 3D cloud generator previewer (26.2 port, slice version).
 *
 * The 3D content is drawn by {@code SimpleCloudsRenderer.renderBeforeLevel} while
 * this screen is active (PreviewDrawPipeline); this class owns the screen shell,
 * the camera interaction (Screen3D mouse drag = rotate, middle-drag = pan,
 * scroll = zoom) and the close behavior.
 *
 * DEVIATIONS from 1.20.1: no per-type generator selector, no offscreen preview
 * image export button (CloudImageRenderer), background is the game sky instead
 * of a flat sky-blue target clear.
 */
public class CloudPreviewerScreen extends Screen3D
{
	private final Screen prev;

	public static void addCloudMeshListener(RegisterClientReloadListenersEvent event)
	{
	}

	/**
	 * Called by MixinGameRenderer on shutdown/level unload: discard the preview
	 * mesh so a re-open regenerates it against the new level's cloud data.
	 */
	public static void destroyMeshGenerator()
	{
		dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getOptionalInstance()
				.ifPresent(dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer::destroyPreview);
	}

	public CloudPreviewerScreen(Screen prev)
	{
		super(Component.literal("Cloud Previewer"), 1.0F, 1000.0F);
		this.prev = prev;
		// Center the camera on the preview box (Y band 16..80).
		this.offset = new org.joml.Vector3f(0.0F, 48.0F, 0.0F);
	}

	@Override
	public void onClose()
	{
		if (this.prev != null)
			this.minecraft.setScreenAndShow(this.prev);
		else
			this.minecraft.setScreenAndShow(null);
		destroyMeshGenerator();
	}

	@Override
	public boolean isPauseScreen()
	{
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor stack, int x, int y, float partialTick)
	{
		// The preview clears the frame itself (sky-blue + cubes); skip the screen's
		// blur/dim overlay (1.20.1 used a dedicated sky-blue target instead).
	}

	/**
	 * GUI-phase pass (Screen3D.render3D). The preview box itself is drawn into the
	 * main frame in the world phase (SimpleCloudsRenderer.drawPreviewInWorld --
	 * the only color-draw path that works from a standalone pass in 26.2); this
	 * screen just displays it and handles camera interaction.
	 */
	@Override
	protected void render3D(Object stack, int mouseX, int mouseY, float partialTick)
	{
	}
}
