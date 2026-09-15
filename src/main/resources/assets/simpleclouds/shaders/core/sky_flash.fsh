#version 330

// 26.2 sky flash (storm plan step 1, plan item 3): the vanilla lightmap sky flash is dead in
// 26.2 -- nothing consumes ClientLevel.getSkyFlashTime() anymore (verified in the jar: only
// Level/ClientLevel reference the field). The port draws its own short brightening, driven by
// the gated strength (a rendered bolt within 2000 blocks and bright, zero for far strikes and
// with "Hide Lightning Flashes" on). Plan item 3: SKY pixels only, like vanilla's sky flash --
// the earlier full-screen white also brightened terrain and clouds (the whole-scene S5 flashes).
// 26.2 clears the main depth to 0.0: sky samples ~0, geometry (0, 1] (terrain_shadows.fsh).
uniform sampler2D DepthSampler;

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
	// Sky = the cleared depth only: with the reversed depth (near 1, far 0, ~near / distance)
	// geometry beyond ~500 blocks already stores < 0.0001, which the old threshold called sky.
	if (texture(DepthSampler, texCoord).r > 1.0e-7)
		discard; // terrain, clouds, entities: not the sky
	fragColor = vec4(1.0, 1.0, 1.0, clamp(Strength, 0.0, 1.0));
}
