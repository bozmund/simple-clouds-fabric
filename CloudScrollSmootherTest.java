import dev.nonamecrackers2.simpleclouds.client.world.CloudScrollSmoother;

public class CloudScrollSmootherTest
{
	private static void check(boolean condition, String message)
	{
		if (!condition)
			throw new AssertionError(message);
	}

	public static void main(String[] args)
	{
		check(!CloudScrollSmoother.shouldSmoothFullSync(false, 42L, 42L), "first join should start at server phase");
		check(!CloudScrollSmoother.shouldSmoothFullSync(true, 42L, 43L), "new world should start at server phase");
		check(CloudScrollSmoother.shouldSmoothFullSync(true, 42L, 42L), "same-world resend should be smoothed");
		CloudScrollSmoother smoother = new CloudScrollSmoother();
		float angle = 1.0F;
		check(Math.abs(smoother.accept(angle, angle + 0.024F) - 0.024F) < 0.00001F, "server error measured incorrectly");
		for (int i = 0; i < 360; i++)
		{
			float next = smoother.correct(angle + 0.0001F, 0.0001F);
			check(next > angle, "cloud motion reversed");
			check(next - angle <= 0.000201F, "cloud field jumped in one tick");
			angle = next;
		}
		check(Math.abs(angle - 1.060F) < 0.0001F, "client did not converge to server time");

		smoother.reset();
		angle = (float)Math.PI - 0.005F;
		float error = smoother.accept(angle, -(float)Math.PI + 0.005F);
		check(error > 0.009F && error < 0.011F, "wraparound took the long path");
		float next = smoother.correct(angle + 0.0001F, 0.0001F);
		check(next > angle && next - angle < 0.000201F, "wraparound jumped or reversed");

		smoother.reset();
		check(Float.isNaN(smoother.accept(1.0F, Float.NaN)), "non-finite packet accepted");
		check(smoother.correct(1.0001F, 0.0001F) == 1.0001F, "non-finite packet changed motion");
		System.out.println("PASS: bounded cloud sync, convergence, wraparound and invalid packet");
	}
}
