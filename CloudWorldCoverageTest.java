import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudWorldCoverage;
import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudWorldCoverage.Rect;
import java.util.*;

public class CloudWorldCoverageTest {
    record Key(int x, int z, int lod) {}
    static final class Buffer { boolean closed; }
    static final List<Buffer> allocated = new ArrayList<>();
    static Buffer allocate() { Buffer b = new Buffer(); allocated.add(b); return b; }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static Map<Key, Rect> grid(int dx, int dz, int lod) {
        Map<Key, Rect> targets = new HashMap<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            int left = x * 32 * lod + dx, top = z * 32 * lod + dz;
            targets.put(new Key(left, top, lod), new Rect(left, top, left + 32 * lod, top + 32 * lod, lod));
        }
        return targets;
    }
    static void assertCoverage(CloudWorldCoverage<Key, Buffer> coverage, int lod) {
        var pieces = coverage.snapshot().values().stream().flatMap(List::stream).toList();
        for (int x = -32 * lod; x < 64 * lod; x += lod)
            for (int z = -32 * lod; z < 32 * lod; z += lod) {
                int owners = 0;
                for (var piece : pieces) if (piece.bounds().contains(x, z)) {
                    check(!piece.source().closed, "closed source still drawable"); owners++;
                }
                check(owners == 1, "world cell " + x + "," + z + " owners=" + owners);
            }
    }
    public static void main(String[] args) {
        int checked = 0;
        int peakFragments = 0, peakOwners = 0;
        // No publication during a reversal: the same physical source should
        // return as one rectangle, not accumulate draw fragments forever.
        var reversible = new CloudWorldCoverage<Key, Buffer>(b -> {
            check(!b.closed, "double release in reversal"); b.closed=true;
        });
        var origin = grid(0,0,8);
        reversible.retarget(origin);
        origin.keySet().forEach(k -> reversible.publish(k,allocate()));
        for (int i=0;i<20;i++) {
            reversible.retarget(grid(32,32,8));
            reversible.retarget(origin);
            check(reversible.fragments(new Key(0,0,8)).size()==1,
                "pure reversal fragmented the unchanged center buffer");
        }
        reversible.close();
        List<String> releasedEqual = new ArrayList<>();
        var equalValues = new CloudWorldCoverage<String,String>(releasedEqual::add);
        String first=new String("same"), second=new String("same");
        equalValues.retarget(Map.of("left",new Rect(0,0,32,32,1),"right",new Rect(32,0,64,32,1)));
        equalValues.publish("left",first); equalValues.publish("right",second);
        equalValues.retarget(Map.of("whole",new Rect(0,0,64,32,1)));
        check(equalValues.fragments("whole").size()==2,"merged equal-valued distinct buffer owners");
        equalValues.close();
        check(releasedEqual.size()==2 && releasedEqual.stream().anyMatch(v -> v==first)
            && releasedEqual.stream().anyMatch(v -> v==second),"lost equal-valued owner");
        for (int lod : new int[] {2, 4, 8}) {
            var coverage = new CloudWorldCoverage<Key, Buffer>(b -> {
                check(!b.closed, "double release"); b.closed = true;
            });
            var initial = grid(0, 0, lod);
            coverage.retarget(initial);
            initial.keySet().forEach(k -> coverage.publish(k, allocate()));
            var shifted = grid(32, 0, lod);
            coverage.retarget(shifted);
            assertCoverage(coverage, lod);
            coverage.publish(new Key(32, 0, lod), allocate());
            assertCoverage(coverage, lod); // formerly 512/256/128 gaps and duplicates
            // Source of that target remains alive in its other clipped target.
            coverage.retarget(initial);
            assertCoverage(coverage, lod);
            Random random = new Random(20260930);
            for (int i = 0; i < 100; i++) {
                var next = grid(i % 2 == 0 ? 32 : 0, i % 4 < 2 ? 32 : 0, lod);
                coverage.retarget(next);
                var keys = new ArrayList<>(next.keySet());
                keys.sort(Comparator.comparingInt(Key::x).thenComparingInt(Key::z));
                Collections.shuffle(keys, random);
                for (Key key : keys) {
                    coverage.publish(key, allocate());
                    assertCoverage(coverage, lod); checked++;
                }
            }
            // Slow/failed workers: never finish a whole layout before another
            // X/Z crossing. The constant interior must stay covered throughout.
            for (int i=0;i<1000;i++) {
                var next=grid((random.nextInt(3)-1)*32,(random.nextInt(3)-1)*32,lod);
                coverage.retarget(next);
                var keys=new ArrayList<>(next.keySet());
                keys.sort(Comparator.comparingInt(Key::x).thenComparingInt(Key::z));
                if (i%4!=0) coverage.publish(keys.get(random.nextInt(keys.size())),allocate());
                assertCoverage(coverage,lod);
                var fragments=coverage.snapshot().values().stream().flatMap(List::stream).toList();
                Set<Buffer> owners=Collections.newSetFromMap(new IdentityHashMap<>());
                fragments.forEach(p -> owners.add(p.source()));
                check(fragments.size()<=next.size()*lod*lod,"fragment lattice bound exceeded");
                peakFragments=Math.max(peakFragments,fragments.size());
                peakOwners=Math.max(peakOwners,owners.size());
                checked++;
            }
            var beforeFailure = coverage.snapshot();
            Buffer rejected = new Buffer(); // rejected source remains caller-owned
            try {
                coverage.publish(new Key(999, 999, lod), rejected);
                throw new AssertionError("accepted unknown target");
            } catch (IllegalArgumentException expected) {}
            check(!rejected.closed, "released caller-owned rejected source");
            check(coverage.snapshot().equals(beforeFailure), "unknown publication changed state");
            try {
                coverage.retarget(Map.of(new Key(0,0,lod), new Rect(0,0,64,64,lod),
                    new Key(32,0,lod), new Rect(32,0,96,64,lod)));
                throw new AssertionError("accepted overlapping layout");
            } catch (IllegalArgumentException expected) {}
            check(coverage.snapshot().equals(beforeFailure), "bad layout changed state");
            coverage.close(); coverage.close();
        }
        // Retirement can fail after publication commits. The renderer's catch
        // must distinguish that case from an upload rejected before publication.
        Buffer retired = new Buffer(), committed = new Buffer();
        var retirementFailure = new CloudWorldCoverage<String, Buffer>(b -> {
            check(!b.closed, "double release after retirement failure");
            b.closed = true;
            if (b == retired) throw new IllegalStateException("injected retirement failure");
        });
        retirementFailure.retarget(Map.of("target", new Rect(0,0,32,32,1)));
        retirementFailure.publish("target", retired);
        try {
            retirementFailure.publish("target", committed);
            throw new AssertionError("missing injected retirement failure");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().equals("injected retirement failure"), "unexpected failure");
        }
        check(retirementFailure.fragments("target").stream().anyMatch(p -> p.source()==committed),
            "publication was not committed before retirement failure");
        check(!committed.closed, "closed live replacement after retirement failure");
        retirementFailure.close();
        check(committed.closed && retired.closed, "retirement failure leaked owner");
        check(allocated.stream().allMatch(b -> b.closed), "leaked buffer owner");
        System.out.println("PASS: " + checked + " partial-publication states preserve unique interior coverage; reversals, clipping and release-once; peak fragments=" + peakFragments + ", peak owners=" + peakOwners);
    }
}
