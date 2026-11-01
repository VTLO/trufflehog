#!/usr/bin/env bash
# Builds the VTLOs3c standalone APK (trufflehog for Android).
#
#   ./build.sh                     # arm64-v8a + x86_64, without git
#   ABIS="arm64-v8a" ./build.sh    # only phones
#   WITH_GIT=1 ./build.sh          # also cross-compile git (enables git/github/gitlab/huggingface sources)
#
# Requirements: Go (version from ../go.mod), JDK 17+, Gradle 8.9+, Android SDK
# with platforms;android-34, build-tools;34.0.0 and an NDK (r26+).
set -euo pipefail
cd "$(dirname "$0")"
source scripts/env.sh

ABIS="${ABIS:-arm64-v8a x86_64}"
for abi in $ABIS; do
  scripts/build-trufflehog.sh "$abi"
  if [ "${WITH_GIT:-0}" = "1" ]; then
    scripts/build-git.sh "$abi"
  fi
done

echo "sdk.dir=$ANDROID_HOME" > local.properties
GRADLE="${GRADLE:-$(command -v ./gradlew || command -v gradle)}"
"$GRADLE" --no-daemon assembleRelease -PtruffleAbis="$(echo "$ABIS" | tr ' ' ',')" \
  -PtruffleVersion="$(git -C .. describe --tags --always 2>/dev/null || echo dev)"

mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/VTLOs3c.apk
echo "APK: $(pwd)/dist/VTLOs3c.apk"
