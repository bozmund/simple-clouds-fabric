package dev.nonamecrackers2.simpleclouds.client.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

/** GPU readback of the independent resolved preview, not a GUI screenshot. */
public final class CloudPreviewExport {
    private CloudPreviewExport() {}
    public static int[] copyRgba(java.nio.ByteBuffer bytes, int width, int height) {
        int[] pixels=new int[Math.multiplyExact(width,height)];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int offset=(y*width+x)*4;
            int r=bytes.get(offset)&255, g=bytes.get(offset+1)&255, b=bytes.get(offset+2)&255, a=bytes.get(offset+3)&255;
            pixels[(height-y-1)*width+x]=(a<<24)|(r<<16)|(g<<8)|b;
        }
        return pixels;
    }
    public static CompletableFuture<Path> save(RenderTarget target, Path gameDirectory) {
        return saveToDirectory(target,gameDirectory.toAbsolutePath().normalize().resolve("simpleclouds/renders"));
    }
    public static CompletableFuture<Path> saveToDirectory(RenderTarget target, Path outputDirectory) {
        var result=new CompletableFuture<Path>();
        Path directory=outputDirectory.toAbsolutePath().normalize();
        try {
            for(Path part=directory;part!=null;part=part.getParent())
                if(Files.isSymbolicLink(part)) throw new java.io.IOException("Symbolic link in image export path: "+part);
            Files.createDirectories(directory);
            Path file=directory.resolve("cloud-"+java.util.UUID.randomUUID()+".png");
            var device=com.mojang.blaze3d.systems.RenderSystem.getDevice();
            int width=target.width,height=target.height;
            var buffer=device.createBuffer(()->"simpleclouds.previewRgbaReadback",
                com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_MAP_READ|com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_DST,
                Math.multiplyExact((long)width*height,4));
            try {
                device.createCommandEncoder().copyTextureToBuffer(target.getColorTexture(),buffer,0,()->{
                    int[] pixels;
                    try(buffer; var mapping=buffer.map(true,false)) {pixels=copyRgba(mapping.data(),width,height);}
                    catch(Exception failure) {result.completeExceptionally(failure);return;}
                    // Vanilla Screenshot forcibly ORs alpha with 0xff000000.
                    // Preserve the actual independent cloud target's RGBA instead.
                    CompletableFuture.runAsync(()->{
                try(var stream=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    var bitmap=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    bitmap.setRGB(0,0,width,height,pixels,0,width);
                    if(!javax.imageio.ImageIO.write(bitmap,"PNG",stream)) throw new java.io.IOException("PNG encoder unavailable");
                } catch(Exception failure) {result.completeExceptionally(failure);return;}
                result.complete(file);
                    });
                },0);
            } catch(Exception failure) {buffer.close();throw failure;}
        } catch(Exception failure) {result.completeExceptionally(failure);}
        return result;
    }
}
