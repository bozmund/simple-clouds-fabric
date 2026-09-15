#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D OldScene;
uniform sampler2D NewScene;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    fragColor = mix(texture(OldScene, texCoord), texture(NewScene, texCoord), clamp(ColorModulator.a, 0.0, 1.0));
}
