#!/usr/bin/env bash
# Asserts the locally built image is well-formed. Usage: ./.github/verify-image.sh <image-tag>
set -euo pipefail
img="${1:?usage: verify-image.sh <image-tag>}"

hc=$(docker inspect --format '{{json .Config.Healthcheck}}' "$img")
[ "$hc" != "null" ] || { echo "FAIL: image has no HEALTHCHECK"; exit 1; }
echo "$hc" | grep -q '90s\|90000000000' || { echo "FAIL: start-period is not 90s: $hc"; exit 1; }

docker run --rm --entrypoint ls "$img" -l /app/app.jar >/dev/null \
  || { echo "FAIL: /app/app.jar missing"; exit 1; }

echo "OK: $img has a 90s HEALTHCHECK and /app/app.jar"
