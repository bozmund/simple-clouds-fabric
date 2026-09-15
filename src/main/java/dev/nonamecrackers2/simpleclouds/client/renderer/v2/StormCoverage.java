package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

/** Contributions to one camera-centered 8x8 cloud-unit footprint.
 * Non-overlapping chunks contribute fractions of the same 64 samples. */
public final class StormCoverage {
    private StormCoverage() {}

    public static float contribution(boolean[] columns, int xCells, int zCells,
            int x0, int z0, int lod, float cameraX, float cameraZ) {
        if (!Float.isFinite(cameraX) || !Float.isFinite(cameraZ)) return 0;
        double startX = Math.floor(cameraX) - 4;
        double startZ = Math.floor(cameraZ) - 4;
        int marked = 0;
        for (int x = 0; x < 8; x++) {
            double localX = (startX + x + 0.5 - x0) / lod;
            if (localX < 0 || localX >= xCells) continue;
            int ix = (int)localX;
            for (int z = 0; z < 8; z++) {
                double localZ = (startZ + z + 0.5 - z0) / lod;
                if (localZ < 0 || localZ >= zCells) continue;
                if (columns[ix * zCells + (int)localZ]) marked++;
            }
        }
        return marked / 64.0F;
    }
}
