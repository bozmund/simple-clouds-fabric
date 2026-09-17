import dev.nonamecrackers2.simpleclouds.client.DevShot;

/** Run with SIMPLECLOUDS_DEV unset/0. No Minecraft instance is initialized. */
public class DevShotDisabledTest {
    public static void main(String[] args) throws Exception {
        if ("1".equals(System.getenv("SIMPLECLOUDS_DEV")))
            throw new AssertionError("Test requires developer mode disabled");
        for (int i = 0; i < 100; i++) DevShot.onWorldFrame();
        for (String name : new String[] {"checked", "done", "testSpawned", "viewSetupDone"}) {
            var field = DevShot.class.getDeclaredField(name);
            field.setAccessible(true);
            if (field.getBoolean(null)) throw new AssertionError("Automation touched " + name);
        }
        System.out.println("PASS: production launch never initializes Minecraft or test automation");
    }
}
