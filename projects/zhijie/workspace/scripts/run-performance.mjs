import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { cpus, totalmem } from "node:os";
import { dirname, resolve } from "node:path";
import { scenarioById, validateResult } from "./performance-tool.mjs";

const separator = process.argv.indexOf("--");
const scenarioIndex = process.argv.indexOf("--scenario");
const outputIndex = process.argv.indexOf("--output");
const metricsIndex = process.argv.indexOf("--metrics");
assert.ok(separator > 0 && scenarioIndex > 0 && outputIndex > 0, "Usage: node scripts/run-performance.mjs --scenario C1 --output result.json [--metrics raw.json] -- adapter [args]");
const configuration = JSON.parse(readFileSync("scripts/performance-scenarios.json", "utf8"));
const scenario = scenarioById(configuration, process.argv[scenarioIndex + 1]);
const adapter = process.argv.slice(separator + 1);
assert.ok(adapter.length > 0, "A real load adapter command is required");
const output = resolve(process.argv[outputIndex + 1]);
mkdirSync(dirname(output), { recursive: true });
const startedAt = new Date().toISOString();
const started = performance.now();
const execution = spawnSync(adapter[0], adapter.slice(1), {
  cwd: process.cwd(),
  env: process.env,
  encoding: "utf8",
  shell: false,
  timeout: scenario.durationSeconds * 1000 + 60000,
  maxBuffer: 16 * 1024 * 1024,
});
if (execution.error) throw execution.error;
writeFileSync(`${output}.stdout.log`, execution.stdout ?? "");
writeFileSync(`${output}.stderr.log`, execution.stderr ?? "");
const metrics = metricsIndex > 0 ? JSON.parse(readFileSync(resolve(process.argv[metricsIndex + 1]), "utf8")) : null;
const result = {
  schemaVersion: 1,
  scenarioId: scenario.id,
  plannedScenario: scenario,
  execution: {
    status: execution.status === 0 ? "completed" : "failed",
    startedAt,
    durationSeconds: (performance.now() - started) / 1000,
    command: adapter,
    exitCode: execution.status,
  },
  hostInventory: {
    platform: process.platform,
    architecture: process.arch,
    logicalCpuCount: cpus().length,
    totalMemoryMiB: Math.floor(totalmem() / 1024 / 1024),
  },
  metrics,
  evidence: [`${output}.stdout.log`, `${output}.stderr.log`],
  conclusion: {
    status: execution.status === 0 ? "metrics_required" : "failed",
    reason: "The runner records execution only; capacity or audio acceptance requires reviewed raw metrics and evidence",
  },
};
validateResult(result, scenario, configuration.resourceBudget);
writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`);
console.log(`Recorded ${scenario.id} adapter execution with conclusion ${result.conclusion.status}; no acceptance pass was inferred.`);
