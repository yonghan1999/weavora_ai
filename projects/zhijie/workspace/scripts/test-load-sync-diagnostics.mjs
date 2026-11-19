import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { openObservedSocket } from "./load-websocket-handshake.mjs";
import { LoadBodyTraffic } from "./load-body-traffic.mjs";
import vm from "node:vm";

// 直接执行负载器的连接入口，注入真实事件顺序，不启动服务或放宽负载断言。
const source = readFileSync("scripts/load-control-plane.mjs", "utf8");
const start = source.indexOf("async function connect(actor) {");
const end = source.indexOf("\nlet exitCode = 0;", start);
assert.ok(start >= 0 && end > start);
const records = [];
const account = { accessToken: "private-access-token", cursor: 0, events: 0, expectedMessage: "private-message" };
class Socket extends EventTarget {
  static latest;
  constructor() {
    super();
    Socket.latest = this;
    queueMicrotask(() => this.dispatchEvent(new Event("open")));
  }
  close() { this.emit("close", { code: 1000, reason: "done" }); }
  emit(type, properties) { this.dispatchEvent(Object.assign(new Event(type), properties)); }
}
const totals = { syncFailures: 0, syncEvents: 0, websocketPeak: 0 };
const traffic = new LoadBodyTraffic();
const metering = { Buffer, measureBody: (field, bytes) => traffic.add(0, field, bytes) };
const context = vm.createContext({
  ...metering, assert, URL, openObservedSocket: address => ({ socket: new Socket(address), handshake: {} }), setTimeout, clearTimeout,
  config: { accounts: [account], groupId: "private-group" },
  base: new URL("http://127.0.0.1:1234"), totals,
  record: async value => records.push(value), sockets: 0, stopping: false,
});
await vm.runInContext(`${source.slice(start, end)}\nconnect(0)`, context);
const socket = Socket.latest;
const event = sequence => JSON.stringify({ sequence, type: "message.created", groupId: "private-group",
  payload: JSON.stringify({ message_id: "private-message" }) });
socket.emit("message", { data: event(1) });
assert.equal(totals.syncEvents, 1);
assert.equal(account.cursor, 1);
assert.equal(account.catchupReceived, true);
const invalid = "private-无效消息";
socket.emit("message", { data: invalid });
assert.deepEqual(traffic.rows(1), [{ second: 0, requestBodyBytes: 0, responseBodyBytes: 0,
  syncBodyBytes: Buffer.byteLength(event(1)) + Buffer.byteLength(invalid) }]);
socket.emit("message", { data: event("private-invalid-sequence") });
socket.emit("message", { data: JSON.stringify({ sequence: 2, groupId: "private-wrong-group" }) });
socket.emit("message", { data: event(1) });
socket.emit("message", { data: JSON.stringify({ sequence: 2, type: "message.created", groupId: "private-group", payload: "private-invalid-payload" }) });
assert.equal(account.cursor, 1, "invalid event must not acknowledge its cursor");
socket.emit("error", { message: "private-transport-url" });
socket.emit("close", { code: 1011, reason: "private-server-reason" });
assert.deepEqual(records.map(value => value.code), [
  "SYNC_EVENT_JSON_INVALID", "SYNC_EVENT_SEQUENCE_INVALID", "SYNC_EVENT_SCOPE_INVALID",
  "SYNC_EVENT_ORDER_INVALID", "SYNC_MESSAGE_PAYLOAD_INVALID", "SYNC_TRANSPORT_ERROR", "SYNC_CLOSED",
]);
assert.equal(totals.syncFailures, 7);
assert.doesNotMatch(JSON.stringify(records), /private-/);
socket.emit("message", { data: event(2) });
assert.equal(account.cursor, 2);
assert.equal(totals.syncEvents, 2);
account.close();
socket.emit("error", {});
assert.equal(totals.syncFailures, 7, "intentional shutdown must not add transport failures");
console.log("Seven injected sync failure categories, cursor retention, subsequent valid delivery and secret filtering passed.");

