#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;

uniform float uAlphaTest;

in vec4 Color;
in vec2 TexCoord;

uniform vec4 uColor;
uniform sampler2D colortex;

void main() {
    FragColor = texture(colortex, TexCoord) * Color * uColor;
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
