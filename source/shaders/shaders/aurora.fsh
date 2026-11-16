#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;

in vec4 Color;
in vec2 TexCoord;

uniform float uAlphaTest;
uniform vec4 uColor;

layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

uniform sampler2D colortex;


void main() {
    vec4 texColor = texture(colortex, TexCoord);

    vec4 oColor = texColor * Color * uColor;
    vec4 litColor = vec4(oColor.rgb, oColor.a);

    FragColor = litColor;
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
