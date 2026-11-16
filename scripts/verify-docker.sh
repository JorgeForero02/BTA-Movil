#!/bin/bash
# Runs scripts/verify.sh inside Docker (image of scripts/Dockerfile).
# Usage: bash scripts/verify-docker.sh [--full | --game]
#
# The repository is mounted read-only and verified on a copy, so the build leaves nothing in it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Tag = first 12 hex of the Dockerfile sha256. Limits: the build context is scripts/ but the tag
# only tracks the Dockerfile; no @sha256 base-image digest because resolving it needs network.
DOCKERFILE_HASH=$(sha256sum "$ROOT/scripts/Dockerfile" | cut -c1-12)
IMG="bta-movil-verify:$DOCKERFILE_HASH"
if ! docker image inspect "$IMG" >/dev/null 2>&1; then
  docker build -q -t "$IMG" "$ROOT/scripts" >/dev/null
fi
win() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
MOUNTS=(-v "$(win "$ROOT"):/repo:ro")
[ -d "$ROOT/_local" ] && MOUNTS+=(-v "$(win "$ROOT/_local"):/local:ro")
# MSYS_NO_PATHCONV: Git Bash must not rewrite the container paths.
MSYS_NO_PATHCONV=1 docker run --rm "${MOUNTS[@]}" "$IMG" bash -c '
  set -e
  mkdir /tmp/r && cd /repo && tar --exclude=./.git --exclude=./_local -cf - . | (cd /tmp/r && tar -xf -)
  if [ -d /local ]; then mkdir /tmp/r/_local && cp -rs /local/. /tmp/r/_local/; fi
  cd /tmp/r && bash scripts/verify.sh "$@"' _ "$@"
