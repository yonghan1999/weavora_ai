import assert from "node:assert/strict";
import test from "node:test";

import { COMMAND_PROTOCOL_VERSION } from "../src/index.js";
import { renderApp } from "../src/views.js";

test("desktop adapter exposes the versioned command protocol", () => {
  assert.equal(COMMAND_PROTOCOL_VERSION, 1);
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
