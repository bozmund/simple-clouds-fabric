package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.util.List;

/** Pure, testable cache identity. All distances are cloud units, not blocks. */
public final class ChunkGenerationKey
{
    private ChunkGenerationKey() {}

    public record Mask(float x, float z, float radius, float m00, float m01,
                       float m10, float m11, int group) {}

    public static long local(int x, int z, int span, int lod, List<Mask> masks)
    {
        // Empty means the generator's infinite preview mode, NOT empty sky.
        long hash = masks.isEmpty() ? 17 : 23;
        double step = lod * 0.5;
        for (Mask m : masks)
        {
            double det = (double)m.m00 * m.m11 - (double)m.m01 * m.m10;
            double extent = Math.max(0, m.radius) + 200 + lod;
            // Conservative inverse-transform ellipse bounds, including the mask's
            // outer fade and one neighbor sample for face culling.
            double ex = Math.abs(det) < 1e-12 ? Double.POSITIVE_INFINITY
                    : extent * Math.hypot(m.m11, m.m01) / Math.abs(det);
            double ez = Math.abs(det) < 1e-12 ? Double.POSITIVE_INFINITY
                    : extent * Math.hypot(m.m10, m.m00) / Math.abs(det);
            if (m.x + ex < x || m.x - ex > x + span
                    || m.z + ez < z || m.z - ez > z + span) continue;
            hash = 31 * hash + m.group;
            double maxDistance = 0;
            double maxTransformed = 0;
            for (int dx : new int[] {-lod, span + lod})
                for (int dz : new int[] {-lod, span + lod})
                {
                    double rx = x + dx - m.x, rz = z + dz - m.z;
                    maxDistance = Math.max(maxDistance, Math.hypot(rx, rz));
                    maxTransformed = Math.max(maxTransformed,
                            Math.hypot(m.m00 * rx + m.m01 * rz, m.m10 * rx + m.m11 * rz));
                }
            // Deep inside a formation its mask is exactly saturated. Drifting its
            // distant boundary cannot alter any sample in this chunk.
            if (maxTransformed < m.radius - 200 - lod)
            {
                hash = 31 * hash + 1;
                continue;
            }
            hash = 31 * hash + 2;
            hash = 31 * hash + quantize(m.x, step);
            hash = 31 * hash + quantize(m.z, step);
            hash = 31 * hash + quantize(m.radius, step);
            double transformStep = step / Math.scalb(1.0,
                    Math.max(0, Math.getExponent(Math.max(1, maxDistance)) + 1));
            hash = 31 * hash + quantize(m.m00, transformStep);
            hash = 31 * hash + quantize(m.m01, transformStep);
            hash = 31 * hash + quantize(m.m10, transformStep);
            hash = 31 * hash + quantize(m.m11, transformStep);
        }
        return hash;
    }

    private static long quantize(double value, double step)
    {
        return Math.round(value / step);
    }

    /** For noise(x + scroll), a positive scroll moves visible features negatively. */
    public static float drawOffset(float generated, float current)
    {
        return generated - current;
    }

    /** Shared lattice origin for all power-of-two LODs. Sampling x + scroll
     * stays on the same noise-space lattice when the generation phase changes.
     * A fixed world lattice would snap the coarse voxels backwards on each swap. */
    public static int latticeShift(float phase, int coarsestLod)
    {
        return -Math.floorMod((int)Math.floor(phase), coarsestLod);
    }
}
