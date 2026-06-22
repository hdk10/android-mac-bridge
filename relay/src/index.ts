/**
 * android-bridge relay — Cloudflare Worker + Durable Object.
 *
 * Dumb forwarder. One Durable Object per pairing-id ("room").
 *   - Mac opens a WebSocket to  /pair/:id/listen   and stays connected
 *     (held cheaply via DO WebSocket Hibernation).
 *   - Phone POSTs an (end-to-end encrypted) blob to  /pair/:id/notify ;
 *     the DO forwards it verbatim to the room's Mac socket(s).
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
  constructor(private ctx: DurableObjectState, private env: Env) {}

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);

    // --- Mac subscribes (WebSocket, hibernatable) ---
    if (url.pathname.endsWith("/listen")) {
      if (req.headers.get("Upgrade") !== "websocket") {
        return new Response("expected websocket", { status: 426 });
      }
      const pair = new WebSocketPair();
      const [client, server] = [pair[0], pair[1]];
      // acceptWebSocket → DO can hibernate while the socket stays open
      this.ctx.acceptWebSocket(server);
      return new Response(null, { status: 101, webSocket: client });
    }

    // --- Phone delivers a notification ---
    if (url.pathname.endsWith("/notify")) {
      if (req.method !== "POST") return new Response("POST only", { status: 405 });
      const body = await req.text();
      if (body.length > 64 * 1024) {
        return new Response("payload too large", { status: 413 });
      }
      const sockets = this.ctx.getWebSockets();
      if (sockets.length === 0) {
        return json({ ok: false, reason: "mac offline" }, 503);
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

  async webSocketError(ws: WebSocket): Promise<void> {
    /* socket dropped — hibernation cleans it up */
  }
}

function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json" },
  });
}
