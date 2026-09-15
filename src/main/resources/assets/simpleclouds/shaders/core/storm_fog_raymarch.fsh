#version 330

// Full-port storm fog (26.2 port of the 1.20.1 program/storm_fog.fsh): a
// 200-step accelerating raymarch from the camera, sampling the storm-fog shadow
// map (depth + storm colour) per step, with per-pixel scene-depth occlusion,
// distance fade, vertical fade, and PER-BOLT local lightning (the fog lights up
// around each bright bolt within 2000 blocks). This replaces the simplified
// screen-space overlay: the fog now follows the actual cloud shapes.
//
// The original's GLSL430 SSBO lightning buffer is a std140 UBO of vec4[16]
// (xyz=position, w=alpha) since 26.2 binds per-pass data as UBOs.
// Portions of the raymarch/intersect math are licensed under MIT (Inigo Quilez,
// see the 1.20.1 source).

uniform sampler2D ShadowMap;
uniform sampler2D ShadowMapColor;
uniform sampler2D DepthSampler;

layout(std140) uniform StormFogRaymarch {
	mat4 InverseWorldProjMat;   // 0
	mat4 InverseModelViewMat;   // 64
	mat4 ShadowProjMat;         // 128
	mat4 ShadowModelViewMat;    // 192
	vec4 CameraPos;             // 256 (xyz world, w pad)
	vec4 CutoffFog;             // 272 (x=CutoffDistance, y=FogStart, z=FogEnd, w=LightTransmittenceDistance)
	vec4 VertFadeColMul;        // 288 (x=VerticalFade, yzw=ColorMultiplier)
	vec4 ColorThresholdV;       // 304 (xyz threshold, w pad)
	vec4 ColorModulator;        // 320 (rgb storm colour, a=1)
	int  TotalLightningBolts;   // 336
	int  _pad0;
	int  _pad1;
	int  _pad2;
};

layout(std140) uniform LightningBolts {
	vec4 BoltData[16]; // xyz=position, w=alpha (bolt fade)
};

in vec2 texCoord;
out vec4 fragColor;

#define STEPS 200
#define BG_COL vec4(0.0)

vec3 getRayDirection(vec2 screenUV)
{
	vec2 uv = screenUV * 2.0 - 1.0;
	vec4 near = vec4(uv, 0.0, 1.0);
	vec4 far = vec4(uv, 1.0, 1.0);
	near = InverseWorldProjMat * near;
	far = InverseWorldProjMat * far;
	near.xyz /= near.w;
	far.xyz /= far.w;
	vec3 nearResult = (InverseModelViewMat * near).xyz;
	vec3 farResult = (InverseModelViewMat * far).xyz;
	return normalize(farResult - nearResult);
}

vec4 shadowMapColorAt(vec3 pos)
{
	vec4 shadowMapPos = ShadowProjMat * ShadowModelViewMat * vec4(pos, 1.0);
	vec3 ndc = shadowMapPos.xyz / shadowMapPos.w;
	vec3 coord = ndc * 0.5 + 0.5;
	float shadowMapDepth = texture(ShadowMap, coord.xy).x;
	if (shadowMapDepth < 1.0 && shadowMapDepth < coord.z)
		return vec4(texture(ShadowMapColor, coord.xy).rgb, 1.0);
	return vec4(0.0);
}

// MIT (Inigo Quilez) — vertical cylinder intersection, bounds the fog to a
// cylinder of radius CutoffFog.x around the vertical axis through the camera.
vec4 cylinderVerticalIntersect(in vec3 ro, in vec3 rd, float he, float ra)
{
	float k2 = 1.0 - rd.y * rd.y;
	float k1 = dot(ro, rd) - ro.y * rd.y;
	float k0 = dot(ro, ro) - ro.y * ro.y - ra * ra;
	float h = k1 * k1 - k2 * k0;
	if (h < 0.0) return vec4(-1.0);
	h = sqrt(h);
	float t = (-k1 - h) / k2;
	float y = ro.y + t * rd.y;
	if (y > -he && y < he) return vec4(t, (ro + t * rd - vec3(0.0, y, 0.0)) / ra);
	t = (((y < 0.0) ? -he : he) - ro.y) / rd.y;
	if (abs(k1 + k2 * t) < h) return vec4(t, vec3(0.0, sign(y), 0.0));
	return vec4(-1.0);
}

