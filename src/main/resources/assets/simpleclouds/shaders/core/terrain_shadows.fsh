#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Terrain cloud shadows — 26.2 port of the 1.20.1 cloud_shadows post program
// (shaders/program/cloud_shadows.fsh). Reconstructs each fragment's view/world
// position from the scene depth, looks it up in the cloud shadow depth map
// (top-down ortho), and darkens the terrain where a cloud is above it.
//
// Original model (step 6, verified against the 1.20.1 source):
// - no shadow within MinimumRadius + 32 blocks of the camera (the pass is the
//   original's "distant shadows" — 1.20.1 created it only with Distant Horizons
//   loaded; the 26.2 slice keeps it always available);
// - fade IN over FadeDistance from there, fade OUT over the last FadeDistance
//   before ShadowSpan/2;
// - 3x3 PCF with ±10-block world offsets (the original's taps);
// - shadowed color = col * ShadowColorMultiplier (0.7, 0.7, 0.8) — a slightly
//   blue 30% darkening, mixed in with the (faded) strength.
//
// 26.2 specifics verified on screen (see PORTING.md, shadow-map task):
// - the main target clears depth to 0.0, so the SKY samples ~0 and geometry
//   samples (0, 1);
// - the scene depth/color must be sampled as textures in a COLOR-ONLY pass
//   (attaching and sampling the same depth image in one pass read back 0);
// - depth -> view position without near/far uniforms: for an OpenGL perspective
//   P, P[2][2] = (f+n)/(n-f), P[3][2] = 2fn/(n-f), zv = -P[3][2] / (ndcZ + P[2][2]),
//   and clip.w == -zv so vx = -ndcX * zv / P[0][0].
uniform sampler2D DepthSampler;     // main target scene depth
uniform sampler2D DiffuseSampler;   // main target scene color
uniform sampler2D ShadowMap;        // cloud shadow depth (D32)

layout(std140) uniform ShadowPass {
	mat4 ShadowViewProj;
	float ShadowBias;
	float MinimumRadius; // render distance in blocks; shadows start at + 32
	float ShadowSpan;    // full span of the shadow volume in blocks
	float FadeDistance;  // fade-in / fade-out length in blocks
	float DebugShowDepth; // step 6 diagnostic: 1 = reconstructed lookup, 2 = raw map, 3 = worldPos as color
	vec3 CameraPos;      // step 6 diagnostic: world-space camera position
};

in vec2 texCoord;
out vec4 fragColor;

// The original's shadowStrengthAt: 1.0 when a cloud is above the point.
float shadowStrengthAt(vec3 pos)
{
	vec4 spos = ShadowViewProj * vec4(pos, 1.0);
	vec2 suv = spos.xy * 0.5 + 0.5;
	if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0)
		return 0.0; // outside the shadow volume: fully lit
	float pointZ = (spos.z / spos.w + 1.0) * 0.5;
	return texture(ShadowMap, suv).r < pointZ - ShadowBias ? 1.0 : 0.0;
}

