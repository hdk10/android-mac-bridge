#!/usr/bin/env bash
# Build the Mac Bridge Android app from the command line (no Android Studio).
# Usage:
#   ./build.sh         # build debug APK
#   ./build.sh install # build + adb install to a connected phone
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$PATH"

echo "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
./gradlew assembleDebug

APK=app/build/outputs/apk/debug/app-debug.apk
echo "APK: $APK"

if [ "${1:-}" = "install" ]; then
  if ! adb devices | grep -qw device; then
    echo "No phone detected. Plug in Pixel, enable USB debugging, run: adb devices"
    exit 1
  fi
  adb install -r "$APK"
  echo "Installed. Launch 'Mac Bridge' on the phone."
fi
