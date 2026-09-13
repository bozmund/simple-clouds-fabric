package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A1 (VISUAL-PARITY-PLAN addendum): pool of reusable direct {@link ByteBuffer}s
 * for the per-chunk CPU instance data.
 *
 * The old code called {@code ByteBuffer.allocateDirect} three times per chunk
 * (initial scratch, every grow, and the result copy in {@code CpuCloudGenerator.bound}),
 * and the chunk generation loop ran continuously, so direct memory only came back
 * when the GC collected the dead buffers — that is how the dev client reached 25 GB.
 *
 * Now: a worker borrows one buffer per instance stream, generates straight into it,
 * and the render thread uploads it to the chunk's GPU buffer and hands it back.
 * Buffers are only ever GROWN (best-fit reuse, never shrunk), and the pool's total
 * retained direct memory is capped: beyond the cap the largest released buffer is
 * dropped for the GC instead of kept, so a one-time dense sky cannot inflate the pool
 * permanently.
 */
public final class ChunkBufferPool
{
	/** Hard cap on the direct memory retained by the pool (bytes). */
	private static final long MAX_RETAINED_BYTES = 256L * 1024L * 1024L;

	private final List<ByteBuffer> free = new ArrayList<>();
	private long retainedBytes = 0L;
	private int highWaterFree = 0;
	private int totalAllocations = 0;

	/**
	 * Borrows a direct buffer with at least {@code minCapacity} bytes (native order,
	 * position 0, limit capacity). Returns the best-fit free buffer when one is big
	 * enough, otherwise allocates a new one.
	 */
	public synchronized ByteBuffer borrow(int minCapacity)
	{
		int best = -1;
		long bestCap = Long.MAX_VALUE;
		for (int i = 0; i < this.free.size(); i++)
		{
			long cap = this.free.get(i).capacity();
			if (cap >= minCapacity && cap < bestCap)
			{
				best = i;
				bestCap = cap;
			}
		}
		ByteBuffer buffer;
		if (best >= 0)
		{
			buffer = this.free.remove(best);
		}
		else
		{
			buffer = ByteBuffer.allocateDirect(Math.max(minCapacity, 256)).order(ByteOrder.nativeOrder());
			this.totalAllocations++;
		}
		buffer.position(0);
		buffer.limit(buffer.capacity());
		return buffer;
	}

	/**
	 * Returns a buffer to the pool (or lets the GC take it when the pool is at its
	 * cap). Safe with null.
	 */
	public synchronized void release(ByteBuffer buffer)
	{
		if (buffer == null)
			return;
		long cap = buffer.capacity();
		if (this.retainedBytes + cap > MAX_RETAINED_BYTES)
		{
			// Drop the largest free buffer first; if still over the cap, drop this one.
			if (!this.free.isEmpty())
			{
				int largest = 0;
				for (int i = 1; i < this.free.size(); i++)
				{
					if (this.free.get(i).capacity() > this.free.get(largest).capacity())
						largest = i;
				}
				this.retainedBytes -= this.free.remove(largest).capacity();
			}
			if (this.retainedBytes + cap > MAX_RETAINED_BYTES)
				return;
		}
		this.free.add(buffer);
		this.retainedBytes += cap;
		this.highWaterFree = Math.max(this.highWaterFree, this.free.size());
	}

	/** Standard {@link CpuCloudGenerator.BufferGrower} implementation for this pool:
	 *  release the old buffer, borrow a bigger one, carry the bytes written so far. */
	public ByteBuffer grow(ByteBuffer current, int minCapacity, int written)
	{
		ByteBuffer bigger = this.borrow(Math.max(minCapacity, current.capacity() * 2));
		if (written > 0)
		{
			ByteBuffer src = current.duplicate();
			src.position(0);
			src.limit(written);
			bigger.position(0);
			bigger.put(src);
			bigger.position(0);
			bigger.limit(bigger.capacity());
		}
		this.release(current);
		return bigger;
	}

	/** Number of buffers currently waiting in the pool. */
	public synchronized int freeCount()
	{
		return this.free.size();
	}

	/** Direct bytes currently held by the pool. */
	public synchronized long retainedBytes()
	{
		return this.retainedBytes;
	}

	/** High-water mark of simultaneously free buffers. */
	public synchronized int highWaterFree()
	{
		return this.highWaterFree;
	}

	/** Total new direct buffers allocated since startup (0 = perfect reuse). */
	public synchronized int totalAllocations()
	{
		return this.totalAllocations;
	}
}
