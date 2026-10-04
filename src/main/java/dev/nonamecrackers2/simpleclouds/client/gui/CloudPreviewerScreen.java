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
 * the camera interaction (Screen3D right-drag = rotate, left-drag = pan,
 * scroll = zoom) and the close behavior.
 *
 * Original noise rows and layer toolbar use a private editable copy. Weather
	 * controls and JSON import/export share that copy. Menu-only rendering remains
	 * a separate lifecycle path.
 */
public class CloudPreviewerScreen extends Screen3D
{
	private final Screen prev;
	private dev.nonamecrackers2.simpleclouds.common.cloud.CloudType selectedType;
	private java.util.List<dev.nonamecrackers2.simpleclouds.common.cloud.CloudType> types = java.util.List.of();
	private net.minecraft.client.gui.components.Button typeButton;
	private final java.util.Map<net.minecraft.resources.Identifier,CloudEditorModel> edits=new java.util.HashMap<>();
	private final java.util.Map<net.minecraft.resources.Identifier,dev.nonamecrackers2.simpleclouds.common.cloud.CloudType> importedTypes=new java.util.HashMap<>();
	private CloudEditorModel editorModel;
	private dev.nonamecrackers2.simpleclouds.client.gui.widget.LayerEditor layerEditor;
	private net.minecraft.client.gui.components.Button addLayer, removeLayer, previousLayer, nextLayer;

