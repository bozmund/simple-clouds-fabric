#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D OldScene;
uniform sampler2D NewScene;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    fragColor = mix(texture(OldScene, texCoord), texture(NewScene, texCoord), clamp(ColorModulator.a, 0.0, 1.0));
}
