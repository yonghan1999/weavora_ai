import assert from "node:assert/strict";
import { createWriteStream, mkdirSync, writeFileSync } from "node:fs";
import { once } from "node:events";
import { createInterface } from "node:readline";
import { resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { openObservedSocket } from "./load-websocket-handshake.mjs";
import { LoadBodyTraffic } from "./load-body-traffic.mjs";
import { classifyLoadResponse } from "./load-http-response.mjs";
import { LoadHttpTransport } from "./load-http-transport.mjs";

// 临时测试账号凭据仅从匿名 stdin 读取，原始记录不包含 URL、正文或令牌。
const lines = createInterface({ input: process.stdin });
const [line] = await once(lines, "line");
lines.close();
const config = JSON.parse(line);
const base = new URL(config.baseUrl);
assert.equal(base.protocol, "http:");
assert.equal(base.hostname, "127.0.0.1");
assert.equal(config.accounts.length, 100);
assert.ok(Number.isInteger(config.durationSeconds) && config.durationSeconds >= 90);
const reconnectRounds = config.reconnectRounds ?? 1;
assert.ok(Number.isInteger(reconnectRounds) && reconnectRounds >= 1 && reconnectRounds <= 10);
const output = resolve(config.output);
mkdirSync(output, { recursive: true });
const raw = createWriteStream(resolve(output, "operations.jsonl"), { flags: "wx" });
const totals = { requests: 0, failures: 0, responseBodyBytes: 0, requestBodyBytes: 0, syncEvents: 0, syncFailures: 0, websocketPeak: 0, reconnects: 0, catchupVerified: 0, observedOffline: 0, observedOnline: 0 };
const latencies = [];
let sequence = 0;
let sockets = 0;
let stopping = false;
const bodyTraffic = new LoadBodyTraffic();
const http = new LoadHttpTransport();

function measureBody(field, bytes) {
  if (started && !stopping) bodyTraffic.add(performance.now() - started, field, bytes);
}

async function record(entry) {
  if (!raw.write(`${JSON.stringify(entry)}\n`)) await once(raw, "drain");
}

async function request(actor, operation, path, body) {
  const requestStarted = performance.now();
  const payload = body === undefined ? undefined : JSON.stringify(body);
  measureBody("requestBodyBytes", Buffer.byteLength(payload ?? ""));
  let status = 0;
  let value;
  let bytes = 0;
  let code = "TRANSPORT_FAILED";
  try {
    const response = await http.send(new URL(path, base), {
      method: payload ? "POST" : "GET",
      headers: { Authorization: `Bearer ${config.accounts[actor].accessToken}`, ...(payload ? { "Content-Type": "application/json" } : {}) },
      body: payload,
      signal: AbortSignal.timeout(5000),
    });
    status = response.status;
    const text = response.text;
    bytes = Buffer.byteLength(text);
    measureBody("responseBodyBytes", bytes);
    ({ value, code } = classifyLoadResponse(status, text, operation, body));
  } catch { /* 原始网络异常可能含查询令牌，不写入记录。 */ }
  const finished = performance.now();
  const latencyMs = finished - requestStarted;
  totals.requests++;
  if (code) totals.failures++;
  totals.responseBodyBytes += bytes;
  totals.requestBodyBytes += Buffer.byteLength(payload ?? "");
  latencies.push(latencyMs);
  await record({ at: new Date().toISOString(), workloadElapsedMs: finished - started,
    actor, operation, status, code, latencyMs, responseBodyBytes: bytes });
  return value;
}

async function connect(actor) {
  const account = config.accounts[actor];
  const address = new URL("/api/v1/ws", base);
  address.protocol = "ws:";
  address.searchParams.set("access_token", account.accessToken);
  address.searchParams.set("cursor", String(account.cursor));
  const { socket: ws, handshake } = openObservedSocket(address);
  account.socket = ws;
  let opened = false;
  let intentional = false;
  const closed = new Promise(resolve => ws.addEventListener("close", resolve, { once: true }));
  account.close = () => { intentional = true; ws.close(); return closed; };
  ws.addEventListener("message", event => {
    if (typeof event.data === "string") measureBody("syncBodyBytes", Buffer.byteLength(event.data));
    let code = "SYNC_EVENT_JSON_INVALID";
    let receivedSequence = null;
    try {
      const value = JSON.parse(event.data);
      code = "SYNC_EVENT_SEQUENCE_INVALID";
      assert.ok(Number.isSafeInteger(value.sequence) && value.sequence > 0);
      receivedSequence = value.sequence;
      code = "SYNC_EVENT_SCOPE_INVALID";
      if (value.type === "user.changed") {
        assert.equal(value.groupId, null);
        assert.equal(value.activityId, null);
        assert.equal(value.memberGeneration, null);
        assert.equal(value.policyRevision, null);
        assert.equal(value.payload, "{}");
      } else {
        assert.equal(value.groupId, config.groupId);
      }
      code = "SYNC_EVENT_ORDER_INVALID";
      assert.ok(value.sequence > account.cursor, "each socket must advance without duplicate or reordered events");
      code = "SYNC_MESSAGE_PAYLOAD_INVALID";
      const messageId = value.type === "message.created" ? JSON.parse(value.payload).message_id : null;
      if (value.type === "message.created") assert.equal(typeof messageId, "string");
      account.cursor = value.sequence;
      account.events++;
      if (messageId && messageId === account.expectedMessage) account.catchupReceived = true;
      totals.syncEvents++;
    } catch {
      totals.syncFailures++;
      // 只保存固定类别、索引和序号；不记录异常、事件正文、资源标识或凭据。
      void record({ operation: "sync-event-invalid", actor, code, receivedSequence,
        cursor: account.cursor, intentional, opened });
    }
  });
  ws.addEventListener("error", () => {
    if (!stopping && !intentional) {
      totals.syncFailures++;
      void record({ at: new Date().toISOString(), operation: "sync-error", actor, code: "SYNC_TRANSPORT_ERROR", opened,
        ...(!opened ? { ...handshake } : {}) });
    }
  });
  ws.addEventListener("close", event => {
    if (opened) sockets--;
    if (!stopping && !intentional) {
      totals.syncFailures++;
      void record({ at: new Date().toISOString(), operation: "sync-close", actor, status: event.code,
        code: ["ACCESS_TOKEN_INVALID", "RESYNC_REQUIRED"].includes(event.reason) ? event.reason : "SYNC_CLOSED" });
    }
  });
  await new Promise((accept, reject) => {
    const timer = setTimeout(() => { intentional = true; ws.close(); reject(new Error("SYNC_OPEN_TIMEOUT")); }, 5000);
    ws.addEventListener("open", () => { clearTimeout(timer); opened = true; sockets++; totals.websocketPeak = Math.max(totals.websocketPeak, sockets); accept(); }, { once: true });
    ws.addEventListener("error", () => { clearTimeout(timer); reject(new Error("SYNC_OPEN_FAILED")); }, { once: true });
  });
}

let exitCode = 0;
let started = 0;
let workloadEnd = 0;
function closeSocket(account) {
  return Promise.race([account.close(), delay(5000, undefined, { ref: false }).then(() => { throw new Error("SYNC_CLOSE_TIMEOUT"); })]);
}
async function rotateSession(actor) {
  const account = config.accounts[actor];
  await closeSocket(account);
  const response = await request(actor, "session-refresh", "/api/v1/auth/refresh", { refreshToken: account.refreshToken });
  const next = response?.data;
  const expiry = Date.parse(next?.accessExpiresAt);
  if (next?.sessionId !== account.sessionId || next?.userId !== account.userId || typeof next.accessToken !== "string" ||
      !next.accessToken || next.accessToken === account.accessToken || typeof next.refreshToken !== "string" ||
      !next.refreshToken || next.refreshToken === account.refreshToken || !Number.isFinite(expiry) || expiry <= Date.now() + 60000) {
    throw new Error("SESSION_ROTATION_FAILED");
  }
  Object.assign(account, { accessToken: next.accessToken, refreshToken: next.refreshToken, accessExpiresAt: next.accessExpiresAt });
  await connect(actor);
  account.rotations++;
}
try {
  for (let actor = 0; actor < 100; actor++) {
    config.accounts[actor].cursor = config.cursor;
    config.accounts[actor].events = 0;
    config.accounts[actor].rotations = 0;
    await connect(actor);
    await delay(45);
  }
  assert.equal(sockets, 100);
  const eventsBefore = totals.syncEvents;
  started = performance.now();
  const workers = config.accounts.map(async (account, actor) => {
    await delay(actor * 100);
    let heartbeat = -15000;
    let read = -5000;
    let write = -20000;
    let friends = -10000;
    let reconnected = 0;
    while (performance.now() - started < config.durationSeconds * 1000) {
      const elapsed = performance.now() - started;
      if (Date.parse(account.accessExpiresAt) - Date.now() <= 60000 - actor * 300) await rotateSession(actor);
      // 最后十人暂停心跳超过生产 60 秒窗口，之后恢复，不修改服务端时钟。
      if ((actor < 90 || elapsed >= 68000) && elapsed - heartbeat >= 15000) {
        await request(actor, "heartbeat", "/api/v1/presence/heartbeat", {});
        heartbeat = elapsed;
      }
      if (elapsed - read >= 5000) {
        await request(actor, "message-read", `/api/v1/groups/${config.groupId}/messages?limit=10`);
        read = elapsed;
      }
      if (elapsed - write >= 20000) {
        await request(actor, "message-write", `/api/v1/groups/${config.groupId}/messages`, { body: `load-${actor}-${sequence++}` });
        write = elapsed;
      }
      if (elapsed - friends >= 10000 || (actor === 89 && elapsed >= 64000 && elapsed < 65000 && !totals.observedOffline)) {
        const result = await request(actor, "friends-presence", "/api/v1/friends");
        if (actor === 89 && result?.data) {
          if (elapsed >= 62000 && elapsed < 68000 && result.data.some(item => item.userId === config.accounts[90].userId && item.presence === "OFFLINE")) totals.observedOffline++;
          if (elapsed >= 78000 && result.data.some(item => item.userId === config.accounts[90].userId && item.presence === "ONLINE")) totals.observedOnline++;
        }
        friends = elapsed;
      }
      if (actor < 10 && reconnected < reconnectRounds && elapsed >= 35000 + reconnected * 3000) {
        await closeSocket(account);
        const missed = await request(actor, "message-write", `/api/v1/groups/${config.groupId}/messages`, { body: `catchup-${actor}-${reconnected}` });
        if (!missed?.data?.id) throw new Error("SYNC_OFFLINE_WRITE_FAILED");
        account.expectedMessage = missed.data.id;
        account.catchupReceived = false;
        await connect(actor);
        const deadline = performance.now() + 5000;
        while (!account.catchupReceived && performance.now() < deadline) await delay(25);
        if (!account.catchupReceived) throw new Error("SYNC_CATCHUP_TIMEOUT");
        account.expectedMessage = null;
        totals.catchupVerified++;
        totals.reconnects++;
        reconnected++;
      }
      await delay(50);
    }
  });
  const outcomes = await Promise.allSettled(workers);
  for (const [actor, outcome] of outcomes.entries()) {
    if (outcome.status === "rejected") await record({ operation: "worker-failure", actor,
      code: ["SYNC_OPEN_TIMEOUT", "SYNC_OPEN_FAILED", "SYNC_OFFLINE_WRITE_FAILED", "SYNC_CATCHUP_TIMEOUT", "SYNC_CLOSE_TIMEOUT", "SESSION_ROTATION_FAILED"]
        .includes(outcome.reason?.message) ? outcome.reason.message : "WORKER_ASSERTION_FAILED" });
  }
  assert.ok(outcomes.every(outcome => outcome.status === "fulfilled"), "all load workers must finish without failure");
  assert.equal(sockets, 100);
  assert.equal(totals.reconnects, 10 * reconnectRounds);
  assert.equal(totals.catchupVerified, 10 * reconnectRounds);
  assert.equal(totals.syncFailures, 0);
  assert.ok(totals.syncEvents > eventsBefore + 100, "real sync deliveries required");
  assert.ok(config.accounts.every(account => account.events >= 100), "each of the hundred sockets must receive real events");
  assert.ok(totals.observedOffline > 0 && totals.observedOnline > 0, "real presence expiry and recovery required");
  assert.ok(totals.requests > 3000 && totals.failures === 0, "every recorded operation must succeed in local smoke");
} catch (error) {
  exitCode = 1;
  // 只输出断言/自定义消息，不输出传输 URL、凭据或响应正文。
  await record({ operation: "run-failure", code: error.code === "ERR_ASSERTION" ? "LOAD_ASSERTION_FAILED" : "LOAD_FAILED" });
} finally {
  workloadEnd = performance.now();
  stopping = true;
  http.close();
  for (const account of config.accounts) account.close?.();
  await delay(300);
  raw.end();
  await once(raw, "finish");
  latencies.sort((a, b) => a - b);
  const traffic = bodyTraffic.rows(started ? workloadEnd - started : 0);
  writeFileSync(resolve(output, "body-traffic.jsonl"), traffic.map(sample => JSON.stringify(sample) + "\n").join(""), { flag: "wx" });
  const summary = { schemaVersion: 1, scope: "local-control-plane-smoke", accounts: 100, reconnectRounds, requestedSeconds: config.durationSeconds,
    durationSeconds: started ? (workloadEnd - started) / 1000 : 0, ...totals,
    syncBodyBytesDuringWorkload: traffic.reduce((total, sample) => total + sample.syncBodyBytes, 0),
    operationSuccessRatePercent: totals.requests ? 100 * (totals.requests - totals.failures) / totals.requests : 0,
    p95LatencyMs: latencies[Math.max(0, Math.ceil(latencies.length * 0.95) - 1)] ?? null,
    syncEventsPerAccountMin: Math.min(...config.accounts.map(account => account.events ?? 0)),
    rotationsPerAccountMin: Math.min(...config.accounts.map(account => account.rotations ?? 0)),
    execution: exitCode ? "failed" : "completed", acceptance: config.durationSeconds === 3600 ? "unverified_target_server" : "unverified_target_server_and_3600_seconds",
    limitations: ["same-host", "unlimited-host-resources", "body-bytes-not-wire-bandwidth", "no-media-or-game", "adapter-does-not-measure-host-resources"] };
  writeFileSync(resolve(output, "summary.json"), `${JSON.stringify(summary, null, 2)}\n`, { flag: "wx" });
  console.log(JSON.stringify(summary));
}
process.exitCode = exitCode;
