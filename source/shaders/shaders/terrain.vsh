#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in vec2 aUV;
layout (location = 4) in vec2 aLightShade;

uniform mat4 model;
layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform mat4 normalTransform;
uniform vec4 lightModelAmbient;
uniform vec4 lightSourcePosition1;
uniform vec4 lightSourcePosition2;
uniform vec4 lightSourceDiffuse1;
uniform vec4 lightSourceDiffuse2;

out vec4 Color;
out float Shade;
out vec2 TexCoord;
out vec2 LightMapCoord;
out vec3 Light;
out vec4 Norm;

vec2 unpackLightCoord(vec2 lightShade) {
    int asInt = int(lightShade.y);
    return vec2((float(asInt & 0xF) + 0.5) / 16.0, (float((asInt & 0xF0) >> 4) + 0.5) / 16.0);
}

float unpackShade(vec2 lightShade) {
    return lightShade.x / 256.0;
}

void main() {
    Norm = normalize(normalTransform * model * vec4(aNormal, 0.0));
    float dir1 = max(0.0, dot(Norm, lightSourcePosition1));
    float dir2 = max(0.0, dot(Norm, lightSourcePosition2));
    // Original uses Diffuse1 for both lights; preserved
    Light = (lightModelAmbient + lightSourceDiffuse1 * (dir1 + dir2)).xyz;

    Color = vec4(aColor, 1.0);
    Shade = unpackShade(aLightShade);
    TexCoord = aUV;
    LightMapCoord = unpackLightCoord(aLightShade);
    gl_Position = matrices.projection * matrices.view * model * vec4(aPos, 1.0);
}
