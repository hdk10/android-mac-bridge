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

## Versioning
Add `"v": 1` field in v2 once the schema changes. v1 messages omit it; treat missing `v` as 1.
