const BASE = "http://localhost:8787";
const ROOM = "testroom_abcd1234";
const got = [];

// Mac role: subscribe via WS
const ws = new WebSocket(`ws://localhost:8787/pair/${ROOM}/listen`);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
ws.onmessage = (e) => got.push(typeof e.data === "string" ? e.data : "(binary)");
console.log("WS connected (Mac role)");

// health check
const h = await fetch(`${BASE}/health`); console.log("health:", (await h.text()).trim());

// Phone role: POST a notification
const payload = JSON.stringify({ enc: "ciphertext-blob-stand-in", n: 1 });
const r = await fetch(`${BASE}/pair/${ROOM}/notify`, { method: "POST", body: payload });
console.log("POST /notify ->", r.status, (await r.json()));

await new Promise(r => setTimeout(r, 300));
console.log("Mac received:", got);

// negative: POST to an empty room (no Mac) -> 503
const r2 = await fetch(`${BASE}/pair/emptyroom_9999/notify`, { method: "POST", body: "x" });
console.log("empty room POST ->", r2.status, (await r2.json()));

ws.close();
console.log(got.length === 1 && got[0] === payload ? "RELAY FORWARD PASS ✅" : "FAIL ❌");
