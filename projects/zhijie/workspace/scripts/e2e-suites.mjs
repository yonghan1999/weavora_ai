import assert from 'node:assert/strict';

export const requiredSuites = [
  'app.weavora.zhijie.controlplane.integration.VerticalSliceIntegrationTest',
  'app.weavora.zhijie.controlplane.group.GroupBrowserIntegrationTest',
];

export function verifySuiteResults(output) {
  const completed = new Set();
  for (const match of output.matchAll(/^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: [^\r\n]+ -- in ([\w.]+)\s*$/gm)) {
    const [, count, failures, errors, skipped, suite] = match;
    if (!requiredSuites.includes(suite)) continue;
    assert.ok(!completed.has(suite) && Number(count) > 0 && [failures, errors, skipped].every(value => value === '0'),
      `Incomplete or duplicate E2E suite: ${suite}`);
    completed.add(suite);
  }
  for (const suite of requiredSuites) assert.ok(completed.has(suite), `Missing E2E suite: ${suite}`);
}
