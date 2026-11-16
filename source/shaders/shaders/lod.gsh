#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout (points) in;
layout (triangle_strip, max_vertices = 36) out;

in vec3 vColor[];
flat in uint vFaces[];

out vec4 Color;

uniform mat4 model;
layout (std140) uniform Matrices {
    mat4 projection;
    mat4 projectionInv;
    mat4 view;
    mat4 viewInv;
} matrices;

#define FACES_MASK_BOTTOM 0x01u
#define FACES_MASK_TOP 0x02u
#define FACES_MASK_NORTH 0x04u
#define FACES_MASK_SOUTH 0x08u
#define FACES_MASK_WEST 0x10u
#define FACES_MASK_EAST 0x20u

/*
 * for north/south faces:
 *   Y  A  B
 *   ^  C  D
 *      >  X
 * for east/west faces:
 *   Y  B  A
 *   ^  D  C
 *      X  <
 * for top/bottom faces:
 *   Z  A  B
 *   ^  C  D
 *      >  X
 * A = 0, 1
 * B = 1, 1
 * C = 0, 0
 * D = 1, 0
 */

#define Aa 0.0
#define Ab 1.0
#define Ba 1.0
#define Bb 1.0
#define Ca 0.0
#define Cb 0.0
#define Da 1.0
#define Db 0.0

#define BR_BOTTOM 0.5
#define BR_TOP 1.0
#define BR_NORTH_SOUTH 0.8
#define BR_EAST_WEST 0.6

void main() {
    uint faces = vFaces[0];
    vec3 cc = vColor[0];

    if (faces > 0u) {
        faces = 0xFFu;
    }

    if ((faces & FACES_MASK_BOTTOM) > 0u) { // min Y
        Color = vec4(cc * BR_BOTTOM, 1.0);

        // C
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Ca, 0.0, Cb, 0.0));
        EmitVertex();

        // D
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Da, 0.0, Db, 0.0));
        EmitVertex();

        // A
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Aa, 0.0, Ab, 0.0));
        EmitVertex();

        // B
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Ba, 0.0, Bb, 0.0));
        EmitVertex();

        EndPrimitive();
    }

    if ((faces & FACES_MASK_TOP) > 0u) { // max Y
        Color = vec4(cc * BR_TOP, 1.0);

        // C
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Ca, 1.0, Cb, 0.0));
        EmitVertex();

        // A
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Aa, 1.0, Ab, 0.0));
        EmitVertex();

        // D
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Da, 1.0, Db, 0.0));
        EmitVertex();

        // B
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Ba, 1.0, Bb, 0.0));
        EmitVertex();

        EndPrimitive();
    }

    if ((faces & FACES_MASK_NORTH) > 0u) { // min Z
        Color = vec4(cc * BR_NORTH_SOUTH, 1.0);

        // C
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Ca, Cb, 0.0, 0.0));
        EmitVertex();

        // A
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Aa, Ab, 0.0, 0.0));
        EmitVertex();

        // D
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Da, Db, 0.0, 0.0));
        EmitVertex();

        // B
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Ba, Bb, 0.0, 0.0));
        EmitVertex();

        EndPrimitive();
    }

    if ((faces & FACES_MASK_SOUTH) > 0u) { // max Z
        Color = vec4(cc * BR_NORTH_SOUTH, 1.0);

        // C
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Ca, Cb, 1.0, 0.0));
        EmitVertex();

        // D
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Da, Db, 1.0, 0.0));
        EmitVertex();

        // A
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Aa, Ab, 1.0, 0.0));
        EmitVertex();

        // B
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(Ba, Bb, 1.0, 0.0));
        EmitVertex();

        EndPrimitive();
    }

    if ((faces & FACES_MASK_WEST) > 0u) { // min X
        Color = vec4(cc * BR_EAST_WEST, 1.0);

        // C
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(0.0, Ca, Cb, 0.0));
        EmitVertex();

        // A
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(0.0, Aa, Ab, 0.0));
        EmitVertex();

        // D
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(0.0, Da, Db, 0.0));
        EmitVertex();

        // B
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(0.0, Ba, Bb, 0.0));
        EmitVertex();

        EndPrimitive();
    }

    if ((faces & FACES_MASK_EAST) > 0u) { // max X
        Color = vec4(cc * BR_EAST_WEST, 1.0);

        // C
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(1.0, Ca, Cb, 0.0));
        EmitVertex();

        // D
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(1.0, Da, Db, 0.0));
        EmitVertex();

        // A
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(1.0, Aa, Ab, 0.0));
        EmitVertex();

        // B
        gl_Position = matrices.projection * matrices.view * model * (gl_in[0].gl_Position + vec4(1.0, Ba, Bb, 0.0));
        EmitVertex();

        EndPrimitive();
    }
}
