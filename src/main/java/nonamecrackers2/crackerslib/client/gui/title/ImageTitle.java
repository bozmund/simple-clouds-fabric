package nonamecrackers2.crackerslib.client.gui.title;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 26.2 port: ResourceLocation -> Identifier; blit uses the 9-arg UV form.
 */
public record ImageTitle(Identifier location, int imageWidth, int imageHeight, int width, int height) implements TitleLogo
{
	public static ImageTitle ofMod(String modid, int imageWidth, int imageHeight, int width, int height)
	{
		Identifier location = Identifier.fromNamespaceAndPath(modid, "textures/gui/config/title/title.png");
		return new ImageTitle(location, imageWidth, imageHeight, width, height);
	}

	public static ImageTitle ofMod(String modid, int imageWidth, int imageHeight, float scale)
	{
		int width = Mth.floor((float)imageWidth * scale);
		int height = Mth.floor((float)imageHeight * scale);
		return ofMod(modid, width, height, width, height);
	}

	@Override
	public void blit(GuiGraphicsExtractor stack, int x, int y, float partialTicks)
	{
		float u2 = (float)this.width / (float)this.imageWidth;
		float v2 = (float)this.height / (float)this.imageHeight;
		stack.blit(this.location, x, y, this.width, this.height, 0.0F, 0.0F, u2, v2);
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
