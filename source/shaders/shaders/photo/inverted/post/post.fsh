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

	color = mix(color, vec3(1.0 - color.r, 1.0 - color.g, 1.0 - color.b), intensity);

	FragColor = vec4(color, 1.0);
}
