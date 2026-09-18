#version 150
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec4 Position;

uniform mat4 ProjMat;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform vec2 BlurDir;

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec2 sampleStep;

void main() 
{
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    vec2 oneTexel = 1.0 / InSize;
    sampleStep = oneTexel * BlurDir;

    texCoord = Position.xy / OutSize;
}
