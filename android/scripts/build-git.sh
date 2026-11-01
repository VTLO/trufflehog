#!/usr/bin/env bash
# Cross-compiles git (with HTTPS support via libcurl + OpenSSL + zlib, all
# linked statically) for one Android ABI. trufflehog's git, github, gitlab,
# huggingface and related sources shell out to the `git` CLI, which Android
# does not ship. This step is optional: without it the APK still works for
# every source that does not need git (filesystem, s3, gcs, docker, postman,
# jenkins, elasticsearch, syslog, analyze, ...).
#
# Output: app/src/main/jniLibs/<abi>/libgit.so and libgit-remote-http.so.
# The app symlinks them to `git`, `git-remote-http` and `git-remote-https`
# at runtime.
source "$(dirname "$0")/env.sh"
setup_abi "${1:?usage: $0 <abi>}"

ZLIB_VERSION=1.3.1
OPENSSL_VERSION=3.3.2
CURL_VERSION=8.10.1
GIT_VERSION=2.47.0

SRC="$BUILD_DIR/src"
WORK="$BUILD_DIR/$ABI/work"
mkdir -p "$SRC" "$WORK"
JOBS="$(nproc)"

fetch() { # url
  local f
  f="$SRC/$(basename "$1")"
  [ -f "$f" ] || curl -fsSL -o "$f" "$1"
}
unpack() { # tarball dir
  rm -rf "${WORK:?}/$2"
  tar -C "$WORK" -xf "$SRC/$1"
}

fetch "https://zlib.net/fossils/zlib-$ZLIB_VERSION.tar.gz"
fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
fetch "https://curl.se/download/curl-$CURL_VERSION.tar.xz"
fetch "https://mirrors.edge.kernel.org/pub/software/scm/git/git-$GIT_VERSION.tar.xz"

export CFLAGS="-O2 -fPIC"

if [ ! -f "$PREFIX/lib/libz.a" ]; then
  echo ">> zlib ($ABI)"
  unpack "zlib-$ZLIB_VERSION.tar.gz" "zlib-$ZLIB_VERSION"
  (cd "$WORK/zlib-$ZLIB_VERSION" && CHOST="$TRIPLE" ./configure --static --prefix="$PREFIX" &&
    make -j"$JOBS" && make install) > "$WORK/zlib.log" 2>&1 || { tail -40 "$WORK/zlib.log"; exit 1; }
fi

if [ ! -f "$PREFIX/lib/libssl.a" ]; then
  echo ">> openssl ($ABI)"
  unpack "openssl-$OPENSSL_VERSION.tar.gz" "openssl-$OPENSSL_VERSION"
  (cd "$WORK/openssl-$OPENSSL_VERSION" &&
    ./Configure "$OPENSSL_TARGET" -D__ANDROID_API__="$ANDROID_API" no-shared no-tests no-docs no-apps no-engine \
      --prefix="$PREFIX" --libdir=lib --openssldir=/system/etc/security &&
    make -j"$JOBS" build_libs && make install_dev) > "$WORK/openssl.log" 2>&1 || { tail -40 "$WORK/openssl.log"; exit 1; }
fi

if [ ! -f "$PREFIX/lib/libcurl.a" ]; then
  echo ">> curl ($ABI)"
  unpack "curl-$CURL_VERSION.tar.xz" "curl-$CURL_VERSION"
  (cd "$WORK/curl-$CURL_VERSION" &&
    ./configure --host="$TRIPLE" --prefix="$PREFIX" --disable-shared --enable-static \
      --with-openssl="$PREFIX" --with-zlib="$PREFIX" \
      --without-libpsl --without-brotli --without-zstd --without-libidn2 --without-nghttp2 --without-libssh2 \
      --disable-ldap --disable-ldaps --disable-manual --disable-docs \
      --without-ca-bundle --without-ca-path --without-ca-fallback &&
    make -j"$JOBS" -C lib && make -C lib install && make -C include install &&
    make install-pkgconfigDATA install-binSCRIPTS) > "$WORK/curl.log" 2>&1 || { tail -40 "$WORK/curl.log"; exit 1; }
fi

echo ">> git ($ABI)"
unpack "git-$GIT_VERSION.tar.xz" "git-$GIT_VERSION"
GIT_MAKE_FLAGS=(
  CC="$CC" AR="$AR" RANLIB="$RANLIB"
  uname_S=Linux uname_M="$GOARCH" uname_O=Android
  prefix=/usr
  CFLAGS="-O2 -I$PREFIX/include"
  LDFLAGS="-L$PREFIX/lib"
  CURLDIR="$PREFIX"
  CURL_LDFLAGS="-L$PREFIX/lib -lcurl -lssl -lcrypto -lz"
  CURL_CONFIG="$PREFIX/bin/curl-config"
  ZLIB_PATH="$PREFIX"
  OPENSSLDIR="$PREFIX"
  NO_GETTEXT=YesPlease NO_TCLTK=YesPlease NO_PERL=YesPlease NO_PYTHON=YesPlease
  NO_EXPAT=YesPlease NO_ICONV=YesPlease NO_REGEX=NeedsStartEnd
  NO_INSTALL_HARDLINKS=YesPlease HAVE_DEV_TTY=YesPlease
)
(cd "$WORK/git-$GIT_VERSION" && make -j"$JOBS" "${GIT_MAKE_FLAGS[@]}" git git-remote-http) > "$WORK/git.log" 2>&1 \
  || { tail -40 "$WORK/git.log"; exit 1; }

cp "$WORK/git-$GIT_VERSION/git" "$JNILIBS_DIR/$ABI/libgit.so"
cp "$WORK/git-$GIT_VERSION/git-remote-http" "$JNILIBS_DIR/$ABI/libgit-remote-http.so"
"$STRIP" "$JNILIBS_DIR/$ABI/libgit.so" "$JNILIBS_DIR/$ABI/libgit-remote-http.so"
ls -lh "$JNILIBS_DIR/$ABI/"libgit*.so
