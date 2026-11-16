#!/bin/bash
# BTA-Movil tests, headless. Usage: ./run-tests.sh <BTA-Movil.jar>   (JDK 17+)
# The real-meshes test (game/RealMeshesTest.java) also needs the game's own libraries
# (fastutil, joml, joml-primitives, slf4j, gson): see the comment in that class.
# Optional: with python3 + playwright and Chromium it also compiles the shaders and compares on the GPU (WebGL2/SwiftShader)
# what the terrain shader receives with the original and with the compressed format.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; JAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
L="$HERE/../libs/*"
javac --release 17 -nowarn -d "$W" -cp "$JAR:$L" $(find "$HERE" -name "*.java" -not -path "*/gpu/*" -not -path "*/game/*") "$HERE/../installer/Installer.java"
CP="$W:$JAR:$L"
java -cp "$CP" MultiDrawTest
java -cp "$CP" FluidContactTest
java -cp "$CP" net.betterthanadventure.btamobile.zalithfix.MultiDrawCacheTest
java -cp "$CP" BytePoolTest
for n in 1 2 4; do java -Dbta.asyncMesh.threads=$n -cp "$CP" MesherTest | grep -v BTA-mesh; done
java -cp "$CP" VertexPackTest
java -cp "$CP" net.betterthanadventure.btamobile.meshfix.PlayerEditTest
java -XX:ActiveProcessorCount=8 -cp "$CP" net.betterthanadventure.btamobile.meshfix.ThreadCountTest
java -cp "$CP" net.betterthanadventure.btamobile.meshfix.SaveBudgetTest
java -Dbta.saveBudgetMs=8 -cp "$CP" net.betterthanadventure.btamobile.meshfix.SaveBudgetTest
java -cp "$CP" net.betterthanadventure.btamobile.glesfix.GlesCheckTest
java -cp "$CP" net.betterthanadventure.btamobile.zalithfix.UploadFenceTest
java -Dbta.packedVertices=false -cp "$CP" VertexPackTest
java -cp "$CP" InstallerTest
java -cp "$CP" net.betterthanadventure.btamobile.meshfix.OptionSwitchesTest
java -Dbta.prof=true -Dbta.prof.sample=decorate -Dbta.prof.sampleMs=1 -Dbta.prof.sampleReport=false -Dbta.glErrors=false -cp "$CP" net.betterthanadventure.btamobile.meshfix.SamplingTest
java -Dbta.prof=true -Dbta.prof.sample=none -cp "$CP" net.betterthanadventure.btamobile.meshfix.SamplingTest
if python3 -c "import playwright" 2>/dev/null; then
  javac --release 17 -nowarn -d "$W" -cp "$JAR:$L" "$HERE/gpu/ShaderFilter.java"
  S="$HERE/../shaders/shaders"
  java -cp "$CP" ShaderFilter "$S/terrain.vsh" "$W/terrain.packed.vsh"
  cp "$S/terrain.vsh" "$S/terrain.fsh" "$W/"
  (cd "$W" && python3 "$HERE/gpu/compile-shaders.py" terrain.vsh terrain.packed.vsh terrain.fsh) | grep -c '"ok": true' | xargs -I{} sh -c '[ {} -eq 5 ] && echo "Shaders: OK (original and compressed compile and link)" || { echo "Shaders: FAILED"; exit 1; }'
  java -cp "$CP" VertexDumper "$W/packed.bin" 99 >/dev/null
  java -Dbta.packedVertices=false -cp "$CP" VertexDumper "$W/orig.bin" 99 >/dev/null
  python3 - "$W" <<'PY'
import sys, struct; W=sys.argv[1]; O=open(W+'/orig.bin','rb').read(); Pk=open(W+'/packed.bin','rb').read()
n=len(O)//28; assert len(Pk)==n*24, 'size'
dif=sum(1 for v in range(n) if O[v*28:v*28+18]!=Pk[v*24:v*24+18] or O[v*28+26:v*28+28]!=Pk[v*24+22:v*24+24])
print(f"Bytes: position, color, normal and light identical to the original format in {n-dif} of {n} vertices" + (" - OK" if dif==0 else " - FAILED")); assert dif==0
out=bytearray()
for v in range(len(O)//28):
    o=O[v*28:(v+1)*28]; out+=o[0:12]+o[12:15]+b'\0'+o[15:18]+b'\0'+o[18:26]+o[26:28]+b'\0\0'
open(W+'/orig32.bin','wb').write(out)
PY
  python3 "$HERE/gpu/compare-attributes.py" "$W/packed.bin" "$W/orig32.bin" "$W/terrain.packed.vsh" | python3 -c "
import json,sys; d=json.load(sys.stdin); e=d['maxErr']
ok = e['pos'] == 0 and e['uv'] <= 0.5/32768+1e-7 and e['color']==0 and e['normal']==0 and e['ls']==0 and d['zerosA']==0 and d['zerosB']==0
print(('GPU: OK' if ok else 'GPU: FAILED') + f\" - {d['vertices']} vertices, max position error {e['pos']:.5f}, UV {e['uv']:.2e}, color/normal/light {e['color']}/{e['normal']}/{e['ls']}\"); sys.exit(0 if ok else 1)"
else
  echo "(no playwright: GPU tests skipped)"
fi
echo "ALL TESTS: OK"
