package net.minecraftforge.fml.config;

/**
 * Minimal stub of Forge's ModConfig for the Fabric port.
 * Only the Type enum (referenced throughout the ported code) is provided.
 */
public class ModConfig
{
    public enum Type
    {
        COMMON,
        CLIENT,
        SERVER;

        public String extension()
        {
            return this.name().toLowerCase();
        }
    }
}
