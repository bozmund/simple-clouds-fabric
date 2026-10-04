#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Original indexed cube instancing with modern per-instance attributes instead
// of an SSBO. One compact record per cube, no expansion into six face instances.
layout(location = 0) in vec3 Position;
layout(location = 2) in vec3 SidePos;
layout(location = 3) in float Radius;
layout(location = 4) in float Brightness;
layout(location = 5) in float Alpha;

layout(std140) uniform CloudShading {
	vec3 DarknessColorModifier;
	float UseNormals;
};

// Step 5: per-chunk scroll offset (same block as clouds.vsh; layout must match).
layout(std140) uniform CloudOffset {
	vec3 Offset;
	float _pad;
};

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out float fogDistance;
layout(location = 2) out float vertexDistance;

#include <simpleclouds:cloud_cell_clip.glsl>

void main()
{
	vec3 transformedPos = Position * Radius + SidePos + Offset;
	vec4 finalPos = vec4(transformedPos, 1.0);
	gl_Position = ProjMat * ModelViewMat * finalPos;
	fogDistance = length((ModelViewMat * finalPos).xz);
	vertexDistance = length((ModelViewMat * finalPos).xyz);

	vertexColor = vec4(mix(DarknessColorModifier, vec3(1.0), Brightness), Alpha);
	if (!cloudCellInsideClip(SidePos.xz)) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
