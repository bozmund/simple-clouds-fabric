import dev.nonamecrackers2.simpleclouds.client.NativeBiomeWeatherProbe;
import dev.nonamecrackers2.simpleclouds.client.compat.ImmersiveWeatherBridge;

public final class NativeBiomeWeatherDisabledTest {
    public static void main(String[] args) throws Exception {
        if("1".equals(System.getenv("SIMPLECLOUDS_DEV"))
            && "1".equals(System.getenv("SIMPLECLOUDS_TEST_NATIVE_BIOME_WEATHER")))
            throw new AssertionError("Fixture must not have complete opt-in");
        for(int i=0;i<100;i++)NativeBiomeWeatherProbe.tick(null);
        var ticks=NativeBiomeWeatherProbe.class.getDeclaredField("ticks");ticks.setAccessible(true);
        if(ticks.getInt(null)!=0)throw new AssertionError("Native fixture advanced without opt-in");
        for(String name:new String[]{"rainData","thunderData"}) {
            var field=ImmersiveWeatherBridge.class.getDeclaredField(name);field.setAccessible(true);
            if(field.get(null)!=null)throw new AssertionError("Optional weather library eagerly linked: "+name);
        }
        var count=ImmersiveWeatherBridge.class.getDeclaredField("nativeSoundSelections");count.setAccessible(true);
        if(count.getInt(null)!=0)throw new AssertionError("Native sound instrumentation ran outside fixture");
        System.out.println("PASS: native biome fixture inert and optional library methods remain unlinked without opt-in");
    }
}
