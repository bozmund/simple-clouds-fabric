package dev.nonamecrackers2.simpleclouds.client;

import dev.nonamecrackers2.simpleclouds.client.cloud.ClientSideCloudTypeManager;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.renderer.CloudImageRenderer;
import net.minecraft.client.Minecraft;

/** Explicit isolated GPU API test; no production launch side effects. */
public final class CloudImageApiProbe {
    private static boolean started,done;
    private static CloudImageRenderer image;
    private static CloudMeshGenerator generator;
    private static java.nio.file.Path directory;
    private static net.minecraft.network.chat.Component result;
    private static int windowWidth,windowHeight;
    private CloudImageApiProbe() {}
    public static void tick(Minecraft mc) {
        if(done || !"1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                || !"1".equals(System.getenv("SIMPLECLOUDS_TEST_IMAGE_API"))) return;
        if(mc.level==null || mc.player==null) return;
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals("/home/jan/simple-clouds-fabric/run"))
            throw new IllegalStateException("Image API fixture requires isolated run directory");
        try {
            if(!started) {
                started=true;
                verifyLegacyMigration();
                var type=java.util.Arrays.stream(ClientSideCloudTypeManager.getInstance().getIndexedCloudTypes())
                    .filter(t->t.id().getPath().equals("cumulus")).findFirst().orElseThrow();
                generator=CloudMeshGenerator.builder().testFacesFacingAway(true).createSingleRegion(type);
                var status=generator.init(mc.getResourceManager());
                if(!status.getErrors().isEmpty()) throw new IllegalStateException("Image API generator init: "+status.getErrors());
                generator.generateMesh();
                directory=mc.gameDirectory.toPath().resolve("simpleclouds/api-probe-"+java.util.UUID.randomUUID());
                windowWidth=mc.getWindow().getWidth();windowHeight=mc.getWindow().getHeight();
                image=CloudImageRenderer.basicIsometric(directory.toFile(),generator);
                image.initialize();image.setBgCol(.1f,.2f,.3f);image.render();
                check(image.getFrameBuffer()!=null && image.getFrameBuffer().width==2048 && image.getFrameBuffer().height==2048,
                    "independent 2048 image target");
                image.exportToRenderedImage(message->result=message);
                image.finalize();image.close(); // Exercise safe async export + immediate try-with-resource-style close.
                return;
            }
            if(result==null) return;
            java.nio.file.Path file;
            try(var paths=java.nio.file.Files.list(directory)) {file=paths.filter(p->p.toString().endsWith(".png")).findFirst().orElseThrow();}
            var bitmap=javax.imageio.ImageIO.read(file.toFile());
            check(bitmap!=null && bitmap.getWidth()==2048 && bitmap.getHeight()==2048,"actual encoded image dimensions");
            int color=bitmap.getRGB(0,0);
            check((color>>>24)==255 && Math.abs(((color>>16)&255)-26)<=1
                && Math.abs(((color>>8)&255)-51)<=1 && Math.abs((color&255)-77)<=1,"original opaque background preserved");
            java.util.Set<Integer> colors=new java.util.HashSet<>();
            for(int y=0;y<2048;y+=16) for(int x=0;x<2048;x+=16) colors.add(bitmap.getRGB(x,y));
            check(colors.size()>20,"actual generated cloud geometry in image");
            check(mc.getWindow().getWidth()==windowWidth && mc.getWindow().getHeight()==windowHeight,"export does not resize window");
            check(image.getFrameBuffer()==null,"deferred close releases image target");
            final int[] chunks={0};
            generator.forRenderableMeshChunks(null,dev.nonamecrackers2.simpleclouds.client.mesh.chunk.MeshChunk::getOpaqueBuffers,
                (chunk,buffers)->chunks[0]++);
            check(chunks[0]>0,"caller generator survives renderer close");
            generator.close();generator=null;done=true;
            org.apache.logging.log4j.LogManager.getLogger("simpleclouds/ImageApiProbe").info(
                "[IMAGE-API] original GPU/2048 PNG/background/window preservation/immediate async close/caller generator ownership PASS file={}",file);
        } catch(Exception failure) {throw new IllegalStateException("Actual image API fixture failed",failure);}
    }
    @SuppressWarnings("removal")
    private static void verifyLegacyMigration() {
        var renderer=dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer.getInstance();
        Runnable[] methods={()->renderer.doBlurPostProcessing(0),
                ()->renderer.doFinalCompositePass(null,0,null),
                ()->renderer.doStormPostProcessing(null,0,null,0,0,0,0,0,0),
                ()->renderer.doCloudShadowProcessing(null,0,null,0,0,0,0),
                renderer::copyDepthFromCloudsToMain,renderer::copyDepthFromMainToClouds,
                renderer::copyDepthFromCloudsToTransparency};
        for(Runnable method:methods) {
            try {method.run();throw new IllegalStateException("Legacy API must not silently succeed");}
            catch(UnsupportedOperationException expected) {
                check(expected.getMessage().contains("docs/renderer-api-26.3.md"),"actionable API migration error");
            }
        }
        org.apache.logging.log4j.LogManager.getLogger("simpleclouds/ImageApiProbe").info(
                "[RENDERER-API] all seven legacy raw-GL stages explicitly reject with migration guidance PASS");
    }
    private static void check(boolean valid,String step) {if(!valid) throw new IllegalStateException("Image API: "+step);}
}
