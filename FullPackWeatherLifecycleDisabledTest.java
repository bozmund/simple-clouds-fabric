import dev.nonamecrackers2.simpleclouds.client.FullPackWeatherLifecycleProbe;
public final class FullPackWeatherLifecycleDisabledTest {
    public static void main(String[] args) {
        FullPackWeatherLifecycleProbe.tick(null);
        System.out.println("PASS fullpack weather fixture inert without both explicit opt-ins");
    }
}
