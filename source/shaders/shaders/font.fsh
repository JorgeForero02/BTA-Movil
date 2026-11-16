#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;

#define MASK_ITALIC 0x01u
#define MASK_UNDERLINE 0x02u
#define MASK_STRIKETHROUGH 0x04u
#define MASK_BOLD 0x08u
#define MASK_OBFUSCATED 0x10u
#define MASK_OUTLINE 0x20u
#define MASK_SHADOW 0x80u

#define F_RES_DEF ivec2(256)
#define F_PIX 128
#define F_PIX_HALF 256

out vec4 FragColor;

in vec4 Color;
in vec2 UV;
in vec2 TexCoord;
in vec2 Lightmap;
in vec2 Size;
in float Config;

uniform float uAlphaTest;
uniform float uLightMapStrength;
uniform vec4 uColor;

uniform sampler2D colortex;
uniform sampler2D lighttex;

// min limit is a bandaid solution for unicode or other similar x256 fonts that arent double thick so that it actually works with bold and outline -PedroThePanda64
// ES: globals need constant initializers; values are set at start of main()
ivec2 r;
ivec2 rh;
ivec2 sh;

bool sampleInside(vec2 tex) {
    // local 'max' renamed to maxUV (shadowed builtin max())
    vec2 maxUV = UV + Size / float(F_PIX);
    return tex.x > UV.x && tex.y > UV.y && tex.x < maxUV.x && tex.y < maxUV.y;
}

// PedroThePanda64 vomited here
vec4 bold(vec2 coord, vec4 text, vec4 textColor)
{
	for(int i = 0; i < sh.x; i++)
	{
		vec2 left = coord - vec2(i + 1, 0) / vec2(rh);
		if(sampleInside(left) && text.a < 0.9999)
			text = texture(colortex, left) * textColor;
	}

	return text;
}

vec4 colorAtPoint(vec2 point, vec4 tint) {
    uint config = uint(Config + 0.01);

    vec4 color = vec4(0.0);
    if (sampleInside(point)) {
        color = texture(colortex, point) * Color * tint;
    }
    if ((config & MASK_BOLD) != 0u) {
		color = bold(point, color, Color * tint);
    }
    vec2 maxUV = UV + Size / float(F_PIX);
    if ((config & MASK_UNDERLINE) != 0u && point.y > maxUV.y && point.y < (maxUV.y + 1.0 / float(F_PIX))) {
        color = Color * tint;
    }
    vec2 mid = UV + Size / float(F_PIX_HALF);
    if ((config & MASK_STRIKETHROUGH) != 0u && point.y > (mid.y - 1.0 / float(F_PIX)) && point.y < mid.y) {
        color = Color * tint;
    }

    return color;
}

bool canReplace(vec2 tex, vec4 tint) {
    vec4 c = colorAtPoint(tex, tint);
    return c.a > 0.1;
}

float outline(vec4 tint)
{
	for(int x = -sh.x; x <= sh.x; x++)
	for(int y = -sh.y; y <= sh.y; y++)
	{
		if(canReplace(TexCoord + vec2(x, y) / vec2(rh), tint)) return 1.0;
	}

	return 0.0;
}

void main() {
    r = max(ivec2(512), textureSize(colortex, 0));
    rh = r / 2;
    sh = r / F_RES_DEF;

    uint config = uint(Config + 0.01);
    vec4 tint = mix(vec4(1.0, 1.0, 1.0, 1.0), texture(lighttex, Lightmap), uLightMapStrength) * uColor;
    FragColor = colorAtPoint(TexCoord, tint);
	
    if (FragColor.a < 0.1) {
        if ((config & MASK_OUTLINE) != 0u) {
			FragColor = vec4(Color.rgb / 4.0, Color.a) * tint * outline(tint);
        } else if ((config & MASK_SHADOW) != 0u) {
            vec2 upLeft = TexCoord - vec2(1.0, 1.0) / float(F_PIX);
            if (canReplace(upLeft, tint)) {
                FragColor = vec4(Color.rgb / 4.0, Color.a) * tint;
            }
        }
    }
    if (FragColor.a < uAlphaTest) {
        discard;
    }
}
