#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom rain (26.2 slice of the 1.20.1 PrecipitationQuad renderer): one
// camera-facing quad per drop. Each vertex carries the drop data inline
// (no instancing) so a single dynamic buffer + index buffer draws everything.
in vec3 DropPos;      // drop anchor (world space, top of the drop)
in float Corner;      // -0.5 or +0.5 (quad x)
in float QuadV;       // 0 (top) .. 1 (bottom of the drop)
in float Length;      // drop length in blocks (raycast/heightmap clamped to 32)
in float Width;       // drop width in blocks (rain intensity * 2, with fade ramp)
in float UVOffset;    // scrolling v offset (texture scroll = falling motion)

out vec2 uv;

void main()
{
	// Camera right/up in world space = rows 0 and 1 of the view matrix.
	vec3 camRight = vec3(ModelViewMat[0][0], ModelViewMat[1][0], ModelViewMat[2][0]);
	vec3 camUp = vec3(ModelViewMat[0][1], ModelViewMat[1][1], ModelViewMat[2][1]);

	vec3 worldPos = DropPos
			+ camRight * (Corner * Width)
			+ camUp * (-QuadV * Length);

	gl_Position = ProjMat * ModelViewMat * vec4(worldPos, 1.0);
	uv = vec2(Corner + 0.5, QuadV);
}
