#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

#define FOG
#define RADIAL_FOG

in vec4 Color;
in vec2 TexCoord;

out vec4 FragColor;

layout (std140) uniform FogState {
	vec4 color;
	int mode;
	float start;
	float end;
	float density;
} fogState;

layout (std140) uniform Matrices {
	mat4 projection;
	mat4 projectionInv;
	mat4 view;
	mat4 viewInv;
} matrices;

uniform float uAlphaTest;
uniform int isEyeInLiquid;
uniform vec2 uViewPortSize;
uniform vec4 uColor;

uniform sampler2D colortex;
uniform sampler2D lighttex;

// NOTE: original function 'distance()' collides with GLSL ES builtin distance();
// overloading builtins is forbidden in GLSL ES, so this internal (non-uniform) function was renamed.
float fragDistance() {
	vec4 fragPos = matrices.viewInv * matrices.projectionInv * vec4((gl_FragCoord.xy / uViewPortSize) * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);
	fragPos /= fragPos.w;

	return length(fragPos.xz);
}

void main() {
	vec4 tex = texture(colortex, TexCoord);
	
	if(tex.a < uAlphaTest) {
		discard;
	}
	
	// Hide clouds when in water or lava
	if(isEyeInLiquid != 0) {
		discard;
	}
	
	vec4 col = Color * uColor;

	float fogStrength = 0.0;

	float end = (fogState.end * 1.5);
	float start = fogState.start / (fogState.end * 1.5);

	fogStrength = fragDistance() / end;
	fogStrength -= start;
	fogStrength /= (1.0 - start);
	fogStrength = clamp(fogStrength, 0.0, 1.0);
	col.a *= 1.0 - fogStrength;
	
	FragColor = tex * col;
}
