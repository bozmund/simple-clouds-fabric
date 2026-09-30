package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** One refresh budget across all LOD rings, with bounded visibility credit.
 * Old offscreen chunks can eventually outrank newly refreshed visible chunks.
 * Caller retains separate priority for missing chunks and pending/fading guards. */
public final class CloudRefreshSchedule {
    private CloudRefreshSchedule() {}
    public static <T> List<T> select(List<T> candidates, int budget,
            ToLongFunction<T> lastGenerationTick, Predicate<T> visible) {
        if (budget <= 0) return List.of();
        List<T> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingLong((T candidate) -> {
            long tick=lastGenerationTick.applyAsLong(candidate);
            return visible.test(candidate) ? Math.max(Long.MIN_VALUE+60, tick)-60 : tick;
        }));
        return List.copyOf(ordered.subList(0, Math.min(budget, ordered.size())));
    }
}
