import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.StormFogMap;

/** Plan item 3: the spatial storm-fog coverage map (rasterization and uniform layout). */
public class StormFogMapTest {
    static int checks = 0;
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-5f; }

    public static void main(String[] args) {
        int n = StormFogMap.CELLS;
        float cell = StormFogMap.CELL_BLOCKS;
        StormFogMap map = new StormFogMap();

        // Camera at 0,0: the map spans -1024..1024 blocks, corner at -16 cells.
        map.begin(0.0, 0.0);
        check(near(map.originX(), -n / 2 * cell) && near(map.originZ(), -n / 2 * cell), "map centred on the camera");
        check(!map.hasCoverage(), "empty after begin");

        // LOD 8 (a column is 64 blocks = one cell): a fully stormy 32x32-column chunk at 0,0
        // covers the positive quarter exactly.
        byte[] full = new byte[32 * 32];
        Arrays.fill(full, (byte) 1);
        map.addChunk(full, 32, 32, 0.0f, 0.0f, 8 * 8);
        check(map.hasCoverage(), "coverage after a stormy chunk");
        for (int x = 0; x < n; x++)
            for (int z = 0; z < n; z++)
                check(near(map.get(x, z), x >= n / 2 && z >= n / 2 ? 1.0f : 0.0f), "LOD 8 cell " + x + "," + z);

        // LOD 1 (8-block columns): one column is 1/64 of a cell; one straddling two cells splits.
        for (int lod : new int[] { 1, 2, 4 }) {
            map.begin(0.0, 0.0);
            byte[] one = new byte[4];
            one[0] = 1; // column (0, 0) of a 2x2-column chunk
            float cb = lod * 8;
            map.addChunk(one, 2, 2, 0.0f, 0.0f, cb);
            check(near(map.get(n / 2, n / 2), cb * cb / (cell * cell)), "LOD " + lod + " single column share");
            map.begin(0.0, 0.0);
            map.addChunk(one, 2, 2, cell - cb / 2, 0.0f, cb); // straddles cells 16 and 17 in X
            float half = (cb / 2) * cb / (cell * cell);
            check(near(map.get(n / 2, n / 2), half) && near(map.get(n / 2 + 1, n / 2), half), "LOD " + lod + " straddling column splits");
        }

        // Negative coordinates and the map edge.
        map.begin(0.0, 0.0);
        map.addChunk(full, 32, 32, -2048.0f, -2048.0f, 8 * 8); // covers -2048..0: the negative quarter
        check(near(map.get(0, 0), 1.0f) && near(map.get(n / 2 - 1, n / 2 - 1), 1.0f) && near(map.get(n / 2, n / 2), 0.0f), "negative quarter");
        map.begin(0.0, 0.0);
        map.addChunk(full, 32, 32, 5000.0f, 5000.0f, 8 * 8);
        check(!map.hasCoverage(), "chunks outside the map are ignored");

        // Overlapping additions are clamped to 1 when read and uploaded.
        map.begin(0.0, 0.0);
        map.addChunk(full, 32, 32, 0.0f, 0.0f, 8 * 8);
        map.addChunk(full, 32, 32, 0.0f, 0.0f, 8 * 8);
        check(near(map.get(n - 1, n - 1), 1.0f), "clamped read");

        // Invalid camera / chunk coordinates: no coverage, no exception.
        map.begin(Double.NaN, 0.0);
        map.addChunk(full, 32, 32, 0.0f, 0.0f, 64.0f);
        check(!map.hasCoverage(), "NaN camera ignored");
        map.begin(0.0, 0.0);
        map.addChunk(full, 32, 32, Float.NaN, 0.0f, 64.0f);
        map.addChunk(full, 32, 32, 0.0f, 0.0f, 0.0f);
        map.addChunk(new byte[3], 32, 32, 0.0f, 0.0f, 64.0f);
        check(!map.hasCoverage(), "invalid chunks ignored");

        // Uniform layout (std140, core/storm_fog.fsh).
        map.begin(100.0, -300.0);
        map.addChunk(full, 32, 32, 0.0f, 0.0f, 8 * 8);
        ByteBuffer ubo = ByteBuffer.allocate(StormFogMap.UBO_BYTES).order(ByteOrder.nativeOrder());
        float[] bolts = { 10, 200, 20, 0.9f, 0.75f, 0.8f, 1.0f, StormFogMap.BOLT_RADIUS };
        map.writeUniform(ubo, 100.0, 70.0, -300.0, 128.0f, 800.0f, bolts, 1, 0);
        check(StormFogMap.UBO_BYTES == 4416, "UBO size 320 + 4096");
        check(near(ubo.getFloat(16), 128.0f) && near(ubo.getFloat(20), 800.0f), "fog top / distance");
        check(near(ubo.getFloat(32), 100.0f) && near(ubo.getFloat(36), 70.0f) && near(ubo.getFloat(40), -300.0f), "camera");
        check(near(ubo.getFloat(48), map.originX()) && near(ubo.getFloat(52), map.originZ()) && near(ubo.getFloat(56), n), "map info");
        check(near(ubo.getFloat(60), 1.0f), "bolt count");
        check(near(ubo.getFloat(64), 10f) && near(ubo.getFloat(76), 0.9f) && near(ubo.getFloat(192 + 12), StormFogMap.BOLT_RADIUS), "bolt 0");
        check(near(ubo.getFloat(64 + 16 + 12), 0.0f), "unused bolt slots are zero");
        int cx = (int) Math.floor((0.0f - map.originX()) / cell) + 1, cz = (int) Math.floor((0.0f - map.originZ()) / cell) + 1;
        check(near(ubo.getFloat(320 + 4 * (cz * n + cx)), map.get(cx, cz)) && map.get(cx, cz) > 0.99f, "coverage index z*CELLS+x");
        System.out.println("StormFogMapTest: " + checks + " checks passed");
    }
}
