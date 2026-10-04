import dev.nonamecrackers2.simpleclouds.client.gui.CloudEditorFiles;
import dev.nonamecrackers2.simpleclouds.client.gui.CloudEditorModel;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticLayeredNoise;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticNoiseSettings;
import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import net.minecraft.resources.Identifier;
import java.nio.file.Files;
import java.util.List;

class CloudEditorFilesTest {
    static void require(boolean v,String s) {if(!v) throw new AssertionError(s);}
    public static void main(String[] args) throws Exception {
        var root=Files.createTempDirectory("simpleclouds-editor-files-");
        var io=new CloudEditorFiles(root);
        var source=new CloudType(Identifier.parse("test:edit"),WeatherType.NONE,0,16,32,0,
                new StaticLayeredNoise(List.of(StaticNoiseSettings.DEFAULT)));
        var model=new CloudEditorModel(source);
        model.setWeatherType(WeatherType.THUNDERSTORM);
        model.setWeatherParameter("storminess",.8f); model.setWeatherParameter("storm_start",12);
        model.setWeatherParameter("storm_fade_distance",64); model.setWeatherParameter("transparency_fade",8);
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,2}) {
            try {model.setWeatherParameter("storminess",invalid);throw new AssertionError("accepted invalid weather");}
            catch(IllegalArgumentException expected) { }
        }
        var edited=model.snapshot();
        var file=io.save("test",edited,false);
        var loaded=io.load("test",source.id());
        require(loaded.toJson().equals(edited.toJson()),"JSON roundtrip lost weather/noise");
        require(source.weatherType()==WeatherType.NONE && source.storminess()==0,"changed registered type");
        var original=Files.readAllBytes(file);
        try {io.save("test",source,false);throw new AssertionError("silent overwrite");}
        catch(java.nio.file.FileAlreadyExistsException expected) { }
        require(java.util.Arrays.equals(original,Files.readAllBytes(file)),"rejected overwrite changed file");
        io.save("test",source,true);
        require(io.load("test.json",source.id()).toJson().equals(source.toJson()),"confirmed overwrite failed");
        for(String bad:new String[]{"../secret","/tmp/x","","..","UPPER","a/b"}) {
            try {io.file(bad);throw new AssertionError("path accepted: "+bad);}
            catch(IllegalArgumentException expected) { }
        }
        Files.writeString(io.file("broken"),"{broken-json");
        try {io.load("broken",source.id());throw new AssertionError("invalid JSON accepted");}
        catch(com.google.gson.JsonParseException expected) { }
        Files.createSymbolicLink(io.file("linked"),file);
        try {io.load("linked",source.id());throw new AssertionError("read symlink");}
        catch(java.io.IOException expected) { }
        try {io.save("linked",source,true);throw new AssertionError("wrote symlink");}
        catch(java.io.IOException expected) { }
        require(io.load("test",source.id()).toJson().equals(source.toJson()),"symlink target damaged");
        System.out.println("PASS: editable weather, JSON roundtrip, no silent overwrite/traversal/symlink, invalid JSON; evidence="+root);
    }
}
