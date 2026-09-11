package dev.nonamecrackers2.simpleclouds.common.cloud.spawning;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.JsonOps;

import dev.nonamecrackers2.simpleclouds.api.common.cloud.spawning.SpawnInfo;
import dev.nonamecrackers2.simpleclouds.common.cloud.CloudTypeSource;
import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.IntProvider;

public class CloudSpawningConfig
{
	public static final CloudSpawningConfig EMPTY = new CloudSpawningConfig(ConstantInt.ZERO, 0, 0, ImmutableMap.of());
	private final IntProvider spawnInterval;
	private final int maxCloudRegions;
	private final int maxInitialRegions;
	private final Map<Identifier, CloudSpawningConfig.Info> weightsPerType;
	private final WeightedList<CloudSpawningConfig.Info> weights;
	
	private CloudSpawningConfig(IntProvider spawnInterval, int maxCloudRegions, int maxInitialRegions, Map<Identifier, CloudSpawningConfig.Info> weightsPerType)
	{
		this.spawnInterval = spawnInterval;
		this.maxCloudRegions = maxCloudRegions;
		this.maxInitialRegions = maxInitialRegions;
		this.weightsPerType = weightsPerType;
		this.weights = WeightedList.of(Lists.newArrayList(weightsPerType.values()).stream()
				.map(info -> new Weighted<>(info, info.weight()))
				.toList());
	}
	
	public static CloudSpawningConfig of(IntProvider spawnInterval, int maxCloudRegions, int maxInitialRegions, ImmutableMap<Identifier, CloudSpawningConfig.Info> weightsPerType)
	{
		if (weightsPerType.isEmpty())
			return EMPTY;
		return new CloudSpawningConfig(spawnInterval, maxCloudRegions, maxInitialRegions, weightsPerType);
	}
	
	public static CloudSpawningConfig fromJson(CloudTypeSource typeValidator, JsonObject object, ImmutableMap<Identifier, CloudSpawningConfig.Info> entries) throws JsonSyntaxException, NullPointerException, IllegalArgumentException
	{
		if (entries.isEmpty())
			return EMPTY;
		IntProvider spawnInterval = net.minecraft.util.valueproviders.ConstantInt.MAP_CODEC.codec().parse(JsonOps.INSTANCE, Objects.requireNonNull(object.get("spawn_interval"))).resultOrPartial(e -> {
			throw new JsonSyntaxException(e);
		}).get();
		int maxCloudRegions = GsonHelper.getAsInt(object, "max_formations");
		int maxInitialRegions = GsonHelper.getAsInt(object, "max_initial_formations");
		if (maxCloudRegions > SimpleCloudsConstants.MAX_CLOUD_FORMATIONS || maxInitialRegions > SimpleCloudsConstants.MAX_CLOUD_FORMATIONS)
			throw new IllegalArgumentException("Maximum cloud formations is " + SimpleCloudsConstants.MAX_CLOUD_FORMATIONS);
//		ImmutableMap.Builder<Identifier, CloudSpawningConfig.Info> values = ImmutableMap.builder();
//		JsonArray entries = GsonHelper.getAsJsonArray(object, "entries");
//		for (JsonElement element : entries)
//		{
//			JsonObject entry = GsonHelper.convertToJsonObject(element, "entry");
//			CloudSpawningConfig.Info info = readInfo(typeValidator, entry);
//			values.put(info.cloudType, info);
//		}
//		ImmutableMap<Identifier, CloudSpawningConfig.Info> map = values.buildOrThrow();
		return new CloudSpawningConfig(spawnInterval, maxCloudRegions, maxInitialRegions, entries);
	}
	
