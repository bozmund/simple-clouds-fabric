#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D DiffuseSampler;
layout(std140) uniform OriginalBlur { vec4 BlurStep; };
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main()
{
	// Original box_blur.fsh, with its effective radius 10 in all six passes.
	vec4 blurred = vec4(0.0);
	float actualRadius = round(BlurStep.z);
	for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0)
		blurred += texture(DiffuseSampler, texCoord + BlurStep.xy * a);
	blurred += texture(DiffuseSampler, texCoord + BlurStep.xy * actualRadius) / 2.0;
	fragColor = blurred / (actualRadius + 0.5);
}
