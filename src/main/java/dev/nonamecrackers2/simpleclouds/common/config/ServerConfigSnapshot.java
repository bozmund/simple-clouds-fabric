package dev.nonamecrackers2.simpleclouds.common.config;

import java.util.List;
import java.util.Objects;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;

/** Immutable SERVER values sent to clients; never a replacement for the world file. */
public record ServerConfigSnapshot(CloudMode cloudMode, String singleModeCloudType,
        List<String> dimensionWhitelist, boolean whitelistAsBlacklist)
{
    public ServerConfigSnapshot
    {
        Objects.requireNonNull(cloudMode, "cloudMode");
        Objects.requireNonNull(singleModeCloudType, "singleModeCloudType");
        dimensionWhitelist = List.copyOf(dimensionWhitelist);
    }

    public static ServerConfigSnapshot capture()
    {
        if (!SimpleCloudsConfig.SERVER_SPEC.isLoaded())
            throw new IllegalStateException("Cannot synchronize an unloaded server configuration");
        var config = SimpleCloudsConfig.SERVER;
        return new ServerConfigSnapshot(config.cloudMode.get(), config.singleModeCloudType.get(),
                List.copyOf(config.dimensionWhitelist.get()), config.whitelistAsBlacklist.get());
    }

    public boolean allowsDimension(String dimension)
    {
        boolean matched = this.dimensionWhitelist.contains(dimension);
        return this.whitelistAsBlacklist ? !matched : matched;
    }

    public ServerConfigSnapshot withCloudMode(CloudMode mode)
    {
        return new ServerConfigSnapshot(mode, this.singleModeCloudType, this.dimensionWhitelist, this.whitelistAsBlacklist);
    }

    public ServerConfigSnapshot withSingleModeCloudType(String type)
    {
        return new ServerConfigSnapshot(this.cloudMode, type, this.dimensionWhitelist, this.whitelistAsBlacklist);
    }
}
