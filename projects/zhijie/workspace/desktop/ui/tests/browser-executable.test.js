import assert from 'node:assert/strict';
import test from 'node:test';
import { browserExecutable } from './browser-executable.mjs';

test('platform defaults select only an available browser for that OS', () => {
  for (const [platform, path] of [['linux', '/usr/bin/chromium'], ['darwin', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'], ['win32', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']]) {
    assert.equal(browserExecutable(platform, {}, candidate => candidate === path), path);
  }
});

test('an explicit browser must be usable and never silently falls back', () => {
  const path = '/opt/test browser/chrome';
  assert.equal(browserExecutable('linux', {ZHIJIE_TEST_CHROME: path}, candidate => candidate === path), path);
  for (const invalid of ['', 'relative/chrome', '/missing/chrome']) {
    assert.throws(() => browserExecutable('linux', {ZHIJIE_TEST_CHROME: invalid}, candidate => candidate === '/usr/bin/chromium'), /ZHIJIE_TEST_CHROME/);
  }
  assert.throws(() => browserExecutable(process.platform, {ZHIJIE_TEST_CHROME: process.cwd()}), /ZHIJIE_TEST_CHROME/);
});
