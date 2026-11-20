import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { LoadHttpTransport } from "./load-http-transport.mjs";
import { openObservedSocket } from "./load-websocket-handshake.mjs";

const server = createServer((request, response) => {
  if (request.url === "/slow") return;
  if (request.url === "/aborted") { response.writeHead(200); response.write("partial"); response.destroy(); return; }
  const chunks = [];
  request.on("data", chunk => chunks.push(chunk));
  request.on("end", () => {
    if (request.url === "/limited") response.statusCode = 429;
    response.end(JSON.stringify({ body: Buffer.concat(chunks).toString(), method: request.method, port: request.socket.remotePort }));
  });
});
server.on("upgrade", (request, socket) => {
  const accept = createHash("sha1").update(request.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  socket.once("data", () => socket.end(Buffer.from([0x88, 2, 0x03, 0xe8])));
});
server.listen(0, "127.0.0.1");
await once(server, "listening");
const base = new URL(`http://127.0.0.1:${server.address().port}`);
const http = new LoadHttpTransport();
const send = (path, body, signal = AbortSignal.timeout(5000)) => http.send(new URL(path, base),
  { method: body ? "POST" : "GET", headers: { "Content-Type": "application/json" }, body, signal });
try {
  const first = await send("/", "中文负载");
  assert.equal(first.status, 200);
  const initial = JSON.parse(first.text);
  assert.equal(initial.body, "中文负载");
  assert.equal(initial.method, "POST");
  const second = await send("/limited");
  assert.equal(second.status, 429);
  assert.equal(JSON.parse(second.text).port, initial.port);
  const { socket, handshake } = openObservedSocket(new URL("ws://127.0.0.1:" + server.address().port));
  await once(socket, "open");
  assert.notEqual(handshake.clientPort, initial.port);
  const closed = once(socket, "close");
  socket.close();
  await closed;
  await assert.rejects(send("/slow", undefined, AbortSignal.timeout(50)), { name: "AbortError" });
  await assert.rejects(send("/aborted"));
} finally {
  http.close();
  server.closeAllConnections();
  await new Promise(accept => server.close(accept));
}
console.log("HTTP status, UTF-8 body, keep-alive reuse, WebSocket pool isolation, deadline and interrupted response passed.");
