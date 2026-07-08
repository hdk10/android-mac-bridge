# android-bridge — Android app ("Mac Bridge")

Captures every phone notification, extracts OTPs, and ships them to the paired Mac —
end-to-end encrypted, over the LAN or the relay. Also does clipboard sync + "open on phone".

## Build

Command line (no Android Studio):

```bash
./build.sh          # debug APK
./build.sh release  # release-signed APK (needs keystore.properties — see below)
./build.sh install  # debug build + adb install to a connected phone
```

Or open the `android/` folder in Android Studio and Run.

### Release signing
A release APK must be signed with your own key (never the debug key):

```bash
STORE_PASS=your-password ./make-release-key.sh   # one-time: creates release.keystore + keystore.properties
./build.sh release
```

`release.keystore` and `keystore.properties` are **gitignored secrets** — back up the
keystore; losing it means you can never update the same app id. See `keystore.properties.example`.

## First-run setup on the phone
1. Launch **Mac Bridge**.
2. Tap **Scan QR to pair** and scan the QR shown by the Android Bridge app on your Mac.
   (No manual IP entry — the QR carries the key + relay + LAN locator.)
3. Tap **Grant** on the *Notification access* card → toggle Mac Bridge ON. Required — Android
   only lets a listener read notifications after this.
4. Tap **Fix** on the *Allow background running* card → mark the app **Unrestricted**, so Android
   doesn't doze the connection when the screen is off.

Both permission cards are shown in-app and deep-link straight to the right settings screen.

## Test
- Trigger a login OTP. Within a second:
  - The Mac posts a notification titled `OTP 123456 — copied`.
  - The OTP is already on the Mac clipboard — just ⌘V.

## Notes / gotchas
- `NotificationListenerService` is system-bound and resilient; the *Unrestricted* battery setting
  keeps the WebSocket alive across doze.
- Off-Wi-Fi delivery goes through the Cloudflare relay (ciphertext only). Same-Wi-Fi goes direct.
