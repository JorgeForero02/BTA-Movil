#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;
in vec2 TexCoords;

uniform sampler2D colortex0;

uniform float intensity;

vec3 toSepia(vec3 color)
{
	// Sequential updates kept as in original (g/b use modified r/g)
	color.r = color.r * 0.393 + color.g * 0.769 + color.b * 0.189;
	color.g = color.r * 0.349 + color.g * 0.686 + color.b * 0.168;
	color.b = color.r * 0.272 + color.g * 0.534 + color.b * 0.131;

	return color;
}

void main()
{
	vec3 color = texture(colortex0, TexCoords).rgb;
	vec3 sepia = toSepia(color);

	color = mix(color, sepia, intensity);

	FragColor = vec4(color, 1.0);
}
