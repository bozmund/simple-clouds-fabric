package dev.nonamecrackers2.simpleclouds.client.gui.widget;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.network.chat.Component;

import dev.nonamecrackers2.simpleclouds.common.noise.ModifiableNoiseSettings;

/**
 * 26.2 vertical-slice stub. The noise layer editor list widget is not ported to the new
 * GUI component API yet; this keeps the type + constructor so the config/previewer code
 * compiles. Port incrementally.
 */
public class LayerEditor extends ContainerObjectSelectionList<LayerEditor.Entry>
{
	private final ModifiableNoiseSettings settings;
	private final Runnable onChanged;

	public LayerEditor(ModifiableNoiseSettings settings, Minecraft mc, int x, int y, int width, int height, Runnable onChanged)
	{
		super(mc, y, x, width, height);
		this.settings = settings;
		this.onChanged = onChanged;
	}

	public class Entry extends ContainerObjectSelectionList.Entry<LayerEditor.Entry>
	{
		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int index, int y, boolean isSelected, float partialTick)
		{
			// TODO(26.2): port the noise layer editor row rendering.
		}

		@Override
		public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children()
		{
			return List.of();
		}

		@Override
		public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables()
		{
			return List.of();
		}
	}
}
