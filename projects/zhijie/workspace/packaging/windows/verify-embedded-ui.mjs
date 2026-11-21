import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { brotliDecompressSync } from 'node:zlib';

const [executable, dist, cache, htmlDigest, output] = process.argv.slice(2);
if (process.argv.length !== 7 || !/^[0-9a-f]{64}$/.test(htmlDigest)) {
  throw new Error('用法：node verify-embedded-ui.mjs <包中桌面程序> <同次构建的 dist> <tauri-codegen-assets> <已核对的嵌入 HTML SHA-256> <全新输出目录>');
}
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const binary = readFileSync(executable);
const expected = new Map();
for (const entry of readdirSync(dist, { recursive: true, withFileTypes: true })) {
  if (entry.isDirectory()) continue;
  if (!entry.isFile()) throw new Error(`构建目录中不是普通文件：${entry.name}`);
  const file = join(entry.parentPath, entry.name);
  const name = relative(dist, file);
  expected.set(name, name === 'index.html' ? htmlDigest : digest(readFileSync(file)));
}
if (!expected.has('index.html') || !expected.has('app.js')) throw new Error('缺少桌面页面入口');

const required = new Set(expected.values());
const found = new Map();
for (const entry of readdirSync(cache, { withFileTypes: true })) {
  if (!entry.isFile()) continue;
  const packed = readFileSync(join(cache, entry.name));
  if (!packed.length) continue;
  const offset = binary.indexOf(packed);
  if (offset === -1) continue;
  const bytes = brotliDecompressSync(binary.subarray(offset, offset + packed.length));
  const sha256 = digest(bytes);
  if (required.has(sha256)) found.set(sha256, { bytes, offset });
}
for (const [name, sha256] of expected) {
  if (!found.has(sha256)) throw new Error(`程序内未找到匹配资源：${name}`);
}

mkdirSync(output);
const records = [];
for (const [name, sha256] of expected) {
  const { bytes, offset } = found.get(sha256);
  const file = resolve(output, name);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, bytes, { flag: 'wx' });
  records.push({ name, offset, sha256 });
}
console.log(JSON.stringify(records, null, 2));
