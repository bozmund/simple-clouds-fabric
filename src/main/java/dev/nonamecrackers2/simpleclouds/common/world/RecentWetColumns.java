package dev.nonamecrackers2.simpleclouds.common.world;

import java.util.LinkedHashMap;

/** Bounded connection/world-local after-weather history, not inferred from global weather. */
public final class RecentWetColumns {
    private final int capacity;
    private final long lifetime;
    private final LinkedHashMap<Long,Long> wet=new LinkedHashMap<>(16,.75f,true);
    private Object owner;
    private long lastTick=Long.MIN_VALUE;
    public RecentWetColumns(int capacity,long lifetime) {
        if(capacity<1||lifetime<0) throw new IllegalArgumentException("invalid wet history bounds");
        this.capacity=capacity; this.lifetime=lifetime;
    }
    public synchronized boolean observe(Object owner,long tick,long column,boolean raining) {
        java.util.Objects.requireNonNull(owner);
        if(this.owner!=owner||tick<lastTick) clear();
        this.owner=owner; this.lastTick=tick;
        if(raining) {
            wet.put(column,tick);
            if(wet.size()>capacity) wet.remove(wet.keySet().iterator().next());
            return false;
        }
        Long last=wet.get(column);
        if(last==null) return false;
        if(tick-last>lifetime) {wet.remove(column);return false;}
        return true;
    }
    public synchronized void clear() {wet.clear();owner=null;lastTick=Long.MIN_VALUE;}
    /** Clear eagerly on world replacement, even if the new dimension makes no weather queries. */
    public synchronized void bindOwner(Object owner) {
        if(this.owner!=owner) {clear();this.owner=owner;}
    }
    public synchronized int size() {return wet.size();}
}
