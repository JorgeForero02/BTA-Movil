#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;
in vec2 TexCoords;

uniform sampler2D colortex0;
uniform sampler2D depthtex0;

uniform ivec2 windowSize;
uniform float intensity;

vec3 getBlur(float r){
	float x, y, xx, yy, rr = r * r, dx, dy, w, w0;
	w0 = 0.3780 / pow(r, 1.975);
	vec2 p;
	vec4 col = vec4(0.0, 0.0, 0.0, 0.0);
	for (dx = 1.0 / float(windowSize.x), x = -r, p.x = (TexCoords.x) + (x * dx); x <= r; x++, p.x += dx)
	{
		xx = x * x;
		for (dy = 1.0 / float(windowSize.y), y = -r, p.y = (TexCoords.y) + (y * dy); y <= r; y++, p.y += dy)
		{
			yy = y * y;
			if (xx + yy <= rr)
			{
				w = w0 * exp((-xx - yy) / (2.0 * rr));
				col += texture(colortex0, p) * w;
			}
		}
	}
	return vec3(col.r, col.g, col.b);
}

void main()
{
	vec3 color = texture(colortex0, TexCoords).rgb;

	float blurFactor = clamp(distance(TexCoords, vec2(0.5)) * 4.0 * intensity, 0.0, 1.0);
	vec3 blur = getBlur(blurFactor * 15.0);

	if (intensity != 0.0)
	{
		color = mix(color, blur, blurFactor);
	}

	FragColor = vec4(color, 1.0);
}
