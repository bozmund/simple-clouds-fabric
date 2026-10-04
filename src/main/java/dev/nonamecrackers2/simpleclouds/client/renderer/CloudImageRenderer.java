package dev.nonamecrackers2.simpleclouds.client.renderer;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector4f;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nonamecrackers2.simpleclouds.client.gui.CloudPreviewExport;
import dev.nonamecrackers2.simpleclouds.client.mesh.chunk.MeshChunk;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudsDrawPipeline;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalCloudDrawBuffers;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.OriginalPreviewTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Original public image API with independent RenderPearl targets. The caller
 * retains ownership of its original GPU generator; window state is untouched. */
public class CloudImageRenderer implements AutoCloseable {
    private static final int SIZE=2048;
    private final Minecraft mc;
    private final File directory;
    private final CloudMeshGenerator generator;
    private float rotX,rotY,zoom;
    private Vector4f background=new Vector4f(0,0,0,1);
    private CloudsDrawPipeline pipeline;
    private OriginalCloudDrawBuffers buffers;
    private RenderTarget target;
    private CompletableFuture<java.nio.file.Path> export;
    private boolean closing;
    public CloudImageRenderer(Minecraft mc,File path,float rotX,float rotY,float zoom,CloudMeshGenerator generator) {
        this.mc=Objects.requireNonNull(mc);this.directory=Objects.requireNonNull(path);
        this.generator=Objects.requireNonNull(generator);
        setRotX(rotX);setRotY(rotY);setZoom(zoom);
    }
    public static CloudImageRenderer basicIsometric(File path,CloudMeshGenerator generator) {
        return new CloudImageRenderer(Minecraft.getInstance(),path,225,45,
                SIZE/(2*(generator.getFadeStart()+32)),generator);
    }
    public @Nullable RenderTarget getFrameBuffer() {return target;}
    public void setRotX(float value) {finite(value);rotX=value;}
    public void setRotY(float value) {finite(value);rotY=value;}
    public void setZoom(float value) {finite(value);if(value<=0) throw new IllegalArgumentException("Nonpositive zoom");zoom=value;}
    public void setBgCol(float r,float g,float b) {
        finite(r);finite(g);finite(b);
        if(r<0||r>1||g<0||g>1||b<0||b>1) throw new IllegalArgumentException("Invalid background color");
        background=new Vector4f(r,g,b,1);
    }
    public void initialize() {
        RenderSystem.assertOnRenderThread();
        if(closing) throw new IllegalStateException("Cloud image closed");
        if(pipeline!=null) throw new IllegalStateException("Cloud image already initialized");
        try {pipeline=new CloudsDrawPipeline();buffers=new OriginalCloudDrawBuffers(mc.getResourceManager());}
        catch(Exception failure) {close();throw new IllegalStateException("Cloud image GPU initialization failed",failure);}
    }
    public void render() {
        RenderSystem.assertOnRenderThread();
        if(closing || pipeline==null || buffers==null) throw new IllegalStateException("Cloud image not initialized or closed");
        if(export!=null && !export.isDone()) throw new IllegalStateException("Cloud image export still pending");
        float far=generator.getFadeEnd()*2;
        var view=OriginalPreviewTransform.view(SIZE,SIZE,zoom,1,far,rotX,rotY,new Vector3f());
        var projection=OriginalPreviewTransform.projection(SIZE,SIZE,far);
        target=null;
        pipeline.beginPreview(projection,SIZE,SIZE,background);
        try {
            generator.forRenderableMeshChunks(null,MeshChunk::getOpaqueBuffers,(chunk,source)->{
                var draw=buffers.get(source,false,1,0);pipeline.drawPreview(view,draw.buffer(),draw.count());
            });
            pipeline.beginTransparency();
            if(generator.transparencyEnabled()) generator.forRenderableMeshChunks(null,
                    chunk->chunk.getTransparentBuffers().orElseThrow(),(chunk,source)->{
                var draw=buffers.get(source,true,1,0);
                pipeline.drawTransparencyClouds(view,draw.buffer(),draw.count(),1,0,0,0);
            });
            pipeline.endTransparency();
        } finally {pipeline.abortPreview();}
        target=pipeline.resolvePreviewForExport();
    }
    public void exportToRenderedImage(Consumer<Component> messageAcceptor) {
        RenderSystem.assertOnRenderThread();Objects.requireNonNull(messageAcceptor);
        if(closing || target==null) throw new IllegalStateException("Cloud image not rendered or closed");
        if(export!=null && !export.isDone()) throw new IllegalStateException("Cloud image export already pending");
        export=CloudPreviewExport.saveToDirectory(target,directory.toPath());
        export.whenComplete((file,failure)->mc.execute(()->messageAcceptor.accept(failure==null
                ?Component.translatable("screenshot.success",Component.literal(file.toString()))
                :Component.translatable("screenshot.failure",failure.getMessage()))));
    }
    /** Legacy explicit restoration call: this port never mutates the window. */
    @Deprecated public void finalize() { }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if(closing) return;
        closing=true;
        if(export!=null && !export.isDone()) {
            export.whenComplete((file,failure)->mc.execute(this::releaseResources));
            return;
        }
        releaseResources();
    }
    private void releaseResources() {
        RenderSystem.assertOnRenderThread();
        target=null;
        if(buffers!=null) {buffers.close();buffers=null;}
        if(pipeline!=null) {pipeline.close();pipeline=null;}
    }
    private static void finite(float value) {if(!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite image value");}
}
