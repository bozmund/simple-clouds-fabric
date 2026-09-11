package nonamecrackers2.crackerslib.client.gui.title;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * 26.2 port: ModList -> FabricLoader; drawString -> text.
 */
public record TextTitle(Component title, int width, int height) implements TitleLogo
{
	public static TextTitle ofModDisplayName(String modid, Style style)
	{
		Minecraft mc = Minecraft.getInstance();
		return FabricLoader.getInstance().getModContainer(modid).map(container -> {
			Component text = Component.literal(container.getMetadata().getName()).withStyle(style);
			return new TextTitle(text, mc.font.width(text), mc.font.lineHeight);
		}).orElseThrow(() -> new NullPointerException("Could not find mod with id '" + modid + "'"));
	}

	public static TextTitle ofModDisplayName(String modid)
	{
		return ofModDisplayName(modid, Style.EMPTY.withBold(true).withUnderlined(true));
	}

	@Override
	public void blit(GuiGraphicsExtractor stack, int x, int y, float partialTicks)
	{
		Minecraft mc = Minecraft.getInstance();
		stack.text(mc.font, this.title, x, y, 0xFFFFFFFF);
	}

	@Override
	public int getWidth()
	{
		return this.width;
	}

	@Override
	public int getHeight()
	{
		return this.height;
	}
}
