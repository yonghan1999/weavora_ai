import assert from 'node:assert/strict';

export const requiredSuites = [
  'app.weavora.zhijie.controlplane.integration.VerticalSliceIntegrationTest',
  'app.weavora.zhijie.controlplane.group.GroupBrowserIntegrationTest',
];

export function verifySuiteResults(output, suites = requiredSuites, exactCount) {
  const completed = new Set();
  for (const match of output.matchAll(/^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: [^\r\n]+ -- in ([\w.]+)\s*$/gm)) {
    const [, count, failures, errors, skipped, suite] = match;
    if (!suites.includes(suite)) continue;
    assert.ok(!completed.has(suite) && Number(count) > 0 && (exactCount === undefined || Number(count) === exactCount) && [failures, errors, skipped].every(value => value === '0'),
      `Incomplete or duplicate E2E suite: ${suite}`);
    completed.add(suite);
  }
  for (const suite of suites) assert.ok(completed.has(suite), `Missing E2E suite: ${suite}`);
}