const server = createServer();
const peerPorts = new Map();
server.on("upgrade", (request, socket) => {
  const scenario = new URL(request.url, "http://localhost").searchParams.get("access_token").slice("private-".length);
  peerPorts.set(scenario, socket.remotePort);
  if (scenario === "accept") {
    const accept = createHash("sha1").update(request.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    socket.once("data", () => socket.end(Buffer.from([0x88, 2, 0x03, 0xe8])));
  } else if (scenario === "disconnect") socket.destroy();
  else setTimeout(() => socket.end(`HTTP/1.1 ${scenario === "limited" ? 429 : 403} private-reason\r\nX-Private: private-header\r\nContent-Length: 0\r\nConnection: close\r\n\r\n`), scenario === "limited" ? 15 : 0);
});
server.listen(0, "127.0.0.1");
await once(server, "listening");
try {
  const scenarios = ["limited", "denied", "disconnect", "accept"];
  const records = [];
  const actual = vm.createContext({ ...metering, assert, URL, openObservedSocket, setTimeout, clearTimeout, stopping: false, sockets: 0,
    config: { accounts: scenarios.map(scenario => ({ accessToken: `private-${scenario}`, cursor: 0 })) },
    base: new URL(`http://127.0.0.1:${server.address().port}`), totals: { syncFailures: 0 },
    record: async value => records.push(value),
  });
  vm.runInContext(source.slice(start, end), actual);
  await Promise.all(scenarios.map((scenario, actor) => scenario === "accept" ? actual.connect(actor) : assert.rejects(actual.connect(actor), /SYNC_OPEN_FAILED/)));
  assert.equal(actual.sockets, 1);
  await actual.config.accounts[3].close();
  assert.equal(actual.sockets, 0);
  const errors = records.filter(entry => entry.operation === "sync-error").sort((left, right) => left.actor - right.actor);
  assert.deepEqual(errors.map(entry => entry.httpStatus), [429, 403, null]);
  assert.deepEqual(errors.map(entry => entry.transportCode), [null, null, "UND_ERR_SOCKET"]);
  assert.deepEqual(errors.map(entry => entry.clientPort), scenarios.slice(0, 3).map(scenario => peerPorts.get(scenario)));
  assert.ok(errors.every(entry => Number.isInteger(entry.clientPort) && Date.parse(entry.startedAt) <= Date.parse(entry.at)));
  assert.ok(errors.every(entry => entry.opened === false && Number.isFinite(Date.parse(entry.at))));
  assert.doesNotMatch(JSON.stringify(records), /private-|access_token|127\.0\.0\.1/);
} finally {
  await new Promise(resolve => server.close(resolve));
}
console.log("Concurrent loopback handshake refusals and socket loss are distinguished without disclosing the URL or response headers.");

const rotationStart = source.indexOf("function closeSocket(account) {");
const rotationEnd = source.indexOf("\ntry {", rotationStart);
assert.ok(rotationStart >= 0 && rotationEnd > rotationStart);
const initial = { userId: "user", sessionId: "session", accessToken: "old-access", refreshToken: "old-refresh",
  accessExpiresAt: new Date(Date.now() + 30000).toISOString(), cursor: 51, rotations: 0 };
const renewed = { userId: initial.userId, sessionId: initial.sessionId, accessToken: "new-access", refreshToken: "new-refresh",
  accessExpiresAt: new Date(Date.now() + 900000).toISOString() };
function runRotation(data, failure) {
  const calls = [];
  const account = { ...initial, close: () => { calls.push("close"); return failure === "close" ? new Promise(() => {}) : Promise.resolve(); } };
  const context = vm.createContext({ config: { accounts: [account] },
    delay: () => failure === "close" ? Promise.resolve() : new Promise(() => {}),
    request: async (actor, operation, path, body) => {
      calls.push("refresh");
      assert.equal(actor, 0);
      assert.equal(operation, "session-refresh");
      assert.equal(path, "/api/v1/auth/refresh");
      assert.equal(body.refreshToken, initial.refreshToken);
      return { data };
    },
    connect: async actor => {
      calls.push("connect");
      assert.equal(actor, 0);
      assert.equal(account.cursor, initial.cursor);
      assert.equal(account.accessToken, renewed.accessToken);
      assert.equal(account.refreshToken, renewed.refreshToken);
      if (failure === "connect") throw new Error("SYNC_OPEN_FAILED");
    },
  });
  return { account, calls, result: vm.runInContext(`${source.slice(rotationStart, rotationEnd)}\nrotateSession(0)`, context) };
}
const success = runRotation(renewed);
await success.result;
assert.deepEqual(success.calls, ["close", "refresh", "connect"]);
assert.equal(success.account.rotations, 1);
for (const invalid of [null, { ...renewed, userId: "other" }, { ...renewed, sessionId: "other" },
  { ...renewed, accessToken: initial.accessToken }, { ...renewed, refreshToken: initial.refreshToken },
  { ...renewed, accessExpiresAt: undefined }, { ...renewed, accessExpiresAt: initial.accessExpiresAt }]) {
  const attempted = runRotation(invalid);
  await assert.rejects(attempted.result, /SESSION_ROTATION_FAILED/);
  assert.deepEqual(attempted.calls, ["close", "refresh"]);
  const { close, ...unchanged } = attempted.account;
  assert.deepEqual(unchanged, initial);
}
const closeFailure = runRotation(renewed, "close");
await assert.rejects(closeFailure.result, /SYNC_CLOSE_TIMEOUT/);
assert.deepEqual(closeFailure.calls, ["close"]);
assert.equal(closeFailure.account.rotations, 0);
const connectFailure = runRotation(renewed, "connect");
await assert.rejects(connectFailure.result, /SYNC_OPEN_FAILED/);
assert.equal(connectFailure.account.rotations, 0);
console.log("Credential rotation preserves the cursor, rejects invalid responses and only counts a successful reconnect.");
