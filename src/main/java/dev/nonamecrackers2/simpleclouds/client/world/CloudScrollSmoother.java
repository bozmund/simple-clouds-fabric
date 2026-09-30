package dev.nonamecrackers2.simpleclouds.client.world;

/** Keeps periodic server clock corrections from moving the entire cloud field in one frame. */
public final class CloudScrollSmoother
{
	// At normal speed this adds no more than one extra tick of cloud movement.
	private static final float MAX_CORRECTION_PER_TICK = 0.0001F;
	private static final float CORRECTION_FRACTION = 0.125F;
	private static final float CONVERGED_ANGLE = 0.000001F;
	private float targetAngle;
	private boolean correcting;

	public static boolean shouldSmoothFullSync(boolean alreadySynced, long currentSeed, long incomingSeed)
	{
		return alreadySynced && currentSeed == incomingSeed;
	}

	public void reset()
	{
		this.correcting = false;
	}

	public float accept(float localAngle, float serverAngle)
	{
		if (!Float.isFinite(localAngle) || !Float.isFinite(serverAngle))
			return Float.NaN;
		float difference = shortestDifference(localAngle, serverAngle);
		this.targetAngle = localAngle + difference;
		this.correcting = Math.abs(difference) > CONVERGED_ANGLE;
		return difference;
	}

	public float correct(float advancedAngle, float naturalAdvance)
	{
		if (!this.correcting)
			return advancedAngle;
		this.targetAngle += naturalAdvance;
		float difference = shortestDifference(advancedAngle, this.targetAngle);
		if (Math.abs(difference) <= CONVERGED_ANGLE)
		{
			this.correcting = false;
			return advancedAngle;
		}
		float correction = Math.max(-MAX_CORRECTION_PER_TICK,
			Math.min(MAX_CORRECTION_PER_TICK, difference * CORRECTION_FRACTION));
		return advancedAngle + correction;
	}

	private static float shortestDifference(float from, float to)
	{
		float difference = to - from;
		return (float)Math.atan2(Math.sin(difference), Math.cos(difference));
	}
}
