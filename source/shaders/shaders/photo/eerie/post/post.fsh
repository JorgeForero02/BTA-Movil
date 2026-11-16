#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;
in vec2 TexCoords;

uniform sampler2D colortex0;

uniform float intensity;

void main(){
	vec3 color = texture(colortex0, TexCoords).rgb;
	
	float grayscale = (color.r + color.g + color.b) / 3.0;
	
	color = mix(color, vec3(grayscale), 0.5 * intensity);
	
	float vignette = length(vec2(TexCoords - 0.5));
	vignette = clamp((vignette - 0.4) * 3.0, 0.0, 1.0);
	
	color = mix(color, vec3(0.0), vignette);
	
	FragColor = vec4(color, 1.0);
}
