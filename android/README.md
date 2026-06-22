# android-bridge — Android app

Captures every phone notification, extracts OTPs, ships them to the Mac over the LAN.

## Build & install
1. Open **Android Studio** → `New project from existing` is not needed; instead:
   - `File → New → Import Project` and select this `android/` folder, **or**
   - create a fresh "Empty Views Activity" project (package `com.lattiq.androidbridge`, min SDK 26) and drop the `.kt` files + manifest below into it. Importing this folder is faster.
2. Confirm the OkHttp dependency is present (see `app/build.gradle.kts`).
3. Set the shared token: in `app/build.gradle.kts`, `BRIDGE_TOKEN` must match `TOKEN` in `mac/bridge.py`.
4. Plug in the Pixel (USB debugging on) → Run.

## First-run setup on the phone
1. Launch **android-bridge**.
2. Enter the Mac's LAN IP (shown in the Mac menu-bar agent, e.g. `192.168.1.50`). Tap **Save**.
3. Tap **Grant Notification Access** → toggle android-bridge ON in the system list. (Required — Android only lets a listener read notifications after this.)
4. Tap **Connect**. Status should read `connected`.

## Test
- Have someone send you an OTP text, or trigger a login OTP. Within a second:
  - Mac posts a notification titled `OTP 123456 — copied`
  - The OTP is already on the Mac clipboard — just ⌘V.

## Notes / gotchas
- **Battery optimization:** Android may kill the listener. Settings → Apps → android-bridge → Battery → **Unrestricted**.
- `NotificationListenerService` is system-bound and fairly resilient, but the foreground service + unrestricted battery keeps the WebSocket alive.
- Same Wi-Fi only. If the phone is on mobile data or a different SSID, it can't reach the Mac (that's v3 relay work).
- Plaintext on LAN — see root `README.md` Security.
