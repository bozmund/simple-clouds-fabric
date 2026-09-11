package dev.nonamecrackers2.simpleclouds.client.compat;

import java.util.Map;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundEventRegistration;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * 26.2 vertical-slice stub. Custom rain-sound replacement is not ported to the new
 * sound system yet; this is a no-op pass-through. Port incrementally.
 */
public class SimpleCloudsSoundReplacements
{
	public static Weighted<Sound> applyReplacement(Weighted<Sound> currentSound, Identifier soundLoc, SoundEventRegistration soundReg, Map<Identifier, Resource> soundCache)
	{
		// TODO(26.2): port custom rain sound replacement to the new sound system.
		return currentSound;
	}
}
