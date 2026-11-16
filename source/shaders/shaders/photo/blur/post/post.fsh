#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;
in vec2 TexCoords;

uniform sampler2D colortex0;

uniform ivec2 windowSize;
uniform float intensity;


vec3 getBlur(){
	vec3 blur = vec3(0.0);
	int q = int(16.0 * intensity);
	float qh = float(q) / 2.0 - 0.5;
	float allStrengths = 0.0;

	vec2 pixel = 1.0 / vec2(windowSize);
	for(int i=0; i < q; i++){
		for(int j=0; j < q; j++){
			vec2 off = vec2(float(i) - qh, float(j) - qh);
			float strength = 1.0 - sin(length(off) / qh);

			blur += texture(colortex0, TexCoords + off * pixel).rgb * strength;

			allStrengths += strength;
		}
	}
	blur /= allStrengths;
	return blur;
}

void main(){
	vec3 color = texture(colortex0, TexCoords).rgb;

	float blurFactor = clamp(distance(TexCoords, vec2(0.5)) * 1.0, 0.0, 1.0);
	vec3 blur = getBlur();

	if (int(16.0 * intensity) > 1)
	{
		color = mix(color, blur, blurFactor);
	}

	FragColor = vec4(color, 1.0);
}
