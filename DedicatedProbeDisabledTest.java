import dev.nonamecrackers2.simpleclouds.client.DedicatedClientProbe;

/** Developer mode alone must never enable the localhost automation fixture. */
class DedicatedProbeDisabledTest {
    public static void main(String[] args) throws Exception {
        if ("1".equals(System.getenv("SIMPLECLOUDS_TEST_DEDICATED")))
            throw new AssertionError("Requires dedicated fixture disabled");
        if ("1".equals(System.getenv("SIMPLECLOUDS_TEST_SLEEP")))
            throw new AssertionError("Requires sleep fixture disabled");
        for (int i = 0; i < 100; i++) {
            DedicatedClientProbe.tick(null);
            dev.nonamecrackers2.simpleclouds.client.SleepClientProbe.tick(null);
        }
        for (Class<?> fixture : new Class<?>[]{DedicatedClientProbe.class,
                dev.nonamecrackers2.simpleclouds.common.event.DedicatedSleepProbe.class}) {
            for (String name : new String[]{"stage", "ticks"}) {
                var field = fixture.getDeclaredField(name);
                field.setAccessible(true);
                if (field.getInt(null) != 0) throw new AssertionError("Fixture touched " + name);
            }
        }
        System.out.println("PASS: dedicated fixture stays inert without exact explicit opt-in");
    }
}
