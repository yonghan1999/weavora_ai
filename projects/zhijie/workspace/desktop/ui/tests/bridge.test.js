import assert from "node:assert/strict";
import test from "node:test";

import { CoreBridge, ProductionStore } from "../src/bridge.js";

test("bridge sends real versioned desktop commands without a mock fallback", async () => {
  const calls = [];
  const bridge = new CoreBridge(async (command, payload) => {
    calls.push({ command, payload });
    return { userId: "user", activity: null, service: "CONNECTED" };
  });
  await bridge.login("user@example.com", "password", "device");
  await bridge.join("room", "device", null, "operation");
  assert.deepEqual(calls.map(call => call.command), ["session_login", "activity_join"]);
  assert.equal(calls[1].payload.operationId, "operation");
  assert.throws(() => new CoreBridge(null), /TAURI_UNAVAILABLE/);
});

test("production store publishes authoritative snapshots", () => {
  const store = new ProductionStore();
  let changed = 0;
  store.addEventListener("changed", () => changed++);
  store.apply({ userId: "user", activity: null, service: "CONNECTED" });
  assert.equal(store.state.userId, "user");
  assert.equal(changed, 1);
  assert.throws(() => store.apply(null), /INVALID_SERVER_RESPONSE/);
});
