import sys, json, base64
from playwright.sync_api import sync_playwright
packed = base64.b64encode(open(sys.argv[1],'rb').read()).decode(); orig = base64.b64encode(open(sys.argv[2],'rb').read()).decode()
filtered_decl = open(sys.argv[3]).read()  # terrain.packed.vsh: we take its attribute block to be sure we test the real filter output
js = r"""([packedB64, origB64, packedSrc]) => {
  const dec = b => Uint8Array.from(atob(b), c => c.charCodeAt(0));
  const P = dec(packedB64), O = dec(origB64);
  const gl = document.createElement('canvas').getContext('webgl2');
  // Vertex shader: the attribute declarations of the real filtered terrain shader, then pass everything through.
  const declsPacked = packedSrc.split('\n').filter(l => /layout \(location|#define aUV/.test(l)).join('\n');
  const mk = (decls) => `#version 300 es
precision highp float;
${decls}
out vec3 oPos; out vec3 oColor; out vec3 oNormal; out vec2 oUV; out vec2 oLS;
void main(){ oPos = aPos; oColor = aColor; oNormal = aNormal; oUV = aUV; oLS = aLightShade; gl_Position = vec4(0.0); }`;
  const declsOrig = `layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in vec2 aUV;
layout (location = 4) in vec2 aLightShade;`;
  const run = (decls, data, layout) => {
    const vs = gl.createShader(gl.VERTEX_SHADER); gl.shaderSource(vs, mk(decls)); gl.compileShader(vs);
    if (!gl.getShaderParameter(vs, gl.COMPILE_STATUS)) return {err: gl.getShaderInfoLog(vs)};
    const fs = gl.createShader(gl.FRAGMENT_SHADER); gl.shaderSource(fs, `#version 300 es
precision highp float; out vec4 c; void main(){ c = vec4(1.0); }`); gl.compileShader(fs);
    const p = gl.createProgram(); gl.attachShader(p, vs); gl.attachShader(p, fs);
    gl.transformFeedbackVaryings(p, ['oPos','oColor','oNormal','oUV','oLS'], gl.INTERLEAVED_ATTRIBS);
    gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) return {err: gl.getProgramInfoLog(p)};
    gl.useProgram(p);
    const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
    const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo); gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
    for (const [i, size, type, norm, stride, off] of layout) { gl.vertexAttribPointer(i, size, type, norm, stride, off); gl.enableVertexAttribArray(i); }
    const n = data.length / layout[0][4];
    const out = gl.createBuffer(); gl.bindBuffer(gl.TRANSFORM_FEEDBACK_BUFFER, out); gl.bufferData(gl.TRANSFORM_FEEDBACK_BUFFER, n * 13 * 4, gl.STATIC_READ);
    const tf = gl.createTransformFeedback(); gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, tf); gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER, 0, out);
    gl.enable(gl.RASTERIZER_DISCARD); gl.beginTransformFeedback(gl.POINTS); gl.drawArrays(gl.POINTS, 0, n); gl.endTransformFeedback(); gl.disable(gl.RASTERIZER_DISCARD);
    gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, null);
    const res = new Float32Array(n * 13); gl.getBufferSubData(gl.TRANSFORM_FEEDBACK_BUFFER, 0, res);
    return {n, res};
  };
  const G = gl;
  // Layouts exactly as BTA (original) and VertexPack.glVertexAttribPointer (packed) set them.
  const L_ORIG = [[0,3,G.FLOAT,false,32,0],[1,3,G.UNSIGNED_BYTE,true,32,12],[2,3,G.BYTE,true,32,16],[3,2,G.FLOAT,false,32,20],[4,2,G.UNSIGNED_BYTE,false,32,28]]; // BTA layout realigned for WebGL (same values)
  const L_PACK = [[0,3,G.FLOAT,false,24,0],[1,3,G.UNSIGNED_BYTE,true,24,12],[2,3,G.BYTE,true,24,15],[3,2,G.UNSIGNED_SHORT,true,24,18],[4,2,G.UNSIGNED_BYTE,false,24,22]];
  const a = run(declsOrig, O, L_ORIG), b = run(declsPacked, P, L_PACK);
  if (a.err || b.err) return {err: a.err || b.err};
  if (a.n !== b.n) return {err: 'n ' + a.n + ' vs ' + b.n};
  const names = ['pos','pos','pos','color','color','color','normal','normal','normal','uv','uv','ls','ls'];
  const maxErr = {}; let worst = null;
  for (let v = 0; v < a.n; v++) for (let k = 0; k < 13; k++) {
    const d = Math.abs(a.res[v*13+k] - b.res[v*13+k]); const nm = names[k];
    if (!(nm in maxErr) || d > maxErr[nm]) { maxErr[nm] = d; if (nm === 'pos' && (!worst || d > worst.d)) worst = {v, k, d, orig: a.res[v*13+k], packed: b.res[v*13+k]}; }
  }
  let zerosA = 0, zerosB = 0; for (let v = 0; v < a.n; v++) { if (a.res[v*13] === 0 && a.res[v*13+1] === 0) zerosA++; if (b.res[v*13] === 0 && b.res[v*13+1] === 0) zerosB++; }
  return {vertices: a.n, gl: gl.getParameter(gl.VERSION), maxErr, worstPos: worst, zerosA, zerosB, a0: Array.from(a.res.slice(0,13)), b0: Array.from(b.res.slice(0,13)), aLast: Array.from(a.res.slice((a.n-1)*13, a.n*13))};
}"""
with sync_playwright() as p:
    b = p.chromium.launch(args=['--use-gl=swiftshader','--enable-unsafe-swiftshader','--ignore-gpu-blocklist'])
    pg = b.new_page(); r = pg.evaluate(js, [packed, orig, filtered_decl]); print(json.dumps(r, indent=1)); b.close()
