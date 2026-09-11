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

out vec4 vertexColor;
out float fogDistance;

// GLSL ES 3.0: no C-style array init of the per-face transforms (see clouds.vsh).
vec3 applySideTransform(vec3 p, int side)
{
	if (side == 0) return p;
	if (side == 1) return vec3(-p.x, p.y, p.z);
	if (side == 2) return vec3(p.y, -p.x, p.z);
	if (side == 3) return vec3(p.y, p.x, p.z);
	if (side == 4) return vec3(p.z, p.y, -p.x);
	return vec3(-p.z, p.y, p.x);
}

void main()
{
	int side = int(Side);

	vec3 transformedPos = applySideTransform(Position, side) * Radius + SidePos;
	vec4 finalPos = vec4(transformedPos, 1.0);
	gl_Position = ProjMat * ModelViewMat * finalPos;
	fogDistance = length((ModelViewMat * finalPos).xz);
	// (The 1.20.1 vsh also out vertexDistance for the weighted-blend weight; the
	// 26.2 slice blends with standard alpha, so it is dropped.)

	vertexColor = vec4(mix(DarknessColorModifier, vec3(1.0), Brightness), Alpha);
}
