package nonamecrackers2.crackerslib.client.gui.widget.config;

import java.util.List;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import nonamecrackers2.crackerslib.common.config.preset.ConfigPreset;

/**
 * 26.2 port: render uses GuiGraphicsExtractor.
 */
public interface ConfigListItem extends Comparable<ConfigListItem>
{
	void init(List<AbstractWidget> widgets, int x, int y, int width, int height);

	void render(GuiGraphicsExtractor stack, int x, int y, int width, int height, int mouseX, int mouseY, float partialTicks);

	void onSavedAndClosed();

	void resetValue();

	boolean isValueReset();

	boolean matchesPreset(ConfigPreset preset, Predicate<String> excluded);

	void setFromPreset(ConfigPreset preset, Predicate<String> excluded);

	@Nullable Tooltip getTooltip(@Nullable ConfigPreset preset);

	boolean matchesSearch(String text);

	static Component shortenText(Component name, int allowedWidth)
	{
		Minecraft mc = Minecraft.getInstance();
		// 26.2: translatable components have an empty getString(), which made the
		// old code below return EMPTY for every translated name. The font
		// resolves translatables at render time, so a component that fits is
		// returned as-is and only the (rare) too-wide case falls back to the
		// character-truncation logic.
		if (mc.font.width(name) <= allowedWidth)
			return name;
		String text = name.getString();
		int currentSize = 0;
		int lastIndex = -1;
		for (int i = 0; i < text.length(); i++)
		{
			currentSize += mc.font.width(FormattedText.of(String.valueOf(text.charAt(i)), name.getStyle()));
			lastIndex = i;
			if (currentSize > allowedWidth)
				break;
		}
		if (lastIndex > 0)
		{
			String newText = text.substring(0, lastIndex + 1);
			if (currentSize > allowedWidth)
				newText += "...";
			return Component.literal(newText).withStyle(name.getStyle());
		}
		else
		{
			return CommonComponents.EMPTY;
		}
	}

	static String extractNameFromPath(String path)
	{
		String name = path;
		int index = path.lastIndexOf('.');
		if (index > 0 && index + 1 < name.length())
			name = path.substring(index + 1);
		return name;
	}
}
