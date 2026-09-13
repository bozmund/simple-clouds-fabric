package dev.nonamecrackers2.simpleclouds.client.sound;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * 26.2 port of the 1.20.1 class of the same name: a positioned thunder sound
 * whose attenuation distance is the mod's (configurable) value instead of the
 * Sound definition's, so distant thunder stays audible out to the configured
 * distance (the original's thunder is heard across the whole storm field).
 */
public class AdjustableAttenuationSoundInstance extends SimpleSoundInstance
{
	private final int attenuationDistance;

	public AdjustableAttenuationSoundInstance(SoundEvent sound, SoundSource source, float volume, float pitch,
			RandomSource random, double x, double y, double z, int attenuationDistance)
	{
		super(sound, source, volume, pitch, random, x, y, z);
		this.attenuationDistance = attenuationDistance;
	}

	@Override
	public WeighedSoundEvents resolve(SoundManager manager)
	{
		WeighedSoundEvents events = super.resolve(manager);
		this.sound = wrap(this.sound, this.attenuationDistance);
		return events;
	}

	@Override
	public Attenuation getAttenuation()
	{
		return Attenuation.LINEAR;
	}

	private static Sound wrap(Sound sound, int attenuationDistance)
	{
		// 26.2: the first component is the Identifier itself (the original passed a
		// location string to the 1.20.1 String-based constructor).
		return new Sound(sound.getLocation(), sound.getVolume(), sound.getPitch(), sound.getWeight(),
				sound.getType(), sound.shouldStream(), sound.shouldPreload(), attenuationDistance);
	}
}
