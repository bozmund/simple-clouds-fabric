#version 330

// Custom rain fragment: the vanilla rain streak texture (white on transparent),
// alpha-blended. A narrow center u-strip per drop (as in the 1.20.1 quad: the
// width maps to u 0.5+-width/4) keeps individual streaks thin.
uniform sampler2D RainTexture;

layout(std140) uniform RainPass {
	float RainAlpha;
};

in vec2 uv;
out vec4 fragColor;

void main()
{
	vec4 tex = texture(RainTexture, uv);
	fragColor = vec4(tex.rgb, tex.a * RainAlpha);
}
