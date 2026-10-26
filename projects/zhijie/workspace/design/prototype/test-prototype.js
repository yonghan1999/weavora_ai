const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const chrome = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
].find((candidate) => fs.existsSync(candidate));
if (!chrome) throw new Error('未找到可用的 Chrome 或 Edge 浏览器');
const profile = path.join(__dirname, '.chrome-test-profile');
const url = 'file:///' + path.join(__dirname, 'index.html').replace(/\\/g, '/');
const results = [];
const errors = [];
let browser;
let socket;
let nextId = 0;
const pending = new Map();

function assert(name, condition, detail = '') {
  if (!condition) throw new Error(`${name}: ${detail || '断言失败'}`);
  results.push({ name, status: 'passed' });
}
async function delay(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }
async function command(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const response = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.text + ': ' + (response.exceptionDetails.exception?.description || ''));
  return response.result.value;
}
async function click(action) {
  await evaluate(`document.querySelector('[data-action="${action}"]').click()`);
  await delay(80);
}
async function main() {
  browser = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run', '--remote-debugging-port=9222', `--user-data-dir=${profile}`, url], { stdio: 'ignore' });
  let pages;
  for (let i = 0; i < 60; i++) {
    try {
      pages = await (await fetch('http://127.0.0.1:9222/json/list')).json();
      if (pages.some((page) => page.type === 'page')) break;
    } catch {}
    await delay(100);
  }
  const page = pages?.find((item) => item.type === 'page');
  if (!page) throw new Error('无法连接无头浏览器');
  socket = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  socket.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const handlers = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) handlers.reject(new Error(message.error.message)); else handlers.resolve(message.result);
    }
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text);
  };
  await command('Runtime.enable');
  await command('Page.enable');
  await command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  for (let i = 0; i < 40; i++) {
    if (await evaluate('document.readyState === "complete" && !!document.querySelector(\'.member-rail\')')) break;
    await delay(100);
  }
  assert('默认展示 C 端群组工作区', await evaluate('state.view === "group" && !!document.querySelector(\'.member-rail\') && document.body.innerText.includes("大厅闲聊")'));
  assert('群组房间成员语音同屏', await evaluate('document.body.innerText.includes("游戏房间") && document.body.innerText.includes("在线 — 4") && !!document.querySelector(\'.voice-panel\')'), await evaluate('JSON.stringify({text:document.body.innerText,voice:!!document.querySelector(\'.voice-panel\'),joined:state.joined})'));
  assert('成员栏不混排虚拟 IP', await evaluate('!!document.querySelector(\'.member-rail\') && document.querySelectorAll(\'.member-rail .ip\').length === 0'));
  const mainScreenshot = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  fs.writeFileSync(path.join(__dirname, 'prototype-walkthrough.png'), Buffer.from(mainScreenshot.data, 'base64'));
  await evaluate('document.querySelector(\'[data-action="nav"][data-view="account"]\').click()');
  await click('logout');
  assert('登录页可渲染', await evaluate('document.body.innerText.includes("使用模拟账号登录")'));
  await evaluate('document.querySelector(\'[data-action="auth-mode"][data-mode="register"]\').click()');
  await click('register');
  assert('注册进入邮箱验证', await evaluate('document.body.innerText.includes("6 位验证码")'), await evaluate('document.body.innerText'));
  await click('verify');
  assert('验证后直达群组工作区', await evaluate('state.authenticated && state.view === "group"'));
  await evaluate('document.querySelector(\'[data-action="nav"][data-view="friends"]\').click()');
  assert('好友中心默认展示在线分类', await evaluate('state.friendTab === "online" && !document.body.innerText.includes("beiyu#1003") && document.querySelectorAll(\'.friend-row\').length === 2'));
  await evaluate('document.querySelector(\'[data-action="friend-tab"][data-tab="all"]\').click()');
  assert('全部分类包含离线好友', await evaluate('document.querySelectorAll(\'.friend-row\').length === 3 && document.body.innerText.includes("北屿")'));
  await evaluate('document.querySelector(\'[data-input="friend-search"]\').value = "beiyu#1003";document.querySelector(\'[data-input="friend-search"]\').dispatchEvent(new Event("input", {bubbles:true}))');
  assert('好友可按账号 ID 搜索', await evaluate('document.querySelectorAll(\'.friend-row\').length === 1 && document.body.innerText.includes("北屿")'));
  await evaluate('document.querySelector(\'[data-input="friend-search"]\').value = "";document.querySelector(\'[data-input="friend-search"]\').dispatchEvent(new Event("input", {bubbles:true}))');
  await evaluate('document.querySelector(\'[data-action="friend-profile"][data-name="阿岚"]\').click()');
  assert('好友资料卡展示账号与共同群组', await evaluate('document.body.innerText.includes("alan#1001") && document.body.innerText.includes("共同群组：周末远征队")'));
  await click('modal-close');
  await evaluate('document.querySelector(\'[data-action="friend-menu"][data-name="阿岚"]\').click()');
  assert('好友更多操作包含资料复制和删除', await evaluate('document.querySelectorAll(\'.friend-menu [role="menuitem"]\').length === 3'));
  await evaluate('document.querySelector(\'[data-action="friend-invite"][data-name="阿岚"]\').click()');
  assert('好友可发起群组邀请', await evaluate('state.modal.target === "阿岚" && document.body.innerText.includes("对方接受后才建立群籍")'));
  await click('confirm-friend-invite');
  await evaluate('document.querySelector(\'[data-action="friend-tab"][data-tab="pending"]\').click()');
  assert('待处理页区分收到和发出的申请', await evaluate('document.body.innerText.includes("收到的申请") && document.body.innerText.includes("发出的申请")'));
  await click('friend-accept');
  assert('好友申请接受', await evaluate('state.friends.some(f => f.name === "青禾" && f.id === "qinghe#1004") && state.requests.length === 0 && state.friendTab === "online"'));
  await click('group-open');
  assert('群消息页可进入', await evaluate('state.view === "group" && state.groupTab === "messages"'));
  await evaluate('document.getElementById("message-input").value = "原型走查消息";document.querySelector(\'[data-form="message"]\').requestSubmit()');
  assert('群消息可发送', await evaluate('state.messages.some(m => m.text === "原型走查消息")'));
  assert('消息删除默认不收进正文', await evaluate('!document.querySelector(\'[data-action="delete-message"]\') && document.querySelectorAll(\'[data-action="message-menu"]\').length >= 2'));
  await evaluate('document.querySelector(\'[data-message="1"]\').focus()');
  await delay(160);
  assert('键盘聚焦显示消息工具条', await evaluate('document.activeElement?.dataset.message === "1" && getComputedStyle(document.querySelector(\'[data-message="1"] .message-actions\')).opacity === "1"'));
  await evaluate('document.querySelector(\'[data-change="role"]\').value = "member";document.querySelector(\'[data-change="role"]\').dispatchEvent(new Event("change", {bubbles:true}))');
  assert('普通成员不能代删他人消息', await evaluate('!document.querySelector(\'[data-action="message-menu"][data-id="1"]\') && !!document.querySelector(\'[data-action="message-menu"][data-id="2"]\')'));
  await evaluate('document.querySelector(\'[data-change="role"]\').value = "owner";document.querySelector(\'[data-change="role"]\').dispatchEvent(new Event("change", {bubbles:true}))');
  await evaluate('document.querySelector(\'[data-action="message-menu"][data-id="1"]\').click()');
  assert('管理员更多菜单包含代删', await evaluate('state.messageMenu === 1 && document.querySelector(\'[data-action="delete-message"]\').textContent.includes("管理员")'));
  await evaluate('document.dispatchEvent(new KeyboardEvent("keydown", {key:"Escape", bubbles:true}))');
  assert('Escape 收起消息菜单', await evaluate('state.messageMenu == null && !document.querySelector(\'[data-action="delete-message"]\')'));
  await evaluate('document.querySelector(\'[data-action="message-menu"][data-id="1"]\').click()');
  await click('delete-message');
  assert('代删前显示确认及审计影响', await evaluate('state.modal.target === 1 && document.body.innerText.includes("追加只读管理记录")'));
  await click('modal-close');
  assert('取消删除不改变消息', await evaluate('!state.messages.find(m => m.id === 1).deleted'));
  await evaluate('document.querySelector(\'[data-action="message-menu"][data-id="1"]\').click()');
  await click('delete-message');
  await click('confirm-delete-message');
  assert('确认代删保留提示并追加记录', await evaluate('state.messages.find(m => m.id === 1).deleted && state.messages.find(m => m.id === 1).text.includes("管理员") && state.audit.some(a => a.target === "消息 M-1")'));
  const ownMessageId = await evaluate('state.messages.find(m => m.text === "原型走查消息").id');
  await evaluate(`document.querySelector('[data-action="message-menu"][data-id="${ownMessageId}"]').click()`);
  await click('delete-message');
  await click('confirm-delete-message');
  assert('本人删除显示作者删除提示', await evaluate(`state.messages.find(m => m.id === ${ownMessageId}).text === "一条消息已被作者删除"`));
  await evaluate('document.querySelector(\'[data-action="group-tab"][data-tab="rooms"]\').click()');
  await click('room-open');
  await click('join-room');
  await click('confirm-join');
  await delay(1100);
  assert('加入后双通道独立连接', await evaluate('state.joined && state.voice === "connected" && state.network === "connected"'));
  assert('活动页默认概览且有三个功能分区', await evaluate('state.roomTab === "overview" && document.querySelectorAll(\'.room-tab\').length === 3'));
  await evaluate('document.querySelector(\'[data-action="room-tab"][data-tab="voice"]\').click()');
  assert('语音页只有语音控制与成员语音状态', await evaluate('document.body.innerText.includes("成员语音状态") && !document.querySelector(\'.member-ip-list\')'));
  await evaluate('document.querySelector(\'[data-action="room-tab"][data-tab="network"]\').click()');
  assert('虚拟网络页独立展示 IP 与手动联机', await evaluate('document.body.innerText.includes("我的虚拟 IP") && document.querySelectorAll(\'.member-ip-list .row\').length === 4 && !document.body.innerText.includes("成员语音状态")'));
  await evaluate('document.querySelector(\'[data-action="room-tab"][data-tab="overview"]\').click()');
  await click('voice-fail');
  assert('语音失败不影响网络', await evaluate('state.voice === "failed" && state.network === "connected"'));
  await click('voice-retry');
  await delay(850);
  assert('语音可独立恢复', await evaluate('state.voice === "connected"'));
  await click('network-fail');
  assert('网络失败不影响语音', await evaluate('state.network === "failed" && state.voice === "connected"'));
  await click('network-retry');
  await delay(850);
  assert('网络可独立恢复', await evaluate('state.network === "connected"'));
  const originalIp = await evaluate('state.currentIp');
  await click('disconnect');
  assert('短时断线进入保留', await evaluate('state.grace && state.voice === "disconnected" && state.network === "disconnected"'));
  await click('resume');
  assert('有效恢复保持原 IP', await evaluate(`!state.grace && state.currentIp === "${originalIp}" && state.voice === "connected" && state.network === "connected"`));
  await click('group-open');
  await evaluate('document.querySelector(\'[data-change="role"]\').value = "member";document.querySelector(\'[data-change="role"]\').dispatchEvent(new Event("change", {bubbles:true}))');
  await evaluate('document.querySelector(\'[data-action="group-tab"][data-tab="audit"]\').click()');
  assert('普通成员不可读管理记录', await evaluate('document.body.innerText.includes("无权限查看管理记录")'));
  await evaluate('document.querySelector(\'[data-change="role"]\').value = "admin";document.querySelector(\'[data-change="role"]\').dispatchEvent(new Event("change", {bubbles:true}))');
  assert('管理员可读只读记录', await evaluate('document.body.innerText.includes("只读管理记录") && !!document.querySelector("table")'));
  assert('管理区不混入社交成员栏', await evaluate('!document.querySelector(\'.member-rail\')'));
  await evaluate('document.querySelector(\'[data-action="nav"][data-view="account"]\').click()');
  await click('support-matrix');
  assert('支持矩阵收纳于账号页对话框且不伪造通过', await evaluate('document.body.innerText.includes("当前所有平台与游戏均为“候选 / 未验证”") && !!document.querySelector(\'.modal\')'));
  const screenshot = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  fs.writeFileSync(path.join(__dirname, 'prototype-compatibility.png'), Buffer.from(screenshot.data, 'base64'));
  assert('走查期间无脚本异常', errors.length === 0, errors.join('; '));
  console.log(JSON.stringify({ ok: true, checks: results, consoleErrors: errors }, null, 2));
}
main().catch((error) => { console.error(error.stack || error); process.exitCode = 1; }).finally(async () => {
  if (socket) socket.close();
  if (browser) browser.kill();
  await delay(500);
  fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
});
