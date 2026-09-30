package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.MappableRingBuffer;

/** Distinct, aligned per-draw clip slices survive deferred GPU execution.
 * Never overwrite one uniform repeatedly while earlier draws still reference it. */
public final class CloudClipRing implements AutoCloseable {
    private final MappableRingBuffer ring;
    private final int stride, slots;
    private int position;
    public CloudClipRing(int slots) {
        if (slots <= 0) throw new IllegalArgumentException("clip slots must be positive");
        int alignment = Math.max(1, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
        stride = (16 + alignment - 1) / alignment * alignment;
        this.slots = slots;
        ring = new MappableRingBuffer(() -> "simpleclouds.worldCellClips",
            GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, Math.multiplyExact(stride, slots));
    }
    public void reset() { position = 0; ring.rotate(); }
    public GpuBufferSlice write(CloudWorldCoverage.Rect bounds) {
        if (position == slots) return null;
        int offset = position++ * stride;
        GpuBuffer buffer = ring.currentBuffer();
        try (var mapped = buffer.map(offset, 16, false, true)) {
            var data = mapped.data();
            data.putFloat(0, bounds.x0() * 8.0F);
            data.putFloat(4, bounds.z0() * 8.0F);
            data.putFloat(8, bounds.x1() * 8.0F);
            data.putFloat(12, bounds.z1() * 8.0F);
        }
        return buffer.slice(offset, 16);
    }
    @Override public void close() { ring.close(); }
}
