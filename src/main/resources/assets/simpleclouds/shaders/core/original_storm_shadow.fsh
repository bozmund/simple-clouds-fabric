#version 330
#extension GL_ARB_separate_shader_objects : require

// Forge 1.20.1 storm_fog_shadow_map: retain only low, dark storm geometry.
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float height;
layout(location = 0) out vec4 fragColor;
void main()
{
	if (height > 32.0 || vertexColor.a < 0.1 ||
		vertexColor.r > 0.7 || vertexColor.g > 0.7 || vertexColor.b > 0.7)
		discard;
	fragColor = vertexColor;
}
