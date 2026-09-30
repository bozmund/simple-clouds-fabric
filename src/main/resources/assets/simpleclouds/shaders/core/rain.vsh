#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Geometry/UVs use the original precipitation rotation, collision and animation.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 0) out vec2 uv;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    uv = UV0;
}
