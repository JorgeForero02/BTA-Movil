#!/bin/bash
# BTA-Movil verification gate.
#
#   bash scripts/verify.sh           quick: shell syntax and compilation of what does not need the game.
#                                    This is what the pre-commit hook runs.
#   bash scripts/verify.sh --full    also rebuilds BTA-Movil.jar from the original bta.jar, checks the
#                                    javac warning ceiling and runs source/tests/run-tests.sh with Java 17
#                                    (pre-push).
#   bash scripts/verify.sh --game    --full + tests with the real game (scripts/game-tests.sh, ~2 min 50 s
#                                    measured, rebuild included; before each release).
#
# Why two modes: the full part needs the BTA 8.0.1 bta.jar, which cannot be redistributed and lives outside
# the repository (_local/, ignored). Without it only the self-contained checks can run.
#
# JDK: the patchers use the JDK's internal ASM with Opcodes.ASM9, which JDK 17 lacks (53 errors with
# eclipse-temurin:17-jdk). They compile with JDK 21; everything that goes into the game compiles with
# --release 17 and the tests run with Java 17.
# The full part uses ':' as the classpath separator (scripts under source/): on Windows it runs inside
# Docker with scripts/verify-docker.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/source"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
ASM_EXPORTS="--add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED"
step() { echo "== $*"; }

usage() { echo "Usage: bash scripts/verify.sh [--full | --game]"; }
MODE=""
if [ $# -gt 0 ]; then
  case "$1" in
    --full|--game) MODE="$1" ;;
    *) echo "ERROR: unknown argument '$1'"; usage; exit 1 ;;
  esac
  [ $# -eq 1 ] || { echo "ERROR: unknown argument '$2'"; usage; exit 1; }
fi

step "shell script syntax"
for s in "$ROOT"/create-modpack.sh "$SRC"/*.sh "$SRC"/tests/*.sh "$ROOT"/scripts/*.sh; do bash -n "$s"; done

step "patcher compilation (JDK with ASM9)"
mkdir "$W/p"; javac $ASM_EXPORTS -Xlint:all -Werror -d "$W/p" "$SRC"/patchers/*.java

step "installer and stubs compilation (--release 17)"
mkdir "$W/i" "$W/s"
javac --release 17 -Xlint:all -Werror -d "$W/i" "$SRC/installer/Installer.java"
javac --release 17 -Xlint:all -Werror -d "$W/s" $(find "$SRC/stubs" -name "*.java")

if [ -z "$MODE" ]; then
  echo "VERIFY QUICK: OK (the full one: --full, with the game in _local/)"
  exit 0
fi

BTA_ZIP="${BTA_ZIP:-$ROOT/_local/bta.v8.0.1.mmc.zip}"
JDK17="${JDK17:-/opt/jdk17}"
[ -f "$BTA_ZIP" ] || { echo "ERROR: the original game is missing ($BTA_ZIP). Put bta.v8.0.1.mmc.zip in _local/"; exit 1; }
[ -x "$JDK17/bin/java" ] || { echo "ERROR: no JDK 17 in $JDK17 (JDK17 variable)"; exit 1; }

step "rebuild from the original bta.jar"
# BTA_WARNINGS: rebuild.sh leaves the javac -Xlint:all warnings over source/src here (and shows them on stderr).
BTA_WARNINGS="$W/warnings.txt" "$SRC/rebuild.sh" "$BTA_ZIP" "$W/BTA-Movil.jar" | tail -1

step "warning ceiling of source/src (scripts/warning-ceiling.json)"
# Two-way ratchet: not one warning more, and if the count drops the ceiling drops. Messages go to stderr.
JDK_MAJOR="$(javac -version 2>&1 | sed -nE 's/^javac ([0-9]+).*/\1/p')"
node -e '
  const fs = require("fs");
  const [, ceilingFile, warningsFile, jdk] = process.argv;
  const ceiling = JSON.parse(fs.readFileSync(ceilingFile, "utf8"));
  const counts = {};
  for (const l of fs.readFileSync(warningsFile, "utf8").split("\n")) {
    const m = l.match(/: warning: \[([a-z-]+)\]/);
    if (m) counts[m[1]] = (counts[m[1]] || 0) + 1;
  }
  if (Number(jdk) !== ceiling.jdk) {
    console.error("ERROR: ceiling measured with JDK " + ceiling.jdk + ", this is JDK " + jdk + ": javac warnings change between versions; measure it again with that JDK");
    process.exit(1);
  }
  const err = [];
  const cats = new Set([...Object.keys(counts), ...Object.keys(ceiling).filter(k => k !== "jdk")]);
  for (const c of [...cats].sort()) {
    const n = counts[c] || 0, t = ceiling[c] || 0;
    if (n > t) err.push("warnings [" + c + "] rise to " + n + " (ceiling " + t + "): fix the new warning");
    else if (n < t) err.push("warnings [" + c + "] drop: set the ceiling of " + c + " to " + n + " in scripts/warning-ceiling.json");
  }
  if (err.length) { for (const e of err) console.error("ERROR: " + e); process.exit(1); }
  console.log("warning ceiling: OK (" + ["cast", "deprecation", "rawtypes"].map(c => counts[c] || 0).join("/") + ")");
' "$ROOT/scripts/warning-ceiling.json" "$W/warnings.txt" "$JDK_MAJOR"

step "tests with Java 17 ($("$JDK17/bin/java" -version 2>&1 | head -1))"
PATH="$JDK17/bin:$PATH" "$SRC/tests/run-tests.sh" "$W/BTA-Movil.jar"

if [ "$MODE" = "--game" ]; then
  step "tests with the real game (source/tests/game)"
  JDK17="$JDK17" bash "$ROOT/scripts/game-tests.sh" "$W/BTA-Movil.jar"
fi
echo "VERIFY ${MODE#--}: OK" | tr a-z A-Z
