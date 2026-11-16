#version 320 es
precision highp float;
precision highp int;

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in uint aFaces;

out vec3 vColor;
flat out uint vFaces;

void main() {
    gl_Position = vec4(aPos, 1.0);

    vColor = aColor;
    vFaces = aFaces;
}
