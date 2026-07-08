#!/usr/bin/env bash
# Generate a release signing key + keystore.properties for the Android app.
# The keystore and keystore.properties are gitignored — they are secrets. KEEP A
# BACKUP: lose this key and you can never ship an update to the same app id.
set -euo pipefail
cd "$(dirname "$0")"

KEYSTORE="release.keystore"
PROPS="keystore.properties"
ALIAS="androidbridge"

if [ -f "$KEYSTORE" ]; then echo "$KEYSTORE already exists — refusing to overwrite"; exit 1; fi

: "${STORE_PASS:?set STORE_PASS (keystore password)}"
: "${KEY_PASS:=$STORE_PASS}"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
"$JAVA_HOME/bin/keytool" -genkeypair -v \
  -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -dname "CN=Android Bridge, OU=, O=, L=, S=, C=US"

cat > "$PROPS" <<EOF
storeFile=release.keystore
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF

echo "wrote $KEYSTORE + $PROPS (both gitignored). Back up $KEYSTORE somewhere safe."
