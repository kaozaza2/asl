#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
assets="$here/../app/src/main/assets/vm"
docker buildx build --platform linux/arm64 --output type=local,dest="$here/out" "$here"
mkdir -p "$assets"
cp "$here/out/Image" "$here/out/initrd.img" "$assets/"
ls -l "$assets"
