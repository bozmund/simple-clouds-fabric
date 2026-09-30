import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import dev.nonamecrackers2.simpleclouds.client.cloud.CloudTypeSelection;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import net.minecraft.resources.Identifier;

public class CloudTypeSelectionTest {
    static void require(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        var clear = new CloudType(Identifier.parse("test:clear"),WeatherType.NONE,0,0,0,0,null);
        var storm = new CloudType(Identifier.parse("test:storm"),WeatherType.THUNDERSTORM,1,0,0,0,null);
        CloudType[] available={clear,storm};
        require(CloudTypeSelection.select(CloudMode.DEFAULT,"invalid",available,false)==available);
        require(CloudTypeSelection.select(CloudMode.AMBIENT,"invalid",available,false)==available);
        require(CloudTypeSelection.select(CloudMode.SINGLE,"test:clear",available,false)[0]==clear);
        require(CloudTypeSelection.select(CloudMode.SINGLE,"test:storm",available,false).length==0);
        require(CloudTypeSelection.select(CloudMode.SINGLE,"test:storm",available,true)[0]==storm);
        require(CloudTypeSelection.select(CloudMode.SINGLE,"test:missing",available,true).length==0);
        require(CloudTypeSelection.select(CloudMode.SINGLE,"INVALID SPACE",available,true).length==0);
        require(CloudTypeSelection.select(CloudMode.SINGLE,"test:clear",new CloudType[0],true).length==0);
        require(available.length==2 && available[0]==clear && available[1]==storm);
        System.out.println("PASS: mode selection, server/client restrictions, invalid/missing IDs, source order preserved");
    }
}