void main()
{
	// Step 6 diagnostic (first thing: the depth discard and the distance-fade
	// guard would otherwise hide it): visualize the stored shadow depth
	// (1.0 = empty, ~0.75 = a cloud at 250 height).
	if (DebugShowDepth > 1.5)
	{
		// Raw map dump (screen UV ≈ map UV, XZ orientation may be mirrored):
		// shows EXACTLY what the map pass stored, independent of reconstruction.
		fragColor = vec4(texture(ShadowMap, texCoord).r, 0.0, 0.0, 1.0);
		return;
	}
	if (DebugShowDepth > 0.5)
	{
		float d0 = texture(DepthSampler, texCoord).r;
		if (d0 <= 0.0001 || d0 >= 0.9999)
		{
			fragColor = vec4(0.0, 1.0, 1.0, 1.0); // sky / no geometry
			return;
		}
		float ndcX0 = texCoord.x * 2.0 - 1.0;
		float ndcY0 = texCoord.y * 2.0 - 1.0;
		float ndcZ0 = d0; // 26.2 isZZeroToOne: the stored depth IS the NDC z (near 0, far 1)
		// GLSL indexes M[column][row]: P22=M[2][2], P23=M[3][2], P32=M[2][3], P33=M[3][3].
		float zv0 = (ProjMat[3][2] - ndcZ0 * ProjMat[3][3]) / (ndcZ0 * ProjMat[2][3] - ProjMat[2][2]);
		float clipW0 = ProjMat[2][3] * zv0 + ProjMat[3][3];
		vec3 viewPos0 = vec3(ndcX0 * clipW0 / ProjMat[0][0], ndcY0 * clipW0 / ProjMat[1][1], zv0);
		vec4 worldPos0 = inverse(ModelViewMat) * vec4(viewPos0, 1.0);
		if (DebugShowDepth > 2.5)
		{
			// worldPos relative to the camera as color: R=x/±2500, G=y/400, B=z/±2500.
			vec3 rel = worldPos0.xyz - vec3(CameraPos);
			fragColor = vec4(
				clamp(rel.x / 2500.0 + 0.5, 0.0, 1.0),
				clamp(rel.y / 400.0 + 0.5, 0.0, 1.0),
				clamp(rel.z / 2500.0 + 0.5, 0.0, 1.0), 1.0);
			return;
			}
		vec4 spos0 = ShadowViewProj * worldPos0;
		vec2 suv0 = spos0.xy * 0.5 + 0.5;
		if (suv0.x < 0.0 || suv0.x > 1.0 || suv0.y < 0.0 || suv0.y > 1.0)
			fragColor = vec4(0.0, 1.0, 0.0, 1.0); // outside the shadow volume
		else
			fragColor = vec4(texture(ShadowMap, suv0).r, 0.0, 0.0, 1.0);
		return;
	}

	float d = texture(DepthSampler, texCoord).r;
	if (d <= 0.0001 || d >= 0.9999)
		discard; // sky (0.0 clear) or fully-far geometry: nothing to shadow

	// Generic perspective reconstruction (works for any projection layout, incl.
	// 26.2's joml setPerspective with its isZZeroToOne inverted-Z matrix):
	//   ndcZ = (P22*zv + P23) / (P32*zv + P33)
	//   zv   = (P23 - ndcZ*P33) / (ndcZ*P32 - P22)
	//   clip.w = P32*zv + P33,  vx = ndcX*clip.w/P00 (P00, P11 diagonal).
	float ndcX = texCoord.x * 2.0 - 1.0;
	float ndcY = texCoord.y * 2.0 - 1.0;
	float ndcZ = d; // 26.2 isZZeroToOne: the stored depth IS the NDC z (near 0, far 1)
	// GLSL indexes M[column][row]: P22=M[2][2], P23=M[3][2], P32=M[2][3], P33=M[3][3].
	float zv = (ProjMat[3][2] - ndcZ * ProjMat[3][3]) / (ndcZ * ProjMat[2][3] - ProjMat[2][2]);
	float clipW = ProjMat[2][3] * zv + ProjMat[3][3];
	vec3 viewPos = vec3(ndcX * clipW / ProjMat[0][0], ndcY * clipW / ProjMat[1][1], zv);
	vec4 worldPos = inverse(ModelViewMat) * vec4(viewPos, 1.0);

	// The original's distance model: len is the fragment's distance from the
	// camera (view-space length).
	float len = length(viewPos);
	float start = MinimumRadius + 32.0;
	float end = ShadowSpan * 0.5;
	if (len < start || len > end)
	{
		fragColor = vec4(texture(DiffuseSampler, texCoord).rgb, 1.0); // unchanged
		return;
	}
	float f = FadeDistance;
	float invF = 1.0 / max(f, 0.001);
	float distFade = clamp(invF * (len - start), 0.0, 1.0)
	               - clamp(invF * (len - end + f), 0.0, 1.0);

	// 3x3 PCF, ±10-block world offsets (the original's taps).
	float strength = 0.0;
	for (int x = -1; x <= 1; x++)
	{
		for (int y = -1; y <= 1; y++)
			strength += shadowStrengthAt(worldPos.xyz + vec3(float(x) * 10.0, 0.0, float(y) * 10.0));
	}
	strength /= 9.0;
	strength *= distFade;
	strength = clamp(strength, 0.0, 1.0);

	// ShadowColorMultiplier from the 1.20.1 cloud_shadows.json.
	fragColor = vec4(texture(DiffuseSampler, texCoord).rgb * vec3(0.7, 0.7, 0.8), strength);
}
