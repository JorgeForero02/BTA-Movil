#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;

in vec2 TexCoords;

uniform sampler2D colortex0;
uniform sampler2D depthtex0;

// Color correction params
uniform float brightness;
uniform float contrast;
uniform float exposure;
uniform float saturation;
uniform float rMod;
uniform float gMod;
uniform float bMod;

uniform bool fxaa;
uniform float heatHaze;
uniform int bloom;

uniform int dimension;
uniform int shaderEffect;

uniform ivec2 windowSize;
uniform float frameTimeCounter;

#define PI 3.14159
#define LUMA vec3(0.299, 0.587, 0.114)

#define FXAA_REDUCE_MIN (1.0/128.0)
#define FXAA_REDUCE_MUL (1.0/8.0)
#define FXAA_SPAN_MAX vec2(8.0)

vec3 applyFXAA(vec2 uv, sampler2D tex, vec2 res)
{
	vec2 invRes = 1.0 / res;

	vec3 rgbNW = texture(tex, (uv + vec2(-0.5, -0.5)) * invRes).rgb;
	vec3 rgbNE = texture(tex, (uv + vec2( 0.5, -0.5)) * invRes).rgb;
	vec3 rgbSW = texture(tex, (uv + vec2(-0.5,  0.5)) * invRes).rgb;
	vec3 rgbSE = texture(tex, (uv + vec2( 0.5,  0.5)) * invRes).rgb;
	vec3 rgbM  = texture(tex, uv * invRes).rgb;

	float lumaNW = dot(rgbNW, LUMA);
	float lumaNE = dot(rgbNE, LUMA);
	float lumaSW = dot(rgbSW, LUMA);
	float lumaSE = dot(rgbSE, LUMA);
	float lumaM  = dot(rgbM,  LUMA);

	float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
	float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

	vec2 dir = vec2(-((lumaNW + lumaNE) - (lumaSW + lumaSE)),
					 ((lumaNW + lumaSW) - (lumaNE + lumaSE))
	);

	float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * FXAA_REDUCE_MUL), FXAA_REDUCE_MIN);
	float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);

	dir = min(FXAA_SPAN_MAX, max(-FXAA_SPAN_MAX, dir * rcpDirMin)) * invRes;

	vec3 rgbA = 0.5 * (
		texture(tex, uv * invRes + dir * -0.1666666666666667).rgb +
		texture(tex, uv * invRes + dir *  0.1666666666666667).rgb
	);
	vec3 rgbB = rgbA * 0.5 + 0.25 * (
		texture(tex, uv * invRes + dir * -0.5).rgb +
		texture(tex, uv * invRes + dir *  0.5).rgb
	);

	float lumaB = dot(rgbB, LUMA);
	if((lumaB < lumaMin) || (lumaB > lumaMax))
		return rgbA;

	return rgbB;
}

vec3 adjustBrightness(vec3 color, float value) {
    return color + value;
}

vec3 adjustContrast(vec3 color, float value) {
    return 0.5 + (1.0 + value) * (color - 0.5);
}

vec3 adjustExposure(vec3 color, float value) {
    return (1.0 + value) * color;
}

vec3 adjustSaturation(vec3 color, float value) {
    const vec3 luminosityFactor = vec3(0.2126, 0.7152, 0.0722);
    vec3 grayscale = vec3(dot(color, luminosityFactor));

    return mix(grayscale, color, 1.0 + value);
}

vec3 adjustColor(vec3 color, float rValue, float gValue, float bValue) {
    return vec3(color.r * rValue, color.g * gValue, color.b * bValue);
}

//https://www.shadertoy.com/view/WlG3zG
vec3 exp2m1(in vec3 x)
{
	return exp2(x) - 1.0;
}

vec3 iq_pow_22(vec3 x)
{
	// By iq, f(x) = (2^x-1 - x*ln2)/(1-ln2)
	return (exp2m1(x) - x * 0.693147) * 3.258891;
}

vec3 Bloom(vec2 coord)
{
	vec3 blur = vec3(0.0);

	int quality = 7 * bloom;
	float range = float(10 * (3 - bloom));

	float qh = float(quality) * 0.5 - 0.5;
	float allStrengths = 0.0;
	for(int i = 0; i < quality; i++)
	for(int j = 0; j < quality; j++)
	{
		vec2 offset = (vec2(float(i) - qh, float(j) - qh) * range) / vec2(windowSize);

		float strength = 1.33 - distance(vec2(qh), vec2(float(i), float(j))) / qh;

		blur += iq_pow_22(texture(colortex0, coord + offset).rgb) * strength;
		allStrengths += strength;
	}

	blur /= allStrengths;

	blur.rgb *= 1.0 - float(bloom) * 0.1;
	return blur.rgb * 0.15 * float(bloom);
}

vec2 HeatHaze(vec2 coord, float amount)
{
	float strength = pow(texture(depthtex0, coord).r, 500.0);
	strength = clamp((strength - 0.16) * 2.0, 0.0, 1.0) * amount;

	vec2 wave = vec2(0.0);

	float waveSize = 50.0;
	wave.x += sin(coord.y * 1.3 * waveSize + frameTimeCounter * -10.0);
	wave.x += sin(coord.y * 5.0 * waveSize + frameTimeCounter * -14.0);
	wave.y += sin(coord.x * 1.0 * waveSize + frameTimeCounter *  5.0) * 2.0;

	return coord + wave * strength * 0.0001;
}


vec3 colorEffects(vec3 tex) {
    if (shaderEffect == 1) {
        return vec3(tex.r, 0.0, 0.0);
    }
    return tex;
}


void main() {
    vec3 color = vec3(0.0);
	vec2 coord = TexCoords;

	if(heatHaze > 0.0) {
		coord = HeatHaze(coord, heatHaze);
	}

    if (fxaa) {
        color = applyFXAA(coord * vec2(windowSize), colortex0, vec2(windowSize));
    } else {
        color = texture(colortex0, coord).rgb;
    }

	if(bloom > 0) {
		color += Bloom(coord);
	}

    color = adjustBrightness(color, brightness);
    color = adjustContrast(color, contrast);
    color = adjustExposure(color, exposure);
    color = adjustSaturation(color, saturation);
    color = adjustColor(color, rMod, gMod, bMod);
    color = colorEffects(color);

    FragColor = vec4(color, 1.0);
}
