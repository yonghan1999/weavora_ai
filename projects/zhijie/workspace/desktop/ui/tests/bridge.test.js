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
  await bridge.rooms("group");
  await bridge.join("room", "device", null, "operation");
  await bridge.connectVoice("activity", "lease");
  await bridge.setMuted(true);
  await bridge.setReceiveEnabled(false);
  await bridge.setVolume(0.5, "member");
  await bridge.selectAudioDevice("speaker-stable-id", true);
  await bridge.audioDevices();
  await bridge.testMicrophone();
  await bridge.testOutput();
  await bridge.request("group-member-role", "group", { role: "ADMIN" }, "member");
  assert.deepEqual(calls.map(call => call.command), [
    "session_login", "room_list", "activity_join", "voice_connect", "voice_set_mute",
    "voice_set_receive", "voice_set_volume", "audio_select", "audio_devices", "audio_test_microphone", "audio_test_output", "product_request",
  ]);
  assert.equal(calls[2].payload.operationId, "operation");
  await bridge.connectNetwork("activity", "lease");
  assert.deepEqual(calls[3].payload, { activityId: "activity", leaseId: "lease" });
  assert.deepEqual(calls.at(-1), { command: "network_connect", payload: calls[3].payload });
  assert.deepEqual(calls[7].payload, { deviceId: "speaker-stable-id", output: true });
  assert.deepEqual(calls[11].payload, { resource: "group-member-role", id: "group", body: { role: "ADMIN" }, targetId: "member" });
  assert.throws(() => new CoreBridge(null), /TAURI_UNAVAILABLE/);
});

test("production store publishes authoritative snapshots", () => {
  const store = new ProductionStore();
  let changed = 0;
  store.addEventListener("changed", () => changed++);
  store.apply({ userId: "user", activity: null, service: "CONNECTED" });
  assert.equal(store.state.userId, "user");
  assert.equal(changed, 1);
  store.applyVoice({ state: "CONNECTED", muted: true, receiveEnabled: true });
  assert.equal(store.state.voice.muted, true);
  assert.equal(changed, 2);
  assert.throws(() => store.apply(null), /INVALID_SERVER_RESPONSE/);
});

test("product notifications expose only the native event payload", async () => {
  const original = globalThis.__TAURI__;
  const received = [];
  let subscribed;
  globalThis.__TAURI__ = { event: { listen: async (name, handler) => {
    subscribed = name;
    handler({ payload: { userId: "user" }, event: name, id: 42 });
    return () => {};
  } } };
  try {
    const unsubscribe = await new CoreBridge(async () => {}).onProductChanged(value => received.push(value));
    assert.equal(subscribed, "product-changed");
    assert.deepEqual(received, [{ userId: "user" }]);
    assert.equal(typeof unsubscribe, "function");
  } finally { globalThis.__TAURI__ = original; }
});
