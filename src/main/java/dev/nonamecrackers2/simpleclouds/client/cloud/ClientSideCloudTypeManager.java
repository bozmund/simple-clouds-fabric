package dev.nonamecrackers2.simpleclouds.client.cloud;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableMap;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.weather.WeatherType;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeDataManager;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeSource;
import net.minecraft.resources.Identifier;

public class ClientSideCloudTypeManager implements CloudTypeSource
{
	private static final ClientSideCloudTypeManager INSTANCE = new ClientSideCloudTypeManager();
	private final CloudTypeDataManager dataManager;
	private Map<Identifier, CloudType> synced = ImmutableMap.of();
	private CloudType[] indexed = new CloudType[0];
	
	private ClientSideCloudTypeManager()
	{
		this.dataManager = new CloudTypeDataManager();
	}
	
	public CloudTypeDataManager getClientSideDataManager()
	{
		return this.dataManager;
	}
	
	@Override
	public CloudType getCloudTypeForId(Identifier id)
	{
		return this.getCloudTypes().get(id);
	}

	@Override
	public CloudType[] getIndexedCloudTypes()
	{
		if (this.indexed.length > 0)
			return this.indexed;
		else
			return this.dataManager.getIndexedCloudTypes();
	}
	
	public Map<Identifier, CloudType> getCloudTypes()
	{
		if (!this.synced.isEmpty())
			return this.synced;
		else
			return this.dataManager.getCloudTypes().collect(java.util.stream.Collectors.toMap(CloudType::id, cloudType -> cloudType));
	}
	
	public void receiveSynced(Map<Identifier, CloudType> synced, CloudType[] indexed)
	{
		this.synced = ImmutableMap.copyOf(synced);
		this.indexed = indexed;
	}
	
	public void clearSynced()
	{
		this.synced = ImmutableMap.of();
		this.indexed = new CloudType[0];
	}
	
	public static ClientSideCloudTypeManager getInstance()
	{
		return INSTANCE;
	}
	
	public static boolean isValidClientSideSingleModeCloudType(@Nullable CloudType type)
	{
		return type != null && type.weatherType() == WeatherType.NONE;
	}
}
