<p align="center">
  <img src="assets/icon.png" width="96" alt="">
</p>

<h1 align="center">Android &lt;&gt; Mac Bridge</h1>

<p align="center">Your Android notifications, OTPs &amp; clipboard, on your Mac — wherever you are.</p>

---

Pair once by scanning a QR, and your phone and Mac stay in sync. Same Wi-Fi or anywhere
over the internet. End-to-end encrypted.

## Features

- **Notifications & texts** — your phone's notifications mirror to the Mac, grouped into
  **SMS** and **Apps** tabs.
- **OTPs** — one-time codes are detected and surfaced; click to copy.
- **Clipboard sync** — copy on the Mac and it auto-lands on the phone; share text from the
  phone (**Share → Mac Bridge**) and it lands on the Mac. Passwords (concealed clipboard)
  are skipped.
- **Anywhere** — same Wi-Fi goes direct (fast); off-Wi-Fi rides an encrypted relay.
- **Multi-device** — pair several phones / Macs at once.

## Download

| | |
|--|--|
| **Mac** — menu-bar app | [AndroidBridge.dmg](dist/AndroidBridge.dmg) |
| **Android** — "Mac Bridge" | [MacBridge.apk](dist/MacBridge.apk) |

> Preview builds (not yet notarized / Play-signed). On macOS, right-click the app → **Open** the first time.

## Setup

1. Install both apps.
2. On the Mac, open **Android Bridge** (menu bar) → **Pair phone** — it shows a QR.
3. On the phone, open **Mac Bridge** → **Scan QR to pair**.

That's it. Notifications appear on your Mac; click to copy an OTP.

**Clipboard:** copying on the Mac auto-sends to the phone. To go the other way, select
text on the phone → **Share** → **Mac Bridge**.

## Private by design

End-to-end encrypted — the relay that carries off-Wi-Fi traffic only ever sees ciphertext, never your messages.

<sub>How it works → <a href="ARCHITECTURE.md">ARCHITECTURE.md</a></sub>
