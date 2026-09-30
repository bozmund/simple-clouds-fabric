#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Per-vertex: base quad corner.
layout(location = 0) in vec3 Position;
// Per-instance (step rate 1): one cloud face.
layout(location = 1) in float Side;
layout(location = 2) in vec3 SidePos;
layout(location = 3) in float Radius;
layout(location = 4) in float Brightness;

layout(std140) uniform CloudLighting {
	vec3 Light0_Direction;
	vec3 Light1_Direction;
	float LightPower;
	float AmbientLight;
};

layout(std140) uniform CloudShading {
	vec3 DarknessColorModifier;
	float UseNormals;
};

// Step 5: per-chunk scroll offset. Each chunk's mesh is generated at a snapshot
// of the scroll drift (genScroll); drawing it at grid position + (scroll -
// genScroll) makes the whole field follow the drift continuously instead of
// jumping SCROLL_REGEN_THRESHOLD (8 cloud units) per chunk. The sampling is
// rigid in world space (sampleLayer: px = (x + scrollX)/scaleX for every layer),
// so one offset is exact for all layers. Regeneration only resets the phase
// anchor to the identical content, so it is invisible.
layout(std140) uniform CloudOffset {
	vec3 Offset;
	float _pad;
};

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out float fogDistance;

// 26.2 (GLSL ES 3.0 / Vulkan): no C-style array initialization of constructors,
// so face normals and face transforms are provided by functions.
vec3 sideNormal(int side)
{
	if (side == 0) return vec3(-1.0, 0.0, 0.0);
	if (side == 1) return vec3(1.0, 0.0, 0.0);
	if (side == 2) return vec3(0.0, -1.0, 0.0);
	if (side == 3) return vec3(0.0, 1.0, 0.0);
	if (side == 4) return vec3(0.0, 0.0, -1.0);
	return vec3(0.0, 0.0, 1.0);
}

#include <simpleclouds:cloud_faces.glsl>
#include <simpleclouds:cloud_cell_clip.glsl>

vec4 mixLight(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color)
{
	lightDir0 = normalize(lightDir0);
	lightDir1 = normalize(lightDir1);
	float light0 = max(0.0, dot(lightDir0, normal));
	float light1 = max(0.0, dot(lightDir1, normal));
	float lightAccum = min(1.0, (light0 + light1) * LightPower + AmbientLight);
	return vec4(vec3(color.r * lightAccum, color.g * lightAccum, color.b), color.a);
}

void main()
{
	int side = int(Side);

	vec3 transformedPos = applySideTransform(Position, side) * Radius + SidePos + Offset;
	vec4 finalPos = vec4(transformedPos, 1.0);
	gl_Position = ProjMat * ModelViewMat * finalPos;
	fogDistance = length((ModelViewMat * finalPos).xz);

	vec4 finalCol = vec4(mix(DarknessColorModifier, vec3(1.0), Brightness), 1.0);
	if (UseNormals > 0.5)
	{
		vec3 normal = sideNormal(side);
		vertexColor = mixLight(Light0_Direction, Light1_Direction, normal, finalCol);
	}
	else
	{
		vertexColor = finalCol;
	}
	if (!cloudCellInsideClip(SidePos.xz)) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
