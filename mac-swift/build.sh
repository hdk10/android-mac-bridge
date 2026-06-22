#!/usr/bin/env bash
# Build MacBridge.app with swiftc (SwiftPM is broken in this CLT; no Xcode).
# libsodium is static-linked so the .app has no runtime dependency.
set -euo pipefail
cd "$(dirname "$0")"

SODIUM="$(brew --prefix libsodium)"
APP="build/AndroidBridge.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

echo "compiling…"
swiftc -parse-as-library -O \
  -import-objc-header sodium-bridge.h \
  -Xcc -I"$SODIUM/include" \
  Sources/*.swift \
  "$SODIUM/lib/libsodium.a" \
  -o "$APP/Contents/MacOS/AndroidBridge"

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

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Android Bridge</string>
  <key>CFBundleDisplayName</key><string>Android Bridge</string>
  <key>CFBundleIdentifier</key><string>com.lattiq.androidbridge.mac</string>
  <key>CFBundleExecutable</key><string>AndroidBridge</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.2</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

echo "built $APP"
