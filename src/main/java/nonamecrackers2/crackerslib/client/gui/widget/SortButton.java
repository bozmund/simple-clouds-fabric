package nonamecrackers2.crackerslib.client.gui.widget;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import nonamecrackers2.crackerslib.CrackersLib;
import nonamecrackers2.crackerslib.client.util.SortType;

/**
 * 26.2 port: onPress(InputWithModifiers), extractContents, Identifier.
 */
public class SortButton extends AbstractButton
{
	private static final Identifier SORT_ICONS = CrackersLib.id("textures/gui/config/sort.png");
	private static final Component NAME = Component.translatable("gui.crackerslib.button.sorting.title");
	private final Consumer<SortType> onPressed;
	private SortType type = SortType.A_TO_Z;

	public SortButton(int x, int y, Consumer<SortType> onPressed)
	{
		super(x, y, 20, 20, NAME);
		this.onPressed = onPressed;
		this.setTooltip(this.buildTooltip());
	}

	@Override
	public void onPress(InputWithModifiers input)
	{
		int next = this.type.ordinal() + 1;
		if (next >= SortType.values().length)
			next = 0;
		this.type = SortType.values()[next];
		this.onPressed.accept(this.type);
		this.setTooltip(this.buildTooltip());
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor stack, int mouseX, int mouseY, float partialTick)
	{
		float texY = 0.0F;
		if (this.type == SortType.Z_TO_A)
			texY = 20.0F;
		float u2 = (float)this.getWidth() / 256f;
		float v = texY / 256f;
		float v2 = v + (float)this.getHeight() / 256f;
		stack.blit(SORT_ICONS, this.getX(), this.getY(), this.getWidth(), this.getHeight(), 0.0F, v, u2, v2);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput pNarrationElementOutput)
	{
		this.defaultButtonNarrationText(pNarrationElementOutput);
	}

	public Tooltip buildTooltip()
	{
		Component text = NAME.copy().append(" ").append(this.type.getName());
		return Tooltip.create(text);
	}
}
