import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticNoiseSettings;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import net.minecraft.resources.Identifier;

public class PreviewSelectionTest {
    public static void main(String[] args) {
        var type = new CloudType(Identifier.parse("test:preview"), WeatherType.THUNDERSTORM,
                .7f, 14, 9, .25f, StaticNoiseSettings.DEFAULT);
        var groups = SimpleCloudsRenderer.dataDrivenGroups(new CloudType[]{type});
        if(groups.size()!=1) throw new AssertionError("preview did not use explicit type");
        var group=groups.get(0);
        if(!group.stormType() || group.storminess()!=.7f || group.stormStart()!=14
                || group.stormFadeDistance()!=9 || group.transparencyFade()!=.25f || group.layers().size()!=1)
            throw new AssertionError("selected type properties lost");
        var clear=new CloudType(Identifier.parse("test:clear"),WeatherType.NONE,0,0,0,0,StaticNoiseSettings.DEFAULT);
        if(SimpleCloudsRenderer.dataDrivenGroups(new CloudType[]{clear}).get(0).equals(group))
            throw new AssertionError("changing preview selection reused old data");
        if(!SimpleCloudsRenderer.dataDrivenGroups(new CloudType[0]).isEmpty())
            throw new AssertionError("empty preview fell back to world types");
        System.out.println("PASS: explicit preview conversion without Minecraft/world initialization; empty selection remains empty");
    }
}
