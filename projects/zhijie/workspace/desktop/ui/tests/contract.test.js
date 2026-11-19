import assert from "node:assert/strict";
import test from "node:test";

import { COMMAND_PROTOCOL_VERSION } from "../src/index.js";
import { renderApp } from "../src/views.js";

test("desktop adapter exposes the versioned command protocol", () => {
  assert.equal(COMMAND_PROTOCOL_VERSION, 1);
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
