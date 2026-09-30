import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CpuCloudGenerator;

/** Same world cells must produce identical records after a grid relocation.
 * Time, regions, camera and noise phase stay fixed; no game or GPU is needed. */
public class CloudRelocationParityTest {
    private static Set<String>[] mesh(int x, int z, int lod, boolean storm) {
        var layer = new CpuCloudGenerator.NoiseLayer(32, .4F, 16, 16, 16, 8, 0, 1);
        var generator = new CpuCloudGenerator(List.of(
            new CpuCloudGenerator.CloudLayerGroup(List.of(layer), .4F, storm, .8F, 4, 8)));
        generator.setRegions(List.of(
            new CpuCloudGenerator.RegionMask(-24, 12, 100, 1, .15F, -.15F, 1, 0)));
        var buffers = generator.generate(x, 0, z, x + 32 * lod, 32, z + 32 * lod,
            8, .75F, .25F, -.5F, .1F, lod, 128,
            ByteBuffer.allocate(8 * 1024 * 1024).order(ByteOrder.nativeOrder()),
            ByteBuffer.allocate(8 * 1024 * 1024).order(ByteOrder.nativeOrder()),
            (b, n, w) -> { throw new AssertionError("unexpected buffer growth"); },
            new float[1], new float[1], 0, new float[] {0, 0}, new float[1]);
        @SuppressWarnings("unchecked")
        Set<String>[] result = new Set[] {new HashSet<String>(), new HashSet<String>()};
        for (int kind = 0; kind < 2; kind++) {
            var buffer = buffers[kind];
            int stride = kind == 0 ? 24 : 28;
            for (int p = 0; p < buffer.limit(); p += stride) {
                StringBuilder record = new StringBuilder();
                for (int q = 0; q < stride; q += 4)
                    record.append(Integer.toHexString(buffer.getInt(p + q))).append(':');
                if (!result[kind].add(record.toString()))
                    throw new AssertionError("duplicate face record");
            }
        }
        return result;
    }

    private static Set<String> overlap(Set<String> records, int x0, int z0, int x1, int z1) {
        Set<String> result = new HashSet<>();
        for (String record : records) {
            String[] fields = record.split(":");
            float x = Float.intBitsToFloat(Integer.parseUnsignedInt(fields[1], 16)) / 8;
            float z = Float.intBitsToFloat(Integer.parseUnsignedInt(fields[3], 16)) / 8;
            if (x >= x0 && x < x1 && z >= z0 && z < z1) result.add(record);
        }
        return result;
    }

    public static void main(String[] args) {
        int comparisons = 0, opaque = 0, transparent = 0;
        for (int lod : new int[] {1, 2, 4, 8})
            for (boolean storm : new boolean[] {false, true})
                for (int base : new int[] {-64, 0})
                    for (int[] shift : new int[][] {{32,0}, {-32,0}, {0,32}, {0,-32}}) {
                        // A LOD-1 chunk and its neighbor do not overlap; use two-cell
                        // relocation there to exercise the same interior invariant.
                        int dx = lod == 1 ? shift[0] / 16 : shift[0];
                        int dz = lod == 1 ? shift[1] / 16 : shift[1];
                        var before = mesh(base, base, lod, storm);
                        var after = mesh(base + dx, base + dz, lod, storm);
                        int x0 = Math.max(base, base + dx), z0 = Math.max(base, base + dz);
                        int x1 = Math.min(base, base + dx) + 32 * lod;
                        int z1 = Math.min(base, base + dz) + 32 * lod;
                        for (int kind = 0; kind < 2; kind++) {
                            var a = overlap(before[kind], x0, z0, x1, z1);
                            var b = overlap(after[kind], x0, z0, x1, z1);
                            if (!a.equals(b)) {
                                var missing = new HashSet<>(a); missing.removeAll(b);
                                var added = new HashSet<>(b); added.removeAll(a);
                                throw new AssertionError("LOD=" + lod + " storm=" + storm
                                    + " base=" + base + " shift=" + dx + "," + dz + " kind=" + kind
                                    + " missing=" + missing.size() + " added=" + added.size());
                            }
                            if (kind == 0) opaque += a.size(); else transparent += a.size();
                            comparisons++;
                        }
                    }
        if (opaque == 0 || transparent == 0) throw new AssertionError("vacuous mesh comparison");
        System.out.println("PASS: " + comparisons + " fixed-phase relocation comparisons; "
            + opaque + " opaque and " + transparent + " transparent overlap records identical");
    }
}
