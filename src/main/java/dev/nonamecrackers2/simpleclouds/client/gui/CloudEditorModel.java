package dev.nonamecrackers2.simpleclouds.client.gui;

import java.util.ArrayList;
import java.util.List;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.noise.AbstractLayeredNoise;
import dev.nonamecrackers2.simpleclouds.common.noise.AbstractNoiseSettings;
import dev.nonamecrackers2.simpleclouds.common.noise.ModifiableNoiseSettings;
import dev.nonamecrackers2.simpleclouds.common.noise.StaticLayeredNoise;
import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;

/** Private mutable editor copy. Registered cloud data is never changed by editing. */
public final class CloudEditorModel {
    private final CloudType source;
    private final List<ModifiableNoiseSettings> layers=new ArrayList<>();
    private int selectedLayer;
    private dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType weatherType;
    private float storminess, stormStart, stormFadeDistance, transparencyFade;
    public CloudEditorModel(CloudType source) {
        this.source=source;
        this.weatherType=source.weatherType(); this.storminess=source.storminess();
        this.stormStart=source.stormStart(); this.stormFadeDistance=source.stormFadeDistance();
        this.transparencyFade=source.transparencyFade();
        if(source.noiseConfig() instanceof AbstractLayeredNoise<?> layered) {
            for(var layer:layered.getNoiseLayers()) this.layers.add(new ModifiableNoiseSettings(layer));
        } else if(source.noiseConfig() instanceof AbstractNoiseSettings<?> single) {
            this.layers.add(new ModifiableNoiseSettings(single));
        } else if(source.noiseConfig().layerCount()!=0) {
            throw new IllegalArgumentException("Unsupported nonempty editor noise type");
        }
    }
    public int layerCount() {return this.layers.size();}
    public int selectedLayerIndex() {return this.selectedLayer;}
    public ModifiableNoiseSettings currentLayer() {return this.layers.isEmpty()?null:this.layers.get(this.selectedLayer);}
    public boolean addLayer() {
        if(this.layers.size()>=CloudMeshGenerator.MAX_NOISE_LAYERS) return false;
        this.layers.add(new ModifiableNoiseSettings()); this.selectedLayer=this.layers.size()-1; return true;
    }
    public boolean removeLayer() {
        if(this.layers.isEmpty()) return false;
        this.layers.remove(this.selectedLayer);
        this.selectedLayer=Math.max(0,Math.min(this.selectedLayer,this.layers.size()-1)); return true;
    }
    public void jumpLayer(int delta) {
        if(!this.layers.isEmpty()) this.selectedLayer=Math.floorMod(this.selectedLayer+delta,this.layers.size());
    }
    public void setWeatherType(dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType value) {
        this.weatherType=java.util.Objects.requireNonNull(value);
    }
    public void setWeatherParameter(String name, float value) {
        float max=switch(name) {
            case "storminess" -> dev.nonamecrackers2.simpleclouds.common.cloud.CloudInfo.STORMINESS_MAX;
            case "storm_start" -> dev.nonamecrackers2.simpleclouds.common.cloud.CloudInfo.STORM_START_MAX;
            case "storm_fade_distance" -> dev.nonamecrackers2.simpleclouds.common.cloud.CloudInfo.STORM_FADE_DISTANCE_MAX;
            case "transparency_fade" -> dev.nonamecrackers2.simpleclouds.common.cloud.CloudInfo.TRANSPARENCY_FADE_MAX;
            default -> throw new IllegalArgumentException("Unknown weather parameter: " + name);
        };
        if(!Float.isFinite(value) || value<0 || value>max) throw new IllegalArgumentException("Out of range: " + name);
        switch(name) {
            case "storminess" -> this.storminess=value;
            case "storm_start" -> this.stormStart=value;
            case "storm_fade_distance" -> this.stormFadeDistance=value;
            case "transparency_fade" -> this.transparencyFade=value;
        }
    }
    public CloudType snapshot() {
        var noise=new StaticLayeredNoise(this.layers.stream().map(ModifiableNoiseSettings::toStatic).toList());
        return new CloudType(this.source.id(),this.weatherType,this.storminess,this.stormStart,
            this.stormFadeDistance,this.transparencyFade,noise);
    }
}
