package nonamecrackers2.crackerslib.client.gui.title;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 26.2 port: uses GuiGraphicsExtractor (replaces GuiGraphics).
 */
public interface TitleLogo
{
	void blit(GuiGraphicsExtractor stack, int x, int y, float partialTicks);

	int getWidth();

	int getHeight();
}
