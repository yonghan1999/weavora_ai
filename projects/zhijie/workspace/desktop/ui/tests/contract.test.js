import assert from "node:assert/strict";
import test from "node:test";

import { COMMAND_PROTOCOL_VERSION } from "../src/index.js";
import { renderApp } from "../src/views.js";

test("desktop adapter exposes the versioned command protocol", () => {
  assert.equal(COMMAND_PROTOCOL_VERSION, 1);
});

test("only current group managers outside the paused room see its reopen action", () => {
  const room = { id: "room", groupId: "group", name: "目标房间", openState: "PAUSED" };
  const state = { userId: "viewer", view: "group", groupId: "group", groups: [{ id: "group", name: "test" }],
    rooms: [room], drafts: {}, groupMembers: [], messages: [] };
  for (const [role, owner, expected] of [["OWNER", "other", true], ["ADMIN", "other", true],
    ["MEMBER", "viewer", true], ["MEMBER", "other", false], [null, "viewer", false]]) {
    state.groupMembers = role ? [{ userId: state.userId, role }] : [];
    room.ownerUserId = owner;
    assert.equal(renderApp(state).includes('data-action="reopen-room"'), expected);
  }
  state.groupMembers = [{ userId: state.userId, role: "ADMIN" }];
  state.activity = { activityId: "activity" }; state.roomId = room.id;
  assert.ok(!renderApp(state).includes('data-action="reopen-room"'));
  state.roomId = "another-room";
  assert.ok(renderApp(state).includes('data-action="reopen-room"'));
  room.openState = "OPEN";
  assert.ok(!renderApp(state).includes('data-action="reopen-room"'));
});

test("only a manager of the idle room can start an activity, without joining it", () => {
  const room = { id: "room", groupId: "group", name: "目标房间", openState: "OPEN" };
  const state = { userId: "viewer", view: "group", groupId: "group", groups: [{ id: "group", name: "test" }],
    rooms: [room], drafts: {}, groupMembers: [], messages: [] };
  for (const [role, owner, expected] of [["OWNER", "other", true], ["ADMIN", "other", true],
    ["MEMBER", "viewer", true], ["MEMBER", "other", false], [null, "viewer", false]]) {
    state.groupMembers = role ? [{ userId: state.userId, role }] : [];
    room.ownerUserId = owner;
    const html = renderApp(state);
    assert.equal(html.includes('data-action="start-room-activity"'), expected);
    assert.equal(html.includes("等待管理者开启活动"), !expected);
    assert.ok(!html.includes('data-action="join-room"'));
  }
});

test("network panel explains only the current activity's unresolved fault", () => {
  const state = { userId: "viewer", view: "room", roomTab: "network", roomId: "r", groupId: "g",
    groups: [{id: "g", name: "group"}], rooms: [{id: "r", name: "room"}], members: [],
    activity: {activityId: "activity", network: "FAILED"}, network: {status: {activityId: "activity", state: "FAILED"}} };
  for (const [code, text] of [["HELPER_UNAVAILABLE", "辅助服务未启动"], ["NETWORK_SUBNET_CONFLICT", "路由重叠"],
      ["TUN_PERMISSION_OR_CREATE_FAILED", "辅助服务权限和驱动"], ["GRANT_EXPIRED", "联机授权已过期"]]) {
    state.network.status.errorCode = code;
    assert.ok(renderApp(state).includes(text), code);
  }
  state.network.status.errorCode = "unknown-sensitive-detail";
  assert.ok(!renderApp(state).includes(state.network.status.errorCode));
  state.network.status.errorCode = "NETWORK_SUBNET_CONFLICT";
  state.network.status.activityId = "old-activity";
  assert.ok(!renderApp(state).includes("路由重叠"));
  state.network.status.activityId = "activity";
  state.activity.network = "CONNECTED";
  assert.ok(!renderApp(state).includes("路由重叠"));
});

test("group chat shows delete controls for the author and current group moderators only", () => {
  const state = { userId: "viewer", view: "group", groupId: "group", groups: [{ id: "group", name: "test" }],
    rooms: [], drafts: {}, groupMembers: [], messages: [
      { id: "mine", authorId: "viewer" }, { id: "other", authorId: "member" },
      { id: "deleted", authorId: "viewer", deleted: true },
    ] };
  for (const role of ["OWNER", "ADMIN", "MEMBER", undefined]) {
    state.groupMembers = role ? [{ userId: "viewer", role }] : [];
    const html = renderApp(state);
    const controls = Array.from(html.matchAll(/data-action="delete-message" data-id="([^"]+)"/g), match => match[1]);
    assert.deepEqual(controls, ["OWNER", "ADMIN"].includes(role) ? ["mine", "other"] : ["mine"], role);
  }
});

test("进房前或语音失败时展示本机设备，与通话状态分开", () => {
  const s = { userId: "me", view: "settings", settingsTab: "voice", groups: [], rooms: [], drafts: {},
    audioDevices: { inputs: [{id: "mic", name: "麦克风 <USB>"}], outputs: [] } };
  for (const state of ["DISCONNECTED", "FAILED"]) {
    s.voice = { state };
    const html = renderApp(s);
    assert.match(html, /麦克风 &lt;USB&gt;/);
    assert.match(html, /未发现输出设备/);
    assert.match(html, /data-audio-device="input"/);
    assert.match(html, /data-action="test-microphone"[^>]*disabled/);
    s.voice.inputDevice = "mic";
    assert.doesNotMatch(renderApp(s), /data-action="test-microphone"[^>]*disabled/);
    if (state === "FAILED") assert.match(html, /语音服务暂不可用/);
  }
  s.audioDevices = null;
  assert.match(renderApp(s), /尚未读取设备列表/);
});
