#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;

in vec4 Color;

void main() {
    FragColor = Color;
}
