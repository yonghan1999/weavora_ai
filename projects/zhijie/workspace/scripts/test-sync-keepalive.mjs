import assert from "node:assert/strict";
import { createInterface } from "node:readline";
import { once } from "node:events";
import { Agent, request } from "node:http";
import { randomBytes } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import { openObservedSocket } from "./load-websocket-handshake.mjs";
import { classifyTransportFailure, LoadHttpTransport } from "./load-http-transport.mjs";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const lines = createInterface({ input: process.stdin });
const [line] = await once(lines, "line");
lines.close();
const config = JSON.parse(line);
const agent = new Agent({ keepAlive: true, maxSockets: 1 });
const http = new LoadHttpTransport();
let peerPort;
try {
  for (let index = 0; index < 99; index++) {
    await new Promise((accept, reject) => {
      const outgoing = request({ host: "127.0.0.1", port: config.port, path: "/actuator/health", agent }, response => {
        assert.equal(response.statusCode, 200);
        response.resume();
        response.on("end", accept);
      });
      outgoing.on("socket", socket => {
        const connected = () => {
          if (peerPort === undefined) peerPort = socket.localPort;
          else assert.equal(socket.localPort, peerPort);
        };
        if (socket.connecting) socket.once("connect", connected);
        else connected();
      });
      outgoing.on("error", () => reject(new Error("HTTP_FAILED")));
      outgoing.end();
    });
    await delay(40);
  }
  const result = await new Promise((accept, reject) => {
    const outgoing = request({ host: "127.0.0.1", port: config.port,
      path: `/api/v1/ws?cursor=${config.cursor}`, agent,
      headers: { Authorization: `Bearer ${config.accessToken}`, Connection: "Upgrade", Upgrade: "websocket",
        "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": randomBytes(16).toString("base64") } });
    outgoing.on("upgrade", (response, socket) => {
      const value = { status: response.statusCode, sameConnection: socket.localPort === peerPort,
        upgrade: response.headers.connection.toLowerCase().split(",").map(token => token.trim()).includes("upgrade"),
        close: response.headers.connection.toLowerCase().split(",").map(token => token.trim()).includes("close") };
      socket.destroy();
      accept(value);
    });
    outgoing.on("response", response => { response.resume(); reject(new Error("UPGRADE_REJECTED")); });
    outgoing.on("error", () => reject(new Error("UPGRADE_FAILED")));
    outgoing.end();
  });
  console.log(JSON.stringify({ requestNumber: 100, ...result }));
  assert.equal(result.status, 101);
  assert.equal(result.sameConnection, true);
  assert.equal(result.upgrade, true);
  assert.equal(result.close, true, "the server enforces its existing HTTP keep-alive limit");
  const source = readFileSync("scripts/load-control-plane.mjs", "utf8");
  const start = source.indexOf("async function request(actor, operation, path, body) {");
  const end = source.indexOf("\nasync function connect(actor) {", start);
  assert.ok(start >= 0 && end > start);
  const context = vm.createContext({ http, classifyTransportFailure, fetch, URL, Buffer, AbortSignal, performance, started: performance.now(),
    base: new URL(`http://127.0.0.1:${config.port}`), config: { accounts: [config] },
    measureBody() {}, classifyLoadResponse: (status, text) => ({ value: JSON.parse(text), code: status === 200 ? null : "FAILED" }),
    totals: { requests: 0, failures: 0, requestBodyBytes: 0, responseBodyBytes: 0 }, latencies: [], record: async () => {},
  });
  vm.runInContext(source.slice(start, end), context);
  for (let index = 0; index < 99; index++) {
    assert.equal((await context.request(0, "health", "/actuator/health")).status, "UP");
    await delay(40);
  }
  assert.equal(context.totals.failures, 0);
  const connection = openObservedSocket(`ws://127.0.0.1:${config.port}/api/v1/ws?cursor=${config.cursor}&access_token=${config.accessToken}`);
  const deadline = setTimeout(() => connection.socket.close(), 5000);
  try {
    await new Promise((accept, reject) => {
      connection.socket.addEventListener("open", accept, { once: true });
      connection.socket.addEventListener("error", () => reject(new Error("ISOLATED_UPGRADE_FAILED")), { once: true });
      connection.socket.addEventListener("close", () => reject(new Error("ISOLATED_UPGRADE_CLOSED")), { once: true });
    });
  } finally { clearTimeout(deadline); }
  const closed = new Promise(accept => connection.socket.addEventListener("close", accept, { once: true }));
  connection.socket.close();
  await closed;
  assert.notEqual(connection.handshake.clientPort, peerPort);
  console.log("A separate WebSocket connection opens at the HTTP keep-alive boundary without weakening the server limit.");
} finally { agent.destroy(); http.close(); }
