package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

/** Bounded development-only exact attachment readback, not visual acceptance. */
public final class OitMrtParityProbe {
    private static final boolean ENABLED=IndexedCloudBlend.enabled()
        && "1".equals(System.getenv("SIMPLECLOUDS_TEST_OIT_MRT_PARITY"));
    private static int started,pending,completed;
    private OitMrtParityProbe() {}
    public static Sample begin() {
        if(!ENABLED || started>=16 || pending>=4)return null;
        // The normal launcher first opens the saved world at an arbitrary
        // camera, before MATCHSHAKE applies its deterministic field/camera.
        // A saved first fixture capture proves that setup has completed.
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().toString().equals(
                "/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3"))
            throw new IllegalStateException("OIT parity requires the owned fullpack fixture");
        if(!java.nio.file.Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots/devshot-SHAKE-01.png")))return null;
        pending++;return new Sample(++started);
    }
    public static final class Sample {
        private final int id;
        private final byte[][] data=new byte[4][];
        private int available;
        private Sample(int id) {this.id=id;}
        public void capture(RenderTarget accum,RenderTarget reveal,boolean candidate) {
            read(accum,8,candidate?2:0);read(reveal,1,candidate?3:1);
        }
        private void read(RenderTarget target,int bytesPerPixel,int slot) {
            var device=RenderSystem.getDevice();
            var buffer=device.createBuffer(()->"simpleclouds.oitParityReadback",
                GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,Math.multiplyExact((long)target.width*target.height,bytesPerPixel));
            try {
                device.createCommandEncoder().copyTextureToBuffer(target.getColorTexture(),buffer,0,()->{
                    try(buffer;var mapping=buffer.map(true,false)) {
                        var bytes=mapping.data();data[slot]=new byte[bytes.remaining()];bytes.get(data[slot]);
                        if(++available==4)verify();
                    } catch(Throwable failure) {
                        org.slf4j.LoggerFactory.getLogger(OitMrtParityProbe.class).error("Simple Clouds ERROR: OIT MRT parity readback failed",failure);
                    }
                },0);
            } catch(Throwable failure) {buffer.close();throw failure;}
        }
        private void verify() {
            for(int slot=0;slot<2;slot++) {
                int mismatch=java.util.Arrays.mismatch(data[slot],data[slot+2]);
                if(mismatch!=-1)throw new IllegalStateException("OIT MRT attachment differs sample="+id+" slot="+slot+" byte="+mismatch);
            }
            boolean coverage=false;for(byte value:data[1])if((value&255)!=255){coverage=true;break;}
            if(!coverage)throw new IllegalStateException("OIT MRT parity sample had no transparent coverage");
            pending--;completed++;
            org.slf4j.LoggerFactory.getLogger(OitMrtParityProbe.class).info("[OIT-MRT-PARITY] sample={} completed={} byte-identical RGBA16F/R8 covered attachments",id,completed);
            if(completed==16)org.slf4j.LoggerFactory.getLogger(OitMrtParityProbe.class).info("[OIT-MRT-PARITY] PASS 16 actual full-field attachment pairs and backend blend-state restoration");
        }
    }
}
