#version 330

// 26.2 vertical-slice storm fog: a darkened, vertically-graded overlay drawn when
// the player is under storm clouds.
//
// DEVIATION from the 1.20.1 original (program/storm_fog.fsh): the original raymarched
// up to 200 steps from the camera, sampling the cloud SHADOW MAP (depth + the storm
// color written by clouds_shadow_map) per step, with distance fade, vertical fade,
// per-pixel scene-depth occlusion and a per-bolt lightning multiplier. That depends
// on the shadow-map subsystem, which is not ported to 26.2 yet. This pass approximates
// it: intensity is measured on the CPU (fraction of the columns around the camera that
// contain storm-type cloud above the player) and applied as a screen-wide darkening
// with a vertical gradient. The LightningMul uniform is wired for the lightning
// flash hook (world effects task).
in vec2 texCoord;

out vec4 fragColor;

layout(std140) uniform StormFog {
	vec3 FogColor;
	float Intensity;
	float VerticalFade;
	float LightningMul;
	float _pad0;
	float _pad1;
	float _pad2;
};

void main()
{
	// The triangle extends past the screen; anything outside is clipped anyway, but
	// guard against edge artifacts.
	if (texCoord.x < 0.0 || texCoord.x > 1.0 || texCoord.y < 0.0 || texCoord.y > 1.0)
		discard;

	// Vertical gradient: strongest near the bottom of the screen (the horizon where
	// the original raymarch accumulated most density), fading upward.
	//
	// Step 3 (storm plan): the old pass saturated at alpha 1.0 under a storm
	// (coverage*2.5 capped at 1.0) - a fully opaque flat layer that hid ALL of
	// the cloud geometry, which is what read as the "smeared grey blobs with
	// streaks" in Jan's shots 3-4. The original's raymarch fog darkens and
	// softens but never fully occludes, so: cap the accumulated density well
	// below opaque (0.45) and fade it to ZERO at the top of the screen, so the
	// cube structure of the storm cloud stays visible through the fog.
	float gradient = mix(1.0, 0.0, pow(clamp(texCoord.y, 0.0, 1.0), VerticalFade));
	float density = clamp(Intensity * gradient * LightningMul * 0.45, 0.0, 1.0);

	fragColor = vec4(FogColor, density);
}
