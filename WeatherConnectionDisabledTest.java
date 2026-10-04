import dev.nonamecrackers2.simpleclouds.client.WeatherConnectionProbe;
public final class WeatherConnectionDisabledTest {
    public static void main(String[] args) throws Exception {
        if(WeatherConnectionProbe.enabled())throw new AssertionError("weather connection fixture unexpectedly enabled");
        WeatherConnectionProbe.connected(null);
        WeatherConnectionProbe.armBeforeDisconnect(null);
        WeatherConnectionProbe.disconnected(null);
        WeatherConnectionProbe.changedDimension(null);
        WeatherConnectionProbe.currentBounds(null);
        System.out.println("PASS weather connection/dimension fixture inert without all explicit opt-ins");
    }
}
