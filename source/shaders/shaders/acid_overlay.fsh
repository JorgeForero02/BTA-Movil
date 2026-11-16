#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;

in vec2 TexCoord;

uniform float uCurrentTick;
uniform vec4  uColor;
uniform float uAlphaTest;
uniform vec2  uViewPortSize;

// https://www.shadertoy.com/view/4lcBWn
float cro(in vec2 a, in vec2 b ) { return a.x*b.y - a.y*b.x; }

float sdUnevenCapsule( in vec2 p, in vec2 pa, in vec2 pb, in float ra, in float rb )
{
    p  -= pa;
    pb -= pa;
    float h = dot(pb,pb);
    vec2  q = vec2( dot(p,vec2(pb.y,-pb.x)), dot(p,pb) )/h;

    q.x = abs(q.x);

    float b = ra-rb;
    vec2  c = vec2(sqrt(h-b*b),b);

    float k = cro(c,q);
    float m = dot(c,q);
    float n = dot(q,q);

         if( k < 0.0 ) return sqrt(h*(n            )) - ra;
    else if( k > c.x ) return sqrt(h*(n+1.0-2.0*q.y)) - rb;
                       return m                       - ra;
}

void main() {
    // Vignette
    vec2 centerCoords = TexCoord - 0.5;
    float dist = length(centerCoords);

    float minVignette = 0.04;
    float vignetteRamp = smoothstep(0.25, 0.6, dist) * 0.6;
    float vignetteAlpha = minVignette + vignetteRamp;

    // Droplets
    float pixelScale = 128.0;
    vec2 dropUV = floor(TexCoord * pixelScale) / pixelScale;

    float cols = 32.0;
    float streamIndex = floor(dropUV.x * cols);
    float localX = fract(dropUV.x * cols) - 0.5;
    float r1 = fract(sin(dot(vec2(streamIndex), vec2(12.9898, 78.233))) * 43758.5453);
    float r2 = fract(sin(dot(vec2(streamIndex), vec2(39.346, 14.653))) * 28412.123);
    float speed = 0.3 + 0.3 * r1;

    float rawY = fract(dropUV.y - 0.003 * uCurrentTick * speed + r2);
    float dropHeight = 0.06 + 0.06 * r2;
    float t = (rawY - (0.5 - dropHeight * 0.5)) / dropHeight;

    float drop = 0.0;
    float core = 0.0;

    if (t > 0.0 && t < 1.0) {
        float distX = abs(localX);
        float w = 0.0;

        if (t > 0.4 && t < 0.9) {
            w = 0.3;

            core = step(distX, 0.15);
        } else if (t > 0.1 && t <= 0.9) {
            w = 0.15;
        }
        drop = step(distX, w);
    }

    // Colors
    vec3 baseColor = vec3(0.85, 1.0, 0.25);
    vec3 dripOuter = vec3(0.65, 0.85, 0.15);
    vec3 dripInner = vec3(0.95, 1.0, 0.25);
    vec3 dropCol = mix(dripOuter, dripInner, core);
    vec3 col = mix(baseColor, dropCol, drop);
    float distSq = dot(centerCoords, centerCoords);
    float dripAlpha = 0.08 * drop * (1.0 + distSq * 2.0);
    float finalAlpha = uColor.a * (vignetteAlpha + dripAlpha);
    FragColor = vec4(col, finalAlpha);
}
