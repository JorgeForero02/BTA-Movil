#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

#define MASK_ITALIC 0x01u
#define MASK_UNDERLINE 0x02u
#define MASK_STRIKETHROUGH 0x04u
#define MASK_BOLD 0x08u
#define MASK_OBFUSCATED 0x10u
#define MASK_OUTLINE 0x20u
#define MASK_SHADOW 0x80u

layout (location = 0) in vec2 aMesh;
layout (location = 1) in vec2 aPos;
layout (location = 2) in vec2 aSize;
layout (location = 3) in vec4 aColor;
layout (location = 4) in vec2 aUV;
layout (location = 5) in float aConfig;
layout (location = 6) in float aLightmap;

out vec4 Color;
out vec2 UV;
out vec2 TexCoord;
out vec2 Lightmap;
out vec2 Size;
out float Config;

uniform mat4 model;
layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform float uZ;

vec2 unpackLightCoord() {
    int asInt = int(aLightmap);
    return vec2((float(asInt & 0xF) + 0.5)/16.0, (float((asInt & 0xF0) >> 4) + 0.5)/16.0);
}

void main() {
    vec2 bSize = aSize/2.0;
    vec4 bColor = aColor;
    vec2 bUV = aUV/256.0;
    vec2 bLightmap = unpackLightCoord();
    float bConfig = aConfig;

    UV = bUV;
    Size = bSize;
    Config = bConfig;
    Color = bColor;
    Lightmap = bLightmap;

    // shiftx, exX, exY derived from mesh vertices
    float shiftX = ((aMesh.y * -2.0) + 1.0);
    vec2 ex = (aMesh * 3.0) - vec2(1.0, 1.0);

    // 8/256 is integer division (= 0), kept as int expression
    vec2 shift = vec2(float(uint(bConfig) & MASK_ITALIC) * shiftX + float(8/256), 0.0);
    TexCoord = bUV + ((aMesh * aSize + ex)/128.0);
    gl_Position = matrices.projection * matrices.view * model * vec4(aPos + aSize * aMesh + ex + shift, uZ, 1.0);
}
