package dev.nonamecrackers2.simpleclouds.client;

import dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Explicit isolated-launch opt-in only. Captures after GUI and exercises model
 * and widget callbacks; pointer/keyboard hit testing remains a separate gate. */
public final class PreviewRuntimeProbe {
    private static final Logger LOGGER=LogManager.getLogger("simpleclouds/PreviewProbe");
    private static int frames;
    private static ProbeScreen screen;
	private static String pendingScreenshot;
	private static float[] editedNoise;
	private static java.util.concurrent.CompletableFuture<java.nio.file.Path> pngExport;
	private static boolean pngVerified, finalCaptureQueued;
	private static boolean disconnectQueued, menuRequested, menuDone;
	private static int menuFrames, menuWaitFrames;
    private static final class ProbeScreen extends CloudPreviewerScreen {
        ProbeScreen() { super(null); }
        void pressIn(net.minecraft.client.gui.screens.Screen target, java.util.function.Predicate<String> label) {
            target.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button)
                .map(c->(net.minecraft.client.gui.components.Button)c).filter(b->label.test(b.getMessage().getString()))
                .findFirst().orElseThrow().onPress(null);
        }
        void submitPopup(String text) {
            var current=Minecraft.getInstance().gui.screen();
            if(!(current instanceof nonamecrackers2.crackerslib.client.gui.Popup)) throw new IllegalStateException("No input popup");
            var box=current.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.EditBox)
                .map(c->(net.minecraft.client.gui.components.EditBox)c).findFirst().orElseThrow();
            box.setValue(text);
            if(!box.getValue().equals(text)) throw new IllegalStateException("Popup input truncated fixture text");
            pressIn(current,label->label.equals(net.minecraft.network.chat.Component.translatable("gui.popup.submit").getString()));
        }
        void closeInfo() {
            var current=Minecraft.getInstance().gui.screen();
            if(!(current instanceof nonamecrackers2.crackerslib.client.gui.Popup)) throw new IllegalStateException("No info popup");
            pressIn(current,label->label.equals(net.minecraft.network.chat.Component.translatable("gui.popup.close").getString()));
        }
        void verifyFilesAndWeather() {
            try {
                var mc=Minecraft.getInstance();
                if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals("/home/jan/simple-clouds-fabric/run"))
                    throw new IllegalStateException("Not isolated editor fixture");
                var oldWeather=this.selectedCloudType().weatherType();
                pressIn(this,label->label.startsWith("Weather:"));
                if(this.selectedCloudType().weatherType()==oldWeather) throw new IllegalStateException("Weather control did not edit");
                pressIn(this,label->label.startsWith("storminess:")); submitPopup("0.61");
                if(this.selectedCloudType().storminess()!=.61f) throw new IllegalStateException("Weather popup edit lost");
                var expected=this.selectedCloudType().toJson();
                String name="codex-editor-"+java.util.UUID.randomUUID().toString().substring(0,16);
                pressIn(this,label->label.equals(net.minecraft.network.chat.Component.translatable("gui.simpleclouds.cloud_previewer.export.title").getString()));
                submitPopup(name); closeInfo();
                var files=new dev.nonamecrackers2.simpleclouds.client.gui.CloudEditorFiles(mc.gameDirectory.toPath());
                if(!files.load(name,this.selectedCloudType().id()).toJson().equals(expected)) throw new IllegalStateException("GUI export lost edits");
                var bytes=java.nio.file.Files.readAllBytes(files.file(name));
                pressIn(this,label->label.equals("Load JSON")); submitPopup(name);
                if(!this.selectedCloudType().id().getPath().startsWith("editor/")
                        || !this.selectedCloudType().toJson().equals(expected)) throw new IllegalStateException("GUI import lost edits");
                this.resize(this.width,this.height);
                if(!this.selectedCloudType().toJson().equals(expected)) throw new IllegalStateException("Imported edits lost on resize");
                pressIn(this,label->label.equals(net.minecraft.network.chat.Component.translatable("gui.simpleclouds.cloud_previewer.export.title").getString()));
                submitPopup(name);
                pressIn(mc.gui.screen(),label->label.equals(net.minecraft.network.chat.Component.translatable("gui.popup.no").getString()));
                if(!java.util.Arrays.equals(bytes,java.nio.file.Files.readAllBytes(files.file(name)))) throw new IllegalStateException("Rejected overwrite changed file");
                pressIn(this,label->label.equals("Load JSON"));
                pressIn(mc.gui.screen(),label->label.equals(net.minecraft.network.chat.Component.translatable("gui.popup.cancel").getString()));
                if(!this.selectedCloudType().toJson().equals(expected)) throw new IllegalStateException("Cancel changed editor");
                pressIn(this,label->label.equals("Load JSON")); submitPopup("missing-"+java.util.UUID.randomUUID().toString().substring(0,8)); closeInfo();
                if(!this.selectedCloudType().toJson().equals(expected)) throw new IllegalStateException("Invalid import destroyed editor");
                LOGGER.info("[PREVIEW-PROBE] GUI weather/export/import/resize/overwrite rejection/cancel/invalid load PASS file={}",files.file(name));
            } catch(Exception failure) {throw new IllegalStateException("Editor file/weather GUI fixture failed",failure);}
        }
        @SuppressWarnings("unchecked") void requestPng() {
            pressIn(this,label->label.equals("Export PNG"));
            try {
                var field=dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.class.getDeclaredField("pendingPreviewExport");
                field.setAccessible(true);
                pngExport=(java.util.concurrent.CompletableFuture<java.nio.file.Path>)field.get(
                    dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getInstance());
                if(pngExport==null) throw new IllegalStateException("PNG button did not queue export");
            } catch(ReflectiveOperationException failure) {throw new IllegalStateException(failure);}
        }
        boolean verifyPng() {
            if(pngExport==null || !pngExport.isDone()) return false;
            if(!(Minecraft.getInstance().gui.screen() instanceof nonamecrackers2.crackerslib.client.gui.Popup)) return false;
            try {
                var path=pngExport.join();
                var image=javax.imageio.ImageIO.read(path.toFile());
                if(image==null || image.getWidth()!=Minecraft.getInstance().getWindow().getWidth()
                        || image.getHeight()!=Minecraft.getInstance().getWindow().getHeight()) throw new IllegalStateException("Wrong PNG dimensions");
                int clear=0, covered=0;
                for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
                    int alpha=image.getRGB(x,y)>>>24;
                    if(alpha==0) clear++; else covered++;
                }
                if(clear==0 || covered==0) throw new IllegalStateException("PNG lost cloud geometry or alpha: "+clear+"/"+covered);
                closeInfo();
                LOGGER.info("[PREVIEW-PROBE] PNG export PASS file={} clear={} covered={} dimensions={}x{}",path,clear,covered,image.getWidth(),image.getHeight());
                return true;
            } catch(Exception failure) {throw new IllegalStateException("PNG verification failed",failure);}
        }
        void rotate() { this.camRotX=200; this.camRotY=100; this.onRotate(); }
        void zoomAndPan() { this.zoom=2; this.offset.set(25,-10,5); this.onMove(); }
        void chooseCumulus() {
            var button=this.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
                .map(child -> (net.minecraft.client.gui.components.Button)child).findFirst().orElseThrow();
            for(int attempt=0;attempt<16;attempt++) {
                if(this.selectedCloudType().id().toString().equals("simpleclouds:cumulus")) return;
                button.onPress(null); // Exercise the real selection callback, not pointer hit testing.
            }
            throw new IllegalStateException("Cumulus missing from editor selector");
        }
        void chooseEmptyFixture() {
            // Empty is an explicit test fixture, not a normal registered UI choice.
            try {
                var field=CloudPreviewerScreen.class.getDeclaredField("selectedType");
                field.setAccessible(true);
                field.set(this,dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants.EMPTY);
                CloudPreviewerScreen.destroyMeshGenerator();
            } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        void verifyLayerWidget() {
            var settings=new dev.nonamecrackers2.simpleclouds.common.noise.ModifiableNoiseSettings();
            int[] changes={0};
            var editor=new dev.nonamecrackers2.simpleclouds.client.gui.widget.LayerEditor(settings,
                Minecraft.getInstance(),10,40,200,Math.max(100,this.height-70),() -> changes[0]++);
            var parameters=dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings.Param.values();
            if(editor.children().size()!=parameters.length) throw new IllegalStateException("Missing editor parameter rows");
            int index=java.util.Arrays.asList(parameters).indexOf(dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings.Param.VALUE_OFFSET);
            var box=(net.minecraft.client.gui.components.EditBox)editor.children().get(index).children().get(0);
            box.setValue("-0.25");
            if(settings.getParam(parameters[index])!=-.25f || changes[0]!=1) throw new IllegalStateException("Valid parameter did not update");
            box.setValue("NaN"); box.setValue(""); box.setValue("not a number");
            if(settings.getParam(parameters[index])!=-.25f || changes[0]!=1) throw new IllegalStateException("Invalid parameter reached noise settings");
            box.setValue("-99");
            if(settings.getParam(parameters[index])!=parameters[index].getMinInclusive() || changes[0]!=2)
                throw new IllegalStateException("Original parameter range clamp failed");
            this.addRenderableWidget(editor);
            LOGGER.info("[PREVIEW-PROBE] layer widget PASS: {} rows, finite edits, rejected invalid/nonfinite values, original range clamp; GUI extraction enabled",parameters.length);
        }
		void editActualLayer() {
			var editor=this.children().stream().filter(child -> child instanceof dev.nonamecrackers2.simpleclouds.client.gui.widget.LayerEditor)
				.map(child -> (dev.nonamecrackers2.simpleclouds.client.gui.widget.LayerEditor)child).findFirst().orElseThrow();
			var box=(net.minecraft.client.gui.components.EditBox)editor.children().get(0).children().get(0);
			LOGGER.info("[PREVIEW-PROBE] input target activeScreen={} editor={} row={} box={} focused={}",
				Minecraft.getInstance().gui.screen()==this,editor.getRectangle(),editor.children().get(0).getRectangle(),box.getRectangle(),box.isFocused());
			if(box.getX()==0 && box.getY()==0) throw new IllegalStateException("Editor field has not been laid out; refusing click at unrelated origin");
			var click=new net.minecraft.client.input.MouseButtonEvent(box.getX()+10,box.getY()+10,
				new net.minecraft.client.input.MouseButtonInfo(1,0));
			if(!this.mouseClicked(click,false)) throw new IllegalStateException("Screen did not route pointer to edit box");
			this.mouseReleased(click);
			// 26.3 uses SDL scancode A=4, keycode a=97 and left-control=64.
			var selectAll=new net.minecraft.client.input.KeyEvent(4,97,64);
			if(!selectAll.isSelectAll()) throw new IllegalStateException("Invalid SDL select-all fixture");
			if(!this.keyPressed(selectAll)) throw new IllegalStateException("Ctrl+A not handled");
			if(!this.charTyped(new net.minecraft.client.input.CharacterEvent('8'))) throw new IllegalStateException("Typed character not handled");
			if(!box.getValue().equals("8")) throw new IllegalStateException("Pointer/keyboard edit did not replace field");
			editedNoise=this.selectedCloudType().noiseConfig().packForShader().clone();
			if(editedNoise[0]!=8) throw new IllegalStateException("Actual editor edit not propagated to preview type");
			var registered=dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager.getInstance().getCloudTypes().get(this.selectedCloudType().id());
			if(registered.noiseConfig().packForShader()[0]==8) throw new IllegalStateException("Fixture/source is not distinguishable");
			LOGGER.info("[PREVIEW-PROBE] routed pointer/Ctrl+A/character edit HEIGHT=8 propagated; registered source unchanged");
		}
		void verifyResize() {
			this.resize(this.width-20,this.height-10);
			if(!java.util.Arrays.equals(editedNoise,this.selectedCloudType().noiseConfig().packForShader()))
				throw new IllegalStateException("Resize discarded edits");
			LOGGER.info("[PREVIEW-PROBE] resize preserved edited noise");
		}
		void press(String label) {
			this.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
				.map(child -> (net.minecraft.client.gui.components.Button)child)
				.filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow().onPress(null);
		}
		void verifyToolbar() {
			int before=this.selectedCloudType().noiseConfig().layerCount();
			this.press("+");
			if(this.selectedCloudType().noiseConfig().layerCount()!=before+1) throw new IllegalStateException("Add layer failed");
			this.press("-");
			if(this.selectedCloudType().noiseConfig().layerCount()!=before) throw new IllegalStateException("Remove layer failed");
			for(int count=before;count>0;count--) this.press("-");
			if(this.selectedCloudType().noiseConfig().layerCount()!=0) throw new IllegalStateException("Remove all left layers");
			LOGGER.info("[PREVIEW-PROBE] actual add/remove/remove-all callbacks PASS");
		}
		void logTextState() {
			for(var child:this.children()) if(child instanceof dev.nonamecrackers2.simpleclouds.client.gui.widget.LayerEditor editor) {
				for(int index=0;index<editor.children().size();index++) {
					var box=(net.minecraft.client.gui.components.EditBox)editor.children().get(index).children().get(0);
					int display=-1;
					try {var field=box.getClass().getDeclaredField("displayPos");field.setAccessible(true);display=field.getInt(box);}
					catch(ReflectiveOperationException failure) {throw new IllegalStateException(failure);}
					LOGGER.info("[PREVIEW-PROBE] text row={} value='{}' width={} inner={} cursor={} display={} focus={}",
						index,box.getValue(),box.getWidth(),box.getInnerWidth(),box.getCursorPosition(),display,box.isFocused());
					LOGGER.info("[PREVIEW-PROBE] text metrics row={} fontWidth={} trimmed='{}' rect={},{},{},{}",
						index,this.font.width(box.getValue()),this.font.plainSubstrByWidth(box.getValue(),box.getInnerWidth()),
						box.getX(),box.getY(),box.getRight(),box.getBottom());
				}
			}
		}
    }
    public static boolean onWorldFrame() {
        if (!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_PREVIEW_TEST"))) return false;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null) return true;
        frames++;
        if(frames==240 || frames==960) {
            screen=new ProbeScreen(); mc.gui.setScreen(screen);
            LOGGER.info("[PREVIEW-PROBE] opened frame={}",frames);
        }
        if(frames==420) screen.rotate();
        if(frames==660) screen.zoomAndPan();
        if(frames==900) { screen.onClose(); LOGGER.info("[PREVIEW-PROBE] closed"); }
        if(frames==1140 || frames==1500) screen.chooseCumulus();
        if(frames==1740) screen.editActualLayer();
        if(frames==1920) screen.verifyResize();
        if(frames==2040) screen.verifyToolbar();
        if(frames==2280) screen.press("+");
        if(frames==2340) screen.verifyFilesAndWeather();
        if(frames==2350) screen.requestPng();
        if(frames>=2360 && !pngVerified) pngVerified=screen.verifyPng();
        if(frames>3000 && !pngVerified) throw new IllegalStateException("PNG export timeout");
        if(frames==2460 && pngVerified) {
            // Do not unload a level while GameRenderer is still drawing its HUD.
            disconnectQueued=true;
        }
        if(frames==1320) screen.chooseEmptyFixture();
        if(frames==360 || frames==600 || frames==840 || frames==1080 || frames==1260 || frames==1440 || frames==1620
				|| frames==1860 || frames==1980 || frames==2220 || (frames>=2400 && pngVerified && !finalCaptureQueued)) {
            int captureFrame=frames>=2400?2400:frames;
            if(frames>=2400) finalCaptureQueued=true;
            String name="devshot-PREVIEW-"+captureFrame+".png";
            LOGGER.info("[PREVIEW-PROBE] capture {} type={} rotation={}/{} zoom={} offset={}",
                name,screen.selectedCloudType(),screen.camRotX(),screen.camRotY(),screen.zoom(),screen.offset());
			screen.logTextState();
			pendingScreenshot=name;
        }
        return true;
    }
	public static void afterGuiFrame() {
		tickMenuFixture();
		if(pendingScreenshot==null) return;
		String name=pendingScreenshot; pendingScreenshot=null;
		Minecraft mc=Minecraft.getInstance();
		Screenshot.grab(mc.gameDirectory,name,mc.gameRenderer.mainRenderTarget(),1,
			message -> LOGGER.info("[PREVIEW-PROBE] saved after GUI {} {}",name,message.getString()));
	}
	private static void tickMenuFixture() {
		if(!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
				|| !"1".equals(System.getenv("SIMPLECLOUDS_PREVIEW_TEST")) || !menuRequested || menuDone) return;
		var mc=Minecraft.getInstance();
		if(++menuWaitFrames>1200) throw new IllegalStateException("Menu preview fixture timeout");
		if(mc.level!=null || mc.getSingleplayerServer()!=null) return;
		if(menuFrames==0) {
			// disconnectFromWorld returns before the save/receiving screen installs
			// its final title screen. Opening earlier lets that callback replace us.
			if(!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
			if(dev.nonamecrackers2.simpleclouds.client.config.ClientServerConfig.get()!=null
					|| dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager.getInstance().hasReceivedSynced())
				throw new IllegalStateException("Server cloud state leaked into menu");
			if(dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager.getInstance().getCloudTypes().size()!=8)
				throw new IllegalStateException("Client resource presets missing in menu");
			screen=new ProbeScreen(); mc.gui.setScreen(screen); screen.chooseCumulus();
			LOGGER.info("[PREVIEW-PROBE] menu-only opened after saved disconnect; local presets8, server state cleared");
		}
		if(mc.gui.screen()!=screen && !(menuFrames>=120
				&& mc.gui.screen() instanceof nonamecrackers2.crackerslib.client.gui.Popup))
			throw new IllegalStateException("Menu editor replaced by "+mc.gui.screen());
		menuFrames++;
		if(menuFrames==60) {screen.rotate(); screen.zoomAndPan(); screen.resize(screen.width,screen.height);}
		if(menuFrames==120) {pngVerified=false; screen.requestPng();}
		if(menuFrames>=140 && screen.verifyPng()) {
			pendingScreenshot="devshot-PREVIEW-MENU.png";
			menuDone=true;
			LOGGER.info("[PREVIEW-PROBE] menu-only preview/rotate/zoom/resize/PNG PASS with no world or server");
		}
	}
	public static void tickClient(Minecraft mc) {
		if(!"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
				|| !"1".equals(System.getenv("SIMPLECLOUDS_PREVIEW_TEST")) || !disconnectQueued || menuRequested) return;
		menuRequested=true; screen.onClose();
		mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Editor menu fixture"));
	}
}
