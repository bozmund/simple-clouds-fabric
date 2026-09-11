package nonamecrackers2.crackerslib.client.gui.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import nonamecrackers2.crackerslib.CrackersLib;

/**
 * 26.2 port: onPress(InputWithModifiers), extractContents, Identifier.
 */
public class SimpleIconButton extends AbstractButton
{
	private final Identifier icon;
	private final Runnable onPressed;

	public SimpleIconButton(Component name, Component description, Identifier icon, int x, int y, Runnable onPressed)
	{
		super(x, y, 20, 20, name);
		this.icon = icon;
		this.onPressed = onPressed;
		this.setTooltip(Tooltip.create(description));
	}

	@Override
	public void onPress(InputWithModifiers input)
	{
		this.onPressed.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor stack, int mouseX, int mouseY, float partialTick)
	{
		float u2 = (float)this.getWidth() / 256f;
		float v2 = (float)this.getHeight() / 256f;
		stack.blit(this.icon, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 0.0F, 0.0F, u2, v2);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput pNarrationElementOutput)
	{
		this.defaultButtonNarrationText(pNarrationElementOutput);
	}
}
