import assert from 'node:assert/strict';
import test from 'node:test';
import { requiredSuites, verifySuiteResults } from './e2e-suites.mjs';

const summary = (suite, count = 2, failures = 0, errors = 0, skipped = 0) =>
  `[INFO] Tests run: ${count}, Failures: ${failures}, Errors: ${errors}, Skipped: ${skipped}, Time elapsed: 3.123 s -- in ${suite}`;
const [vertical, desktop] = requiredSuites;

test('accepts separate executed suite summaries on the current run', () => {
  verifySuiteResults([summary(desktop), summary(vertical), '[INFO] BUILD SUCCESS'].join('\r\n'));
});

test('does not accept a successful aggregate in place of either suite', () => {
  for (const only of requiredSuites) assert.throws(() => verifySuiteResults(`${summary(only)}\n[INFO] BUILD SUCCESS`), /Missing/);
});

test('rejects skipped, failed, empty and duplicate suite evidence', () => {
  for (const bad of [summary(desktop, 2, 0, 0, 1), summary(desktop, 0), summary(desktop, 2, 1),
    summary(desktop, 2, 0, 1), `${summary(desktop)}\n${summary(desktop)}`]) {
    assert.throws(() => verifySuiteResults(`${summary(vertical)}\n${bad}`), /Incomplete or duplicate/);
  }
});

test('room invitation requires all three recipient states to execute', () => {
  const check = output => verifySuiteResults(output, [desktop], 3);
  check(summary(desktop, 3));
  for (const output of [summary(desktop, 2), summary(desktop, 3, 0, 0, 1), summary(vertical, 3)]) {
    assert.throws(() => check(output));
  }
});
