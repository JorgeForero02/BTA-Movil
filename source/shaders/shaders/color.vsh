#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec4 aColor;

out vec4 Color;

uniform mat4 model;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

void main() {
    gl_Position = matrices.projection * matrices.view * model * vec4(aPos, 1.0);
    Color = aColor;
}
