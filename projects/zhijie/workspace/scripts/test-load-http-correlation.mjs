import assert from "node:assert/strict";
import { once } from "node:events";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import test from "node:test";
import vm from "node:vm";
import { classifyLoadResponse } from "./load-http-response.mjs";
import { classifyTransportFailure, LoadHttpTransport } from "./load-http-transport.mjs";

test("failed load requests record the observed peer port and time window only", async () => {
  const peers = new Map();
  const server = createServer((request, response) => {
    const path = request.url.split("?")[0];
    peers.set(path, { port: request.socket.remotePort, at: Date.now() });
    if (path === "/reset") { request.socket.destroy(); return; }
    if (path === "/slow") return;
    response.statusCode = path === "/limited" ? 429 : 200;
    response.end(path === "/limited" ? '{"error":{"code":"RATE_LIMITED"}}' : '{"data":[]}');
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const http = new LoadHttpTransport();
  try {
    const records = [];
    const totals = { requests: 0, failures: 0, responseBodyBytes: 0, requestBodyBytes: 0 };
    const context = vm.createContext({ base: new URL(`http://127.0.0.1:${server.address().port}`), http, URL, Buffer,
      performance, AbortSignal, started: performance.now(), totals, latencies: [], measureBody() {},
      config: { accounts: [{ accessToken: "PRIVATE_TEST_MARKER" }] }, classifyTransportFailure, classifyLoadResponse,
      record: async row => records.push(row) });
    const source = readFileSync(new URL("./load-control-plane.mjs", import.meta.url), "utf8");
    const start = source.indexOf("async function request(actor,");
    const end = source.indexOf("\nasync function connect(actor)", start);
    assert.ok(start >= 0 && end > start);
    vm.runInContext(source.slice(start, end), context);
    const send = path => context.request(0, "message-read", `${path}?private=PRIVATE_TEST_MARKER`);
    await send("/ok");
    assert.equal(records[0].code, null);
    assert.ok(!Object.hasOwn(records[0], "clientPort") && !Object.hasOwn(records[0], "startedAt"));
    await Promise.all([send("/limited"), send("/reset"), send("/slow")]);
    assert.equal(peers.get("/ok").port, peers.get("/limited").port);
    for (const [path, code] of [["/limited", "RATE_LIMITED"], ["/reset", "HTTP_CONNECTION_RESET"], ["/slow", "HTTP_DEADLINE_EXCEEDED"]]) {
      const row = records.find(item => item.code === code);
      const observed = peers.get(path);
      assert.equal(row.clientPort, observed.port);
      assert.ok(Date.parse(row.startedAt) <= observed.at && observed.at <= Date.parse(row.at));
    }
    assert.equal(totals.failures, 3);
    context.AbortSignal = { timeout: () => AbortSignal.abort() };
    await send("/not-sent");
    assert.equal(peers.has("/not-sent"), false);
    assert.equal(records.at(-1).code, "TRANSPORT_FAILED");
    assert.equal(records.at(-1).clientPort, null);
    assert.equal(totals.failures, 4);
    assert.doesNotMatch(JSON.stringify(records), /PRIVATE_TEST_MARKER|Authorization|127\.0\.0\.1/);
  } finally {
    http.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
