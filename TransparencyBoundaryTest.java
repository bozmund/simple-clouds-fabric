import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;

/** Exact open-interval boundaries from the original cube_mesh.comp shader. */
public class TransparencyBoundaryTest {
    private static void check(boolean actual, boolean expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        check(CpuCloudGenerator.isTransparentEdge(-0.5f, 1.0f), true, "interior alpha edge");
        check(CpuCloudGenerator.isTransparentEdge(0.0f, 1.0f), false, "zero noise is not transparent");
        check(CpuCloudGenerator.isTransparentEdge(-0.0f, 1.0f), false, "negative zero is not transparent");
        check(CpuCloudGenerator.isTransparentEdge(-1.0f, 1.0f), false, "negative fade boundary");
        check(CpuCloudGenerator.isTransparentEdge(-0.001f, 0.01f), false, "minimum fade boundary");
        check(CpuCloudGenerator.isTransparentEdge(-0.001f, 0.011f), true, "above minimum fade");

        for (int lod : new int[] {1, 2, 4, 8, 16}) {
            check(CpuCloudGenerator.insideTransparencyDistance(-lod, 0, 0, 0, lod), false,
                    "negative exact cutoff, LOD " + lod);
            check(CpuCloudGenerator.insideTransparencyDistance(lod, 0, 0, 0, lod), false,
                    "positive exact cutoff, LOD " + lod);
            check(CpuCloudGenerator.insideTransparencyDistance(lod - 1, 0, 0, 0, lod), true,
                    "inside cutoff, LOD " + lod);
        }
        // A center-sampled LOD-2 cube would be at 9 and incorrectly excluded.
        check(CpuCloudGenerator.insideTransparencyDistance(8, 0, 0, 0, 8.5f), true,
                "use lattice origin, not cube center");
        int highSpan = 80;
        if (CpuCloudGenerator.transparencyDistance(highSpan, 32, 1) != 12
                || CpuCloudGenerator.transparencyDistance(highSpan, 32, 25) != 320
                || CpuCloudGenerator.transparencyDistance(highSpan, 32, 50) != 640
                || CpuCloudGenerator.transparencyDistance(highSpan, 32, 100) != 1280)
            throw new AssertionError("LOD radius and percentage must match the original mesh generator");
        if (CpuCloudGenerator.transparencyDistance(42, 32, 37) != 248)
            throw new AssertionError("Custom LOD radius did not update transparency cutoff");
        try {
            CpuCloudGenerator.transparencyDistance(80, 32, 0);
            throw new AssertionError("Invalid percentage accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: strict transparency noise and distance boundaries at five LODs");
    }
}
