import assert from "node:assert/strict";
import test from "node:test";
import { classifyLoadResponse } from "./load-http-response.mjs";

test("only fixed failure codes reach records, never untrusted response data", () => {
  for (const code of ["", "PRIVATE_TEST_MARKER", { private: "value" }, null]) {
    assert.deepEqual(classifyLoadResponse(429, JSON.stringify({ error: { code }, data: { id: "private" } }), "message-write"), { code: "HTTP_FAILED" });
  }
  assert.deepEqual(classifyLoadResponse(429, '{"error":{"code":"RATE_LIMITED"}}', "message-read"), { code: "RATE_LIMITED" });
  assert.deepEqual(classifyLoadResponse(503, "not JSON", "heartbeat"), { code: "HTTP_FAILED" });
});

test("a successful HTTP status does not make an invalid payload successful", () => {
  for (const text of ["null", "42", "{}", "[]", '{"data":null}', "not JSON"]) {
    assert.deepEqual(classifyLoadResponse(200, text, "heartbeat"), { code: "RESPONSE_INVALID" });
  }
  for (const [operation, data, code] of [["heartbeat", {}, "HEARTBEAT_RESPONSE_INVALID"],
    ["friends-presence", {}, "LIST_RESPONSE_INVALID"], ["message-read", false, "LIST_RESPONSE_INVALID"],
    ["message-write", { id: 7, body: "sample" }, "WRITE_RESPONSE_INVALID"]]) {
    assert.deepEqual(classifyLoadResponse(200, JSON.stringify({ data }), operation, { body: "sample" }), { code });
  }
  for (const [operation, data] of [["heartbeat", { recorded: true }], ["friends-presence", []],
    ["message-read", []], ["message-write", {id: "test-id", body: "sample"}]]) {
    assert.deepEqual(classifyLoadResponse(200, JSON.stringify({ data }), operation, { body: "sample" }), { value: { data }, code: null });
  }
});
