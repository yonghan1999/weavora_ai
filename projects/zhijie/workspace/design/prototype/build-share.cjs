const fs = require('node:fs');
const path = require('node:path');

const root = __dirname;
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
let html = read('index.html');
html = html.replace('<link rel="stylesheet" href="styles.css">', () => `<style>${read('styles.css').replace(/<\/style/gi, '<\\/style')}</style>`);
for (const name of ['domain.js', 'app.js']) {
  const source = read(name) + (name === 'domain.js' ? '\n' + read('media.js') : '');
  html = html.replace(`<script src="${name}"></script>`, () => `<script>${source.replace(/<\/script/gi, '<\\/script')}</script>`);
}
html = html.replace('  <script src="media.js"></script>\n', '');
html = html.replace('<a href="index.html">重新加载原型</a>', '请使用浏览器打开文件并允许 JavaScript。');
const output = path.resolve(root, '../../../../../tmp/weavora-prototype.html');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, html, 'utf8');
console.log(`单文件分享版：${output}（${(Buffer.byteLength(html) / 1024).toFixed(1)} KB）`);
