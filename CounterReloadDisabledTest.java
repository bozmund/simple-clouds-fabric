import dev.nonamecrackers2.simpleclouds.client.CounterSnapshotRuntimeProbe;

/** Partial opt-in must never access Minecraft or start a resource reload. */
public final class CounterReloadDisabledTest {
    public static void main(String[] args) throws Exception {
        if ("1".equals(System.getenv("SIMPLECLOUDS_DEV"))
                && "1".equals(System.getenv("SIMPLECLOUDS_TEST_COUNTER_SNAPSHOT"))
                && "1".equals(System.getenv("SIMPLECLOUDS_TEST_COUNTER_RELOAD")))
            throw new AssertionError("All three flags enabled in disabled-fixture test");
        for (int i=0;i<100;i++) CounterSnapshotRuntimeProbe.tick(null);
        for (String name : new String[]{"ticks","reloads","settled"}) {
            var field=CounterSnapshotRuntimeProbe.class.getDeclaredField(name);
            field.setAccessible(true);
            if (field.getInt(null)!=0) throw new AssertionError("Fixture touched "+name);
        }
        for (String name : new String[]{"reload","previous","world"}) {
            var field=CounterSnapshotRuntimeProbe.class.getDeclaredField(name);
            field.setAccessible(true);
            if (field.get(null)!=null) throw new AssertionError("Fixture initialized "+name);
        }
        System.out.println("PASS: counter resource-reload fixture stays inert without every explicit opt-in");
    }
}
