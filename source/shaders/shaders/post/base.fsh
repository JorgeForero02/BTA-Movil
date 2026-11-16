#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;
in vec2 TexCoords;

uniform sampler2D colortex0;
uniform sampler2D depthtex0;

uniform float gamma;

uniform int cbCorrectionMode;

vec3 adjustGamma(vec3 color, float value)
{
    return pow(color, vec3(1.0 - value + 0.5));
}

vec3 cbCorrection(vec3 tex)
{
    int mode = cbCorrectionMode;
    if (mode < 1 || mode > 4) return tex;

    if (mode == 4)//Grayscale
    {
        return vec3((tex.r + tex.g + tex.b) / 3.0);
    }

    float L = (17.8824 * tex.r) + (43.5161 * tex.g) + (4.11935 * tex.b);
    float M = (3.45565 * tex.r) + (27.1554 * tex.g) + (3.86714 * tex.b);
    float S = (0.0299566 * tex.r) + (0.184309 * tex.g) + (1.46709 * tex.b);

    // init to avoid undefined values (always overwritten for modes 1-3)
    float l = 0.0, m = 0.0, s = 0.0;
    if (mode == 1)//Protanopia
    {
        l = 0.0 * L + 2.02344 * M + -2.52581 * S;
        m = 0.0 * L + 1.0 * M + 0.0 * S;
        s = 0.0 * L + 0.0 * M + 1.0 * S;
    }

    if (mode == 2)//Deuteranopia
    {
        l = 1.0 * L + 0.0 * M + 0.0 * S;
        m = 0.494207 * L + 0.0 * M + 1.24827 * S;
        s = 0.0 * L + 0.0 * M + 1.0 * S;
    }

    if (mode == 3)//Tritanopia
    {
        l = 1.0 * L + 0.0 * M + 0.0 * S;
        m = 0.0 * L + 1.0 * M + 0.0 * S;
        s = -0.395913 * L + 0.801109 * M + 0.0 * S;
    }

    vec3 error;
    error.r = (0.0809444479 * l) + (-0.130504409 * m) + (0.116721066 * s);
    error.g = (-0.0102485335 * l) + (0.0540193266 * m) + (-0.113614708 * s);
    error.b = (-0.000365296938 * l) + (-0.00412161469 * m) + (0.693511405 * s);
    vec3 diff = tex - error;
    vec3 correction;
    correction.r = 0.0;
    correction.g =  (diff.r * 0.7) + (diff.g * 1.0);
    correction.b =  (diff.r * 0.7) + (diff.b * 1.0);
    correction = tex + correction;

    return correction;
}

void main()
{
    vec3 color = vec3(0.0);

    color = texture(colortex0, TexCoords).rgb;

    color = cbCorrection(color);

    color = adjustGamma(color, gamma);

    FragColor = vec4(color, 1.0);
}
