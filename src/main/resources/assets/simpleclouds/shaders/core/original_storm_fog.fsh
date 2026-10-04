#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D DepthSampler;
uniform sampler2D ShadowMap;
uniform sampler2D ShadowMapColor;
layout(std140) uniform OriginalStormFog {
	mat4 InverseWorldProjMat;
	mat4 InverseWorldViewMat;
	mat4 ShadowProjMat;
	mat4 ShadowModelViewMat;
	vec4 CameraBolts;
	vec4 FogParams; // start, end, debug, unused
	vec4 ColorModulator;
	vec4 Lightning[16]; // world xyz, alpha; same original SSBO record
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 screenToWorld(vec2 uv, float depth)
{
	// 26.3 reversed-Z stores NDC depth directly; do NOT apply legacy 2*d-1.
	vec4 view = InverseWorldProjMat * vec4(uv * 2.0 - 1.0, depth, 1.0);
	view /= view.w;
	return (InverseWorldViewMat * view).xyz;
}
vec4 shadowMapColorAt(vec3 pos)
{
	vec4 projected = ShadowProjMat * ShadowModelViewMat * vec4(pos, 1.0);
	// Zero-to-one shadow projection (see OriginalStormShadowTransform): depth is NDC z.
	vec3 ndc = projected.xyz / projected.w;
	vec3 coord = vec3(ndc.xy * 0.5 + 0.5, ndc.z);
	float depth = texture(ShadowMap, coord.xy).r;
	if (depth < 1.0 && depth < coord.z)
		return vec4(texture(ShadowMapColor, coord.xy).rgb, 1.0);
	return vec4(0.0);
}
float lightningModifier(vec3 pos)
{
	for (int i = 0; i < int(CameraBolts.w); i++)
	{
		float dist = distance(Lightning[i].xz, pos.xz);
		if (dist < 2000.0)
			return 1.0 + Lightning[i].w * clamp(2.0 - dist * 0.001, 0.0, 1.0);
	}
	return 1.0;
}
void main()
{
	float depth = texture(DepthSampler, texCoord).r;
	bool sky = depth <= 1.0e-7;
	vec3 ray = screenToWorld(texCoord, sky ? 0.5 : depth) - CameraBolts.xyz;
	float sceneDepth = sky ? 1.0e20 : length(ray);
	vec3 rayDir = normalize(ray);
	vec3 point = CameraBolts.xyz + rayDir;
	float density = 0.0;
	vec3 colorAccum = vec3(0.0);
	float fogSteps = 0.0;
	vec3 firstHit = vec3(0.0);
	for (int i = 0; i < 200; i++)
	{
		// Forge 1.20.1 quadratic spacing, thresholds, fades and accumulation.
		point = CameraBolts.xyz + rayDir * 0.2 * pow(float(i), 2.0);
		float rayDepth = distance(point, CameraBolts.xyz);
		if (sceneDepth < rayDepth) break;
		vec4 col = shadowMapColorAt(point);
		if (col.a > 0.0 && col.r <= 0.7 && col.g <= 0.7 && col.b <= 0.7)
		{
			if (fogSteps == 0.0) firstHit = point;
			float fadeFactor = 1.0;
			if (rayDepth > FogParams.x)
				fadeFactor = 1.0 - min(pow((rayDepth - FogParams.x) / max(FogParams.y - FogParams.x, 0.0001), 0.05), 1.0);
			fadeFactor *= clamp(1.0 + point.y / 400.0, 0.0, 1.0);
			density += rayDepth * 0.0001 * fadeFactor;
			fogSteps += 1.0;
			colorAccum += col.rgb * vec3(0.6, 0.6, 0.8) * ColorModulator.rgb;
		}
		if (density >= 1.0) break;
	}
	if (FogParams.z > 0.5 && FogParams.z < 1.5)
	{
		fragColor = sky ? vec4(0.2,0.4,1.0,1.0) : vec4(vec3(clamp(sceneDepth/3000.0,0.0,1.0)),1.0);
		return;
	}
	if (FogParams.z > 3.5)
	{
		// Dev debug 4: shadow-map lookup at the first fogged point (r,g = xy, b = depth).
		vec4 p = ShadowProjMat * ShadowModelViewMat * vec4(firstHit, 1.0);
		fragColor = fogSteps > 0.0 ? vec4((p.xy / p.w) * 0.5 + 0.5, p.z / p.w, 1.0) : vec4(0.0, 0.0, 0.0, 1.0);
		return;
	}
	if (FogParams.z > 2.5)
	{
		// Dev debug 3: first fogged point, r = altitude above y 0 / 2500, g = distance / 8000.
		fragColor = fogSteps > 0.0
			? vec4(clamp(firstHit.y / 2500.0, 0.0, 1.0), clamp(distance(firstHit, CameraBolts.xyz) / 8000.0, 0.0, 1.0), 0.0, 1.0)
			: vec4(0.0, 0.0, 0.3, 1.0);
		return;
	}
	if (FogParams.z > 1.5) { fragColor = vec4(min(density,1.0),0.0,0.0,1.0); return; }
	if (fogSteps <= 0.0) { fragColor = vec4(0.0); return; }
	fragColor = vec4(colorAccum / fogSteps * lightningModifier(point), min(density,1.0));
}
