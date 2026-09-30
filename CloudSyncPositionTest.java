import dev.nonamecrackers2.simpleclouds.common.event.CloudSyncPosition;

public class CloudSyncPositionTest
{
	private static void check(boolean condition, String message)
	{
		if (!condition)
			throw new AssertionError(message);
	}

	public static void main(String[] args)
	{
		CloudSyncPosition origin = new CloudSyncPosition(0, 0);
		check(!origin.isFarFrom(1024, 0, 1024), "exact sync radius should be retained");
		check(origin.isFarFrom(1025, 0, 1024), "past sync radius should resync");
		check(origin.isFarFrom(800, 800, 1024), "diagonal travel should resync");
		check(origin.isFarFrom(65536, 0, 1024), "large squared distance overflowed");
		check(new CloudSyncPosition(-100, -100).isFarFrom(1000, -100, 1024),
			"negative-to-positive travel should resync");
		check(!new CloudSyncPosition(-100, -100).isFarFrom(-99, -101, 1024),
			"short travel across negative coordinates should not resync");
		check(new CloudSyncPosition(Integer.MIN_VALUE, 0).isFarFrom(Integer.MAX_VALUE, 0, 1024),
			"full coordinate range should not wrap");
		System.out.println("PASS: cloud resync distance, sign boundaries and overflow");
	}
}
