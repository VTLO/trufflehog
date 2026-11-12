# Shared toolchain settings for the Android native builds.
# shellcheck shell=bash
set -euo pipefail

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
: "${ANDROID_NDK_HOME:=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)}"
: "${ANDROID_API:=24}"
export ANDROID_HOME ANDROID_NDK_HOME ANDROID_API

if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "Android NDK not found. Set ANDROID_NDK_HOME or install it with sdkmanager 'ndk;27.2.12479018'" >&2
  exit 1
fi

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$ANDROID_DIR/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ANDROID_DIR/build-native}"
JNILIBS_DIR="$ANDROID_DIR/app/src/main/jniLibs"
export ANDROID_DIR REPO_ROOT BUILD_DIR JNILIBS_DIR

# setup_abi <abi> sets compiler variables for an Android ABI.
setup_abi() {
  ABI="$1"
  case "$ABI" in
    arm64-v8a)   TRIPLE=aarch64-linux-android;    CLANG_TRIPLE=aarch64-linux-android;   GOARCH=arm64; GOARM=;  OPENSSL_TARGET=android-arm64 ;;
    armeabi-v7a) TRIPLE=arm-linux-androideabi;    CLANG_TRIPLE=armv7a-linux-androideabi; GOARCH=arm;   GOARM=7; OPENSSL_TARGET=android-arm ;;
    x86_64)      TRIPLE=x86_64-linux-android;     CLANG_TRIPLE=x86_64-linux-android;    GOARCH=amd64; GOARM=;  OPENSSL_TARGET=android-x86_64 ;;
    x86)         TRIPLE=i686-linux-android;       CLANG_TRIPLE=i686-linux-android;      GOARCH=386;   GOARM=;  OPENSSL_TARGET=android-x86 ;;
    *) echo "unknown ABI: $ABI" >&2; exit 1 ;;
  esac
  export ABI TRIPLE GOARCH GOARM OPENSSL_TARGET
  export CC="$TOOLCHAIN/bin/${CLANG_TRIPLE}${ANDROID_API}-clang"
  export CXX="$TOOLCHAIN/bin/${CLANG_TRIPLE}${ANDROID_API}-clang++"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
  PREFIX="$BUILD_DIR/$ABI/prefix"
  export PREFIX
  mkdir -p "$PREFIX" "$JNILIBS_DIR/$ABI"
}
