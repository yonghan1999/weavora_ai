const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const chrome = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
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
  const mainScreenshot = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  fs.writeFileSync(path.join(__dirname, 'prototype-walkthrough.png'), Buffer.from(mainScreenshot.data, 'base64'));
  await evaluate('document.querySelector(\'[data-action="nav"][data-view="account"]\').click()');
  await click('logout');
  assert('登录页可渲染', await evaluate('document.body.innerText.includes("使用模拟账号登录")'));
  await evaluate('document.querySelector(\'[data-action="auth-mode"][data-mode="register"]\').click()');
  await click('register');
  assert('注册进入邮箱验证', await evaluate('document.body.innerText.includes("6 位验证码")'), await evaluate('document.body.innerText'));
  await click('verify');
  assert('验证后进入首页', await evaluate('state.authenticated && state.view === "home"'));
  await evaluate('document.querySelector(\'[data-action="nav"][data-view="friends"]\').click()');
  await click('friend-accept');
  assert('好友申请接受', await evaluate('state.friends.some(f => f.name === "青禾") && state.requests.length === 0'));
  await click('group-open');
  assert('群消息页可进入', await evaluate('state.view === "group" && state.groupTab === "messages"'));
  await evaluate('document.getElementById("message-input").value = "原型走查消息";document.querySelector(\'[data-form="message"]\').requestSubmit()');
  assert('群消息可发送', await evaluate('state.messages.some(m => m.text === "原型走查消息")'));
  await evaluate('document.querySelector(\'[data-action="group-tab"][data-tab="rooms"]\').click()');
  await click('room-open');
  await click('join-room');
  await click('confirm-join');
  await delay(1100);
  assert('加入后双通道独立连接', await evaluate('state.joined && state.voice === "connected" && state.network === "connected"'));
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
  await evaluate('document.querySelector(\'[data-action="nav"][data-view="compatibility"]\').click()');
  assert('兼容性不伪造通过', await evaluate('document.body.innerText.includes("当前所有平台与游戏均为“候选 / 未验证”")'));
  const screenshot = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  fs.writeFileSync(path.join(__dirname, 'prototype-compatibility.png'), Buffer.from(screenshot.data, 'base64'));
  assert('走查期间无脚本异常', errors.length === 0, errors.join('; '));
  console.log(JSON.stringify({ ok: true, checks: results, consoleErrors: errors }, null, 2));
}
main().catch((error) => { console.error(error.stack || error); process.exitCode = 1; }).finally(async () => {
  if (socket) socket.close();
  if (browser) browser.kill();
  await delay(200);
  fs.rmSync(profile, { recursive: true, force: true });
});
