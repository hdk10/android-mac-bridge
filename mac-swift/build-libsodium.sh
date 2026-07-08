#!/usr/bin/env bash
# Build a UNIVERSAL (arm64 + x86_64) static libsodium from source into ./.libsodium.
# Used by build.sh so the shipped .app runs on both Apple Silicon and Intel Macs
# without depending on Homebrew's host-only arch. Idempotent: skips if already universal.
set -euo pipefail
cd "$(dirname "$0")"

VER="1.0.20"
DEST="$PWD/.libsodium"
LIB="$DEST/lib/libsodium.a"
MIN="13.0"   # keep in sync with LSMinimumSystemVersion / build.sh

# Already built and universal? done.
if [ -f "$LIB" ] && lipo -info "$LIB" 2>/dev/null | grep -q "arm64" && lipo -info "$LIB" 2>/dev/null | grep -q "x86_64"; then
  echo "libsodium universal already present: $LIB"
  exit 0
fi

WORK="$(mktemp -d)"
TARBALL="libsodium-${VER}-stable.tar.gz"
echo "downloading libsodium ${VER}…"
curl -fsSL "https://download.libsodium.org/libsodium/releases/$TARBALL" -o "$WORK/$TARBALL"
tar -xzf "$WORK/$TARBALL" -C "$WORK"
SRC="$WORK/libsodium-stable"

build_arch() {
  local arch="$1" pfx="$2"
  echo "building libsodium ($arch)…"
  ( cd "$SRC" && make distclean >/dev/null 2>&1 || true
    CFLAGS="-arch $arch -mmacosx-version-min=$MIN -O2" \
    LDFLAGS="-arch $arch" \
    ./configure --host="${arch}-apple-darwin" --prefix="$pfx" \
      --disable-shared --enable-static --disable-dependency-tracking >/dev/null
    make -j"$(sysctl -n hw.ncpu)" >/dev/null
    make install >/dev/null )
}

build_arch arm64  "$WORK/out-arm64"
build_arch x86_64 "$WORK/out-x86_64"

mkdir -p "$DEST/lib" "$DEST/include"
cp -R "$WORK/out-arm64/include/." "$DEST/include/"
lipo -create "$WORK/out-arm64/lib/libsodium.a" "$WORK/out-x86_64/lib/libsodium.a" -output "$LIB"

echo "universal libsodium → $LIB"
lipo -info "$LIB"
rm -rf "$WORK"
