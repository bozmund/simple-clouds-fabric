package dev.nonamecrackers2.simpleclouds.common.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.Nullable;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-level cloud manager persistence.
 *
 * 26.2: the old {@code computeIfAbsent(deserializer, supplier, id)} API and the
 * abstract {@code save(CompoundTag)} method are gone; saved data is now a
 * {@link SavedDataType} (id + constructor + {@link Codec}). The codec
 * round-trips the manager's fields; the manager reference itself is
 * transient (it is created per level) and attached by {@code MixinServerLevel}
 * after the storage resolves this instance.
 */
public class CloudData extends SavedData
{
	public static final Identifier ID = SimpleCloudsMod.id("clouddata");

	public static final Codec<CloudData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.LONG.fieldOf("Seed").forGetter(d -> d.manager == null ? d.seed : d.manager.getSeed()),
			Codec.FLOAT.fieldOf("ScrollAngle").forGetter(d -> d.manager == null ? d.scrollAngle : d.manager.getScrollAngle()),
			Codec.FLOAT.fieldOf("Speed").forGetter(d -> d.manager == null ? d.speed : d.manager.getCloudSpeed()),
			Codec.INT.fieldOf("Height").forGetter(d -> d.manager == null ? d.height : d.manager.getCloudHeight()),
			// 26.2 ships the pre-null-refactor Mojang serialization: no Codec.optional(),
			// use optionalFieldOf (MapCodec<Optional<...>>).
			CompoundTag.CODEC.optionalFieldOf("cloud_generator").forGetter(d -> d.manager == null ? java.util.Optional.ofNullable(d.generatorTag) : java.util.Optional.ofNullable(d.manager.getCloudGenerator().toTag()))
	).apply(i, CloudData::fromFields));

	/**
	 * No DataFixTypes constant can be added by mods (final enum); this value is
	 * only the fixer lookup key. Fresh saves are written with the current data
	 * version, so the datafix pass is a no-op for them.
	 */
	public static final SavedDataType<CloudData> TYPE = new SavedDataType<>(ID, CloudData::new, CODEC, DataFixTypes.SAVED_DATA_WORLD_CLOCKS);

	private long seed;
	private float scrollAngle;
	private float speed;
	private int height;
	@Nullable
	private CompoundTag generatorTag;
	private boolean loaded;
	@Nullable
	private transient ServerCloudManager manager;

	public CloudData()
	{
	}

	private static CloudData fromFields(long seed, float scrollAngle, float speed, int height, java.util.Optional<CompoundTag> generator)
	{
		CloudData data = new CloudData();
		data.seed = seed;
		data.scrollAngle = scrollAngle;
		data.speed = speed;
		data.height = height;
		data.generatorTag = generator.orElse(null);
		data.loaded = true;
		return data;
	}

	/**
	 * Called by {@code MixinServerLevel} right after
	 * {@code getDataStorage().computeIfAbsent(TYPE)}: applies the loaded values
	 * to the per-level manager (or, for a brand-new world, simply adopts the
	 * manager's freshly rolled seed).
	 */
	public void attach(ServerCloudManager manager)
	{
		this.manager = manager;
		if (this.loaded)
		{
			manager.setSeed(this.seed);
			manager.setScrollAngle(this.scrollAngle);
			manager.setCloudSpeed(this.speed);
			manager.setCloudHeight(this.height);
			if (this.generatorTag != null)
				manager.getCloudGenerator().readTag(this.generatorTag);
		}
		this.setDirty();
	}

	/** The manager mutates on its own ticks; keep the file in sync on autosave. */
	public void markChanged()
	{
		this.setDirty();
	}
}
