#!/bin/sh
# Builds BTA-Movil.jar and the Amethyst and Zalith packages from your ORIGINAL BTA 8.0.1 bta.jar.
# Usage: ./create-modpack.sh <bta.jar | bta.v8.0.1.mmc.zip | folder to search> [output_folder] [--install <.minecraft>]
# Needs only Java 17 (nothing is compiled). Without arguments it looks for the bta.jar by itself.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -Djava.awt.headless=true -jar "$SCRIPT_DIR/BTA-Movil-Installer.jar" "$@"
