#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout (location = 0) in vec2 aMesh;
layout (location = 1) in vec3 aPos;
layout (location = 2) in float aScale;
layout (location = 3) in vec4 aColor;
layout (location = 4) in vec2 aUv;
layout (location = 5) in vec2 aUvSizes;
layout (location = 6) in float aLightmap;

uniform mat4 model;
layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

out vec4 Color;
out vec2 TexCoord;
out vec2 LightMapCoord;

vec2 unpackLightCoord() {
    int asInt = int(aLightmap);
    return vec2((float(asInt & 0xF) + 0.5) / 16.0, (float((asInt & 0xF0) >> 4) + 0.5) / 16.0);
}

void main() {
    Color = aColor;
    LightMapCoord = unpackLightCoord();

    TexCoord = aUv + (((aMesh * vec2(1.0, -1.0)) + vec2(1.0, 1.0)) / 2.0) * aUvSizes;
    gl_Position = matrices.projection * matrices.view * model * (vec4(aPos, 1.0) + matrices.viewInv * vec4(aMesh, 0.0, 0.0) * aScale);
}
