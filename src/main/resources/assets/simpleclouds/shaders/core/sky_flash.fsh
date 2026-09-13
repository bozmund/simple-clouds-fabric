#version 330

// 26.2 sky flash (storm plan step 1): the vanilla lightmap sky flash is dead in
// 26.2 — nothing consumes ClientLevel.getSkyFlashTime() anymore (verified in the
// jar: only Level/ClientLevel reference the field). The port therefore draws its
// own short full-screen white brightening, driven by the SAME gated strength as
// the storm-fog lift: 2-tick renewal per frame while a rendered bolt is within
// 2000 blocks and bright (fade > 0.5), zero for far strikes and zero when
// "Hide Sky Flashes" is on (flashStrength checks the option).
in vec2 texCoord;

out vec4 fragColor;

layout(std140) uniform SkyFlash {
	float Strength;
	float _pad0;
	float _pad1;
	float _pad2;
};

void main()
{
	if (texCoord.x < 0.0 || texCoord.x > 1.0 || texCoord.y < 0.0 || texCoord.y > 1.0)
		discard;

	// Alpha-blended white = a uniform brightening of everything on screen,
	// close to the original's lightmap sky-column lift.
	fragColor = vec4(1.0, 1.0, 1.0, clamp(Strength, 0.0, 1.0));
}