	@Override
	protected void init()
	{
		super.init();
		var selectedId = this.selectedType == null ? null : this.selectedType.id();
		var available=new java.util.HashMap<>(dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager.getInstance().getCloudTypes());
		available.putAll(this.importedTypes);
		this.types = available.values().stream().sorted(java.util.Comparator.comparing(t -> t.id().toString())).toList();
		var source = this.types.stream().filter(t -> t.id().equals(selectedId)).findFirst()
				.orElse(this.types.isEmpty() ? null : this.types.get(0));
		this.editorModel=source==null?null:this.edits.computeIfAbsent(source.id(),ignored -> new CloudEditorModel(source));
		this.selectedType=this.editorModel==null?null:this.editorModel.snapshot();
		this.typeButton = this.addRenderableWidget(net.minecraft.client.gui.components.Button
				.builder(this.typeLabel(), button -> this.selectNextType())
				.bounds(10, 10, Math.max(80, Math.min(160, this.width - 190)), 20).build());
		this.typeButton.active = this.types.size() > 1;
		this.addLayer=this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("+"),button -> {
			if(this.editorModel!=null && this.editorModel.addLayer()) {this.parametersChanged();this.rebuildLayerEditor();}
		}).bounds(10,this.height-30,20,20).build());
		this.removeLayer=this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("-"),button -> {
			if(this.editorModel!=null && this.editorModel.removeLayer()) {this.parametersChanged();this.rebuildLayerEditor();}
		}).bounds(35,this.height-30,20,20).build());
		this.previousLayer=this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("<"),button -> {
			if(this.editorModel!=null) {this.editorModel.jumpLayer(-1);this.rebuildLayerEditor();}
		}).bounds(60,this.height-30,20,20).build());
		this.nextLayer=this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal(">"),button -> {
			if(this.editorModel!=null) {this.editorModel.jumpLayer(1);this.rebuildLayerEditor();}
		}).bounds(85,this.height-30,20,20).build());
		this.layerEditor=null;
		this.rebuildLayerEditor();
		this.addFileAndWeatherControls();
	}

	private CloudEditorFiles editorFiles() {
		return new CloudEditorFiles(this.minecraft.gameDirectory.toPath());
	}
	private void showFileError(Exception error) {
		nonamecrackers2.crackerslib.client.gui.Popup.createInfoPopup(this, 300,
			Component.literal("Cloud file operation failed: " + error.getMessage()));
	}
	private void saveType(String name, boolean overwriteConfirmed) {
		if(this.editorModel==null) return;
		try {
			var path=this.editorFiles().save(name,this.editorModel.snapshot(),overwriteConfirmed);
			nonamecrackers2.crackerslib.client.gui.Popup.createInfoPopup(this,300,
				Component.translatable("gui.simpleclouds.cloud_previewer.popup.exported.cloud_type",path.toString()));
		} catch(java.nio.file.FileAlreadyExistsException exists) {
			nonamecrackers2.crackerslib.client.gui.Popup.createYesNoPopup(this,()->this.saveType(name,true),300,
				Component.translatable("gui.simpleclouds.cloud_previewer.popup.export.exists"));
		} catch(Exception error) {this.showFileError(error);}
	}
	private void addFileAndWeatherControls() {
		int x=Math.max(this.width-170,180);
		var png=this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Export PNG"),button->{
			dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getInstance().exportPreviewImage()
				.whenComplete((path,error)->this.minecraft.execute(()->{
					// Do not reopen an editor the user closed while GPU readback ran.
					if(this.minecraft.gui.screen()!=this) return;
					if(error!=null) this.showFileError(new java.io.IOException(error.getMessage(),error));
					else nonamecrackers2.crackerslib.client.gui.Popup.createInfoPopup(this,300,Component.literal("Cloud image saved: "+path));
				}));
		}).bounds(x,10,160,20).build());
		png.active=this.editorModel!=null;
		var weather=this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
			Component.literal("Weather"),button->{
				if(this.editorModel==null) return;
				var values=dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType.values();
				this.editorModel.setWeatherType(values[(this.editorModel.snapshot().weatherType().ordinal()+1)%values.length]);
				this.parametersChanged(); this.rebuildWidgets();
			}).bounds(x,40,160,20).build());
		weather.active=this.editorModel!=null;
		if(this.editorModel!=null) weather.setMessage(Component.literal("Weather: "+this.editorModel.snapshot().weatherType().getSerializedName()));
		String[] names={"storminess","storm_start","storm_fade_distance","transparency_fade"};
		for(int i=0;i<names.length;i++) {
			String name=names[i];
			float value=this.editorModel==null?0:switch(name) {
				case "storminess" -> this.editorModel.snapshot().storminess();
				case "storm_start" -> this.editorModel.snapshot().stormStart();
				case "storm_fade_distance" -> this.editorModel.snapshot().stormFadeDistance();
				default -> this.editorModel.snapshot().transparencyFade();
			};
			var control=this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
				Component.literal(name+": "+value),button ->
					nonamecrackers2.crackerslib.client.gui.Popup.createTextFieldPopup(this, text->{
						try {this.editorModel.setWeatherParameter(name,Float.parseFloat(text));
							this.parametersChanged(); this.rebuildWidgets();}
						catch(IllegalArgumentException error) {this.showFileError(error);}
					},300,Component.literal(name)))
				.bounds(x,65+i*25,160,20).build());
			control.active=this.editorModel!=null;
		}
		var save=this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
			Component.translatable("gui.simpleclouds.cloud_previewer.export.title"),button->
				nonamecrackers2.crackerslib.client.gui.Popup.createTextFieldPopup(this,name->this.saveType(name,false),300,
					Component.translatable("gui.simpleclouds.cloud_previewer.popup.export.cloud_type")))
			.bounds(x,this.height-30,75,20).build());
		save.active=this.editorModel!=null;
		this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Load JSON"),button->
			nonamecrackers2.crackerslib.client.gui.Popup.createTextFieldPopup(this,name->{
				try {
					var file=this.editorFiles().file(name);
					String base=file.getFileName().toString().replaceFirst("\\.json$", "");
					var type=this.editorFiles().load(name,net.minecraft.resources.Identifier.fromNamespaceAndPath("simpleclouds","editor/"+base));
					if(this.edits.containsKey(type.id()))
						nonamecrackers2.crackerslib.client.gui.Popup.createYesNoPopup(this,()->this.acceptImport(type),300,
							Component.literal("Replace the unsaved editor copy of "+type.id()+"?"));
					else this.acceptImport(type);
				} catch(Exception error) {this.showFileError(error);}
			},300,Component.literal("JSON filename in simpleclouds/cloudtypes")))
			.bounds(x+80,this.height-30,80,20).build());
	}
	private void acceptImport(dev.nonamecrackers2.simpleclouds.common.cloud.CloudType type) {
		this.edits.put(type.id(),new CloudEditorModel(type));
		this.importedTypes.put(type.id(),type); this.selectedType=type; this.rebuildWidgets();
	}

	private Component typeLabel()
	{
		return Component.literal(this.selectedType == null ? "No cloud types available" : "Cloud: " + this.selectedType.id() + " >");
	}

	private void selectNextType()
	{
		if (this.types.isEmpty()) return;
		int index=-1;
		for(int i=0;i<this.types.size();i++) if(this.selectedType!=null && this.types.get(i).id().equals(this.selectedType.id())) index=i;
		var source=this.types.get((index+1)%this.types.size());
		this.editorModel=this.edits.computeIfAbsent(source.id(),ignored -> new CloudEditorModel(source));
		this.selectedType=this.editorModel.snapshot();
		this.typeButton.setMessage(this.typeLabel());
		this.rebuildWidgets();
	}

	private void parametersChanged() {
		if(this.editorModel!=null) this.selectedType=this.editorModel.snapshot();
	}
	private void rebuildLayerEditor() {
		if(this.layerEditor!=null) this.removeWidget(this.layerEditor);
		this.layerEditor=null;
		if(this.editorModel!=null && this.editorModel.currentLayer()!=null)
			this.layerEditor=this.addRenderableWidget(new dev.nonamecrackers2.simpleclouds.client.gui.widget.LayerEditor(
				this.editorModel.currentLayer(),this.minecraft,10,40,Math.max(160,this.width/4),Math.max(30,this.height-80),this::parametersChanged));
		int count=this.editorModel==null?0:this.editorModel.layerCount();
		this.addLayer.active=this.editorModel!=null && count<dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator.MAX_NOISE_LAYERS;
		this.removeLayer.active=count>0;
		this.previousLayer.active=count>1;
		this.nextLayer.active=count>1;
	}
	@Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
		super.extractRenderState(graphics,mouseX,mouseY,partialTick);
		int count=this.editorModel==null?0:this.editorModel.layerCount();
		graphics.text(this.font,Component.translatable("gui.simpleclouds.cloud_previewer.current_layer",
			count==0?Component.literal("NONE"):Component.literal(String.valueOf(this.editorModel.selectedLayerIndex()+1))),
			110,this.height-24,0xFFFFFFFF);
	}

	public dev.nonamecrackers2.simpleclouds.common.cloud.CloudType selectedCloudType()
	{
		return this.selectedType;
	}

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
		super(Component.translatable("gui.simpleclouds.cloud_previewer.title"), 0.25F, 5000.0F);
		this.prev = prev;
	}

	@Override
	public void onClose()
	{
		if (this.prev != null)
			this.minecraft.gui.setScreen(this.prev);
		else
			this.minecraft.gui.setScreen(null);
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
		// The world-phase preview has already composited its independent target.
		// Do not obscure it with the default GUI blur/dim overlay.
	}

	/**
	 * GUI controls are extracted after the original-camera offscreen preview is
	 * composited in the world phase. Menu-only preview remains a separate port item.
	 */
	@Override
	protected void render3D(Object stack, int mouseX, int mouseY, float partialTick)
	{
		if(this.minecraft.level==null && stack instanceof GuiGraphicsExtractor graphics)
			dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getOptionalInstance()
				.ifPresent(renderer->renderer.renderMenuPreview(graphics,this));
	}
}
