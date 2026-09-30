package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Separates persistent cloud faces from faces that actually appear or disappear.
 * Brightness/edge alpha are deliberately not part of the identity: they may vary
 * while the same world-space face remains present. Duplicate faces are matched as
 * a multiset, so overlapping cloud groups cannot silently lose geometry. */
public final class CloudFaceDelta
{
	private CloudFaceDelta() {}

	public record Split(byte[] stable, byte[] added, byte[] removed, byte[] complete)
	{
		public int stableCount(int stride) { return this.stable.length / stride; }
		public int addedCount(int stride) { return this.added.length / stride; }
		public int removedCount(int stride) { return this.removed.length / stride; }
	}

	public static Split split(byte[] previous, ByteBuffer next, int stride)
	{
		if (stride < 20 || next == null || next.remaining() % stride != 0
				|| previous != null && previous.length % stride != 0)
			throw new IllegalArgumentException("Invalid cloud face buffer or stride");
		ByteBuffer current = next.slice().order(ByteOrder.nativeOrder());
		int nextCount = current.remaining() / stride;
		byte[] complete = new byte[current.remaining()];
		current.duplicate().get(complete);
		if (previous == null || previous.length == 0)
			return new Split(new byte[0], complete, new byte[0], complete);

		ByteBuffer old = ByteBuffer.wrap(previous).order(ByteOrder.nativeOrder());
		int oldCount = previous.length / stride;
		// Primitive open-addressed keys with FIFO chains for duplicates. The old
		// map allocated a key, node, boxed index and deque for almost every face.
		// Keep exact five-word identity and multiset semantics
		// without allocating objects per face. Key representatives never move when
		// a match is consumed, so collisions remain searchable after a chain ends.
		int capacity = 16;
		while (capacity < (long) oldCount * 2)
		{
			if (capacity >= 1 << 30)
				throw new IllegalArgumentException("Cloud face index too large");
			capacity <<= 1;
		}
		int[] keys = new int[capacity], heads = new int[capacity], tails = new int[capacity];
		int[] links = new int[oldCount];
		Arrays.fill(links, -1);
		int mask = capacity - 1;
		for (int i = 0; i < oldCount; i++)
		{
			int slot = findSlot(old, i * stride, old, stride, keys, mask);
			if (keys[slot] == 0)
			{
				keys[slot] = i + 1;
				heads[slot] = tails[slot] = i;
			}
			else
			{
				links[tails[slot]] = i;
				tails[slot] = i;
			}
		}

		boolean[] matchedOld = new boolean[oldCount];
		boolean[] stableNext = new boolean[nextCount];
		int stableCount = 0;
		for (int i = 0; i < nextCount; i++)
		{
			int slot = findSlot(current, i * stride, old, stride, keys, mask);
			if (keys[slot] != 0 && heads[slot] >= 0)
			{
				stableNext[i] = true;
				int match = heads[slot];
				matchedOld[match] = true;
				heads[slot] = links[match];
				stableCount++;
			}
		}

		byte[] stable = new byte[stableCount * stride];
		byte[] added = new byte[(nextCount - stableCount) * stride];
		byte[] removed = new byte[(oldCount - stableCount) * stride];
		int stableAt = 0, addedAt = 0, removedAt = 0;
		for (int i = 0; i < nextCount; i++)
		{
			if (stableNext[i])
			{
				System.arraycopy(complete, i * stride, stable, stableAt, stride);
				stableAt += stride;
			}
			else
			{
				System.arraycopy(complete, i * stride, added, addedAt, stride);
				addedAt += stride;
			}
		}
		for (int i = 0; i < oldCount; i++)
			if (!matchedOld[i])
			{
				System.arraycopy(previous, i * stride, removed, removedAt, stride);
				removedAt += stride;
			}
		return new Split(stable, added, removed, complete);
	}

	private static int findSlot(ByteBuffer buffer, int offset, ByteBuffer old,
			int stride, int[] keys, int mask)
	{
		int hash = 1;
		for (int word = 0; word < 20; word += 4)
			hash = 31 * hash + buffer.getInt(offset + word);
		// Spread the high bits: grid-aligned float coordinates have many zero low
		// bits. Hash collisions must never be treated as equal faces.
		hash ^= hash >>> 16;
		hash *= 0x7feb352d;
		hash ^= hash >>> 15;
		int slot = hash & mask;
		while (keys[slot] != 0)
		{
			int other = (keys[slot] - 1) * stride;
			boolean equal = true;
			for (int word = 0; word < 20; word += 4)
				if (buffer.getInt(offset + word) != old.getInt(other + word))
				{
					equal = false;
					break;
				}
			if (equal) break;
			slot = (slot + 1) & mask;
		}
		return slot;
	}
}
