#!/bin/bash
# Rebuilds BTA-Movil.jar from the ORIGINAL BTA 8.0.1 bta.jar using the patchers and sources in this folder.
# Needs JDK 21+ (the patchers use Opcodes.ASM9 from the internal ASM; what goes into the jar is built with --release 17).
# Usage: ./rebuild.sh <bta.jar or bta.v8.0.1.mmc.zip> [output.jar]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INPUT="${1:-}"; OUTPUT_JAR="${2:-$HERE/BTA-Movil.jar}"
[ -f "$INPUT" ] || { echo "Usage: $0 <bta.jar | bta.v8.0.1.mmc.zip> [output.jar]"; exit 1; }
INPUT="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"                 # absolute paths (the script changes directory)
mkdir -p "$(dirname "$OUTPUT_JAR")"; OUTPUT_JAR="$(cd "$(dirname "$OUTPUT_JAR")" && pwd)/$(basename "$OUTPUT_JAR")"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
ASM_EXPORTS="--add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED"
LWJGL_CP="$HERE/libs/lwjgl.jar:$HERE/libs/lwjgl-opengl.jar:$HERE/libs/lwjgl-glfw.jar"

# 0) original bta.jar (also accepts the MultiMC zip)
BTA_ZIP_ENTRY=$(unzip -Z1 "$INPUT" 2>/dev/null | grep -m1 "jarmods/bta.jar$" || true)
if [ -n "$BTA_ZIP_ENTRY" ]; then unzip -q -j "$INPUT" "$BTA_ZIP_ENTRY" -d "$W"; BTA="$W/bta.jar"; else BTA="$INPUT"; fi
BTA_SHA1=$(sha1sum "$BTA" | cut -d' ' -f1)
[ "$BTA_SHA1" = "ff8cb27732a8e4bfd003200210ea10017199bf3f" ] || echo "WARNING: bta.jar is not the expected 8.0.1 (sha1 $BTA_SHA1); trying anyway, but the patchers' exact counts will fail if that version changed anything."
mkdir "$W/jar" "$W/p" "$W/cls" "$W/stubcls"; (cd "$W/jar" && unzip -q "$BTA")

