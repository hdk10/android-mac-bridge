# android-bridge wire protocol (v1)

Transport: WebSocket. Mac = server (`ws://<mac-ip>:8765`). Phone = client, connects out.

Encoding: one JSON object per WebSocket text message.

## Phone → Mac: notification

```json
{
  "token": "change-me-shared-secret",
  "app":   "com.google.android.apps.messaging",
  "title": "VM-HDFCBK",
  "text":  "123456 is your OTP for login. Valid 10 min.",
  "otp":   "123456",
  "time":  1750000000000
}
```

| field   | type            | notes                                                        |
|---------|-----------------|--------------------------------------------------------------|
| `token` | string          | shared secret. Mac drops messages whose token mismatches.    |
| `app`   | string          | Android package name that posted the notification.           |
| `title` | string          | notification title (may be empty).                           |
| `text`  | string          | notification body (may be empty).                            |
| `otp`   | string \| null  | OTP extracted on phone. `null` if none detected.             |
| `time`  | number          | epoch millis on phone.                                       |

## Mac behaviour
1. Reject message if `token` != configured token.
2. `otp` = message `otp`, else re-run extraction on `text` then `title` (defensive).
3. If `otp` present → copy to clipboard (`pyperclip`) + post notification titled with the OTP.
4. Else → post a plain mirror notification.

## OTP extraction rule (both sides run it)
- Regex: `\b(\d{4,8})\b`
- Hint-gated: only treat the match as an OTP if title/text (lowercased) contains one of:
  `otp, code, verification, verify, password, passcode, 2fa, one-time, otp:`
- Prevents copying random 6-digit numbers (order totals, phone numbers) as OTPs.

## Clipboard sync (v2)

A `clip` message carries clipboard text in either direction, using the **same**
secretbox envelope and per-pairing key as notifications.

```json
{
  "type":  "clip",
  "clip":  "the copied text",
  "id":    "uuid",
  "did":   "<sender device id>",
  "dname": "<sender device name>",
  "time":  1750000000000
}
```

| field   | type   | notes                                                        |
|---------|--------|--------------------------------------------------------------|
| `type`  | string | `"clip"`.                                                    |
| `clip`  | string | UTF-8 clipboard text (rides in `clip`, not `text`).          |
| `id`    | string | message id — receivers de-dupe by it (LAN + relay dual-send).|
| `did`/`dname` | string | sender identity (Mac id/name, or phone id/name).       |

- **Mac → phone:** the Mac auto-pushes on every copy (a pasteboard-change poller),
  skipping concealed/transient pasteboard items (passwords). Sent over LAN to every
  paired phone **and** via the relay (`?to=phone`) so it works off-Wi-Fi.
- **Phone → Mac:** the phone has no background clipboard read (Android OS restriction),
  so it pushes via the **share sheet** ("Mac Bridge" share target) — sent over LAN +
  relay just like a notification. The Mac writes it to the pasteboard.
- **Echo guard (both sides):** the value last written to the local clipboard from a
  received `clip` is remembered; a copy equal to it is not re-sent. The Mac also
  rebaselines its pasteboard change-count on a programmatic write so the poller does
  not bounce it back.

## Relay direction (v2)

The relay is bidirectional. Sockets and POSTs carry a role/target, both defaulting to
`mac` for v1 back-compat:

- `GET  /pair/:id/listen?role=phone|mac`  — subscribe; socket is tagged with `role` (default `mac`).
- `POST /pair/:id/notify?to=phone|mac`    — forward the blob only to sockets tagged `to` (default `mac`).

So the phone's existing notification POST (no `?to`) still reaches the Mac, and a new
`?to=phone` POST from the Mac reaches the phone's `?role=phone` socket — never echoing
back to the sender's own role.

## Versioning
`v2` adds the `clip` message and the relay `role`/`to` params; both are back-compatible
(missing `role`/`to` ⇒ `mac`). v1 messages omit any `v` field; treat missing `v` as 1.
