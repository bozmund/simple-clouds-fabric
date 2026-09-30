import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudGridTransition;
import java.util.*;

public class CloudGridTransitionTest
{
    record Key(int x, int z, int lod) {}
    static final class Value { final int id; boolean closed; Value(int id) { this.id = id; } }
    static int nextId;
    static final List<Value> allocated = new ArrayList<>();
    static Value allocate() { Value v = new Value(nextId++); allocated.add(v); return v; }
    static void close(Value v) { check(!v.closed, "double release " + v.id); v.closed = true; }
    static void check(boolean yes, String why) { if (!yes) throw new AssertionError(why); }
    static Set<Key> targets(int x, int z) {
        Set<Key> out = new HashSet<>();
        for (int lod : new int[] {1, 2, 4, 8}) {
            int n = lod == 1 ? 8 : 10;
            for (int i = -n / 2; i < n / 2; i++)
                for (int j = -n / 2; j < n / 2; j++)
                    out.add(new Key(x + i * 32 * lod, z + j * 32 * lod, lod));
        }
        return out;
    }
    static void invariants(Map<Key, Value> current,
            Map<Key, CloudGridTransition.Retained<Key, Value>> retained, Set<Key> targets) {
        Set<Value> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var e : current.entrySet()) {
            check(targets.contains(e.getKey()), "non-target exact");
            check(!e.getValue().closed && owned.add(e.getValue()), "invalid exact ownership");
        }
        for (var e : retained.entrySet()) {
            check(targets.contains(e.getKey()) && !current.containsKey(e.getKey()), "invalid retained slot");
            check(e.getKey().lod() == e.getValue().origin().lod(), "LOD changed");
            check(!e.getValue().value().closed && owned.add(e.getValue().value()), "duplicate retained ownership");
        }
        check(owned.size() <= targets.size(), "unbounded residents");
        for (Value v : allocated) check(v.closed || owned.contains(v), "lost live owner " + v.id);
    }
    public static void main(String[] args) {
        Map<Key, Value> current = new HashMap<>();
        Map<Key, CloudGridTransition.Retained<Key, Value>> retained = new HashMap<>();
        Set<Key> initial = targets(-32, -32);
        for (Key key : initial) current.put(key, allocate());
        Map<Key, Value> before = new HashMap<>(current);
        Set<Key> outward = targets(0, -32);
        CloudGridTransition.retarget(current, retained, outward,
                key -> new Key(key.x() - 32, key.z(), key.lod()), CloudGridTransitionTest::close);
        check(current.size() == 56 && retained.size() == 300, "crossing discarded distant field");
        invariants(current, retained, outward);
        // Failed generation/upload performs no ownership mutation.
        Map<Key, CloudGridTransition.Retained<Key, Value>> failed = new HashMap<>(retained);
        check(retained.equals(failed), "failure lost predecessor");
        CloudGridTransition.retarget(current, retained, initial,
                key -> new Key(key.x() + 32, key.z(), key.lod()), CloudGridTransitionTest::close);
        check(retained.isEmpty() && current.size() == 356, "reverse did not restore world keys");
        for (var e : current.entrySet()) check(before.get(e.getKey()) == e.getValue(), "restoration moved geometry");
        invariants(current, retained, initial);
        Random random = new Random(20260929);
        int x = -32, z = -32;
        for (int i = 0; i < 1000; i++) {
            int dx = (random.nextInt(7) - 3) * 32, dz = (random.nextInt(7) - 3) * 32;
            x += dx; z += dz;
            Set<Key> next = targets(x, z);
            boolean layoutReset = i % 73 == 0;
            CloudGridTransition.retarget(current, retained, next,
                    key -> layoutReset ? null : new Key(key.x() - dx, key.z() - dz, key.lod()),
                    CloudGridTransitionTest::close);
            invariants(current, retained, next);
            for (Key key : next) {
                if (random.nextInt(4) != 0) continue; // unfinished/failed workers stay retained
                Value replacement = allocate();
                Value old = current.put(key, replacement);
                var fallback = retained.remove(key);
                if (old != null) close(old);
                if (fallback != null) close(fallback.value());
            }
            invariants(current, retained, next);
        }
        current.values().forEach(CloudGridTransitionTest::close);
        retained.values().forEach(v -> close(v.value()));
        check(allocated.stream().allMatch(v -> v.closed), "shutdown leak");
        System.out.println("CloudGridTransitionTest: forward/reverse, failures, 1000 repeated crossings, publication and shutdown PASS");
    }
}
