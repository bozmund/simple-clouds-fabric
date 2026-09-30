import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudGridTransition;
import java.util.*;

/** Offline diagnostic of physical coverage, distinct from buffer ownership.
 * Exit 1 means the current partial-publication policy leaves interior gaps or
 * duplicate ownership. Do not add to the passing suite until that policy is fixed. */
public class CloudGridCoverageProbe {
    record Key(int x, int z, int lod) {}
    static Set<Key> grid(int dx, int dz, int lod) {
        Set<Key> keys = new HashSet<>();
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++)
                keys.add(new Key(x * 32 * lod + dx, z * 32 * lod + dz, lod));
        return keys;
    }
    static int[] coverage(Map<Key, Key> exact,
            Map<Key, CloudGridTransition.Retained<Key, Key>> retained, int lod) {
        List<Key> origins = new ArrayList<>(exact.values());
        retained.values().forEach(v -> origins.add(v.origin()));
        int gaps = 0, duplicates = 0;
        // Central area remains within both old and new layouts. Outer growth
        // and clipping cannot account for any counted missing/duplicate cell.
        for (int x = -32 * lod; x < 64 * lod; x += lod)
            for (int z = -32 * lod; z < 32 * lod; z += lod) {
                int owners = 0;
                for (Key origin : origins)
                    if (x >= origin.x && x < origin.x + 32 * lod
                            && z >= origin.z && z < origin.z + 32 * lod) owners++;
                if (owners == 0) gaps++;
                if (owners > 1) duplicates++;
            }
        return new int[] {gaps, duplicates};
    }
    public static void main(String[] args) {
        boolean failed = false;
        for (int lod : new int[] {2, 4, 8}) {
            Map<Key, Key> exact = new HashMap<>();
            Map<Key, CloudGridTransition.Retained<Key, Key>> retained = new HashMap<>();
            grid(0, 0, lod).forEach(k -> exact.put(k, k));
            var targets = grid(32, 0, lod);
            CloudGridTransition.retarget(exact, retained, targets,
                k -> new Key(k.x - 32, k.z, k.lod), k -> {});
            int[] before = coverage(exact, retained, lod);
            if (before[0] != 0 || before[1] != 0)
                throw new AssertionError("fixture not initially covered");
            Key published = new Key(32, 0, lod);
            exact.put(published, published);
            retained.remove(published); // successful production replacement
            int[] after = coverage(exact, retained, lod);
            System.out.printf("LOD=%d one completed replacement: missing cells=%d duplicate cells=%d%n",
                lod, after[0], after[1]);
            failed |= after[0] != 0 || after[1] != 0;
            // Completing all neighbors restores a regular tessellation.
            retained.clear(); exact.clear(); targets.forEach(k -> exact.put(k, k));
            int[] complete = coverage(exact, retained, lod);
            if (complete[0] != 0 || complete[1] != 0)
                throw new AssertionError("complete replacement is not covered");
        }
        if (failed) throw new AssertionError("partial publication violates physical coverage; buffer ownership alone is insufficient");
    }
}
