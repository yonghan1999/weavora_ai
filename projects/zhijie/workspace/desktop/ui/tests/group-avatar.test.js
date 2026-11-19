import assert from 'node:assert/strict';
import test from 'node:test';
import { groupAvatarImage, readGroupAvatar } from '../src/group-avatar.js';

test('leaving the file picker empty preserves the previous avatar', async () => {
  assert.equal(await readGroupAvatar(new File([], '')), null);
  assert.equal(await readGroupAvatar(new File([], ''), 'existing-avatar'), 'existing-avatar');
});

test('invalid and oversized files cannot be saved as avatars', async () => {
  for (const file of [new File([], 'empty.png', { type: 'image/png' }),
    new File(['not a png'], 'fake.png', { type: 'image/png' }),
    new File(['<svg/>'], 'vector.svg', { type: 'image/svg+xml' }),
    new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'large.jpg', { type: 'image/jpeg' })]) {
    await assert.rejects(readGroupAvatar(file), /GROUP_AVATAR_INVALID/);
  }
});

test('group cards never load external URLs or interpolate untrusted markup as images', () => {
  for (const value of [null, 'https://example.test/avatar.png', 'data:image/svg+xml;base64,PHN2Zy8+',
    'data:image/png;base64,abc" onload="x', 'data:image/png;base64,' + 'a'.repeat(32768)]) {
    assert.equal(groupAvatarImage(value), '');
  }
});
