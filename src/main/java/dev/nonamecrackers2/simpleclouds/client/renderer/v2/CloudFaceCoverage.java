package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Builds a worker's predecessor mesh from every world-space source fragment.
 * Half-open cell-center selection matches cloud_cell_clip.glsl. Complete
 * face records (including brightness/alpha and duplicate group faces) survive. */
public final class CloudFaceCoverage {
    private CloudFaceCoverage() {}
    private static boolean included(ByteBuffer source, int at, CloudWorldCoverage.Rect bounds) {
        float x = source.getFloat(at + 4), z = source.getFloat(at + 12);
        if (!Float.isFinite(x) || !Float.isFinite(z))
            throw new IllegalArgumentException("nonfinite cloud face center");
        return x >= bounds.x0() * 8.0F && x < bounds.x1() * 8.0F
            && z >= bounds.z0() * 8.0F && z < bounds.z1() * 8.0F;
    }
    public static byte[] gather(List<CloudWorldCoverage.Fragment<byte[]>> fragments, int stride) {
        if (stride != 24 && stride != 28) throw new IllegalArgumentException("invalid cloud face stride");
        int size = 0;
        for (var fragment : fragments) {
            byte[] bytes = fragment.source();
            if (bytes == null || bytes.length % stride != 0)
                throw new IllegalArgumentException("malformed predecessor face data");
            ByteBuffer source = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
            for (int at = 0; at < bytes.length; at += stride)
                if (included(source, at, fragment.bounds())) size = Math.addExact(size, stride);
        }
        byte[] result = new byte[size];
        int write = 0;
        for (var fragment : fragments) {
            byte[] bytes = fragment.source();
            ByteBuffer source = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
            for (int at = 0; at < bytes.length; at += stride)
                if (included(source, at, fragment.bounds())) {
                    System.arraycopy(bytes, at, result, write, stride); write += stride;
                }
        }
        return result;
    }
}
