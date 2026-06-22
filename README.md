# android-bridge

Self-built Pixel → Mac notification bridge. LAN-only MVP. No third-party app, no cloud.

**Goal:** phone notifications (especially OTPs) appear on Mac. Click the menu-bar icon → see recent notifications → each has actions (**Copy OTP** / **Copy message**). A banner pops on arrival so you notice it. **No auto-copy** — the clipboard only changes when you click an action.

## Why this exists
macOS has no Continuity bridge for Android. Off-shelf tools (KDE Connect, Google Messages web) either can't surface OTPs cleanly or force a heavy texting UI. This is the minimal thing: capture every phone notification, ship it to the Mac over the LAN, and let you grab the OTP from the menu bar.

## Architecture
```
[Pixel 10]                         [LAN WebSocket]            [Mac]
NotificationListenerService   →    ws://<mac-ip>:8765    →   rumps menu-bar agent
- capture ALL notifs                                          - banner on arrival
- regex OTP (hint-gated)                                      - dropdown: recent notifs
- OkHttp WebSocket client                                     - Copy OTP / Copy message
```

- **Mac is the server** (listens on `:8765`). Phone connects out to Mac's LAN IP.
- **No auto-copy.** Banner on arrival; click 🌉 → recent notifs, each with **Copy OTP** / **Copy message**.
- Transport = plaintext JSON over WebSocket. **LAN-trust only.** See Security.

## Layout
- `mac/` — Python menu-bar agent. `mac/run.sh` (foreground), `mac/install_login_item.sh` (LaunchAgent: autostart + restart-on-crash).
- `android/` — Kotlin app. Command-line build: `android/build.sh install`.
- `PROTOCOL.md` — wire format.
- `memory/PROJECT.md` — full context, decisions, roadmap.

## Quick start (Mac side)
```bash
cd mac
./run.sh                    # foreground: venv + deps + launch
# or persistent:
./install_login_item.sh     # LaunchAgent, autostarts at login, silent menu bar
```
Menu bar shows a 🌉 icon. Click it for recent notifications + **Copy OTP** / **Copy message**, and **📱 Pair phone**.

## Quick start (Android side)
```bash
cd android
./build.sh install          # JDK17 + SDK env, gradle assembleDebug, adb install
```
Then grant Notification Access on the phone and pair by QR (below).

## Pairing (QR — no USB, no typing)
1. Mac menu bar 🌉 → **📱 Pair phone (show QR)** → a QR opens on screen.
2. Phone → **Mac Bridge** → **📷 Scan QR to pair** → point at the QR.
3. Done — the app saves IP + port + token and auto-connects.

The QR encodes `{"v":1,"ip":<mac-ip>,"port":8765,"token":<hex>}`. The token is a persistent random secret generated on first run, stored at `~/.androidbridge/config.json`. Manual IP entry remains as a fallback (uses the placeholder `BRIDGE_TOKEN`).

## Security (read before trusting OTPs to it)
Plaintext over LAN with a shared token. Anyone on your Wi-Fi who knows the token + Mac IP can inject fake notifications; OTPs in transit are unencrypted. The QR token (random per-install hex) is better than a hardcoded secret, but the channel is still cleartext. **v2 must add TLS** before this is safe on untrusted networks. Fine for a trusted home LAN.

## Roadmap
- v1 (this): LAN, recent-notif menu, Copy OTP / Copy message, QR pairing, LaunchAgent. **One direction: phone → Mac.**
- v2: TLS over the WebSocket; clipboard sync both ways.
- v3: relay server for off-Wi-Fi reach; mDNS discovery so a DHCP IP change doesn't break pairing.
- Calls: out of scope — cellular call audio can't route over LAN (no public Android in-call-audio API).
