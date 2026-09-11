package dev.nonamecrackers2.simpleclouds.common.cloud.spawning;

import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudType;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeDataManager;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeSource;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

public class CloudSpawningDataManager extends SimplePreparableReloadListener<Map<Identifier, JsonElement>> implements net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener
{
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static CloudSpawningDataManager instance;
	
	public static CloudSpawningDataManager getInstance()
	{
		if (instance == null)
			instance = new CloudSpawningDataManager(CloudTypeDataManager.getServerInstance());
		return instance;
	}
	private CloudTypeSource source;
	private CloudSpawningConfig config;
	
	public CloudSpawningDataManager(CloudTypeSource source)
	{
		super();
		this.source = source;
		this.config = CloudSpawningConfig.EMPTY;
	}
	
	@Override
	public net.minecraft.resources.Identifier getFabricId()
	{
		return net.minecraft.resources.Identifier.fromNamespaceAndPath("simpleclouds", "cloud_spawning");
	}

	@Override
	public java.util.Collection<net.minecraft.resources.Identifier> getFabricDependencies()
	{
		return java.util.List.of(net.minecraft.resources.Identifier.fromNamespaceAndPath("simpleclouds", "cloud_types"));
	}
	
	public CloudSpawningConfig getConfig()
	{
		return this.config;
	}

	@Override
	protected Map<Identifier, JsonElement> prepare(ResourceManager manager, ProfilerFiller filler)
	{
		filler.push("cloud_spawning");
		ImmutableMap.Builder<Identifier, JsonElement> builder = ImmutableMap.builder();
		manager.listResources("cloud_spawning", id -> id.getPath().endsWith(".json")).forEach((id, resource) -> {
			try
			{
				JsonElement element = GSON.fromJson(resource.openAsReader(), JsonElement.class);
				// Key by <namespace>:<filename-without-.json> (the cloud_spawning/ directory
				// stripped), so apply() can find the root as simpleclouds:config -- matching
				// the key normalization the 1.20.1 vanilla SimpleJsonResourceReloadListener did.
				String path = id.getPath();
				String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
				builder.put(Identifier.fromNamespaceAndPath(id.getNamespace(), name), element);
			}
			catch (Exception e)
			{
				LOGGER.error("Failed to parse cloud spawning config: {}", id, e);
			}
		});
		filler.pop();
		return builder.build();
	}

	@Override
	protected void apply(Map<Identifier, JsonElement> resources, ResourceManager manager, ProfilerFiller filler)
	{
		JsonElement root = resources.get(SimpleCloudsMod.id("config"));
		if (root == null)
		{
			LOGGER.error("Could not find root Simple Clouds config");
			this.config = CloudSpawningConfig.EMPTY;
			return;
		}
		
		// Singleplayer load-order artifact: the first resource reload can run before
		// the cloud_types listener has applied (its data is then only [EMPTY]). The
		// entries are id-keyed and re-loaded on every /reload, so trust the type
		// references for this round and re-validate once the types exist.
		CloudTypeSource validator = this.source;
		if (this.source.getIndexedCloudTypes().length <= 1)
		{
			LOGGER.warn("cloud_types data not loaded yet; deferring cloud type validation for this cloud_spawning reload");
			validator = new CloudTypeSource()
			{
				public CloudType getCloudTypeForId(Identifier id)
				{
					return null;
				}
				public CloudType[] getIndexedCloudTypes()
				{
					return new CloudType[0];
				}
				public boolean doesCloudTypeExist(Identifier id)
				{
					return true;
				}
			};
		}
		
		ImmutableMap.Builder<Identifier, CloudSpawningConfig.Info> entries = ImmutableMap.builder();
		
		for (var entry : resources.entrySet())
		{
			if (entry.getValue() != root)
			{
				try
				{
					CloudSpawningConfig.Info info = CloudSpawningConfig.readInfo(validator, GsonHelper.convertToJsonObject(entry.getValue(), "root"));
					entries.put(info.cloudType(), info);
				}
				catch (JsonSyntaxException | IllegalArgumentException | NullPointerException e)
				{
					LOGGER.error("Failed to parse spawn info for file '" + entry.getKey() + "'", e);
					this.config = CloudSpawningConfig.EMPTY;
				}
			}
		}
		
		try
		{
			this.config = CloudSpawningConfig.fromJson(validator, GsonHelper.convertToJsonObject(root, "root"), entries.build());
		}
		catch (JsonSyntaxException | IllegalArgumentException | NullPointerException e)
		{
			LOGGER.error("Failed to parse cloud spawn config", e);
			this.config = CloudSpawningConfig.EMPTY;
		}
	}
}
