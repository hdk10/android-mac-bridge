#!/usr/bin/env bash
# Package build/AndroidBridge.app into a drag-to-install DMG:
#   [ AndroidBridge.app ]  --drag-->  [ Applications ]
# Output: ../dist/AndroidBridge.dmg  (universal; runs on Apple Silicon + Intel).
set -euo pipefail
cd "$(dirname "$0")"

APP="build/AndroidBridge.app"
OUT="../dist/AndroidBridge.dmg"
VOL="Android Bridge"

# Build the app first unless told to reuse an existing bundle.
if [ "${1:-}" != "--no-build" ]; then ./build.sh; fi
[ -d "$APP" ] || { echo "missing $APP — run ./build.sh"; exit 1; }

STAGE="$(mktemp -d)/dmg"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"     # drag target

mkdir -p ../dist
rm -f "$OUT"
hdiutil create -volname "$VOL" -srcfolder "$STAGE" -ov -format UDZO "$OUT" >/dev/null
rm -rf "$STAGE"

echo "built $OUT"
hdiutil imageinfo "$OUT" | awk '/Format:/{print "  format:",$2}'
