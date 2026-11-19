import { spawn } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { resolve, extname, sep } from "node:path";
import { createServer } from "node:http";
import assert from "node:assert/strict";

const chrome = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
].find(existsSync);
assert.ok(chrome, "需要 Chrome 或 Edge");
const output = resolve(".test-artifacts");
mkdirSync(output, { recursive: true });
const dist = resolve("dist");
const server = createServer((req, res) => {
  const file = resolve(
    dist,
    "." +
      new URL(req.url, "http://localhost").pathname.replace(
        /\/$/,
        "/index.html",
      ),
  );
  if (!file.startsWith(dist + sep) || !existsSync(file)) {
    res.writeHead(404).end();
    return;
  }
  res.setHeader(
    "Content-Type",
    {
      ".html": "text/html; charset=utf-8",
      ".js": "text/javascript; charset=utf-8",
      ".css": "text/css; charset=utf-8",
    }[extname(file)] || "application/octet-stream",
  );
  res.end(readFileSync(file));
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const url = `http://127.0.0.1:${server.address().port}/`;
const port = 11000 + Math.floor(Math.random() * 500);
const browser = spawn(
  chrome,
  [
    "--headless=new",
    "--disable-gpu",
    "--no-first-run",
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${output}/profile-${process.pid}`,
    "about:blank",
  ],
  { stdio: "ignore", windowsHide: true },
);
const delay = (ms) => new Promise((r) => setTimeout(r, ms));
let socket,
  seq = 0;
const pending = new Map(),
  errors = [],
  checks = [];
function command(method, params = {}) {
  const id = ++seq;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const r = await command("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (r.exceptionDetails)
    throw new Error(
      r.exceptionDetails.exception?.description || r.exceptionDetails.text,
    );
  return r.result.value;
}
async function check(name, expression) {
  assert.ok(await evaluate(expression), name);
  checks.push(name);
}
async function click(selector) {
  await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  await delay(150);
}
async function fill(selector, value) {
  await evaluate(
    `(()=>{const x=document.querySelector(${JSON.stringify(selector)});x.value=${JSON.stringify(value)};x.dispatchEvent(new Event('input',{bubbles:true}));})()`,
  );
}
async function shot(name) {
  const r = await command("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  writeFileSync(`${output}/${name}.png`, Buffer.from(r.data, "base64"));
}
const fixture = String.raw`
globalThis.calls=[];globalThis.failLogin=true;globalThis.windowCalls=[];
const group={id:'g1',name:'周末远征队',description:'约好下一局，一起探索。',ownerUserId:'u1'};
const room={id:'r1',groupId:'g1',name:'周末方块局',gameName:'MINECRAFT',description:'生存建造',openState:'OPEN',currentActivityId:'a1'};
let activity=null,voice={state:'DISCONNECTED',muted:false,receiveEnabled:true,masterVolume:1},messages=[];
const snapshot=()=>({userId:'u1',activity,service:'CONNECTED',lastErrorCode:null});
globalThis.__TAURI__={window:{getCurrentWindow:()=>({minimize:async()=>windowCalls.push('minimize'),toggleMaximize:async()=>windowCalls.push('maximize'),isMaximized:async()=>true,close:async()=>windowCalls.push('close'),startDragging:async()=>windowCalls.push('drag')})},core:{invoke:async(command,p={})=>{
calls.push({command,p});
if(command==='session_login'){await new Promise(r=>setTimeout(r,80));if(failLogin)throw 'SERVICE_UNAVAILABLE';return snapshot();}
if(command==='session_logout')return {userId:null,activity:null,service:'DISCONNECTED'};
if(command==='group_list')return [group];if(command==='room_list')return [room];
if(command==='activity_join'){activity={activityId:'a1',voice:'DISCONNECTED',network:'DISCONNECTED',virtualIpv4:'10.66.0.2'};return snapshot();}
if(command==='activity_leave'){activity=null;return snapshot();}
if(command==='voice_connect'){voice.state='FAILED';voice.failureCode='VOICE_CAPACITY_LIMITED';throw 'VOICE_CAPACITY_LIMITED';}
if(command==='voice_snapshot')return voice;
if(command==='voice_set_mute'){voice.muted=p.muted;return voice;}
if(command==='voice_set_receive'){voice.receiveEnabled=p.enabled;return voice;}
if(command==='voice_set_volume'){voice.masterVolume=p.volume;return voice;}
if(command==='product_request'){
if(p.resource==='friends')return [{userId:'u2',email:'friend@example.test',presence:'ONLINE'}];
if(p.resource==='group-members')return [{userId:'u1',role:'OWNER'},{userId:'u2',role:'MEMBER'}];
if(p.resource==='activity-members')return [{userId:'u1',state:'ACTIVE'}];
if(p.resource==='messages'){const rows=[...messages].reverse();if(!p.targetId)return rows.slice(0,50);if(globalThis.failHistory)throw 'SERVICE_UNAVAILABLE';const index=rows.findIndex(row=>row.id===p.targetId);return index<0?[]:rows.slice(index+1,index+51);}
if(p.resource==='message-send'){messages.push({id:'m1',authorId:'u1',body:p.body.body,createdAt:new Date().toISOString()});return messages.at(-1);}
if(p.resource==='message-delete'){messages[0].deleted=true;return messages[0];}
if(p.resource==='friend-search')return [{userId:'u2',email:p.id}];
if(p.resource==='group-invite')return {id:'invite-1'};
if(['register','verify','forgot','reset'].includes(p.resource))throw 'EMAIL_DELIVERY_UNAVAILABLE';
return {};}
throw 'UNEXPECTED_COMMAND';}}};`;
try {
  let pages;
  for (let i = 0; i < 80; i++) {
    try {
      pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      if (pages.length) break;
    } catch {}
    await delay(100);
  }
  socket = new WebSocket(
    pages.find((x) => x.type === "page").webSocketDebuggerUrl,
  );
  await new Promise((r, j) => {
    socket.onopen = r;
    socket.onerror = j;
  });
  socket.onmessage = (event) => {
    const m = JSON.parse(event.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result);
    }
    if (m.method === "Runtime.exceptionThrown")
      errors.push(
        m.params.exceptionDetails.exception?.description ||
          m.params.exceptionDetails.text,
      );
  };
  await command("Page.enable");
  await command("Runtime.enable");
  await command("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await command("Page.addScriptToEvaluateOnNewDocument", { source: fixture });
  await command("Page.navigate", { url });
  for (let i = 0; i < 60; i++) {
    if (await evaluate('!!document.querySelector(".auth-card")')) break;
    await delay(100);
  }
  await check(
    "登录使用设计卡片、品牌与自定义标题栏",
    '!!document.querySelector(".auth-card .brand") && document.querySelectorAll(".window-controls button").length===3',
  );
  await check(
    "窗口按钮位于最右侧",
    'Math.abs(document.querySelector(".window-controls").getBoundingClientRect().right-innerWidth)<1',
  );
  await shot("login-1440");
  await click('[data-window="minimize"]');
  await click('[data-window="maximize"]');
  await check(
    "窗口按钮调用桌面接口",
    'windowCalls.includes("minimize") && windowCalls.includes("maximize")',
  );
  await fill('[name="email"]', "user@example.test");
  await fill('[name="password"]', "test-password-123");
  await evaluate('document.querySelector("[data-form=auth]").requestSubmit()');
  await delay(250);
  await check(
    "服务失败留在登录并显示中文错误",
    '!!document.querySelector(".auth-card") && document.getElementById("feedback").textContent.includes("无法连接")',
  );
  await evaluate("failLogin=false");
  await fill('[name="password"]', "test-password-123");
  await evaluate('document.querySelector("[data-form=auth]").requestSubmit()');
  await delay(300);
  await check(
    "真实页面路径呈现大厅与房卡",
    'document.querySelectorAll(".room-card").length===1 && !!document.querySelector(".rail") && document.body.innerText.includes("周末远征队")',
  );
  await shot("lobby-1440");
  for (const width of [900, 600]) {
    await command("Emulation.setDeviceMetricsOverride", {
      width,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await check(
      `大厅 ${width} 无横向溢出`,
      'document.documentElement.scrollWidth <= innerWidth && document.querySelector(".window-controls").getBoundingClientRect().right <= innerWidth',
    );
    await shot(`lobby-${width}`);
  }
  await command("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await click('[data-view="friends"]');
  await check(
    "好友来自接口",
    'document.body.innerText.includes("friend@example.test")',
  );
  await click('[data-action="add-friend"]');
  await check(
    "对话框设置背景隔离及首字段焦点",
    'document.getElementById("app").inert && document.activeElement.name==="query"',
  );
  await evaluate(
    'document.querySelector(".modal-actions .button.primary").focus();document.dispatchEvent(new KeyboardEvent("keydown",{key:"Tab",bubbles:true,cancelable:true}))',
  );
  await check(
    "对话框 Tab 焦点循环",
    'document.activeElement.getAttribute("aria-label")==="关闭"',
  );
  await click('[data-action="dialog-close"]');
  await click('[data-view="groups"]');
  await click('[data-action="open-group"]');
  await shot("group-1440");
  await fill("#message-input", "草稿<&>");
  await click('[data-view="lobby"]');
  await click('[data-view="groups"]');
  await click('[data-action="open-group"]');
  await check(
    "群聊草稿跨导航保留",
    'document.getElementById("message-input").value==="草稿<&>"',
  );
  await evaluate(
    'document.querySelector("[data-form=message]").requestSubmit()',
  );
  await delay(200);
  await check(
    "消息经接口发送且转义",
    'document.querySelector(".message-text").textContent==="草稿<&>" && !document.querySelector(".message-text > *")',
  );
  await evaluate(`globalThis.originalMessages=messages.slice();messages=Array.from({length:103},(_,index)=>({id:'page-'+index,authorId:'u2',createdAt:'2026-11-01T12:00:00Z',body:'历史 <'+index+'>',deleted:index===2}));`);
  await click('[data-view="groups"]'); await click('[data-action="open-group"]');
  await fill('#message-input','翻页草稿');
  await click('[data-action="message-history"]');
  await check('历史第一页无跳条且不改写实时消息', `JSON.stringify(Array.from(document.querySelectorAll('[data-message-history] .message-text'),node=>node.textContent))===JSON.stringify(Array.from({length:50},(_,i)=>'历史 <'+(i+3)+'>')) && document.querySelectorAll('.group-chat-body .message').length===50`);
  await evaluate('globalThis.failHistory=true'); await click('[role=dialog] [data-action="message-history"]');
  await check('翻页失败保留页和重试按钮', `document.querySelector('#dialog-error').textContent.length>0 && document.querySelectorAll('[data-message-history] .message-text').length===50`);
  await evaluate('failHistory=false'); await click('[role=dialog] [data-action="message-history"]');
  await check('末页按时间顺序显示删除占位并停止翻页', `JSON.stringify(Array.from(document.querySelectorAll('[data-message-history] .message-text'),node=>node.textContent))===JSON.stringify(['历史 <0>','历史 <1>','此消息已被删除']) && !document.querySelector('[role=dialog] [data-action=message-history]')`);
  await click('[role=dialog] [data-action="dialog-close"].ghost');
  await check('返回最新后草稿与最新消息保持', `document.querySelector('#message-input').value==='翻页草稿' && Array.from(document.querySelectorAll('.group-chat-body .message-text')).at(-1).textContent==='历史 <102>'`);
  await evaluate('messages=originalMessages'); await click('[data-view="groups"]'); await click('[data-action="open-group"]');
  await click('[data-view="lobby"]');
  await click('[data-action="join-room"]');
  await evaluate(
    'document.querySelector("[data-form=dialog]").requestSubmit()',
  );
  await delay(300);
  await check(
    "入房后保留独立语音失败和网络未连接",
    '!!document.querySelector(".voice-dock") && document.querySelector(".room-flags").innerText.includes("连接失败") && document.querySelector(".room-flags").innerText.includes("未连接")',
  );
  await click('[data-tab="network"]');
  await check(
    "未接入网络不伪造队友地址",
    'document.querySelector(".network-panel").innerText.includes("尚未就绪") && document.querySelectorAll(".ip-address").length===0',
  );
  await shot("room-1440");
  await command("Emulation.setDeviceMetricsOverride", {
    width: 600,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await check(
    "600 房间无横向溢出",
    "document.documentElement.scrollWidth<=innerWidth",
  );
  await shot("room-600");
  await click('[data-view="settings"]');
  await click('[data-settings="account"]');
  await click('[data-action="logout"]');
  await check(
    "退出必须先确认",
    '!!document.querySelector("[role=dialog]") && !calls.some(x=>x.command==="session_logout")',
  );
  await click('[data-action="dialog-close"]');
  await check("取消不退出", '!!document.querySelector(".rail")');
  await click('[data-action="logout"]');
  await evaluate(
    'document.querySelector("[data-form=dialog]").requestSubmit()',
  );
  await delay(250);
  await check("确认后返回登录", '!!document.querySelector(".auth-card")');
  await click('[data-action="auth-register"]');
  await fill('[name="email"]', "user@example.test");
  await fill('[name="password"]', "test-password-123");
  await evaluate('document.querySelector("[data-form=auth]").requestSubmit()');
  await delay(200);
  await check(
    "邮件未接入不显示假验证码或成功",
    'document.getElementById("feedback").textContent.includes("邮件服务尚未接入") && !document.body.innerText.includes("000000")',
  );
  assert.deepEqual(errors, []);
  console.log(
    JSON.stringify(
      { checks: checks.length, passed: checks, scriptErrors: errors },
      null,
      2,
    ),
  );
} finally {
  socket?.close();
  browser.kill();
  server.close();
}
