import assert from "node:assert/strict";

const metricNames = [
  "cpuPeakPercent",
  "memoryPeakMiB",
  "upstreamPeakKbitPerSecond",
  "downstreamPeakKbitPerSecond",
  "operationSuccessRatePercent",
  "p95LatencyMs",
  "sampleCount",
];

export function validateScenario(scenario) {
  assert.match(scenario.id, /^C[0-4]$/, "Unknown capacity scenario");
  assert.ok(scenario.capacityClass);
  assert.ok(Number.isInteger(scenario.accounts) && scenario.accounts >= 0);
  assert.ok(Number.isInteger(scenario.durationSeconds) && scenario.durationSeconds > 0);
  assert.ok(Array.isArray(scenario.rooms));
  assert.ok(Array.isArray(scenario.workloads) && scenario.workloads.length > 0);
  assert.ok(Array.isArray(scenario.evidenceRequirements) && scenario.evidenceRequirements.length > 0);
  const roomAccounts = scenario.rooms.reduce((total, room) => total + room.members * (room.count ?? 1), 0);
  assert.ok(roomAccounts <= scenario.accounts, `${scenario.id} room membership exceeds accounts`);
  return scenario;
}

export function scenarioById(configuration, id) {
  const scenario = configuration.scenarios.find(item => item.id === id);
  assert.ok(scenario, `Unknown scenario: ${id}`);
  return validateScenario(scenario);
}

export function budgetFailures(result, scenario, budget) {
  const failures = [];
  const metrics = result.metrics;
  if (!metrics) return ["metrics-missing"];
  if (metrics.memoryPeakMiB > budget.memoryMiB) failures.push("memory");
  if (metrics.upstreamPeakKbitPerSecond > budget.upstreamKbitPerSecond) failures.push("upstream");
  if (metrics.downstreamPeakKbitPerSecond > budget.downstreamKbitPerSecond) failures.push("downstream");
  if (metrics.cpuPeakPercent > budget.cpuCores * 100) failures.push("cpu");
  if (result.execution.durationSeconds < scenario.durationSeconds) failures.push("duration");
  const thresholds = scenario.successThresholds;
  if (thresholds) {
    if (metrics.operationSuccessRatePercent < thresholds.operationSuccessRatePercent) failures.push("success-rate");
    if (metrics.p95LatencyMs > thresholds.p95LatencyMs) failures.push("latency");
  }
  return failures;
}

export function validateResult(result, scenario, budget) {
  assert.equal(result.schemaVersion, 1);
  assert.equal(result.scenarioId, scenario.id);
  assert.ok(["not_run", "running", "completed", "failed"].includes(result.execution.status));
  assert.ok(Number.isFinite(result.execution.durationSeconds) && result.execution.durationSeconds >= 0);
  assert.ok(Array.isArray(result.evidence));
  assert.ok(result.conclusion && ["not_run", "metrics_required", "failed", "passed"].includes(result.conclusion.status));
  if (result.execution.status === "not_run") {
    assert.equal(result.conclusion.status, "not_run", "Unexecuted scenarios cannot have a pass conclusion");
    assert.equal(result.metrics, null);
    assert.equal(result.evidence.length, 0);
  }
  if (result.conclusion.status === "passed") {
    assert.equal(result.execution.status, "completed", "A pass requires a completed real execution");
    assert.ok(result.execution.command?.length > 0, "A pass requires the exact load command");
    assert.ok(result.evidence.length >= scenario.evidenceRequirements.length, "A pass requires all raw evidence categories");
    for (const name of metricNames) {
      assert.ok(Number.isFinite(result.metrics?.[name]) && result.metrics[name] >= 0, `Missing metric: ${name}`);
    }
    assert.ok(result.metrics.sampleCount > 0, "A pass requires real samples");
    assert.deepEqual(budgetFailures(result, scenario, budget), [], "A pass cannot exceed scenario or resource budgets");
  }
  return result;
}
