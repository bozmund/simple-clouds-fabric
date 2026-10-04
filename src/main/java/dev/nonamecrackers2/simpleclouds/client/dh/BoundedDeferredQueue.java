package dev.nonamecrackers2.simpleclouds.client.dh;

import java.util.ArrayDeque;
import java.util.Objects;

/** Cross-thread diagnostic queue. Offer reports an explicitly discarded oldest entry. */
public final class BoundedDeferredQueue<T> {
    private final ArrayDeque<T> entries=new ArrayDeque<>();
    private final int capacity;
    public BoundedDeferredQueue(int capacity) {
        if(capacity<1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity=capacity;
    }
    public synchronized boolean offer(T entry) {
        Objects.requireNonNull(entry);
        boolean discarded=entries.size()==capacity;
        if(discarded) entries.removeFirst();
        entries.addLast(entry);
        return discarded;
    }
    public synchronized T poll() {return entries.pollFirst();}
    public synchronized int size() {return entries.size();}
}
