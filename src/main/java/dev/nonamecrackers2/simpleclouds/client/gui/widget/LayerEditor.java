package dev.nonamecrackers2.simpleclouds.client.gui.widget;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nonamecrackers2.simpleclouds.common.noise.ModifiableNoiseSettings;
import dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings;
import nonamecrackers2.crackerslib.client.gui.widget.config.ConfigListItem;

/**
 * Original noise parameter rows adapted to the 26.3 extracted GUI and entry bounds.
 */
public class LayerEditor extends ContainerObjectSelectionList<LayerEditor.Entry>
{
	private final ModifiableNoiseSettings settings;
	private final Runnable onChanged;
	private static final int ROW_HEIGHT=30, TEXT_WIDTH=40;

	public LayerEditor(ModifiableNoiseSettings settings, Minecraft mc, int x, int y, int width, int height, Runnable onChanged)
	{
		// Modern constructor: width,height,y,entryHeight (not the old stub's order).
		super(mc, width, height, y, ROW_HEIGHT);
		this.setX(x);
		this.centerListVertically=false;
		this.settings = settings;
		this.onChanged = onChanged;
		this.buildEntries();
	}
	public void buildEntries() {
		this.clearEntries();
		for(var parameter:AbstractNoiseSettings.Param.values()) this.addEntry(new Entry(parameter));
	}
	@Override public int getRowWidth() {return this.getWidth()-5;}
	@Override protected int scrollBarX() {return this.getX()+this.getWidth()-5;}
	@Override protected void extractListSeparators(GuiGraphicsExtractor graphics) { }
	@Override protected void extractListBackground(GuiGraphicsExtractor graphics) {
		graphics.fill(this.getX(),this.getY(),this.getRight(),this.getBottom(),0x99000000);
	}

	public class Entry extends ContainerObjectSelectionList.Entry<LayerEditor.Entry>
	{
		private final AbstractNoiseSettings.Param parameter;
		private final Component name;
		private final List<Component> tooltip;
		private final EditBox box;
		Entry(AbstractNoiseSettings.Param parameter) {
			this.parameter=parameter;
			Component fullName=Component.translatable("gui.simpleclouds.noise_settings.param."+parameter.name().toLowerCase(Locale.ROOT)+".name");
			this.name=ConfigListItem.shortenText(fullName,TEXT_WIDTH);
			this.tooltip=List.of(fullName,Component.translatable("gui.simpleclouds.noise_settings.param.range",parameter.getMinInclusive(),parameter.getMaxInclusive()));
			this.box=new EditBox(LayerEditor.this.minecraft.font,0,0,60,20,CommonComponents.EMPTY);
			this.box.setValue(String.valueOf(LayerEditor.this.settings.getParam(parameter)));
			this.box.setResponder(text -> {
				try {
					float value=Float.parseFloat(text);
					if(!Float.isFinite(value)) throw new NumberFormatException("Non-finite noise value");
					this.box.setTextColor(value<parameter.getMinInclusive() || value>parameter.getMaxInclusive()?0xFFFF5555:0xFFFFFFFF);
					LayerEditor.this.settings.setParam(parameter,value);
					LayerEditor.this.onChanged.run();
				} catch(NumberFormatException invalid) {this.box.setTextColor(0xFFFF5555);}
			});
		}
		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean isSelected, float partialTick)
		{
			int left=this.getX(),top=this.getY(),width=this.getWidth(),height=this.getHeight();
			graphics.outline(left,top,width-5,height,0xAAFFFFFF);
			if(mouseX>=left && mouseX<left+20+TEXT_WIDTH && mouseY>=top && mouseY<top+height)
				graphics.setTooltipForNextFrame(LayerEditor.this.minecraft.font,this.tooltip,Optional.empty(),mouseX,mouseY);
			graphics.text(LayerEditor.this.minecraft.font,this.name,left+5,top+height/2-LayerEditor.this.minecraft.font.lineHeight/2,0xFFFFFFFF);
			this.box.setPosition(left+20+TEXT_WIDTH,top+height/2-this.box.getHeight()/2);
			this.box.setWidth(Math.max(20,width-30-TEXT_WIDTH));
			this.box.extractRenderState(graphics,mouseX,mouseY,partialTick);
		}

		@Override
		public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children()
		{
			return List.of(this.box);
		}

		@Override
		public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables()
		{
			return List.of(this.box);
		}
		@Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
			if(super.mouseClicked(event,doubleClick)) return true;
			if(event.button()!=1) return false;
			LayerEditor.this.setSelected(this); return true;
		}
		@Override public boolean mouseDragged(MouseButtonEvent event,double dragX,double dragY) {
			if(super.mouseDragged(event,dragX,dragY)) return true;
			if(event.button()!=1 || event.x()<this.getX() || event.x()>=this.getX()+20+TEXT_WIDTH
				|| event.y()<this.getY() || event.y()>=this.getY()+this.getHeight()) return false;
			float value=(float)(LayerEditor.this.settings.getParam(this.parameter)+dragX);
			if(!Float.isFinite(value)) return false;
			LayerEditor.this.settings.setParam(this.parameter,value);
			this.box.setValue(String.valueOf(LayerEditor.this.settings.getParam(this.parameter)));
			return true;
		}
	}
}
