package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import dev.nonamecrackers2.simpleclouds.client.mesh.chunk.MeshChunk;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.util.IdentityHashMap;

/** Draw ownership follows original MeshChunk buffers, not a second mesh scheduler.
 * Conversion happens only after an original publication (or height change). */
public final class OriginalCloudDrawBuffers implements AutoCloseable {
    private static final class Entry {
        GpuBuffer buffer;
        long revision=Long.MIN_VALUE;
        float height=Float.NaN;
		float scale=Float.NaN;
        boolean transparent;
        int count;
    }
    private final IdentityHashMap<MeshChunk.BufferSet,Entry> entries=new IdentityHashMap<>();
    private final OriginalMeshDrawAdapter adapter;
    public OriginalCloudDrawBuffers(ResourceManager resources) throws IOException {
        adapter=new OriginalMeshDrawAdapter(resources);
    }
    public CloudsDrawPipeline.InstanceSource get(MeshChunk.BufferSet source, boolean transparent, float height) {
		return get(source,transparent,8,height);
	}
	public CloudsDrawPipeline.InstanceSource get(MeshChunk.BufferSet source, boolean transparent, float scale, float height) {
        RenderSystem.assertOnRenderThread();
        Entry entry=entries.computeIfAbsent(source,ignored->new Entry());
        if(entry.revision!=source.getPublicationRevision() || entry.height!=height || entry.scale!=scale || entry.transparent!=transparent) {
            int elements=Math.min(source.getElementCount(),source.getMaxElements());
            int count=elements;
            long bytes=(long)count*24;
            if(count>0) {
                GpuBuffer target=entry.buffer;
                boolean replace=target==null || target.size()<bytes;
                if(replace) target=RenderSystem.getDevice().createBuffer(()->"simpleclouds.originalDraw",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,bytes);
                try {
                    adapter.convert(source.getBufferId(),elements,((GlBuffer)target).handle(),transparent,scale,height);
                } catch(RuntimeException failure) {
                    if(replace) target.close();
                    throw failure;
                }
                if(replace) {
                    if(entry.buffer!=null) entry.buffer.close();
                    entry.buffer=target;
                }
            }
            entry.count=count;
            entry.height=height;
			entry.scale=scale;
            entry.transparent=transparent;
            entry.revision=source.getPublicationRevision();
        }
        return new CloudsDrawPipeline.InstanceSource(entry.buffer,entry.count);
    }
    @Override public void close() {
        for(Entry entry:entries.values()) if(entry.buffer!=null) entry.buffer.close();
        entries.clear();
        adapter.close();
    }
}
