import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects.Sample;
import dev.nonamecrackers2.simpleclouds.common.world.LocalWeatherEffects.Condition;
import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticNoiseSettings;
import net.minecraft.resources.Identifier;

public class LocalWeatherEffectsTest {
    static void check(boolean value,String message) {if(!value) throw new AssertionError(message);}
    public static void main(String[] args) {
        var clear=new Sample(0,1,Float.NaN,Float.POSITIVE_INFINITY,true);
        check(clear.thunder()==0&&clear.windX()==0&&clear.windZ()==0,"dry thunder/nonfinite inputs");
        check(clear.matches(Condition.CLEAR,false)&&clear.matches(Condition.ALWAYS,false),"clear conditions");
        check(!clear.matches(Condition.DURING_WEATHER,false)&&!clear.matches(Condition.ONLY_DURING_STORMY_WEATHER,false),"weather leaked outside local coverage");
        check(!clear.matches(Condition.AFTER_WEATHER,false)&&clear.matches(Condition.AFTER_WEATHER,true),"after-weather requires real local history");
        var wet=new Sample(.3f,0,1,0,true);
        check(wet.matches(Condition.ONLY_DURING_NORMAL_WEATHER,false)&&!wet.matches(Condition.AFTER_WEATHER,true),"normal/after separation");
        var storm=new Sample(.2f,1,0,1,true);
        check(storm.thunder()==.2f&&storm.matches(Condition.ONLY_DURING_STORMY_WEATHER,false)&&!storm.matches(Condition.ONLY_DURING_NORMAL_WEATHER,false),"storm envelope");
        for(WeatherType weather:WeatherType.values()) {
            var type=new CloudType(Identifier.fromNamespaceAndPath("codex","effect-envelope"),weather,.2f,16,16,.2f,StaticNoiseSettings.DEFAULT);
            float top=type.stormStart()*SimpleCloudsConstants.CLOUD_SCALE+128;
            float full=CloudManager.calculateRainLevel(type,0,top,128);
            check(full==(weather.includesRain()?1:0),"base envelope "+weather);
            check(CloudManager.calculateRainLevel(type,SimpleCloudsConstants.RAIN_THRESHOLD,top,128)==0,"outside edge "+weather);
            check(CloudManager.calculateRainLevel(type,0,top+SimpleCloudsConstants.RAIN_VERTICAL_FADE,128)==0,"above cloud "+weather);
            if(weather.includesRain()) check(Math.abs(CloudManager.calculateRainLevel(type,0,top+SimpleCloudsConstants.RAIN_VERTICAL_FADE/2,128)-.5f)<.00001f,"vertical envelope changed");
        }
        System.out.println("PASS local effect conditions, finite inputs, dry thunder and shared original rain envelope");
    }
}
