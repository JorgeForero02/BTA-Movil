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

float getDepth(vec2 coord){
	return texture(depthtex0, coord).r;
}

void main(){
	vec3 color = texture(colortex0, TexCoords).rgb;
	
	float outline = 0.0;
	
	float size = 4.0 * intensity;
	
	outline += clamp(getDepth(TexCoords) - getDepth(TexCoords + vec2(size / float(windowSize.x), 0.0)), 0.0, 1.0);
	outline += clamp(getDepth(TexCoords) - getDepth(TexCoords - vec2(size / float(windowSize.x), 0.0)), 0.0, 1.0);
	outline += clamp(getDepth(TexCoords) - getDepth(TexCoords + vec2(0.0, size / float(windowSize.y))), 0.0, 1.0);
	outline += clamp(getDepth(TexCoords) - getDepth(TexCoords - vec2(0.0, size / float(windowSize.y))), 0.0, 1.0);
	
	outline = 1.0 - clamp(outline * 512.0, 0.0, 1.0);
	
	color *= outline;
	
	FragColor = vec4(color, 1.0);
}
