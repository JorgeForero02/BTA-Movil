#!/bin/bash
# Regenerates BTA-Movil-Installer.jar from the SOURCES: rebuilds the jar (rebuild.sh), computes the
# differences against the original bta.jar and packages the installer. Needs JDK 21+ (see rebuild.sh) and python3.
# Usage: ./create-installer.sh <bta.jar | bta.v8.0.1.mmc.zip> [output.jar]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INPUT="${1:-}"; [ -f "$INPUT" ] || { echo "Usage: $0 <bta.jar | bta.v8.0.1.mmc.zip> [output.jar]"; exit 1; }
OUTPUT_JAR="${2:-$HERE/../BTA-Movil-Installer.jar}"
mkdir -p "$(dirname "$OUTPUT_JAR")"; OUTPUT_JAR="$(cd "$(dirname "$OUTPUT_JAR")" && pwd)/$(basename "$OUTPUT_JAR")"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
"$HERE/rebuild.sh" "$INPUT" "$W/BTA-Movil.jar"
BTA_ZIP_ENTRY=$(unzip -Z1 "$INPUT" 2>/dev/null | grep -m1 "jarmods/bta.jar$" || true)
if [ -n "$BTA_ZIP_ENTRY" ]; then unzip -q -j "$INPUT" "$BTA_ZIP_ENTRY" -d "$W"; BTA="$W/bta.jar"; else BTA="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"; fi
BTA_SHA1=$(sha1sum "$BTA" | cut -d' ' -f1)
[ "$BTA_SHA1" = "ff8cb27732a8e4bfd003200210ea10017199bf3f" ] || { echo "ERROR: the installer must be built from the exact 8.0.1 bta.jar (sha1 $BTA_SHA1)"; exit 1; }
mkdir "$W/orig" "$W/new" "$W/cls"
(cd "$W/orig" && unzip -q "$BTA"); (cd "$W/new" && unzip -q "$W/BTA-Movil.jar")
python3 "$HERE/installer/mkdelta.py" "$W/orig" "$W/new" "$BTA" "$W/cls/bta-movil"
mkdir "$W/cls/templates"; cp "$HERE"/templates/* "$W/cls/templates/"
javac --release 17 -nowarn -d "$W/cls" "$HERE/installer/Installer.java"
printf 'Main-Class: Installer\n' > "$W/m.txt"
rm -f "$OUTPUT_JAR"; (cd "$W/cls" && jar cfm "$OUTPUT_JAR" "$W/m.txt" .)
echo "Done: $OUTPUT_JAR"
