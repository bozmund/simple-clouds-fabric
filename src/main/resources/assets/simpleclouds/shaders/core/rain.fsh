#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom rain fragment: the vanilla rain streak texture (white on transparent),
// alpha-blended. A narrow center u-strip per drop (as in the 1.20.1 quad: the
// width maps to u 0.5+-width/4) keeps individual streaks thin.
uniform sampler2D RainTexture;

layout(std140) uniform RainPass {
	float RainAlpha;
};

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 fragColor;

void main()
{
	vec4 tex = texture(RainTexture, uv);
	fragColor = vec4(tex.rgb, tex.a * RainAlpha);
}
