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
  await bridge.connectVoice("activity");
  await bridge.setMuted(true);
  await bridge.setReceiveEnabled(false);
  await bridge.setVolume(0.5, "member");
  await bridge.selectAudioDevice(2, true);
  assert.deepEqual(calls.map(call => call.command), [
    "session_login", "room_list", "activity_join", "voice_connect", "voice_set_mute",
    "voice_set_receive", "voice_set_volume", "audio_select",
  ]);
  assert.equal(calls[2].payload.operationId, "operation");
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
