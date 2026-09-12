#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Terrain cloud shadows (26.2 slice of the 1.20.1 cloud_shadows post program):
// reconstructs each fragment's world position from the scene depth texture, looks
// it up in the cloud shadow depth map (top-down ortho), and darkens the terrain
// where a cloud is above it.
//
// 26.2 specifics verified on screen (see PORTING.md, shadow-map task):
// - the main target clears depth to 0.0, so the SKY samples ~0 and geometry
//   samples (0, 1);
// - the scene depth must be sampled as a texture in a COLOR-ONLY pass (attaching
//   and sampling the same depth image in one pass read back 0);
// - depth -> view position without near/far uniforms: for an OpenGL perspective
//   P, P[2][2] = (f+n)/(n-f), P[3][2] = 2fn/(n-f), zv = -P[3][2] / (ndcZ + P[2][2]),
//   and clip.w == -zv so vx = -ndcX * zv / P[0][0].
uniform sampler2D DepthSampler; // main target scene depth
uniform sampler2D ShadowMap;    // cloud shadow depth (D32)

layout(std140) uniform ShadowPass {
	mat4 ShadowViewProj;
	float ShadowBias;
	float Intensity;
	float _pad0;
	float _pad1;
};

in vec2 texCoord;
out vec4 fragColor;

void main()
{
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
	float ndcZ = d * 2.0 - 1.0;
	float zv = (ProjMat[2][3] - ndcZ * ProjMat[3][3]) / (ndcZ * ProjMat[3][2] - ProjMat[2][2]);
	float clipW = ProjMat[3][2] * zv + ProjMat[3][3];
	vec3 viewPos = vec3(ndcX * clipW / ProjMat[0][0], ndcY * clipW / ProjMat[1][1], zv);
	vec4 worldPos = inverse(ModelViewMat) * vec4(viewPos, 1.0);

	// Ortho shadow lookup (w == 1).
	vec4 spos = ShadowViewProj * worldPos;
	vec2 suv = spos.xy * 0.5 + 0.5;
	if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0)
		discard; // outside the shadow volume: fully lit

	float pointZ = (spos.z / spos.w + 1.0) * 0.5;

	// Both the stored shadow depth and the point depth are WINDOW-space z [0,1]
	// (linear under the ortho projection). 4-tap mini-PCF for softer edges.
	float texel = 1.0 / 512.0;
	float shadowDepth = 0.0;
	shadowDepth += texture(ShadowMap, suv + vec2(-texel, -texel)).r;
	shadowDepth += texture(ShadowMap, suv + vec2( texel, -texel)).r;
	shadowDepth += texture(ShadowMap, suv + vec2(-texel,  texel)).r;
	shadowDepth += texture(ShadowMap, suv + vec2( texel,  texel)).r;
	shadowDepth *= 0.25;

	float inShadow = shadowDepth < pointZ - ShadowBias ? 1.0 : 0.0;

	fragColor = vec4(0.0, 0.0, 0.0, Intensity * inShadow);
}
