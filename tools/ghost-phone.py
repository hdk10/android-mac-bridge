#!/usr/bin/env python3
"""
Virtual phone — simulate an extra paired phone for multi-device testing.

Reads the Mac's pairing secret from ~/.androidbridge/config.json (same key+room
every paired phone shares) and posts an encrypted notification to the relay with
a *different* device id, so the Mac sees it as a second phone.

Usage:
  python3 tools/ghost-phone.py                       # one notification, default ghost
  python3 tools/ghost-phone.py --did ghost-2 --name "Ghost Nexus" --text "hi from ghost 2"
  python3 tools/ghost-phone.py --heartbeat 30        # stay "present": ping every 30s + a notif
  python3 tools/ghost-phone.py --otp 482913          # OTP banner

Requires: pip install pynacl requests
"""
import argparse, json, time, uuid, base64, pathlib, sys

try:
    from nacl.secret import SecretBox
    from nacl.utils import random as nacl_random
    import requests
except ImportError:
    sys.exit("pip install pynacl requests")

CONFIG = pathlib.Path.home() / ".androidbridge" / "config.json"
RELAY_BASE = "android-bridge-relay.hardikkatyarmal123.workers.dev"


def load_secret():
    cfg = json.loads(CONFIG.read_text())
    key = base64.b64decode(cfg["key"])          # 32-byte secretbox key
    return key, cfg["room"]


def seal(key, plaintext):
    box = SecretBox(key)
    nonce = nacl_random(SecretBox.NONCE_SIZE)   # 24 bytes
    ct = box.encrypt(plaintext.encode(), nonce) # .ciphertext is nonce||mac+cipher? -> use .encrypt result
    return base64.b64encode(ct.nonce + ct.ciphertext).decode()


def post(room, blob):
    r = requests.post(f"https://{RELAY_BASE}/pair/{room}/notify",
                      data=blob.encode(),
                      headers={"User-Agent": "ghost-phone/1.0"}, timeout=15)
    try:
        delivered = r.json().get("delivered", "?")
    except Exception:
        delivered = r.text[:80]
    print(f"  -> {r.status_code} delivered={delivered}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--did", default="ghost-1")
    ap.add_argument("--name", default="Ghost Pixel")
    ap.add_argument("--app", default="com.ghost.test")
    ap.add_argument("--title", default="Ghost")
    ap.add_argument("--text", default="hello from a virtual phone")
    ap.add_argument("--otp", default=None)
    ap.add_argument("--heartbeat", type=int, default=0,
                    help="seconds between pings; keeps the ghost 'present'")
    args = ap.parse_args()

    key, room = load_secret()
    print(f"ghost did={args.did!r} name={args.name!r} room={room}")

    def notif():
        payload = {
            "id": str(uuid.uuid4()), "app": args.app,
            "title": args.title, "text": args.text,
            "otp": args.otp, "time": int(time.time() * 1000),
            "key": "ghost-key", "did": args.did, "dname": args.name,
        }
        print(f"notif: {args.title} | {args.text}")
        post(room, seal(key, json.dumps(payload)))

    def ping():
        payload = {"type": "ping", "did": args.did, "dname": args.name}
        post(room, seal(key, json.dumps(payload)))

    notif()
    if args.heartbeat > 0:
        print(f"heartbeat every {args.heartbeat}s — Ctrl-C to stop (sends 'bye')")
        try:
            while True:
                time.sleep(args.heartbeat)
                ping()
        except KeyboardInterrupt:
            post(room, seal(key, json.dumps({"type": "bye", "did": args.did, "dname": args.name})))
            print("\nbye sent — ghost dropped")


if __name__ == "__main__":
    main()
