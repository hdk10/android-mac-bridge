# android-bridge

Self-built Pixel → Mac notification bridge. LAN-only MVP. No third-party app, no cloud.

**Goal:** phone notifications (especially OTPs) appear on Mac. Click the menu-bar icon → see recent notifications → each has actions (**Copy OTP** / **Copy message**). A banner pops on arrival so you notice it. **No auto-copy** — the clipboard only changes when you click an action.

## Why this exists
macOS has no Continuity bridge for Android. Off-shelf tools (KDE Connect, Google Messages web) either can't auto-copy OTPs or force a heavy texting UI. This is the minimal thing: capture every phone notification, ship it to the Mac over the LAN, auto-extract the OTP, drop it on the clipboard.

## Architecture
```
[Pixel 10]                         [LAN WebSocket]            [Mac]
NotificationListenerService   →    ws://<mac-ip>:8765    →   rumps menu-bar agent
- capture ALL notifs                                          - post macOS notification
- regex OTP (hint-gated)                                      - auto-copy OTP → clipboard
- OkHttp WebSocket client                                     - WebSocket server
```

- **Mac is the server** (listens on `:8765`). Phone connects out to Mac's LAN IP.
- Transport = plaintext JSON over WebSocket. **LAN-trust only.** See Security.

## Layout
- `mac/` — Python menu-bar agent. Runnable now. See `mac/run.sh`.
- `android/` — Kotlin app skeleton. Open in Android Studio, set Mac IP, grant notification access.
- `PROTOCOL.md` — wire format.
- `memory/PROJECT.md` — full context, decisions, roadmap.

## Quick start (Mac side, today)
```bash
cd mac
./run.sh        # creates venv, installs deps, launches menu-bar agent
```
Menu bar shows a 🌉 icon + the IP:port to point the phone at. Click it to see recent notifications; each expands to **Copy OTP** / **Copy message**.

## Quick start (Android side)
See `android/README.md`. Summary: open in Android Studio → set Mac IP in app → grant Notification Access → toggle on.

## Security (read before trusting OTPs to it)
MVP ships plaintext over LAN with a shared token. Anyone on your Wi-Fi who knows the token + Mac IP can inject fake notifications. OTPs in transit are unencrypted. **v2 must add TLS + per-device key pairing** (KDE Connect's model) before this is safe on untrusted networks. Fine for a trusted home LAN MVP.

## Roadmap
- v1 (this): LAN, OTP auto-copy, notif mirror. **One direction: phone → Mac.**
- v2: TLS + device pairing; clipboard sync both ways.
- v3: relay server for off-Wi-Fi reach.
- Calls: out of scope — cellular call audio can't route over LAN (no public Android in-call-audio API).
