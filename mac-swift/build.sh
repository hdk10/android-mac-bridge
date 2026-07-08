#!/usr/bin/env bash
# Build AndroidBridge.app with swiftc (SwiftPM is broken in this CLT; no Xcode).
# Produces a UNIVERSAL binary (arm64 + x86_64) so it runs on Apple Silicon AND
# Intel Macs. libsodium is static-linked (universal, built from source by
# build-libsodium.sh) so the .app has no runtime dependency.
set -euo pipefail
cd "$(dirname "$0")"

MIN="13.0"                      # macOS floor — MenuBarExtra needs 13; matches Info.plist
ARCHS=(arm64 x86_64)
APP="build/AndroidBridge.app"

# Universal static libsodium (arm64+x86_64). Built once, cached in .libsodium.
./build-libsodium.sh
SODIUM="$PWD/.libsodium"

rm -rf "$APP" build/slices
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" build/slices

echo "compiling universal (${ARCHS[*]})…"
slices=()
for arch in "${ARCHS[@]}"; do
  out="build/slices/AndroidBridge-$arch"
  swiftc -parse-as-library -O \
    -target "${arch}-apple-macosx${MIN}" \
    -import-objc-header sodium-bridge.h \
    -Xcc -I"$SODIUM/include" \
    Sources/*.swift \
    "$SODIUM/lib/libsodium.a" \
    -o "$out"
  slices+=("$out")
done
lipo -create "${slices[@]}" -output "$APP/Contents/MacOS/AndroidBridge"
echo "arch: $(lipo -archs "$APP/Contents/MacOS/AndroidBridge")"

# App icon (.icns) from the shared brand logo, so cmd-tab / notifications / Finder match.
ICON_SRC="../assets/icon.png"
if [ -f "$ICON_SRC" ] && command -v iconutil >/dev/null; then
  ICONSET="$(mktemp -d)/AppIcon.iconset"; mkdir -p "$ICONSET"
  for s in 16 32 128 256 512; do
    sips -z $s $s "$ICON_SRC" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null 2>&1
    sips -z $((s*2)) $((s*2)) "$ICON_SRC" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null 2>&1
  done
  iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns" 2>/dev/null && echo "icon embedded"
fi

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Android Bridge</string>
  <key>CFBundleDisplayName</key><string>Android Bridge</string>
  <key>CFBundleIdentifier</key><string>com.androidbridge.mac</string>
  <key>CFBundleExecutable</key><string>AndroidBridge</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.2</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>${MIN}</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

# Proper (ad-hoc) code signature. Seals the whole bundle so the resource seal is
# valid and the app launches after Gatekeeper is cleared (right-click -> Open, or
# a Developer ID + notarization when available). Signing an unsigned bundle
# otherwise leaves the "no resources" seal warning seen on the old build.
codesign --force --deep --sign - --identifier com.androidbridge.mac "$APP"
codesign --verify --deep --strict "$APP" && echo "codesign ok (adhoc)"

echo "built $APP"
