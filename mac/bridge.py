#!/usr/bin/env python3
"""android-bridge — Mac menu-bar agent.

Listens on a LAN WebSocket for phone notifications. No window, no auto-copy.
Click the menu-bar icon to see recent notifications; each expands to actions
(Copy OTP / Copy message). A banner is posted on arrival so you notice it when
the phone is away.

Pairing: a persistent random token is generated on first run and stored in
~/.androidbridge/config.json. "Pair phone" renders a QR (ip + port + token);
the Android app scans it to configure itself — no USB, no manual IP entry.
"""
import asyncio
import json
import os
import re
import secrets
import socket
import subprocess
import threading
from collections import deque
from functools import partial
from pathlib import Path

import pyperclip
import qrcode
import rumps
import websockets

PORT = 8765
MAX_RECENT = 15

CONFIG_DIR = Path.home() / ".androidbridge"
CONFIG_PATH = CONFIG_DIR / "config.json"
QR_PATH = CONFIG_DIR / "pair_qr.png"


def load_or_create_token() -> str:
    """Persistent per-install token. Generated once, reused across restarts."""
    try:
        if CONFIG_PATH.exists():
            tok = json.loads(CONFIG_PATH.read_text()).get("token")
            if tok:
                return tok
    except (OSError, json.JSONDecodeError):
        pass
    tok = secrets.token_hex(16)
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps({"token": tok}, indent=2))
    os.chmod(CONFIG_PATH, 0o600)
    return tok


TOKEN = load_or_create_token()

OTP_RE = re.compile(r"\b(\d{4,8})\b")
OTP_HINTS = ("otp", "code", "verification", "verify", "password",
             "passcode", "2fa", "one-time", "otp:")

# pkg name -> friendly label for the menu
APP_LABELS = {
    "com.google.android.apps.messaging": "Messages",
    "com.whatsapp": "WhatsApp",
    "com.google.android.gm": "Gmail",
}


