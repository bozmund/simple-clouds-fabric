package net.minecraftforge.fml;
public class ModList {
    public static ModList get() { return new ModList(); }
    public boolean isLoaded(String modid) { 
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(modid); 
    }
}
