#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// 26.2 port of clouds_transparency.vsh (1.20.1 used an SSBO of TransparentCubeInfo;
// here the per-instance attributes replace sides.data[gl_InstanceID], exactly as in
// the opaque clouds.vsh port). Transparent voxels emit all six faces (no neighbor
// culling, as in the original createTransparentCube).
in vec3 Position;
in float Side;
in vec3 SidePos;
in float Radius;
in float Brightness;
in float Alpha;

layout(std140) uniform CloudShading {
	vec3 DarknessColorModifier;
	float UseNormals;
};

// Step 5: per-chunk scroll offset (same block as clouds.vsh; layout must match).
layout(std140) uniform CloudOffset {
	vec3 Offset;
	float _pad;
};

out vec4 vertexColor;
out float fogDistance;

// GLSL ES 3.0: no C-style array init of the per-face transforms (see clouds.vsh).
#moj_import <simpleclouds:cloud_faces.glsl>

void main()
{
	int side = int(Side);

	vec3 transformedPos = applySideTransform(Position, side) * Radius + SidePos + Offset;
	vec4 finalPos = vec4(transformedPos, 1.0);
	gl_Position = ProjMat * ModelViewMat * finalPos;
	fogDistance = length((ModelViewMat * finalPos).xz);
	// (The 1.20.1 vsh also out vertexDistance for the weighted-blend weight; the
	// 26.2 slice blends with standard alpha, so it is dropped.)

	vertexColor = vec4(mix(DarknessColorModifier, vec3(1.0), Brightness), Alpha);
}
