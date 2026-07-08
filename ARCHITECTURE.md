# Architecture

Two apps + a tiny relay. The phone captures notifications and sends them to the
Mac, end-to-end encrypted. On the same Wi-Fi it goes direct; off-Wi-Fi it goes
through a Cloudflare relay that only ever sees ciphertext.

```
            same Wi-Fi (fast path)                 anywhere (mobile data / other Wi-Fi)
 [Phone] ──encrypted──▶ [Mac LAN server]    [Phone] ──encrypted POST──▶ [Cloudflare relay] ──▶ [Mac]
   secretbox                 :8765                       relay forwards ciphertext only
```

## Apps
- **Mac Bridge** (Android, Kotlin/Jetpack Compose) — runs a `NotificationListenerService`,
  encrypts each notification, and sends it.
- **Android Bridge** (macOS, SwiftUI menu-bar, built with `swiftc` + libsodium) — runs a
  LAN WebSocket server *and* a relay client, decrypts, shows the notifications.
- **relay** (Cloudflare Worker + Durable Object) — one DO per pairing-id; forwards opaque
  blobs between phone and Mac in **both** directions (sockets/POSTs carry a `role`/`to`
  tag, default `mac`). Never decrypts.

## Clipboard sync
- **Mac → phone:** the Mac watches its pasteboard and auto-pushes each copy (skipping
  concealed/password items) to every paired phone — LAN + relay (`?to=phone`), so it
  works off-Wi-Fi. The phone writes it to its clipboard.
- **Phone → Mac:** Android forbids background clipboard reads, so the phone pushes via
  the **share sheet** ("Mac Bridge" share target); the Mac writes it to the pasteboard.
- An echo guard on both ends (remember the last clip written from a received message;
  the Mac also rebaselines its pasteboard change-count) prevents sync loops.

## End-to-end encryption
- NaCl **secretbox** (XSalsa20-Poly1305). Wire format: `base64(nonce[24] || mac+cipher)`.
  Wire-compatible across PyNaCl, Android (Lazysodium), and macOS (libsodium).
- A 32-byte symmetric key is generated on the Mac and shared with the phone via the
  pairing **QR** (`{v:2, key, room, relay, ip, port}`). The relay forwards ciphertext only;
  it cannot read or forge messages.

## Transport (hybrid)
- The phone **dual-sends**: LAN (if the socket is up) *and* the relay as a backstop, so a
  notification is never lost during a network switch. The Mac **de-duplicates** by message id.
- **mDNS / Bonjour**: the Mac advertises `_androidbridge._tcp`; the phone discovers the Mac's
  current LAN IP, so a DHCP/router IP change doesn't require re-pairing.

## Presence
- The phone sends an encrypted **heartbeat**: every 30s over the LAN (no relay traffic when the
  LAN socket is up), or every 60s over the relay when off-LAN. The off-LAN beat rides the phone's
  already-open relay socket (cheap 20:1 WebSocket billing, no Worker request), falling back to a
  POST if that socket isn't ready — so presence is never less reliable than a plain POST. The Mac
  shows **Connected** while it has heard from the phone within ~180s. Data (notifications, clips)
  always dual-sends over LAN **and** the relay, independent of the heartbeat.
- On **unpair**, the phone sends a `bye`; the Mac drops presence immediately.

## Pairing flow
1. Mac shows the QR (carries key + relay coordinates + LAN locator).
2. Phone scans it, stores the config, then **verifies reachability** (LAN socket up, or a relay
   ping that the Mac acknowledges) before showing *Connected*. Failure → error → reset.

## Repo layout
- `android/` — Kotlin app (`./build.sh install`).
- `mac-swift/` — SwiftUI app (`./build.sh` → `build/AndroidBridge.app`). No Xcode/SPM needed;
  uses `swiftc` + static libsodium.
- `relay/` — Cloudflare Worker (`npm run deploy`).
- `dist/` — packaged downloads (DMG + APK).
- `PROTOCOL.md` — exact wire format.

## Security notes
- The pairing key never leaves the two devices. Re-pairing rotates it.
- Transport to the relay is HTTPS/WSS; payloads are additionally E2E encrypted.
- Preview builds are not yet code-signed (macOS notarization) or Play-signed.