# 1) compile the patchers
javac $ASM_EXPORTS -nowarn -d "$W/p" "$HERE"/patchers/*.java
PATCHERS_DIR="$W/p"; JAR_DIR="$W/jar"; CLIENT_PKG=net/minecraft/client

# 2) Phase 0/1 (Pojav launchers): GLFW, multidraw, buffer upload, shader uniform
java $ASM_EXPORTS -cp $PATCHERS_DIR GlfwPatch "4:$JAR_DIR/$CLIENT_PKG/render/window/GameWindowGLFW.class" "1:$JAR_DIR/$CLIENT_PKG/render/window/GameWindowGLFW\$MonitorInfo.class"
REDIRECT_MULTIDRAW=$(java $ASM_EXPORTS -cp $PATCHERS_DIR Redirect "$JAR_DIR/$CLIENT_PKG/render/terrain/RenderList.class" org/lwjgl/opengl/GL41 glMultiDrawArrays "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V" net/betterthanadventure/btamobile/zalithfix/MultiDraw); echo "$REDIRECT_MULTIDRAW"
echo "$REDIRECT_MULTIDRAW" | grep -q ": 1 redirected" || { echo "ERROR: expected 1 glMultiDrawArrays in RenderList"; exit 1; }
REDIRECT_BUFFER_SUBDATA=$(java $ASM_EXPORTS -cp $PATCHERS_DIR Redirect "$JAR_DIR/$CLIENT_PKG/render/terrain/VertexBuffer.class" org/lwjgl/opengl/GL41 glBufferSubData "(IJLjava/nio/ByteBuffer;)V" net/betterthanadventure/btamobile/zalithfix/BufferUpload); echo "$REDIRECT_BUFFER_SUBDATA"
echo "$REDIRECT_BUFFER_SUBDATA" | grep -q ": 1 redirected" || { echo "ERROR: expected 1 glBufferSubData in VertexBuffer"; exit 1; }
# (fenced upload: BufferUpload tracks which buffer is bound and when one is created or grows)
REDIRECT_BIND_BUFFER=$(java $ASM_EXPORTS -cp $PATCHERS_DIR Redirect "$JAR_DIR/$CLIENT_PKG/render/terrain/VertexBuffer.class" org/lwjgl/opengl/GL41 glBindBuffer "(II)V" net/betterthanadventure/btamobile/zalithfix/BufferUpload); echo "$REDIRECT_BIND_BUFFER"
echo "$REDIRECT_BIND_BUFFER" | grep -q ": 9 redirected" || { echo "ERROR: expected 9 glBindBuffer in VertexBuffer"; exit 1; }
REDIRECT_BUFFER_DATA=$(java $ASM_EXPORTS -cp $PATCHERS_DIR Redirect "$JAR_DIR/$CLIENT_PKG/render/terrain/VertexBuffer.class" org/lwjgl/opengl/GL41 glBufferData "(IJI)V" net/betterthanadventure/btamobile/zalithfix/BufferUpload); echo "$REDIRECT_BUFFER_DATA"
echo "$REDIRECT_BUFFER_DATA" | grep -q ": 2 redirected" || { echo "ERROR: expected 2 glBufferData in VertexBuffer"; exit 1; }
LDC_TEXTURE=$(java $ASM_EXPORTS -cp $PATCHERS_DIR Ldc "$JAR_DIR/$CLIENT_PKG/render/renderer/GLRenderer.class" texture uTextureMatrix); echo "$LDC_TEXTURE"
echo "$LDC_TEXTURE" | grep -q ": 1 ldc replaced" || { echo "ERROR: expected 1 ldc \"texture\" in GLRenderer"; exit 1; }

# 3) Direct GLES phase: GL functions GLES lacks -> shim, startup in Minecraft.main, GLSL ES 3.20 shaders
java $ASM_EXPORTS -cp $PATCHERS_DIR GlesPatch "$JAR_DIR" > "$W/gles.log"; tail -1 "$W/gles.log"
cp -r "$HERE"/shaders/* "$JAR_DIR/"

# 4) Phases 1-4 (performance): threaded chunks, RenderGlobal hooks, profiler/fixes, culling, decoration; Legacy crafting
java $ASM_EXPORTS -cp $PATCHERS_DIR MeshPatch "$JAR_DIR" > "$W/mesh.log"; tail -1 "$W/mesh.log"
# (RgPatch also changes the "near" threshold 2048 -> AsyncChunkRenderer.nearThreshold(): 576 with threaded chunks, 2048 without)
java $ASM_EXPORTS -cp $PATCHERS_DIR RgPatch "$JAR_DIR/$CLIENT_PKG/render/RenderGlobal.class"
java $ASM_EXPORTS -cp $PATCHERS_DIR ProfPatch "$JAR_DIR"
java $ASM_EXPORTS -cp $PATCHERS_DIR CullPatch "$JAR_DIR"
java $ASM_EXPORTS -cp $PATCHERS_DIR GenPatch "$JAR_DIR"
java $ASM_EXPORTS -cp $PATCHERS_DIR CraftPatch "$JAR_DIR"
# compact terrain vertices (28 -> 24 bytes): writes, size, attributes and decoding in the shader
java $ASM_EXPORTS -cp $PATCHERS_DIR PackPatch "$JAR_DIR"
# player actions (break/place) -> sections rebuilt in the same frame; freed VBO ranges -> fences
java $ASM_EXPORTS -cp $PATCHERS_DIR EditPatch "$JAR_DIR"
# water faces against ice, glass...: 0.005 inward, closed edges; no face against permafrost (no z-fighting)
java $ASM_EXPORTS -cp $PATCHERS_DIR FluidPatch "$JAR_DIR"
# on-screen keyboard (ScreenKeyboard writes without keyPressed): create-world and add-server read their fields every tick
java $ASM_EXPORTS -cp $PATCHERS_DIR KeyboardPatch "$JAR_DIR"
REDIRECT_VERTEX_ATTRIB=$(java $ASM_EXPORTS -cp $PATCHERS_DIR Redirect "$JAR_DIR/$CLIENT_PKG/render/tessellator/TessellatorTerrainImpl\$TerrainVertexConfig.class" org/lwjgl/opengl/GL41 glVertexAttribPointer "(IIIZIJ)V" net/betterthanadventure/btamobile/meshfix/VertexPack); echo "$REDIRECT_VERTEX_ATTRIB"
echo "$REDIRECT_VERTEX_ATTRIB" | grep -q ": 5 redirected" || { echo "ERROR: expected 5 glVertexAttribPointer in TerrainVertexConfig"; exit 1; }
REDIRECT_SHADER_SOURCE=$(java $ASM_EXPORTS -cp $PATCHERS_DIR Redirect "$JAR_DIR/$CLIENT_PKG/render/shader/Shader.class" org/lwjgl/opengl/GL41 glShaderSource "(ILjava/lang/CharSequence;)V" net/betterthanadventure/btamobile/meshfix/VertexPack); echo "$REDIRECT_SHADER_SOURCE"
echo "$REDIRECT_SHADER_SOURCE" | grep -q ": 2 redirected" || { echo "ERROR: expected 2 glShaderSource in Shader"; exit 1; }

# 5) compile our classes against the already patched jar and add them
javac --release 17 -d "$W/stubcls" $(find "$HERE/stubs" -name "*.java")
if [ -n "${BTA_WARNINGS:-}" ]; then
  # Only for scripts/verify.sh --full: saves the -Xlint:all warnings (and still shows them).
  javac --release 17 -Xlint:all -proc:none -cp "$W/stubcls:$JAR_DIR:$LWJGL_CP" -d "$W/cls" $(find "$HERE/src" -name "*.java") 2>&1 | tee "$BTA_WARNINGS" >&2
else
  javac --release 17 -nowarn -proc:none -cp "$W/stubcls:$JAR_DIR:$LWJGL_CP" -d "$W/cls" $(find "$HERE/src" -name "*.java")
fi
cp -r "$W/cls"/* "$JAR_DIR/"
for k in net/minecraft/client/render/terrain/AsyncChunkRenderer net/minecraft/client/render/terrain/CaveCuller \
         net/minecraft/core/world/chunk/provider/MobileWorldgen net/betterthanadventure/btamobile/meshfix/Mesher \
         net/betterthanadventure/btamobile/meshfix/MobileOptions net/betterthanadventure/btamobile/glesfix/GlesShim net/betterthanadventure/btamobile/zalithfix/BufferUpload \
         net/betterthanadventure/btamobile/meshfix/VertexPack net/betterthanadventure/btamobile/meshfix/BytePool net/betterthanadventure/btamobile/meshfix/PlayerEdit \
         net/minecraft/client/gui/KeyboardSync; do
  [ -f "$JAR_DIR/$k.class" ] || { echo "ERROR: missing $k.class"; exit 1; }
done

# 6) menu texts
LANG_DIR="$JAR_DIR/assets/minecraft/lang/en_US"
printf '\noptions.btaCaveCulling=Cave Culling\noptions.btaEntityCulling=Entity Culling\noptions.btaSmoothDecoration=Smooth World Decoration\noptions.btaHideUndecorated=Hide Undecorated Chunks\noptions.btaLegacyCrafting=Console Crafting\noptions.btaConsoleMenus=Console Menus and Buttons\noptions.btaMergeDraws=Merge Draw Calls\noptions.btaSyncEdits=Instant Block Updates\noptions.btaMeshThreads=Chunk Building Threads\noptions.btaPackedVertices=Compact Vertices (restart)\noptions.btaAsyncMesh=Background Chunk Building (restart)\noptions.btaFluidEdges=Water Edge Fix (restart)\n' >> "$LANG_DIR/options.lang"
printf '\ngui.options.page.video.category.btamovil=BTA Movil\ngui.options.page.controls.category.btaconsole=Console Style (Legacy)\n' >> "$LANG_DIR/gui.lang"

# 7) package
rm -f "$OUTPUT_JAR"; (cd "$JAR_DIR" && zip -q -r -X "$OUTPUT_JAR" META-INF $(ls | grep -v META-INF))
echo "Done: $OUTPUT_JAR ($(stat -c %s "$OUTPUT_JAR") bytes)"
