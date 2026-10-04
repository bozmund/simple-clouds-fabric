package dev.nonamecrackers2.simpleclouds.client.compat;

import net.minecraft.core.particles.ParticleOptions;
import java.util.WeakHashMap;

/** Producer identity survives queued creation; no thread-local state or scale guessing. */
public final class NativeDustProvenance {
    // Pinned native ParticleColor memoizes three distinct DustGrainParticleEffect
    // objects; that class has identity equality. No world/particle reference retained.
    private static final WeakHashMap<ParticleOptions,Boolean> ambient=new WeakHashMap<>();
    private NativeDustProvenance() {}
    public static synchronized void markAmbient(ParticleOptions options) {
        if(options!=null)ambient.put(options,Boolean.TRUE);
    }
    public static synchronized boolean isAmbient(ParticleOptions options) {
        return ambient.containsKey(options);
    }
}
