# android-bridge relay (Cloudflare Workers + Durable Objects)

Dumb forwarder so phone → Mac works **off the same Wi-Fi**, zero user config.
One Durable Object per pairing-id ("room"):

```
Mac   ──WS────────> /pair/:id/listen   (stays connected, held via DO hibernation)
Phone ──POST blob─> /pair/:id/notify   → DO forwards verbatim to the room's Mac socket
```

The relay **never decrypts**. Payloads are opaque ciphertext — confidentiality +
authenticity are the clients' job (NaCl secretbox keyed by the symmetric secret
exchanged at QR-pair time). Knowing the pairing-id only lets you *deliver* bytes;
without the key the Mac drops them.

## Endpoints
| Method | Path | Who | Purpose |
|--------|------|-----|---------|
| GET (WS upgrade) | `/pair/:id/listen` | Mac | subscribe; receives every forwarded blob |
| POST | `/pair/:id/notify` | Phone | deliver one (encrypted) notification |
| GET | `/health` | — | liveness |

`:id` = url-safe, 8–64 chars. POST body capped at 64 KB. `/notify` returns
`503 {ok:false,reason:"mac offline"}` when no Mac socket is connected.

## Local dev + test
```bash
npm install
npm run dev                 # wrangler dev on http://localhost:8787
node test_relay.mjs         # WS subscribe + POST + assert forward (separate shell)
```

## Deploy (needs your Cloudflare account — free plan is fine)
```bash
npx wrangler login          # interactive, opens browser  (run as: ! npx wrangler login)
npm run deploy              # wrangler deploy
```
Deploy prints your URL, e.g. `https://android-bridge-relay.<subdomain>.workers.dev`.
That base URL goes into the QR payload so the apps know where to reach the relay.

Free plan note: Durable Objects on the free plan require the SQLite backend —
already configured via `new_sqlite_classes` in `wrangler.toml`.

## Cost
Forwarding tiny JSON. Free tier (100k req/day + DO hibernation) covers a personal
deployment and early users comfortably. Hibernation means an idle Mac connection
costs almost nothing.

## Not yet wired (next steps)
- Mac agent: open a WS to `/pair/:id/listen` (in addition to the LAN server) and
  decrypt incoming blobs.
- Android: NaCl-encrypt each notification and POST to `/pair/:id/notify` when off-LAN.
- QR payload: add `relay` base URL + symmetric key; drop the raw LAN IP as the
  sole locator.
- Hybrid: try LAN first, fall back to relay.
