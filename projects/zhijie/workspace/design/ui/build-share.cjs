const fs = require('node:fs');
const path = require('node:path');

const root = __dirname;
const read = name => fs.readFileSync(path.resolve(root, name), 'utf8');
let html = read('index.html');
const styles = ['../prototype/styles.css', 'ui.css'];
html = html.replace('<link rel="stylesheet" href="../prototype/styles.css">', () => `<style>${styles.map(read).join('\n').replace(/<\/style/gi, '<\\/style')}</style>`);
html = html.replace('  <link rel="stylesheet" href="ui.css">\n', '');
for (const name of ['domain.js', 'app.js']) {
  const source = read('../prototype/' + name) + (name === 'app.js' ? '\n;\n' + read('presentation.js') : '');
  html = html.replace(`<script src="../prototype/${name}"></script>`, () => `<script>${source.replace(/<\/script/gi, '<\\/script')}</script>`);
}
html = html.replace('  <script src="presentation.js"></script>\n', '');
const output = path.resolve(root, '../../../../../tmp/weavora-ui.html');
html = html.replace('<a href="index.html">重新加载预览</a>', '启用 JavaScript 后重新打开文件。');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, html, 'utf8');
console.log(`UI 单文件预览：${output}（${(Buffer.byteLength(html) / 1024).toFixed(1)} KB）`);
let system = read('system.html');
system = system.replace('<link rel="stylesheet" href="../prototype/styles.css">', () => `<style>${[...styles,'system.css'].map(read).join('\n').replace(/<\/style/gi,'<\\/style')}</style>`);
system = system.replace('  <link rel="stylesheet" href="ui.css">\n','').replace('  <link rel="stylesheet" href="system.css">\n','');
system = system.replace('<script src="system.js"></script>',() => `<script>${read('system.js').replace(/<\/script/gi,'<\\/script')}</script>`);
system = system.replace('href="index.html"','href="weavora-ui.html"');
const systemOutput = path.join(path.dirname(output),'weavora-design-system.html');
fs.writeFileSync(systemOutput,system,'utf8');
console.log(`设计系统工作台：${systemOutput}`);
