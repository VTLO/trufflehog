#!/usr/bin/env bash
# Cross-compiles the trufflehog CLI for one Android ABI with cgo enabled, so
# that DNS resolution and TLS go through Android's bionic libc (a CGO_ENABLED=0
# build on Android tries to reach a resolver on 127.0.0.1:53 and fails).
#
# Output: app/src/main/jniLibs/<abi>/libtrufflehog.so (an executable; the
# lib*.so name makes Android extract it to nativeLibraryDir, the only app
# location from which a modern app may exec binaries).
source "$(dirname "$0")/env.sh"
setup_abi "${1:?usage: $0 <abi>}"

VERSION="${TRUFFLEHOG_VERSION:-$(git -C "$REPO_ROOT" describe --tags --always 2>/dev/null || echo dev)}-android"
echo ">> building trufflehog $VERSION for $ABI"
cd "$REPO_ROOT"
CGO_ENABLED=1 GOOS=android GOARCH="$GOARCH" GOARM="$GOARM" CC="$CC" CXX="$CXX" \
  go build -trimpath \
    -ldflags="-s -w -X github.com/trufflesecurity/trufflehog/v3/pkg/version.BuildVersion=$VERSION" \
    -o "$JNILIBS_DIR/$ABI/libtrufflehog.so" .
ls -lh "$JNILIBS_DIR/$ABI/libtrufflehog.so"
