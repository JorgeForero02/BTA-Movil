#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

out vec4 FragColor;
in vec2 TexCoords;

uniform sampler2D depthtex0;

void main(){
	float depth = texture(depthtex0, TexCoords).r;
	
	depth = pow(depth, 1000.0);
	
	FragColor = vec4(depth, depth, depth, 1.0);
}
