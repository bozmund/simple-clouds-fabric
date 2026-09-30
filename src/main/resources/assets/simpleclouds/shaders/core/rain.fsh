#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom rain fragment: the vanilla rain streak texture (white on transparent),
// alpha-blended. A narrow center u-strip per drop (as in the 1.20.1 quad: the
// width maps to u 0.5+-width/4) keeps individual streaks thin.
uniform sampler2D RainTexture;
uniform sampler2D SceneColor;

layout(std140) uniform RainPass {
	float RainAlpha;
};

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 fragColor;

void main()
{
	vec4 tex = texture(RainTexture, uv);
	// The original particle texture is translucent: fixed white loses contrast
	// over bright clouds, while a fixed dark tint vanishes under storm cover.
	// Read the scene captured before precipitation and smoothly adapt its tint.
	vec3 scene = texelFetch(SceneColor, ivec2(gl_FragCoord.xy), 0).rgb;
	float luminance = dot(scene, vec3(0.2126, 0.7152, 0.0722));
	vec3 tint = mix(vec3(0.92, 0.95, 1.0), vec3(0.42, 0.56, 0.72), smoothstep(0.22, 0.82, luminance));
	fragColor = vec4(tex.rgb * tint, tex.a * RainAlpha);
}
