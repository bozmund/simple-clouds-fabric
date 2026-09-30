package dev.nonamecrackers2.simpleclouds.client.cloud;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.CloudMode;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import net.minecraft.resources.Identifier;

/** Shared selection for both mesh groups and their region indices. */
public final class CloudTypeSelection {
    private CloudTypeSelection() {}
    public static CloudType[] select(CloudMode mode, String rawId, CloudType[] available, boolean serverControlled) {
        if (mode != CloudMode.SINGLE) return available;
        Identifier id = Identifier.tryParse(rawId);
        if (id != null) for (CloudType type : available)
            if (type.id().equals(id) && (serverControlled || ClientSideCloudTypeManager.isValidClientSideSingleModeCloudType(type)))
                return new CloudType[]{type};
        // Invalid/unknown single types must not silently render every type.
        return new CloudType[0];
    }
}