def lan_ip() -> str:
    """Best-effort primary LAN IP (no traffic actually sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def extract_otp(*fields: str):
    """Hint-gated OTP extraction. Only returns a number if the text smells like an OTP."""
    blob = " ".join(f for f in fields if f)
    if not blob or not any(h in blob.lower() for h in OTP_HINTS):
        return None
    m = OTP_RE.search(blob)
    return m.group(1) if m else None


def notify(title: str, subtitle: str, text: str) -> None:
    """Post a macOS banner. rumps first; fall back to osascript."""
    try:
        rumps.notification(title, subtitle, text)
    except Exception:
        subprocess.run([
            "osascript", "-e",
            f"display notification {json.dumps(text)} "
            f"with title {json.dumps(title)} subtitle {json.dumps(subtitle)}",
        ], check=False)


def app_label(pkg: str) -> str:
    return APP_LABELS.get(pkg, pkg.split(".")[-1] if pkg else "phone")


class Bridge(rumps.App):
    def __init__(self) -> None:
        super().__init__("🌉", quit_button=None)
        self.ip = lan_ip()
        self.recent: deque = deque(maxlen=MAX_RECENT)   # newest first
        self.inbox: deque = deque()                     # thread-safe handoff from WS thread
        self._qr_cache_key = None                       # (ip, token) the cached QR was built for
        self._qr_image_obj = None                       # cached NSImage of the QR
        self._build_menu()
        # drain the WS inbox on the main thread (rumps menu edits must be main-thread)
        rumps.Timer(self._drain, 1).start()
        threading.Thread(target=self._serve, daemon=True).start()

    # --- menu rendering (main thread only) ---
    def _build_menu(self) -> None:
        self.menu.clear()
        self.menu.add(rumps.MenuItem(f"Listening  {self.ip}:{PORT}"))
        self.menu.add(rumps.separator)

        if not self.recent:
            self.menu.add(rumps.MenuItem("No notifications yet"))
        else:
            for n in self.recent:
                self.menu.add(self._notif_item(n))

        self.menu.add(rumps.separator)
        self.menu.add(self._pair_item())
        self.menu.add(rumps.MenuItem("Clear recent", callback=self._clear))
        self.menu.add(rumps.MenuItem("Quit", callback=rumps.quit_application))

    def _pair_item(self) -> rumps.MenuItem:
        """'Pair phone' parent; hovering shows the QR inline in the submenu."""
        parent = rumps.MenuItem("📱 Pair phone")
        qr = rumps.MenuItem("")
        qr._menuitem.setImage_(self._qr_image())            # QR drawn inside the menu
        parent.add(qr)
        parent.add(rumps.MenuItem(f"{self.ip}:{PORT} · open Mac Bridge → Scan QR"))
        return parent

    def _qr_image(self):
        """NSImage of the pairing QR, cached per (ip, token)."""
        from AppKit import NSImage
        from Foundation import NSMakeSize
        ip = lan_ip()
        key = (ip, TOKEN)
        if self._qr_cache_key == key and self._qr_image_obj is not None:
            return self._qr_image_obj
        self.ip = ip
        payload = json.dumps({"v": 1, "ip": ip, "port": PORT, "token": TOKEN})
        img = qrcode.make(payload)
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        with open(QR_PATH, "wb") as f:
            img.save(f)
        nsimg = NSImage.alloc().initWithContentsOfFile_(str(QR_PATH))
        nsimg.setSize_(NSMakeSize(240, 240))
        self._qr_cache_key = key
        self._qr_image_obj = nsimg
        return nsimg

    def _notif_item(self, n: dict) -> rumps.MenuItem:
        otp = n.get("otp")
        label = app_label(n.get("app", ""))
        preview = (n.get("text") or n.get("title") or "").strip()[:38]
        crown = "🔑 " if otp else ""
        parent = rumps.MenuItem(f"{crown}{label}  ·  {preview}")
        if otp:
            parent.add(rumps.MenuItem(f"Copy OTP  {otp}",
                                      callback=partial(self._copy, otp)))
        full = (n.get("text") or n.get("title") or "")
        parent.add(rumps.MenuItem("Copy message",
                                  callback=partial(self._copy, full)))
        return parent

    # --- callbacks ---
    def _copy(self, value: str, _sender=None) -> None:
        pyperclip.copy(value)

    def _clear(self, _sender=None) -> None:
        self.recent.clear()
        self._build_menu()

    # --- inbox drain (main thread) ---
    def _drain(self, _timer=None) -> None:
        changed = False
        while self.inbox:
            self.recent.appendleft(self.inbox.popleft())
            changed = True
        if changed:
            self._build_menu()

    # --- WebSocket server (own asyncio loop in a daemon thread) ---
    def _serve(self) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        loop.run_until_complete(self._main())
        loop.run_forever()

    async def _main(self) -> None:
        async with websockets.serve(self._handle, "0.0.0.0", PORT):
            await asyncio.Future()  # run forever

    async def _handle(self, ws, *_) -> None:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except (json.JSONDecodeError, TypeError):
                continue
            if msg.get("token") != TOKEN:
                continue
            self._on_notif(msg)

    def _on_notif(self, msg: dict) -> None:
        title = msg.get("title", "") or ""
        text = msg.get("text", "") or ""
        msg["otp"] = msg.get("otp") or extract_otp(text, title)
        try:  # lightweight receive log for debugging/verification
            with open("/tmp/androidbridge_rx.log", "a") as f:
                f.write(f"{msg.get('app')} | {title} | {text} | otp={msg['otp']}\n")
        except OSError:
            pass
        # hand off to main thread for menu rebuild
        self.inbox.append(msg)
        # banner so you notice it (no copy)
        label = app_label(msg.get("app", ""))
        if msg["otp"]:
            notify(f"OTP {msg['otp']}", label, text or title)
        else:
            notify(title or label, label, text)


if __name__ == "__main__":
    Bridge().run()
