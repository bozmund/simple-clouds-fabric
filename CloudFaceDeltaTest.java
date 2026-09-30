import dev.nonamecrackers2.simpleclouds.client.renderer.v2.CloudFaceDelta;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class CloudFaceDeltaTest
{
	private static final int STRIDE = 24;

	private static byte[] faces(int... sides)
	{
		ByteBuffer b = ByteBuffer.allocate(sides.length * STRIDE).order(ByteOrder.nativeOrder());
		for (int i = 0; i < sides.length; i++)
		{
			b.putFloat(sides[i]).putFloat(i == 3 ? 1 : 0).putFloat(2).putFloat(3)
					.putFloat(4).putFloat(0.5f);
		}
		return b.array();
	}

	private static void check(boolean condition, String message)
	{
		if (!condition) throw new AssertionError(message);
	}

	public static void main(String[] args)
	{
		randomizedMultisets();
		byte[] first = faces(0, 1, 2);
		CloudFaceDelta.Split initial = CloudFaceDelta.split(null, ByteBuffer.wrap(first), STRIDE);
		check(initial.stableCount(STRIDE) == 0 && initial.addedCount(STRIDE) == 3, "initial field");
		check(initial.removedCount(STRIDE) == 0, "initial removals");

		byte[] changed = faces(0, 1, 3);
		CloudFaceDelta.Split delta = CloudFaceDelta.split(first, ByteBuffer.wrap(changed), STRIDE);
		check(delta.stableCount(STRIDE) == 2, "unchanged faces must stay opaque");
		check(delta.addedCount(STRIDE) == 1 && delta.removedCount(STRIDE) == 1, "only real changes fade");
		check(Arrays.equals(delta.complete(), changed), "retain complete mesh for next update");

		byte[] duplicates = new byte[STRIDE * 2];
		System.arraycopy(first, 0, duplicates, 0, STRIDE);
		System.arraycopy(first, 0, duplicates, STRIDE, STRIDE);
		CloudFaceDelta.Split fewer = CloudFaceDelta.split(duplicates,
				ByteBuffer.wrap(Arrays.copyOf(duplicates, STRIDE)), STRIDE);
		check(fewer.stableCount(STRIDE) == 1 && fewer.removedCount(STRIDE) == 1, "duplicate faces are a multiset");
		System.out.println("CloudFaceDeltaTest PASS");
	}

	private static void randomizedMultisets()
	{
		java.util.Random random = new java.util.Random(20260930);
		for (int test = 0; test < 2000; test++)
		{
			int stride = 20 + 4 * (test % 4);
			int oldCount = random.nextInt(96), newCount = random.nextInt(96);
			byte[] previous = new byte[oldCount * stride], complete = new byte[newCount * stride];
			byte[][] identities = new byte[24][20];
			for (byte[] identity : identities) random.nextBytes(identity);
			// Distinct identities with intentionally identical polynomial hashes.
			// Test real collisions, not only accidental collisions from the table.
			ByteBuffer.wrap(identities[0]).order(ByteOrder.nativeOrder()).putInt(0, 1).putInt(4, 0);
			System.arraycopy(identities[0], 0, identities[1], 0, 20);
			ByteBuffer.wrap(identities[1]).order(ByteOrder.nativeOrder()).putInt(0, 0).putInt(4, 31);
			for (byte[] data : new byte[][]{previous, complete})
			{
				random.nextBytes(data);
				for (int offset = 0; offset < data.length; offset += stride)
					System.arraycopy(identities[random.nextInt(identities.length)], 0, data, offset, 20);
			}
			boolean[] matchedOld = new boolean[oldCount], matchedNew = new boolean[newCount];
			int stableCount = 0;
			// Independent quadratic oracle, choosing the first unmatched old face.
			for (int next = 0; next < newCount; next++)
				for (int old = 0; old < oldCount; old++)
					if (!matchedOld[old] && Arrays.equals(previous, old * stride, old * stride + 20,
							complete, next * stride, next * stride + 20))
					{
						matchedOld[old] = matchedNew[next] = true;
						stableCount++;
						break;
					}
			byte[] stable = new byte[stableCount * stride];
			byte[] added = new byte[(newCount - stableCount) * stride];
			byte[] removed = new byte[(oldCount - stableCount) * stride];
			int stableAt = 0, addedAt = 0, removedAt = 0;
			for (int i = 0; i < newCount; i++)
				if (matchedNew[i]) { System.arraycopy(complete, i * stride, stable, stableAt, stride); stableAt += stride; }
				else { System.arraycopy(complete, i * stride, added, addedAt, stride); addedAt += stride; }
			for (int i = 0; i < oldCount; i++)
				if (!matchedOld[i]) { System.arraycopy(previous, i * stride, removed, removedAt, stride); removedAt += stride; }
			ByteBuffer input = (test % 2 == 0 ? ByteBuffer.allocateDirect(complete.length + 11)
					: ByteBuffer.allocate(complete.length + 11));
			input.position(7).put(complete).flip().position(7);
			input = input.asReadOnlyBuffer();
			int position = input.position(), limit = input.limit();
			var split = CloudFaceDelta.split(previous, input, stride);
			check(Arrays.equals(stable, split.stable()), "random stable " + test);
			check(Arrays.equals(added, split.added()), "random added " + test);
			check(Arrays.equals(removed, split.removed()), "random removed " + test);
			check(Arrays.equals(complete, split.complete()), "random complete " + test);
			check(input.position() == position && input.limit() == limit, "input consumed " + test);
		}
	}
}
