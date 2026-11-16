#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aPosO;
layout (location = 2) in vec4 aColor;
layout (location = 3) in vec4 aColorO;
layout (location = 4) in vec2 aUV;

out vec4 Color;
out vec2 TexCoord;
out vec2 LightMapCoord; // declared but unwritten in original; kept for interface matching

uniform mat4 model;
uniform float uPartialTick;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

void main() {
    gl_Position = matrices.projection * matrices.view * model * vec4(mix(aPosO, aPos, uPartialTick), 1.0);

    TexCoord = aUV;
    Color = mix(aColorO, aColor, uPartialTick);
}
