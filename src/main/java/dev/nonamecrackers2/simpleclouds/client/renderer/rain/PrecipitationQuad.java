package dev.nonamecrackers2.simpleclouds.client.renderer.rain;

import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 26.2 vertical-slice stub. Custom rain/snow quads are not ported to the new vertex
 * consumer API yet; this keeps the type + constants so dependent code compiles.
 * Port incrementally.
 */
public class PrecipitationQuad
{
	public static final float MAX_LENGTH = 32.0F;
	public static final float MAX_WIDTH = 2.0F;
	public static final Map<Biome.Precipitation, Identifier> TEXTURE_BY_PRECIPITATION = Util.make(() -> {
		ImmutableMap.Builder<Biome.Precipitation, Identifier> map = ImmutableMap.builder();
		map.put(Biome.Precipitation.RAIN, Identifier.parse("textures/environment/rain.png"));
		map.put(Biome.Precipitation.SNOW, Identifier.parse("textures/environment/snow.png"));
		return map.build();
	});
	private final Biome.Precipitation precipitation;
	private final Function<ClipContext, BlockHitResult> raycaster;

	public PrecipitationQuad(Biome.Precipitation precipitation, Function<ClipContext, BlockHitResult> raycaster, BlockPos position, float xRot, float yRot, int lifeSpan, float initialWidth)
	{
		if (precipitation == Biome.Precipitation.NONE)
			throw new IllegalArgumentException("Cannot be NONE precipitation type");
		this.precipitation = precipitation;
		this.raycaster = raycaster;
	}

	public Biome.Precipitation getPrecipitation()
	{
		return this.precipitation;
	}
}
