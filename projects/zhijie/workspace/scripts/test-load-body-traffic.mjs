import assert from "node:assert/strict";
import test from "node:test";
import { LoadBodyTraffic } from "./load-body-traffic.mjs";

test("counts directions, UTF-8 bytes, empty seconds and a partial final second", () => {
  const traffic = new LoadBodyTraffic();
  assert.deepEqual(traffic.rows(0), []);
  traffic.add(999.9, "requestBodyBytes", Buffer.byteLength("群"));
  traffic.add(1000, "syncBodyBytes", 9);
  traffic.add(1001, "syncBodyBytes", 4);
  traffic.add(3000, "responseBodyBytes", 27);
  const values = duration => traffic.rows(duration).map(row =>
    [row.second, row.requestBodyBytes, row.responseBodyBytes, row.syncBodyBytes]);
  assert.deepEqual(values(3000), [[0, 3, 0, 0], [1, 0, 0, 13], [2, 0, 0, 0]]);
  assert.deepEqual(values(3001).at(-1), [3, 0, 27, 0]);
});
