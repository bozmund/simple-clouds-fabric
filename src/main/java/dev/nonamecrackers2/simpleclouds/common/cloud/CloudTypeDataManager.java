package dev.nonamecrackers2.simpleclouds.common.cloud;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

public class CloudTypeDataManager extends SimplePreparableReloadListener<Map<Identifier, JsonElement>> implements CloudTypeSource, net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener
{
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final CloudTypeDataManager SERVER = new CloudTypeDataManager();
	
	public static CloudTypeDataManager getServerInstance()
	{
		return SERVER;
	}
	private Map<Identifier, CloudType> cloudTypes = ImmutableMap.of(SimpleCloudsConstants.EMPTY.id(), SimpleCloudsConstants.EMPTY);
	private CloudType[] indexedCloudTypes = new CloudType[] { SimpleCloudsConstants.EMPTY };
	
	public CloudTypeDataManager()
	{
		super();
	}
	
	@Override
	public net.minecraft.resources.Identifier getFabricId()
	{
		return net.minecraft.resources.Identifier.fromNamespaceAndPath("simpleclouds", "cloud_types");
	}

	@Override
	public java.util.Collection<net.minecraft.resources.Identifier> getFabricDependencies()
	{
		return java.util.List.of();
	}
	
	@Override
	protected Map<Identifier, JsonElement> prepare(ResourceManager manager, ProfilerFiller filler)
	{
		filler.push("cloud_types");
		ImmutableMap.Builder<Identifier, JsonElement> builder = ImmutableMap.builder();
		manager.listResources("cloud_types", id -> id.getPath().endsWith(".json")).forEach((id, resource) -> {
			try
			{
				JsonElement element = GSON.fromJson(resource.openAsReader(), JsonElement.class);
				// 26.2 listResources keys are the FULL resource path
				// (ns:cloud_types/file.json); the rest of the mod (spawn config
				// "type" fields, config defaults, synced region ids) uses
				// ns:<filename-without-extension> -- the key form 1.20.1's
				// SimpleJsonResourceReloadListener produced. Normalize to that.
				String path = id.getPath();
				String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
				builder.put(Identifier.fromNamespaceAndPath(id.getNamespace(), name), element);
			}
			catch (Exception e)
			{
				LOGGER.error("Failed to parse cloud type: {}", id, e);
			}
		});
		filler.pop();
		return builder.build();
	}

	@Override
	protected void apply(Map<Identifier, JsonElement> files, ResourceManager manager, ProfilerFiller filler)
	{
		Map<Identifier, CloudType> types = Maps.newHashMap();
		for (var entry : files.entrySet())
		{
			Identifier id = entry.getKey();
			JsonElement element = entry.getValue();
			try
			{
				JsonObject object = GsonHelper.convertToJsonObject(element, "root");
				types.put(id, CloudType.readFromJson(id, object));
			}
			catch (JsonSyntaxException e)
			{
				LOGGER.error("Failed to parse cloud type: {}", id, e);
			}
		}
		
		// 1.20.1 parity: EMPTY is always part of the type set (first, sorted by id).
		this.indexedCloudTypes = Streams.concat(
				Stream.of(SimpleCloudsConstants.EMPTY),
				types.values().stream().sorted(Comparator.comparing(t -> t.id().toString()))).toArray(i -> new CloudType[i]);
		types.put(SimpleCloudsConstants.EMPTY.id(), SimpleCloudsConstants.EMPTY);
		this.cloudTypes = ImmutableMap.copyOf(types);
		LOGGER.info("Loaded {} cloud types", this.cloudTypes.size());
	}

	public Stream<CloudType> getCloudTypes()
	{
		return this.cloudTypes.values().stream();
	}

	@Override
	public CloudType getCloudTypeForId(Identifier id)
	{
		return this.cloudTypes.get(id);
	}

	@Override
	public CloudType[] getIndexedCloudTypes()
	{
		return this.indexedCloudTypes;
	}
}