// Per-bolt local lightning: the nearest bolt within 2000 blocks lifts the fog
// brightness by bolt.Alpha * distMul. This is the original's per-bolt local
// lighting (the 26.2 slice used a global screen flash instead).
float getNearestLightningBoltColorModifier(vec3 position)
{
	for (int i = 0; i < TotalLightningBolts; i++)
	{
		vec4 bolt = BoltData[i];
		float dist = distance(bolt.xz, position.xz);
		if (dist < 2000.0)
		{
			float distMul = clamp(2.0 - dist * 0.001, 0.0, 1.0);
			return 1.0 + bolt.w * distMul;
		}
	}
	return 1.0;
}

vec3 screenToWorldPos(vec2 coord, float depth)
{
	// InverseWorldProjMat is the inverse of a STANDARD [-1,1] projection (built
	// from the camera FOV+aspect). The 26.2 main depth is z-to-1 (stored depth =
	// NDC z, near 0 / far 1), so convert it to the standard [-1,1] NDC z first:
	// standardZ = 2*z - 1.
	vec3 ndc = vec3(coord * 2.0 - 1.0, depth * 2.0 - 1.0);
	vec4 view = InverseWorldProjMat * vec4(ndc, 1.0);
	view.xyz /= view.w;
	vec3 result = (InverseModelViewMat * view).xyz;
	return result;
}

void main()
{
	if (texCoord.x < 0.0 || texCoord.x > 1.0 || texCoord.y < 0.0 || texCoord.y > 1.0)
		discard;

	float cutoffDistance = CutoffFog.x;
	float fogStart = CutoffFog.y;
	float fogEnd = CutoffFog.z;
	float verticalFade = VertFadeColMul.x;
	vec3 colorMultiplier = VertFadeColMul.yzw;
	vec3 colorThreshold = ColorThresholdV.xyz;

	vec3 rayDir = getRayDirection(texCoord);
	vec3 point = CameraPos.xyz + rayDir;
	float sceneDepth = length(screenToWorldPos(texCoord, texture(DepthSampler, texCoord).r)); // z-to-1 -> standard inside

	float density = 0.0;
	vec3 colorAccum = vec3(0.0);
	float fogSteps = 0.0;

	for (int i = 0; i < STEPS; i++)
	{
		point = CameraPos.xyz + rayDir * 0.2 * pow(float(i), 2.0);
		float rayDepth = distance(point, CameraPos.xyz);

		// Stop the ray once it passes the scene (terrain) at this pixel.
		if (sceneDepth < rayDepth)
			break;

		vec4 col = shadowMapColorAt(point);

		if (col.a > 0.0 && col.r <= colorThreshold.r && col.g <= colorThreshold.g && col.b <= colorThreshold.b)
		{
			// 0.0002 (the 1.20.1 value is 0.0001): the 26.2 raymarch accumulates over
			// fewer in-cloud steps than the original's 200-step accelerating march, so
			// a slightly higher rate keeps the fog opaque/moody instead of a light
			// translucent wash where the bright sky shows through.
			float densityAdd = rayDepth * 0.0002;
			float fadeFactor = 1.0;
			if (rayDepth > fogStart)
				fadeFactor = 1.0 - min(pow((rayDepth - fogStart) / (fogEnd - fogStart), 0.05), 1.0);
			fadeFactor *= clamp(1.0 + point.y / verticalFade, 0.0, 1.0);
			density += densityAdd * fadeFactor;
			fogSteps += 1.0;
			colorAccum += vec3(col.rgb * colorMultiplier * ColorModulator.rgb);
		}

		if (density >= 1.0)
			break;
	}

	if (fogSteps <= 0.0)
	{
		fragColor = BG_COL;
		return;
	}

	vec3 avgCol = colorAccum / fogSteps;
	vec4 finalCol = vec4(avgCol, min(density, 1.0));
	float lightningMul = getNearestLightningBoltColorModifier(point);
	finalCol.rgb *= lightningMul;
	fragColor = finalCol;
}
