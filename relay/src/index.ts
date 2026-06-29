/**
 * android-bridge relay — Cloudflare Worker + Durable Object.
 *
 * Dumb forwarder. One Durable Object per pairing-id ("room").
 *   - A client opens a WebSocket to  /pair/:id/listen?role=phone|mac  and stays
 *     connected (held cheaply via DO WebSocket Hibernation). `role` defaults to
 *     `mac` for back-compat with existing Mac clients.
 *   - A client POSTs an (end-to-end encrypted) blob to  /pair/:id/notify?to=phone|mac ;
 *     the DO forwards it verbatim to the room's sockets tagged with the target
 *     role only (so a sender never receives its own message). `to` defaults to
 *     `mac` for back-compat with the phone's existing notification POST.
 *
 * The relay never decrypts anything — payloads are opaque ciphertext.
 * Confidentiality + authenticity are the clients' job (NaCl secretbox with
 * the symmetric key exchanged at QR-pair time).
 */

export interface Env {
  ROOMS: DurableObjectNamespace;
}

// pairing-id: url-safe, 8–64 chars
const ROUTE = /^\/pair\/([A-Za-z0-9_-]{8,64})\/(listen|notify)$/;

type Role = "phone" | "mac";

// Validate + default a role/target query param. Returns null on invalid value.
function parseRole(value: string | null, fallback: Role): Role | null {
  if (value === null) return fallback;
  if (value === "phone" || value === "mac") return value;
  return null;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response("android-bridge relay ok\n");
    }
    const m = url.pathname.match(ROUTE);
    if (!m) return new Response("not found", { status: 404 });
    const [, id] = m;
    const stub = env.ROOMS.get(env.ROOMS.idFromName(id));
    return stub.fetch(req);
  },
};

export class Room {
  constructor(private ctx: DurableObjectState) {}

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);

    // --- Client subscribes (WebSocket, hibernatable) ---
    if (url.pathname.endsWith("/listen")) {
      if (req.headers.get("Upgrade") !== "websocket") {
        return new Response("expected websocket", { status: 426 });
      }
      // role defaults to `mac` so existing Mac clients (no ?role) keep working.
      const role = parseRole(url.searchParams.get("role"), "mac");
      if (role === null) {
        return new Response("invalid role", { status: 400 });
      }
      const pair = new WebSocketPair();
      const [client, server] = [pair[0], pair[1]];
      // acceptWebSocket → DO can hibernate while the socket stays open.
      // Tag the socket with its role so notify can target it.
      this.ctx.acceptWebSocket(server, [role]);
      return new Response(null, { status: 101, webSocket: client });
    }

    // --- Client delivers a notification ---
    if (url.pathname.endsWith("/notify")) {
      if (req.method !== "POST") return new Response("POST only", { status: 405 });
      // `to` defaults to `mac` so the phone's existing POST (no ?to) keeps
      // reaching the Mac.
      const to = parseRole(url.searchParams.get("to"), "mac");
      if (to === null) {
        return new Response("invalid target", { status: 400 });
      }
      const body = await req.text();
      if (body.length > 64 * 1024) {
        return new Response("payload too large", { status: 413 });
      }
      // Forward only to sockets tagged with the target role (prevents echo).
      const sockets = this.ctx.getWebSockets(to);
      if (sockets.length === 0) {
        return json({ ok: false, reason: `${to} offline` }, 503);
      }
      let delivered = 0;
      for (const ws of sockets) {
        try { ws.send(body); delivered++; } catch { /* dead socket */ }
      }
      return json({ ok: true, delivered });
    }

    return new Response("not found", { status: 404 });
  }

  // --- WebSocket Hibernation handlers (Mac → relay direction) ---
  async webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer): Promise<void> {
    // Mac sends "ping" as a keepalive; reply so it can detect a dead relay.
    if (msg === "ping") {
      try { ws.send("pong"); } catch { /* ignore */ }
    }
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    try { ws.close(code, "bye"); } catch { /* ignore */ }
  }

  async webSocketError(_ws: WebSocket): Promise<void> {
    /* socket dropped — hibernation cleans it up */
  }
}

function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json" },
  });
}
