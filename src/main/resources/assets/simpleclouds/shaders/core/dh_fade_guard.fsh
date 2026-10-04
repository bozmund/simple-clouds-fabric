#version 330
#extension GL_ARB_separate_shader_objects : require

// Undo Distant Horizons' vanilla fade on pixels the cloud pass covered. The clouds are drawn
// before the translucent stage (so rain and water blend over them); DH's fade runs later, at
// executeOutline, and blends LOD colour over anything at the vanilla render-distance edge,
// clouds included. SavedSampler holds the colour from just before that fade.
uniform sampler2D SavedSampler;
uniform sampler2D CloudDepthSampler;
uniform sampler2D PreCloudDepthSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main()
{
	// Reversed-Z: the cloud pass moved the depth closer only where a cloud was drawn.
	if (!(texture(CloudDepthSampler, texCoord).r > texture(PreCloudDepthSampler, texCoord).r))
		discard;
	fragColor = vec4(texture(SavedSampler, texCoord).rgb, 1.0);
}
