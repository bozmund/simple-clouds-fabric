#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
layout(std140) uniform RainPass {
    float RainAlpha;
    vec3 CameraPosition;
};

// Geometry/UVs use the original precipitation rotation, collision and animation.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in ivec2 UV2;
uniform sampler2D Sampler2;
layout(location = 0) out vec2 uv;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out float sphericalDistance;
layout(location = 3) out float cylindricalDistance;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    uv = UV0;
    vertexColor = sample_lightmap(Sampler2, UV2);
    // Original PARTICLE Position was camera-relative before view rotation.
    // Our batch is absolute-world, so subtract the camera for fog distances.
    vec3 relativePosition = Position - CameraPosition;
    sphericalDistance = length(relativePosition);
    cylindricalDistance = max(length(relativePosition.xz), abs(relativePosition.y));
}
