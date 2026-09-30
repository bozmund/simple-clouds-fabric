package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.util.*;
import java.util.function.Consumer;

/** World-space coverage of asynchronous chunks. A retained buffer may supply
 * disjoint pieces of several logical targets without moving any vertex.
 * Callers must clip each draw to its fragment and derive deltas from all
 * fragments of a target, not just its former logical-slot owner. */
public final class CloudWorldCoverage<K, V> implements AutoCloseable {
    public record Rect(int x0, int z0, int x1, int z1, int lod) {
        public Rect {
            if (lod <= 0 || x0 >= x1 || z0 >= z1
                    || x0 % lod != 0 || z0 % lod != 0 || x1 % lod != 0 || z1 % lod != 0)
                throw new IllegalArgumentException("invalid world-cell rectangle");
        }
        public Rect intersection(Rect other) {
            // A retained source keeps its own voxel lattice even when its
            // world area enters a target with a different requested LOD.
            // Production ring bounds are multiples of 32, aligned to all LODs.
            int left = Math.max(x0, other.x0), top = Math.max(z0, other.z0);
            int right = Math.min(x1, other.x1), bottom = Math.min(z1, other.z1);
            return left < right && top < bottom ? new Rect(left, top, right, bottom, lod) : null;
        }
        public boolean contains(int x, int z) {
            return x >= x0 && x < x1 && z >= z0 && z < z1;
        }
    }
    public record Fragment<V>(Rect bounds, V source) {}
    private Map<K, Rect> targets = Map.of();
    private Map<K, List<Fragment<V>>> pieces = Map.of();
    private final Consumer<V> release;
    public CloudWorldCoverage(Consumer<V> release) { this.release = Objects.requireNonNull(release); }

    private Set<V> owners() {
        Set<V> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        pieces.values().forEach(list -> list.forEach(piece -> owners.add(piece.source)));
        return owners;
    }
    private void releaseUnused(Set<V> before) {
        Set<V> after = owners();
        before.forEach(value -> { if (!after.contains(value)) release.accept(value); });
    }
    public List<Fragment<V>> fragments(K target) { return pieces.getOrDefault(target, List.of()); }
    public Map<K, List<Fragment<V>>> snapshot() { return Map.copyOf(pieces); }

    private static Rect joined(Rect a, Rect b) {
        if (a.lod != b.lod) return null;
        if (a.x0 == b.x0 && a.x1 == b.x1 && (a.z1 == b.z0 || b.z1 == a.z0))
            return new Rect(a.x0, Math.min(a.z0,b.z0), a.x1, Math.max(a.z1,b.z1), a.lod);
        if (a.z0 == b.z0 && a.z1 == b.z1 && (a.x1 == b.x0 || b.x1 == a.x0))
            return new Rect(Math.min(a.x0,b.x0), a.z0, Math.max(a.x1,b.x1), a.z1, a.lod);
        return null;
    }
    private static <V> List<Fragment<V>> coalesced(List<Fragment<V>> input) {
        List<Fragment<V>> fragments = new ArrayList<>(input);
        boolean changed;
        do {
            changed = false;
            search: for (int i=0;i<fragments.size();i++) for (int j=i+1;j<fragments.size();j++) {
                Fragment<V> a=fragments.get(i), b=fragments.get(j);
                if (a.source != b.source) continue; // buffer identity, never value equality
                Rect union = joined(a.bounds,b.bounds);
                if (union == null) continue;
                fragments.set(i,new Fragment<>(union,a.source)); fragments.remove(j);
                changed=true; break search;
            }
        } while (changed);
        fragments.sort(Comparator.comparingInt((Fragment<V> p) -> p.bounds.x0)
            .thenComparingInt(p -> p.bounds.z0).thenComparingInt(p -> p.bounds.x1).thenComparingInt(p -> p.bounds.z1));
        return List.copyOf(fragments);
    }

    public void retarget(Map<K, Rect> next) {
        Map<K, Rect> checked = Map.copyOf(next);
        List<Rect> bounds = new ArrayList<>(checked.values());
        for (int i = 0; i < bounds.size(); i++)
            for (int j = i + 1; j < bounds.size(); j++)
                if (bounds.get(i).intersection(bounds.get(j)) != null)
                    throw new IllegalArgumentException("overlapping world targets");
        Set<V> before = owners();
        List<Fragment<V>> previous = pieces.values().stream().flatMap(List::stream).toList();
        Map<K, List<Fragment<V>>> rebuilt = new HashMap<>();
        checked.forEach((key, rect) -> {
            List<Fragment<V>> clipped = new ArrayList<>();
            for (Fragment<V> piece : previous) {
                Rect overlap = piece.bounds.intersection(rect);
                if (overlap != null) clipped.add(new Fragment<>(overlap, piece.source));
            }
            rebuilt.put(key, coalesced(clipped));
        });
        targets = checked;
        pieces = rebuilt;
        releaseUnused(before);
    }
    /** Invoke only after successful generation/upload. A failed worker leaves
     * its fragments untouched. Each world cell in the target is replaced once. */
    public void publish(K target, V source) {
        Rect bounds = targets.get(target);
        if (bounds == null) throw new IllegalArgumentException("unknown target");
        Objects.requireNonNull(source);
        Set<V> before = owners();
        Map<K, List<Fragment<V>>> next = new HashMap<>(pieces);
        next.put(target, List.of(new Fragment<>(bounds, source)));
        pieces = next;
        releaseUnused(before);
    }
    @Override public void close() {
        Set<V> before = owners();
        pieces = Map.of(); targets = Map.of();
        before.forEach(release);
    }
}
