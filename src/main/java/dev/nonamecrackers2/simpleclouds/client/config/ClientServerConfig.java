package dev.nonamecrackers2.simpleclouds.client.config;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.common.config.ServerConfigSnapshot;

/** Connection-scoped, in-memory SERVER copy. Accessed only on the client thread. */
public final class ClientServerConfig
{
    private static ServerConfigSnapshot snapshot;

    private ClientServerConfig() {}

    public static ServerConfigSnapshot get() { return snapshot; }

    public static void receive(ServerConfigSnapshot incoming)
    {
        snapshot = java.util.Objects.requireNonNull(incoming);
    }

    public static void clear() { snapshot = null; }

    public static void updateCloudMode(CloudMode mode)
    {
        if (snapshot != null) snapshot = snapshot.withCloudMode(mode);
    }

    public static void updateSingleModeCloudType(String type)
    {
        if (snapshot != null) snapshot = snapshot.withSingleModeCloudType(type);
    }
}
