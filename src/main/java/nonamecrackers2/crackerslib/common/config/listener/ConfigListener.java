package nonamecrackers2.crackerslib.common.config.listener;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableList;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Fabric port: a poll-based config change listener.
 *
 * The original relied on Forge's ModConfigEvent / OnConfigOptionSaved bus events.
 * On Fabric there is no equivalent, so the listener is driven by {@link #poll()},
 * which the host mod calls periodically (e.g. on a client tick). The cached-value
 * comparison logic is unchanged.
 */
public class ConfigListener
{
	private final ModConfig.Type type;
	private final String modid;
	private final List<ConfigListener.OptionListener<?>> values;

	private ConfigListener(ModConfig.Type type, String modid, List<ConfigListener.OptionListener<?>> values)
	{
		this.type = type;
		this.modid = modid;
		this.values = values;
	}

	public void poll()
	{
		for (ConfigListener.OptionListener<?> value : this.values)
		{
			if (value.isChanged())
			{
				value.callCallback();
				value.updateCached();
			}
		}
	}

	public void resetCache()
	{
		for (ConfigListener.OptionListener<?> value : this.values)
			value.updateCached();
	}

	public void clearCache()
	{
		for (ConfigListener.OptionListener<?> value : this.values)
			value.cleartCache();
	}

	public static ConfigListener.Builder builder(ModConfig.Type type, String modid)
	{
		return new ConfigListener.Builder(type, modid);
	}

	public static class Builder
	{
		private final ModConfig.Type type;
		private final String modid;
		private final ImmutableList.Builder<ConfigListener.OptionListener<?>> values = ImmutableList.builder();

		private Builder(ModConfig.Type type, String modid)
		{
			this.type = type;
			this.modid = modid;
		}

		public <T> Builder addListener(ForgeConfigSpec.ConfigValue<T> value, BiConsumer<T, T> onChanged)
		{
			this.values.add(new ConfigListener.OptionListener<>(value, onChanged));
			return this;
		}

		public ConfigListener build()
		{
			return new ConfigListener(this.type, this.modid, this.values.build());
		}

		/**
		 * Fabric: simply builds the listener. The host mod is responsible for
		 * calling {@link ConfigListener#poll()} on a schedule.
		 */
		public ConfigListener buildAndRegister()
		{
			return this.build();
		}
	}

	private static class OptionListener<T>
	{
		private final ForgeConfigSpec.ConfigValue<T> option;
		private final BiConsumer<T, T> onChanged;
		private @Nullable T cached;

		OptionListener(ForgeConfigSpec.ConfigValue<T> option, BiConsumer<T, T> onChanged)
		{
			this.option = option;
			this.onChanged = onChanged;
		}

		boolean isChanged()
		{
			if (this.cached == null)
				return false;
			else
				return !Objects.equals(this.cached, this.option.get());
		}

		void callCallback()
		{
			if (this.cached != null)
				this.onChanged.accept(this.cached, this.option.get());
		}

		@SuppressWarnings("unchecked")
		void callCallbackUnsafe(Object old, Object newValue)
		{
			this.onChanged.accept((T)old, (T)newValue);
		}

		void updateCached()
		{
			this.cached = this.option.get();
		}

		@SuppressWarnings("unchecked")
		void updateCachedUnsafe(Object value)
		{
			this.cached = (T)value;
		}

		void cleartCache()
		{
			this.cached = null;
		}
	}
}
