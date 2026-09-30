import java.util.Optional;

import org.apache.commons.lang3.tuple.Pair;

import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;

public class LocalWeatherClassificationTest
{
	private static void check(boolean condition, String message)
	{
		if (!condition)
			throw new AssertionError(message);
	}

	public static void main(String[] args)
	{
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		check(CloudManager.matchesPrecipitation(Pair.of(true, Biome.Precipitation.RAIN), Biome.Precipitation.RAIN), "rain was not recognized");
		check(!CloudManager.matchesPrecipitation(Pair.of(true, Biome.Precipitation.RAIN), Biome.Precipitation.SNOW), "rain was classified as snow");
		check(CloudManager.matchesPrecipitation(Pair.of(true, Biome.Precipitation.SNOW), Biome.Precipitation.SNOW), "snow was not recognized");
		check(!CloudManager.matchesPrecipitation(Pair.of(false, Biome.Precipitation.RAIN), Biome.Precipitation.RAIN), "rain leaked outside the cloud");
		check(!CloudManager.matchesPrecipitation(Pair.of(true, Biome.Precipitation.NONE), Biome.Precipitation.RAIN), "dry biome produced rain");

		Biome borderline = new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.16F)
			.downfall(0.5F)
			.specialEffects(new BiomeSpecialEffects(0, Optional.empty(), Optional.empty(), Optional.empty(), BiomeSpecialEffects.GrassColorModifier.NONE))
			.generationSettings(BiomeGenerationSettings.EMPTY)
			.build();
		BlockPos mountain = new BlockPos(0, 256, 0);
		check(borderline.getPrecipitationAt(mountain, 63) == Biome.Precipitation.SNOW,
			"sea-level elevation should cool mountain precipitation to snow");
		// Temperature is cached by block position, so use a distinct position.
		check(borderline.getPrecipitationAt(new BlockPos(1, 256, 0), 80000) == Biome.Precipitation.RAIN,
			"game time wrongly used as the sea level hides elevation cooling");
		System.out.println("PASS: local rain/snow gating and 26.3 sea-level precipitation semantics");
	}
}
