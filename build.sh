#!/usr/bin/env bash
# One-command build: unit tests -> APK (debug signing). For a release-signed
# APK, pass the signing material via environment variables and run
# ./build-apk.sh release instead (see build-apk.sh header).
set -euo pipefail
cd "$(dirname "$0")"
exec ./build-apk.sh debug
