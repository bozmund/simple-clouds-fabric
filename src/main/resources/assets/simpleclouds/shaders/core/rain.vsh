#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom rain (26.2 slice of the 1.20.1 PrecipitationQuad renderer): one
// camera-facing quad per drop. Each vertex carries the drop data inline
// (no instancing) so a single dynamic buffer + index buffer draws everything.
layout(location = 0) in vec3 DropPos;      // drop anchor (world space, top of the drop)
layout(location = 1) in float Corner;      // -0.5 or +0.5 (quad x)
layout(location = 2) in float QuadV;       // 0 (top) .. 1 (bottom of the drop)
layout(location = 3) in float Length;      // drop length in blocks (raycast/heightmap clamped to 32)
layout(location = 4) in float Width;       // drop width in blocks (rain intensity * 2, with fade ramp)
layout(location = 5) in float UVOffset;    // scrolling v offset (texture scroll = falling motion)

layout(location = 0) out vec2 uv;

void main()
{
	// Camera right in world space = row 0 of the view matrix (horizontal: the camera never rolls).
	vec3 camRight = vec3(ModelViewMat[0][0], ModelViewMat[1][0], ModelViewMat[2][0]);

	// A rain streak is vertical in the WORLD and only turns about the vertical axis to face
	// the camera - the 1.20.1 original billboards yaw-only (its quad's long axis is -Y in a
	// pose stack rotated about Y). Extending it along the camera's up axis instead made the
	// rain fall sideways as soon as the player looked up. camRight is horizontal for any
	// unrolled camera, so it stays the width axis.
	vec3 worldPos = DropPos
			+ camRight * (Corner * Width)
			+ vec3(0.0, -1.0, 0.0) * (QuadV * Length);

	gl_Position = ProjMat * ModelViewMat * vec4(worldPos, 1.0);
	// The falling motion IS the texture scrolling down the streak: the drop itself never
	// moves (as in the 1.20.1 PrecipitationQuad, whose bottom vertex is uv.y = length/10 +
	// vOffset). UVOffset already carries vOffset + QuadV * Length * 0.1 from the Java side,
	// so using QuadV here instead left every streak frozen - rain that hung in the air.
	uv = vec2(Corner + 0.5, UVOffset);
}
