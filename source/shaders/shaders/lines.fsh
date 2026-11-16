#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;

uniform float uAlphaTest;

in vec4 Color;
uniform vec4 uColor;

void main() {
    FragColor = Color * uColor;
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
