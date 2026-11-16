#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;

in vec4 Color;
in vec2 TexCoord;
in vec2 LightMapCoord;
in vec3 Light;

uniform float uLightMapStrength;
uniform float uAlphaTest;
uniform vec4 uColor;
uniform vec2 uViewPortSize;

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

uniform sampler2D colortex;
uniform sampler2D lighttex;

// NOTE: original function 'distance()' collides with GLSL ES built-in 'distance'.
// GLSL ES 3.x forbids redeclaring/overloading built-ins (compile error), so this
// internal helper (not looked up by Java) is renamed to fragDistance().
float fragDistance() {
    vec4 fragPos = matrices.projectionInv * vec4((gl_FragCoord.xy / uViewPortSize) * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);
    fragPos /= fragPos.w;

    return length(fragPos.xyz);
}

// Taken from https://github.com/hughsk/glsl-fog
float fogFactorLinear() {
    return 1.0 - clamp(((fogState.end - (fragDistance())) / (fogState.end - fogState.start)), 0.0, 1.0);
}

float fogFactorExp() {
    return 1.0 - clamp(exp(-fogState.density * (fragDistance())), 0.0, 1.0);
}

float fogFactorExp2() {
    float LOG2 = -1.442695;
    float d = fogState.density * (fragDistance());
    return 1.0 - clamp(exp2(d * d * LOG2), 0.0, 1.0);
}


vec4 computeFog(vec4 color) {
    float f = 0.0;
    if (fogState.mode == 0) { // Linear
        f = fogFactorLinear();
    }
    else if (fogState.mode == 1) { // EXP
        f = fogFactorExp();
    }
    else if (fogState.mode == 2) { // EXP2
        f = fogFactorExp2();
    }
    return vec4(mix(color.rgb, fogState.color.rgb, f), color.a);
}

void main() {
    vec4 lightColor = mix(vec4(1.0, 1.0, 1.0, 1.0), texture(lighttex, LightMapCoord), uLightMapStrength);
    vec4 texColor = texture(colortex, TexCoord);

    vec4 oColor = texColor * lightColor * Color * uColor;
    vec4 litColor = vec4(oColor.rgb * clamp(Light, 0.0, 1.0), oColor.a);

    vec4 fColor = computeFog(litColor);


    FragColor = fColor;
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
