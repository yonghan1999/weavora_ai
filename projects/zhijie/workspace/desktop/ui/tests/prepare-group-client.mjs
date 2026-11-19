import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const build = execFileSync('cargo', ['test', '--locked', '--offline', '-p', 'zhijie-shell', '--no-run', '--message-format=json'],
  { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, windowsHide: true });
const targets = build.trim().split(/\r?\n/).map(line => JSON.parse(line)).filter(message =>
  message.reason === 'compiler-artifact' && message.target.name === 'zhijie-shell' && message.profile.test && message.executable);
assert.equal(targets.length, 1, 'one Shell test binary required');
process.stdout.write(targets[0].executable);
