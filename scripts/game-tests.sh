#!/bin/bash
# Tests with the real game (source/tests/game/): meshes with 1/2/4 threads, all recipes and
# water faces, with BTA blocks and menus, headless. Linux or inside the container:
#   bash scripts/verify-docker.sh --game     (Windows; includes --full)
#   bash scripts/game-tests.sh <BTA-Movil.jar>
# Needs the game libraries in _local/game-libs/ (fastutil 8.5.15, joml 1.10.7, joml-primitives 1.10.0,
# slf4j-api 2.0.7, gson 2.10.1, legacy-lwjgl3 1.0.6 and bta-brigadier 1.0.2: the versions in
# patches/net.minecraft.json of the official zip; the README lists where to download them). Without
# legacy-lwjgl3 GameSettings does not initialize and the mesh test excludes 419 blocks and fails; without
# bta-brigadier World cannot be built (DecorationTest). They run with Java 17.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
LIBS="${GAME_LIBS:-$ROOT/_local/game-libs}"
JDK17="${JDK17:-/opt/jdk17}"
[ -n "$(ls "$LIBS"/*.jar 2>/dev/null)" ] || { echo "ERROR: missing game libraries in $LIBS"; exit 1; }
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
CP="$JAR:$ROOT/source/libs/*:$LIBS/*"
"$JDK17/bin/javac" --release 17 -nowarn -d "$W" -cp "$CP" $(find "$ROOT/source/tests/game" -name "*.java")
J="$JDK17/bin/java -Djava.awt.headless=true -cp $W:$CP"
for n in 1 2 4; do timeout 600 $J -Dbta.asyncMesh.threads=$n RealMeshesTest; done
timeout 600 $J RecipesTest
timeout 600 $J -Dbta.asyncMesh=false SyncRecipesTest
timeout 600 $J -Dbta.packedVertices=false WaterTest
timeout 600 $J CraftingRoomTest
timeout 600 $J CraftingTest
timeout 600 $J net.minecraft.client.render.terrain.GenerationTest
timeout 600 $J TileSnapshotTest
timeout 600 $J -Dbta.tileSnapshot=false TileSnapshotTest
timeout 600 $J -Dbta.keyboardSync=false net.minecraft.client.gui.KeyboardTest
timeout 600 $J net.minecraft.client.gui.KeyboardTest
# Decoration: one case per JVM (properties), dumps in $W/deco (not in $W, which is on the classpath) and a
# last JVM that compares. -XX:hashCode=2 fixes the order of BTA's identity sets.
mkdir "$W/deco"
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.smoothDecoration=false -Dbta.test.decoCounters=true -Dtest.case=A -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.smoothDecoration=false -Dbta.test.decoCounters=true -Dtest.case=Ap -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.smoothDecoration=false -Dbta.test.decoCounters=true -Dtest.case=B -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.smoothDecoration=false -Dtest.case=M -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.test.decoCounters=true -Dbta.decorateBudgetMs=1000000 -Dbta.deferCascade=false -Dtest.case=C -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.test.decoCounters=true -Dbta.decorateBudgetMs=1000000 -Dtest.case=D -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.test.decoCounters=true -Dbta.decorateBudgetMs=1000000 -Dtest.case=E -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.test.decoCounters=true -Dbta.decorateBudgetMs=1000000 -Dtest.case=F -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dbta.test.decoCounters=true -Dbta.decorateBudgetMs=1000000 -Dtest.case=G -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dtest.case=T -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
timeout 600 $J -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -Dtest.case=compare -Dtest.dir=$W/deco net.minecraft.core.world.chunk.provider.DecorationTest
echo "GAME TESTS: OK"