	public static CloudSpawningConfig.Info readInfo(CloudTypeSource typeValidator, JsonObject object) throws JsonSyntaxException, NullPointerException, IllegalArgumentException
	{
		String rawId = GsonHelper.getAsString(object, "type");
		Identifier id = Identifier.read(rawId).resultOrPartial(e -> {
			throw new IllegalArgumentException(e);
		}).get();
		if (!typeValidator.doesCloudTypeExist(id))
			throw new IllegalArgumentException("Unknown cloud type with id '" + id + "'");
		
		int weight = GsonHelper.getAsInt(object, "weight");
		
		FloatProvider speed = net.minecraft.util.valueproviders.ConstantFloat.MAP_CODEC.codec().parse(JsonOps.INSTANCE, object.get("speed")).resultOrPartial(e -> {
			throw new JsonSyntaxException(e);
		}).get();
		
		IntProvider radius = net.minecraft.util.valueproviders.ConstantInt.MAP_CODEC.codec().parse(JsonOps.INSTANCE, object.get("radius")).resultOrPartial(e -> {
			throw new JsonSyntaxException(e);
		}).get();
		
		IntProvider existTicks = net.minecraft.util.valueproviders.ConstantInt.MAP_CODEC.codec().parse(JsonOps.INSTANCE, object.get("exist_ticks")).resultOrPartial(e -> {
			throw new JsonSyntaxException(e);
		}).get();
		
		IntProvider growTicks = net.minecraft.util.valueproviders.ConstantInt.MAP_CODEC.codec().parse(JsonOps.INSTANCE, object.get("grow_ticks")).resultOrPartial(e -> {
			throw new JsonSyntaxException(e);
		}).get();
		
		FloatProvider stretchFactor;
		if (object.has("stretch_factor"))
		{
			stretchFactor = net.minecraft.util.valueproviders.ConstantFloat.MAP_CODEC.codec().parse(JsonOps.INSTANCE, object.get("stretch_factor")).resultOrPartial(e -> {
				throw new JsonSyntaxException(e);
			}).orElse(ConstantFloat.of(1.0F));
		}
		else
		{
			stretchFactor = ConstantFloat.of(1.0F);
		}
		
		boolean movesToPlayer = false;
		if (object.has("moves_to_player"))
			movesToPlayer = GsonHelper.getAsBoolean(object, "moves_to_player");
		
		int orderWeight = GsonHelper.getAsInt(object, "order_weight");
		if (orderWeight <= 0)
			throw new IllegalArgumentException("Order weight must be >= 1");
		
		return new CloudSpawningConfig.Info(id, weight, speed, radius, existTicks, growTicks, stretchFactor, movesToPlayer, orderWeight);
	}
	
	public boolean isEmpty()
	{
		return this.weightsPerType.isEmpty(); 
	}
	
	public IntProvider getSpawnInterval()
	{
		return this.spawnInterval;
	}
	
	public int getMaxRegions()
	{
		return this.maxCloudRegions;
	}
	
	public int getMaxInitialRegions()
	{
		return this.maxInitialRegions;
	}
	
	public @Nullable CloudSpawningConfig.Info getWeightInfo(Identifier cloudType)
	{
		return this.weightsPerType.get(cloudType);
	}
	
	public Optional<CloudSpawningConfig.Info> getRandom(RandomSource random)
	{
		return this.weights.getRandom(random);
	}
	
	public static record Info(Identifier cloudType, int weight, FloatProvider speed, IntProvider radius, IntProvider existTicks, IntProvider growTicks, FloatProvider stretchFactor, boolean movesToPlayer, int orderWeight) implements SpawnInfo
	{
		@Override
		public int determineExistTicks(RandomSource random)
		{
			return this.existTicks.sample(random);
		}
		
		@Override
		public int determineGrowTicks(RandomSource random)
		{
			return this.growTicks.sample(random);
		}
		
		@Override
		public int determineRadius(RandomSource random)
		{
			return this.radius.sample(random);
		}
		
		@Override
		public float determineSpeed(RandomSource random)
		{
			return this.speed.sample(random);
		}
		
		@Override
		public float determineStretchFactor(RandomSource random)
		{
			return this.stretchFactor.sample(random);
		}
		
		public JsonObject toJson() throws IllegalArgumentException
		{
			JsonObject object = new JsonObject();
			
			object.addProperty("type", this.cloudType.toString());
			object.addProperty("weight", this.weight);
			object.addProperty("speed", this.speed.sample(net.minecraft.util.RandomSource.create()));
			object.addProperty("radius", this.radius.sample(net.minecraft.util.RandomSource.create()));
			object.addProperty("exist_ticks", this.existTicks.sample(net.minecraft.util.RandomSource.create()));
			object.addProperty("grow_ticks", this.radius.sample(net.minecraft.util.RandomSource.create()));
			object.addProperty("stretch_factor", this.stretchFactor.sample(net.minecraft.util.RandomSource.create()));
			object.addProperty("moves_to_player", this.movesToPlayer);
			if (this.orderWeight <= 0)
				throw new IllegalArgumentException("Order weight must be >= 1");
			object.addProperty("order_weight", this.orderWeight);
			
			return object;
		}
	}
}
