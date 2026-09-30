package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Moves drawable ownership between logical slots without moving world geometry. */
public final class CloudGridTransition
{
    private CloudGridTransition() {}

    public record Retained<K, V>(K origin, V value) {}

    /** Exact world matches win. Other old slots remain drawable until replacement succeeds.
     * Every displaced value is either transferred once or released once. */
    public static <K, V> void retarget(Map<K, V> current,
            Map<K, Retained<K, V>> retained, Set<K> targets,
            Function<K, K> previousSlot, Consumer<V> release)
    {
        Map<K, Retained<K, V>> oldSlots = new HashMap<>(retained);
        retained.clear();
        var entries = current.entrySet().iterator();
        while (entries.hasNext())
        {
            var entry = entries.next();
            if (!targets.contains(entry.getKey()))
            {
                var replaced = oldSlots.put(entry.getKey(),
                        new Retained<>(entry.getKey(), entry.getValue()));
                if (replaced != null) release.accept(replaced.value());
                entries.remove();
            }
        }
        // Returning across a boundary can restore original world chunks directly,
        // even when the outward replacement never finished.
        var old = oldSlots.entrySet().iterator();
        while (old.hasNext())
        {
            var entry = old.next();
            var drawable = entry.getValue();
            if (targets.contains(drawable.origin()))
            {
                if (!current.containsKey(drawable.origin()))
                    current.put(drawable.origin(), drawable.value());
                else
                    release.accept(drawable.value());
                old.remove();
            }
        }
        for (K target : targets)
        {
            if (current.containsKey(target)) continue;
            var drawable = oldSlots.remove(previousSlot.apply(target));
            if (drawable != null) retained.put(target, drawable);
        }
        oldSlots.values().forEach(drawable -> release.accept(drawable.value()));
    }
}
