#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec2 aUV;

out vec4 Color;
out vec2 TexCoord;

uniform float xOffset;
uniform float zOffset;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform mat4 model;

void main() {
    Color = aColor;

    float xInt = floor(xOffset);
    float zInt = floor(zOffset);
    float xFrac = fract(xOffset);
    float zFrac = fract(zOffset);

    // explicit float literal: GLSL ES has no implicit int-to-float conversion
    TexCoord = aUV + vec2(
        xInt * 1.0 / 256.0,
        zInt * 1.0 / 256.0
    );

    vec3 pos = aPos;
    pos.x -= xFrac;
    pos.z -= zFrac;

    gl_Position = matrices.projection * matrices.view * model * vec4(pos, 1.0);
}
