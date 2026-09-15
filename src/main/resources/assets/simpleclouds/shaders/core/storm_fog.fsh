#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Spatial storm fog (Fabric 26.2 port, plan item 3). The previous slice darkened the whole
// screen by one camera-wide scalar and lifted it for every strike. Now:
// - each pixel's view ray is marched (STEPS samples) from the camera to the scene surface
//   (or MaxDistance for the sky), and only samples BELOW the storm clouds' base (FogTop) and
//   under storm cover (the CPU coverage map, StormFogMap) add fog -- cloud-free view regions
//   stay clear;
// - lightning lights the fog locally: each nearby bolt adds light to the samples within its
//   radius, fading with distance, so a far strike lights nothing here (its thunder is handled
//   by WorldEffects); bolt strength is 0 when stormFogLightningFlashes is off or "Hide
//   Lightning Flashes" is on.
// Depth -> world reconstruction is the one terrain_shadows.fsh uses (verified on screen): 26.2
// clears the main depth to 0.0 (sky ~0, geometry in (0, 1]) and the stored depth is the NDC z.
// FogParams.w = 1 shows the reconstructed distance (sky white) to check that convention on a
// captured frame; 2 shows the fog amount (red) and the coverage at the camera (green).
uniform sampler2D DepthSampler;

layout(std140) uniform StormFog {
	vec4 FogColor;    // rgb, a = strength
	vec4 FogParams;   // x = fog top (world Y), y = max march distance, z = density per block, w = debug
	vec4 CameraCell;  // xyz = camera world position, w = map cell size (blocks)
	vec4 MapInfo;     // x, y = world X, Z of the map corner, z = cells per side, w = bolt count
	vec4 BoltPos[8];  // xyz = world position, w = light strength
	vec4 BoltColor[8];// rgb, a = light radius (blocks)
	vec4 Coverage[256];
};

in vec2 texCoord;
out vec4 fragColor;

const int STEPS = 24;

float coverageCell(int x, int z)
{
	int n = int(MapInfo.z);
	if (x < 0 || z < 0 || x >= n || z >= n)
		return 0.0;
	int i = z * n + x;
	return Coverage[i >> 2][i & 3];
}

float coverageAt(vec2 xz)
{
	vec2 g = (xz - MapInfo.xy) / CameraCell.w - 0.5;
	vec2 f = fract(g);
	ivec2 c = ivec2(floor(g));
	float a = coverageCell(c.x, c.y), b = coverageCell(c.x + 1, c.y);
	float d = coverageCell(c.x, c.y + 1), e = coverageCell(c.x + 1, c.y + 1);
	return mix(mix(a, b, f.x), mix(d, e, f.x), f.y);
}

vec3 boltLight(vec3 p)
{
	vec3 light = vec3(0.0);
	int count = int(MapInfo.w);
	for (int i = 0; i < 8; i++)
	{
		if (i >= count)
			break;
		float k = clamp(1.0 - distance(p, BoltPos[i].xyz) / max(BoltColor[i].a, 1.0), 0.0, 1.0);
		light += BoltColor[i].rgb * BoltPos[i].w * k * k * 4.0;
	}
	return light;
}

void main()
{
	if (texCoord.x < 0.0 || texCoord.x > 1.0 || texCoord.y < 0.0 || texCoord.y > 1.0)
		discard;

	float d = texture(DepthSampler, texCoord).r;
	// Sky = the cleared value only. 26.2's depth is reversed (near 1, far 0) and falls roughly as
	// near / distance, so geometry beyond ~500 blocks (far clouds, distant terrain) already stores
	// less than 0.0001 -- the terrain-shadow threshold would call it sky.
	bool sky = d <= 1.0e-7;
	float ndcX = texCoord.x * 2.0 - 1.0;
	float ndcY = texCoord.y * 2.0 - 1.0;
	float ndcZ = sky ? 0.5 : d; // sky: any finite depth gives the ray direction
	// GLSL indexes M[column][row]: P22=M[2][2], P23=M[3][2], P32=M[2][3], P33=M[3][3].
	float zv = (ProjMat[3][2] - ndcZ * ProjMat[3][3]) / (ndcZ * ProjMat[2][3] - ProjMat[2][2]);
	float clipW = ProjMat[2][3] * zv + ProjMat[3][3];
	vec3 viewPos = vec3(ndcX * clipW / ProjMat[0][0], ndcY * clipW / ProjMat[1][1], zv);
	vec3 world = (inverse(ModelViewMat) * vec4(viewPos, 1.0)).xyz;

	vec3 cam = CameraCell.xyz;
	vec3 ray = world - cam;
	float sceneDist = length(ray);
	vec3 dir = ray / max(sceneDist, 0.0001);
	float maxDist = FogParams.y;
	if (FogParams.w > 0.5 && FogParams.w < 1.5)
	{
		// Depth-convention check on a captured frame: sky blue, geometry grey by distance
		// (black at the camera, white at 3000 blocks), so far clouds and sky are told apart.
		fragColor = sky ? vec4(0.2, 0.4, 1.0, 1.0) : vec4(vec3(clamp(sceneDist / 3000.0, 0.0, 1.0)), 1.0);
		return;
	}
	float dist = sky ? maxDist : min(sceneDist, maxDist);
	float stepLen = dist / float(STEPS);
	// Interleaved gradient noise: breaks up the step banding.
	float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));

	float transmittance = 1.0;
	vec3 light = vec3(0.0);
	for (int s = 0; s < STEPS; s++)
	{
		vec3 p = cam + dir * ((float(s) + jitter) * stepLen);
		if (p.y > FogParams.x)
			continue; // above the storm clouds' base: no storm fog
		float cover = coverageAt(p.xz);
		if (cover <= 0.0)
			continue;
		float a = 1.0 - exp(-cover * FogParams.z * stepLen);
		light += transmittance * a * (FogColor.rgb + boltLight(p));
		transmittance *= 1.0 - a;
	}
	if (FogParams.w > 1.5)
	{
		fragColor = vec4(1.0 - transmittance, coverageAt(cam.xz), 0.0, 1.0);
		return;
	}
	// The original's fog darkens and softens but never fully occludes the storm cloud.
	float alpha = clamp((1.0 - transmittance) * FogColor.a, 0.0, 0.8);
	if (alpha <= 0.001)
		discard;
	fragColor = vec4(light / max(1.0 - transmittance, 0.0001), alpha);
}
