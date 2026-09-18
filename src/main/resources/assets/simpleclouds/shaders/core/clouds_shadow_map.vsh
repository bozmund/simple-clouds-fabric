#version 430
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec3 Position;

#include <simpleclouds:opaque.glsl>

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out float height;

void main() 
{
	SideInfo info = sides.data[gl_InstanceID];
	
	vec4 transformedPos = vec4(Position, 1.0) * transformations[uint(info.side)];
	vec3 sideOffset = vec3(info.x, info.y, info.z);
	vec4 finalPos = vec4(transformedPos.xyz * info.radius + sideOffset, 1.0);
	height = finalPos.y;
    gl_Position = ProjMat * ModelViewMat * finalPos;
	
	vertexColor = vec4(vec3(info.brightness), 1.0);
}
