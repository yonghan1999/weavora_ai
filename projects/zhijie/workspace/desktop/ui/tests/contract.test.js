import assert from "node:assert/strict";
import test from "node:test";

import { COMMAND_PROTOCOL_VERSION } from "../src/index.js";

test("desktop adapter exposes the versioned command protocol", () => {
  assert.equal(COMMAND_PROTOCOL_VERSION, 1);
});
