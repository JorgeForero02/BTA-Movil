import sys, json
from playwright.sync_api import sync_playwright
srcs = {n: open(n).read() for n in sys.argv[1:]}
js = """(srcs) => {
  const c = document.createElement('canvas'); const gl = c.getContext('webgl2'); if (!gl) return {error: 'no webgl2'};
  const out = {renderer: gl.getParameter(gl.VERSION)};
  const comp = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    return {ok: gl.getShaderParameter(s, gl.COMPILE_STATUS), log: gl.getShaderInfoLog(s), sh: s}; };
  for (const [name, src0] of Object.entries(srcs)) {
    const src = src0.replace('#version 320 es', '#version 300 es');
    const type = name.endsWith('.fsh') ? gl.FRAGMENT_SHADER : gl.VERTEX_SHADER;
    const r = comp(type, src); out[name] = {ok: r.ok, log: r.log};
  }
  // link: vertex (original or packed) + fragment, and check attribute locations
  for (const v of Object.keys(srcs).filter(n => n.endsWith('.vsh'))) {
    const vs = comp(gl.VERTEX_SHADER, srcs[v].replace('#version 320 es','#version 300 es')).sh;
    const fsName = Object.keys(srcs).find(n => n.endsWith('.fsh'));
    const fs = comp(gl.FRAGMENT_SHADER, srcs[fsName].replace('#version 320 es','#version 300 es')).sh;
    const p = gl.createProgram(); gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p);
    const attrs = {}; for (const n of ['aPos','aPosPacked','aColor','aNormal','aUV','aLightShade']) attrs[n] = gl.getAttribLocation(p, n);
    out[v + ' + link'] = {ok: gl.getProgramParameter(p, gl.LINK_STATUS), log: gl.getProgramInfoLog(p), attrs};
  }
  return out; }"""
with sync_playwright() as p:
    b = p.chromium.launch(args=['--use-gl=swiftshader','--enable-unsafe-swiftshader','--ignore-gpu-blocklist'])
    pg = b.new_page(); r = pg.evaluate(js, srcs); print(json.dumps(r, indent=1)); b.close()
