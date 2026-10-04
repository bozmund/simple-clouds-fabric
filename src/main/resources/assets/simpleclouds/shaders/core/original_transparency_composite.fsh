#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D AccumTexture;
uniform sampler2D RevealageTexture;
layout(location = 0) out vec4 fragColor;

// Same original resolve, factored into source-alpha blending to avoid sampling
// the framebuffer currently being written: avg*(1-r) + destination*r.
void main()
{
	ivec2 uv = ivec2(gl_FragCoord.xy);
	float revealage = texelFetch(RevealageTexture, uv, 0).r;
	if (revealage == 1.0) discard;
	vec4 accum = texelFetch(AccumTexture, uv, 0);
	vec4 absoluteAccum = abs(accum);
	if (isinf(max(max(absoluteAccum.r, absoluteAccum.g), max(absoluteAccum.b, absoluteAccum.a))))
		accum.rgb = vec3(accum.a);
	vec3 avg = accum.rgb / max(accum.a, 0.00001);
	fragColor = vec4(avg, 1.0 - revealage);
}
