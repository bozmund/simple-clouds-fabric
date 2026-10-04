import dev.nonamecrackers2.simpleclouds.client.gui.CloudEditorModel;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import dev.nonamecrackers2.simpleclouds.common.noise.*;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import net.minecraft.resources.Identifier;
import java.util.Arrays;
import java.util.List;

public class CloudEditorModelTest {
    static void require(boolean value,String message) {if(!value) throw new AssertionError(message);}
    public static void main(String[] args) {
        var source=new CloudType(Identifier.parse("test:edit"),WeatherType.THUNDERSTORM,.7f,14,9,.25f,
            new StaticLayeredNoise(List.of(StaticNoiseSettings.DEFAULT,StaticNoiseSettings.DEFAULT)));
        float[] original=source.noiseConfig().packForShader().clone();
        var model=new CloudEditorModel(source);
        require(model.layerCount()==2,"Import lost noise layers");
        model.currentLayer().setParam(AbstractNoiseSettings.Param.VALUE_OFFSET,2);
        var first=model.snapshot();
        require(!Arrays.equals(original,first.noiseConfig().packForShader()),"Edit not in snapshot");
        require(Arrays.equals(original,source.noiseConfig().packForShader()),"Edited registered source");
        require(first.id().equals(source.id()) && first.weatherType()==source.weatherType() && first.storminess()==.7f
            && first.stormStart()==14 && first.stormFadeDistance()==9 && first.transparencyFade()==.25f,"Metadata lost");
        model.jumpLayer(-1);require(model.selectedLayerIndex()==1,"Previous did not wrap");
        model.jumpLayer(1);require(model.selectedLayerIndex()==0,"Next did not wrap");
        model.currentLayer().setParam(AbstractNoiseSettings.Param.VALUE_OFFSET,-2);
        require(first.noiseConfig().packForShader()[1]==2,"Snapshot mutated with editor");
        while(model.removeLayer()) { }
        require(model.layerCount()==0 && model.currentLayer()==null && model.snapshot().noiseConfig().layerCount()==0,"Empty state has stale layers");
        for(int i=0;i<dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator.MAX_NOISE_LAYERS;i++)
            require(model.addLayer(),"Stopped adding before original limit");
        require(!model.addLayer(),"Exceeded original layer limit");
        var empty=new CloudEditorModel(SimpleCloudsConstants.EMPTY);
        require(empty.layerCount()==0 && empty.addLayer() && empty.layerCount()==1,"Empty import/add failed");
        System.out.println("PASS editor model: original layers/metadata, isolated immutable snapshots, wrapping, remove-all/add and original layer limit");
    }
}
