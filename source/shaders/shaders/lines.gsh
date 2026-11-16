#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout (lines) in;
layout (triangle_strip, max_vertices = 4) out;

in vec4 vColor[];

out vec4 Color;

uniform vec2 uViewPortSize;
uniform float uLineWidth;

void main()
{
    vec4 p1 = gl_in[0].gl_Position;
    vec4 p2 = gl_in[1].gl_Position;

    vec2 dir    = normalize((p2.xy/p2.w - p1.xy/p1.w) * uViewPortSize);
    vec2 offset = vec2(-dir.y, dir.x) * uLineWidth / uViewPortSize;

    Color = vColor[0];
    gl_Position = p1 + vec4(offset.xy * p1.w, 0.0, 0.0);
    EmitVertex();

    Color = vColor[0];
    gl_Position = p1 - vec4(offset.xy * p1.w, 0.0, 0.0);
    EmitVertex();

    Color = vColor[1];
    gl_Position = p2 + vec4(offset.xy * p2.w, 0.0, 0.0);
    EmitVertex();

    Color = vColor[1];
    gl_Position = p2 - vec4(offset.xy * p2.w, 0.0, 0.0);
    EmitVertex();

    EndPrimitive();
}
