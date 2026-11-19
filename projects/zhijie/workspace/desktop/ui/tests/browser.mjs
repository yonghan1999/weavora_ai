import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, writeFileSync, readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { createServer } from "node:http";
import assert from "node:assert/strict";
import { browserExecutable } from "./browser-executable.mjs";

const chrome = browserExecutable();
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
const profile = mkdtempSync(resolve(output, "profile-"));
const browser = spawn(
  chrome,
  [
    "--headless=new",
    "--disable-gpu",
    "--no-first-run",
    "--remote-debugging-port=0",
    `--user-data-dir=${profile}`,
    "about:blank",
  ],
  { stdio: "ignore", windowsHide: true },
);
const delay = (ms) => new Promise((r) => setTimeout(r, ms));
const browserClosed = new Promise(resolve => browser.once("close", resolve));
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
globalThis.calls=[];globalThis.failLogin=true;globalThis.windowCalls=[];globalThis.sessionExpired=false;
globalThis.failFriendRequests=false;globalThis.failFriendResponse=false;globalThis.friendResponseDelay=0;
globalThis.friendListDelay=0;
globalThis.friends=[{userId:'u2',email:'friend@example.test',presence:'ONLINE'}];
globalThis.groupMembers=[{userId:'u1',role:'OWNER'},{userId:'u2',role:'MEMBER'},{userId:'u3',role:'ADMIN'}];
globalThis.failMemberChange=false;globalThis.memberChangeDelay=0;
globalThis.ownerTransfers=[];globalThis.failOwnerDecision=false;globalThis.ownerDecisionDelay=0;globalThis.failRecovery=false;
globalThis.invitations=[{id:'gi1',groupId:'g2',groupName:'新群 <&>',inviterId:'u9',inviteeId:'u1'}];
globalThis.sentInvitations=[{id:'sent1',groupId:'g1',groupName:'周末远征队',inviterId:'u1',inviteeId:'u8'}];
globalThis.failInvitations=false;globalThis.failInvitationDecision=false;globalThis.invitationDelay=0;
globalThis.activityMembers=[{userId:'u1',state:'ACTIVE',memberGeneration:1},{userId:'u2',state:'ACTIVE',memberGeneration:3},{userId:'u3',state:'ACTIVE',memberGeneration:1}];
globalThis.failRoomGovernance=false;globalThis.roomGovernanceDelay=0;
globalThis.failAudit=false;globalThis.auditEntries=[{id:'audit-1',actorId:'actor <&>',targetType:'ROOM',targetId:'room <img src=x>',action:'ROOM_UPDATE',result:'SUCCESS',occurredAt:'2026-11-17T08:30:00Z'}];
globalThis.incoming=[{id:'fr1',requesterId:'u3',email:'new<friend>@example.test',accountStatus:'ACTIVE'},{id:'fr2',requesterId:'u4',email:'second@example.test',accountStatus:'ACTIVE'},{id:'fr3',requesterId:'u5',email:'disabled@example.test',accountStatus:'DISABLED'}];
const group={id:'g1',name:'周末远征队',description:'约好下一局，一起探索。',ownerUserId:'u1'};
const room={id:'r1',groupId:'g1',name:'周末方块局',gameName:'MINECRAFT',description:'生存建造',openState:'OPEN',currentActivityId:'a1',ownerUserId:'u1'};
let activity=null,voice={state:'DISCONNECTED',muted:false,receiveEnabled:true,masterVolume:1},messages=[];
const snapshot=()=>({userId:sessionExpired?null:'u1',activity:sessionExpired?null:activity,service:globalThis.serviceState??'CONNECTED',lastErrorCode:null});
globalThis.__TAURI__={window:{getCurrentWindow:()=>({minimize:async()=>windowCalls.push('minimize'),toggleMaximize:async()=>windowCalls.push('maximize'),isMaximized:async()=>true,close:async()=>windowCalls.push('close'),startDragging:async()=>windowCalls.push('drag')})},core:{invoke:async(command,p={})=>{
calls.push({command,p});
if(command==='session_login'){await new Promise(r=>setTimeout(r,80));if(failLogin)throw 'SERVICE_UNAVAILABLE';return snapshot();}
if(command==='session_logout')return {userId:null,activity:null,service:'DISCONNECTED',lastErrorCode:'LOGOUT_CLEANUP_PENDING'};
if(command==='group_list')return [group];if(command==='room_list')return [room];
if(command==='activity_join'){activity={activityId:'a1',leaseId:'lease-room',voice:'DISCONNECTED',network:'DISCONNECTED',virtualIpv4:'10.66.0.2'};return snapshot();}
if(command==='activity_leave'){activity=null;return snapshot();}
if(command==='voice_connect'){if(globalThis.holdVoice)await new Promise(done=>globalThis.resumeVoice=done);voice.state='FAILED';voice.failureCode='VOICE_CAPACITY_LIMITED';throw 'VOICE_CAPACITY_LIMITED';}
if(command==='voice_snapshot'){if(globalThis.failVoiceRead)throw 'SERVICE_UNAVAILABLE';return structuredClone(voice);}
if(command==='state_snapshot'){if(globalThis.failStateRead){globalThis.failedStateReads++;throw 'SERVICE_UNAVAILABLE';}return snapshot();}
if(command==='network_connect'){if(globalThis.holdNetwork)await new Promise(done=>globalThis.resumeNetwork=done);throw 'HELPER_UNAVAILABLE';}
if(command==='network_snapshot'){if(globalThis.failNetworkRead)throw 'SERVICE_UNAVAILABLE';return {status:{state:activity?(globalThis.networkState??'FAILED'):'DISCONNECTED',activityId:activity?.activityId},peers:[]};}
if(command==='voice_set_mute'){voice.muted=p.muted;return voice;}
if(command==='voice_set_receive'){voice.receiveEnabled=p.enabled;return voice;}
if(command==='voice_set_volume'){if(p.memberId){voice.memberVolumes??={};voice.memberVolumes[p.memberId]=p.volume;}else voice.masterVolume=p.volume;return structuredClone(voice);}
if(command==='audio_devices')return voice.state==='CONNECTED'?{inputs:globalThis.inputDevices??[{index:0,id:'mic',name:'麦克风 <USB>'}],outputs:[{index:1,id:'speaker',name:'耳机'}]}:{inputs:[],outputs:[]};
if(command==='audio_select'){if(p.output)throw 'AUDIO_OUTPUT_SWITCH_FAILED';voice.inputDevice=p.deviceId;voice.inputFailureCode=null;return structuredClone(voice);}
if(command==='audio_test_microphone'){await new Promise(r=>setTimeout(r,100));if(globalThis.microphoneTestError)throw globalThis.microphoneTestError;return {frames:300,samples:144000,peak:globalThis.microphoneTestSilent?0:0.5,rms:globalThis.microphoneTestSilent?0:0.1};}
if(command==='audio_test_output'){await new Promise(r=>setTimeout(r,100));if(globalThis.outputTestError)throw globalThis.outputTestError;return null;}
if(command==='product_request'){
if(p.resource==='audit'){if(failAudit)throw 'CURRENT_GRANT_REQUIRED';const index=p.targetId?auditEntries.findIndex(item=>item.id===p.targetId)+1:0;return structuredClone(auditEntries.slice(index,index+50));}
if(p.resource==='invitations'||p.resource==='group-invitations'){if(failInvitations)throw 'SERVICE_UNAVAILABLE';const items=p.resource==='invitations'?invitations:sentInvitations;const cursor=p.resource==='invitations'?p.id:p.targetId;const index=cursor?items.findIndex(item=>item.id===cursor)+1:0;return structuredClone(items.slice(index,index+50));}
if(p.resource==='invitation-accept'||p.resource==='invitation-revoke'){await new Promise(r=>setTimeout(r,invitationDelay));if(failInvitationDecision)throw 'INVITATION_NOT_AVAILABLE';if(p.resource==='invitation-accept')invitations=invitations.filter(item=>item.id!==p.id);else sentInvitations=sentInvitations.filter(item=>item.id!==p.targetId);return {};}
if(p.resource==='owner-transfers')return structuredClone(ownerTransfers);
if(p.resource==='owner-transfer-create'){await new Promise(r=>setTimeout(r,ownerDecisionDelay));if(failOwnerDecision)throw 'OWNER_TRANSFER_PENDING';ownerTransfers=[{id:'ot1',fromUserId:'u1',toUserId:p.body.targetUserId,createdAt:new Date().toISOString()}];return ownerTransfers[0];}
if(p.resource==='owner-transfer-cancel'||p.resource==='owner-transfer-accept'){await new Promise(r=>setTimeout(r,ownerDecisionDelay));if(failOwnerDecision)throw 'OWNER_TRANSFER_NOT_AVAILABLE';if(p.resource==='owner-transfer-accept'){group.ownerUserId='u1';groupMembers.forEach(m=>m.role=m.userId==='u1'?'OWNER':'MEMBER');}ownerTransfers=[];return {};}
if(p.resource==='owner-recovery'){if(failRecovery)throw 'SERVICE_UNAVAILABLE';return {id:'recovery-1',status:'PENDING'};}
if(p.resource==='friends')return structuredClone(friends);
if(p.resource==='friend-requests'){await new Promise(r=>setTimeout(r,friendListDelay));if(failFriendRequests)throw 'SERVICE_UNAVAILABLE';const index=p.id?incoming.findIndex(item=>item.id===p.id)+1:0;return structuredClone(incoming.slice(index,index+50));}
if(p.resource==='friend-accept'||p.resource==='friend-reject'){await new Promise(r=>setTimeout(r,friendResponseDelay));if(failFriendResponse)throw 'FRIEND_REQUEST_CLOSED';incoming=incoming.filter(item=>item.id!==p.id);return {};}
if(p.resource==='group-members')return structuredClone(groupMembers);
if(p.resource==='group-member-role'||p.resource==='group-member-remove'){await new Promise(r=>setTimeout(r,memberChangeDelay));if(failMemberChange)throw 'CURRENT_GRANT_REQUIRED';if(p.resource==='group-member-role')groupMembers.find(m=>m.userId===p.targetId).role=p.body.role;else groupMembers=groupMembers.filter(m=>m.userId!==p.targetId);return {};}
if(p.resource==='activity-members')return structuredClone(activityMembers);
if(p.resource==='room-owner'){await new Promise(r=>setTimeout(r,roomGovernanceDelay));if(failRoomGovernance)throw 'CURRENT_GRANT_REQUIRED';room.ownerUserId=p.body.targetUserId;return structuredClone(room);}
if(p.resource==='activity-kick'){await new Promise(r=>setTimeout(r,roomGovernanceDelay));if(failRoomGovernance)throw 'ACTIVITY_MEMBER_CHANGED';activityMembers=activityMembers.filter(m=>m.userId!==p.targetId);return {};}
if(p.resource==='messages'){if(globalThis.throttleMessages){globalThis.throttledReads++;throw 'RATE_LIMITED';}const rows=[...messages].reverse();if(!p.targetId)return rows.slice(0,50);if(globalThis.failHistory)throw 'SERVICE_UNAVAILABLE';const index=rows.findIndex(row=>row.id===p.targetId);return index<0?[]:rows.slice(index+1,index+51);}
if(p.resource==='message-send'){messages.push({id:'m1',authorId:'u1',body:p.body.body,createdAt:new Date().toISOString()});return messages.at(-1);}
if(p.resource==='message-delete'){if(globalThis.failDelete)throw 'CURRENT_GRANT_REQUIRED';const message=messages.find(row=>row.id===p.id);if(!message)throw 'RESOURCE_NOT_AVAILABLE';message.deleted=true;return message;}
if(p.resource==='friend-search')return [{userId:'u2',email:p.id}];
if(p.resource==='group-invite')return {id:'invite-1'};
if(['register','verify','forgot','reset'].includes(p.resource))throw 'EMAIL_DELIVERY_UNAVAILABLE';
return {};}
throw 'UNEXPECTED_COMMAND';}}};
globalThis.__TAURI__.event={listen:async(name,listener)=>{if(name==='product-changed')globalThis.emitProduct=payload=>listener({payload});return ()=>{};}};`;
try {
  let pages;
  for (let i = 0; i < 80; i++) {
    try {
      const port = Number(readFileSync(resolve(profile, "DevToolsActivePort"), "utf8").split(/\r?\n/, 1)[0]);
      if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid browser debug port");
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
  await command("Emulation.setDeviceMetricsOverride", {width:600,height:900,deviceScaleFactor:1,mobile:false});
  await evaluate(`globalThis.editing=document.querySelector('#friend-search');editing.focus();editing.value='保留输入';globalThis.serviceState='RETRYING'`);
  await delay(1300);
  await check("窄屏断线提示可见且保留输入焦点", `document.querySelector('[aria-label="重试服务连接"]').getBoundingClientRect().width>0 && document.documentElement.scrollWidth<=innerWidth && document.activeElement===editing && editing.value==='保留输入'`);
  await evaluate(`globalThis.serviceState='CONNECTED'`);
  await delay(1300);
  await check("连接恢复不打断编辑", `document.querySelector('.connection-pill').dataset.service==='CONNECTED' && !document.querySelector('[aria-label="重试服务连接"]') && document.activeElement===editing && editing.value==='保留输入'`);
  await evaluate(`editing.value='';editing.blur()`);
  await command("Emulation.setDeviceMetricsOverride", {width:1440,height:900,deviceScaleFactor:1,mobile:false});
  await evaluate('friends[0].presence="OFFLINE";emitProduct({userId:"u1"})');
  await delay(900);
  await check("定向通知自动更新好友离线状态", 'document.querySelector(".friend-row small").textContent==="离线"');
  await evaluate('friends[0].presence="ONLINE";emitProduct({userId:"u1"})');
  await delay(900);
  await check("心跳恢复通知自动更新好友在线状态", 'document.querySelector(".friend-row small").textContent==="在线"');
  await evaluate('friends=[];emitProduct({userId:"u1"})');
  await delay(900);
  await check("另一端删除关系后自动移除好友", '!document.querySelector(".friend-row")');
  await evaluate('friends=[{userId:"u2",email:"friend@example.test",presence:"ONLINE"}];emitProduct({userId:"u1"})');
  await delay(900);
  await check("另一端接受申请后自动显示好友", 'document.querySelector(".friend-row strong").textContent==="friend@example.test"');
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
  await evaluate('failFriendRequests=true');
  await click('[data-action="friend-requests"]');
  await check("申请列表加载失败给出错误且不显示假列表", '!document.querySelector("[role=dialog]") && document.body.innerText.includes("无法连接")');
  await evaluate('failFriendRequests=false');
  await click('[data-action="friend-requests"]');
  await check("好友申请展示真实桥接列表并转义邮箱", 'document.querySelectorAll(".friend-request-row").length===3 && document.querySelector(".friend-request-row strong").textContent==="new<friend>@example.test" && !document.querySelector(".friend-request-row friend") && !document.querySelector("[name=id]")');
  await check("不可用申请账号只允许拒绝", '!document.querySelector("[data-action=accept-friend-request][data-id=fr3]") && !!document.querySelector("[data-action=reject-friend-request][data-id=fr3]")');
  await evaluate('incoming.push({id:"fr-live",requesterId:"u-live",email:"live@example.test",accountStatus:"ACTIVE"});document.querySelector("[data-action=reject-friend-request][data-id=fr2]").focus();emitProduct({userId:"u1"})');
  await delay(900);
  await check("打开中好友申请自动更新并保留按钮焦点", '!!document.querySelector("[data-id=fr-live]") && document.activeElement.dataset.id==="fr2"');
  await evaluate('incoming=incoming.filter(item=>item.id!=="fr-live");emitProduct({userId:"u1"})');
  await delay(900);
  await check("另一端处理后从打开列表移除申请", 'document.querySelectorAll(".friend-request-row").length===3');
  await evaluate('friendListDelay=1000;globalThis.listStart=calls.length;emitProduct({userId:"u1"})');
  await delay(650);
  await check("后台列表读取确已开始", 'calls.slice(listStart).some(x=>x.p.resource==="friend-requests")');
  await click('[data-action="dialog-close"]');
  await click('[data-action="add-friend"]');
  await fill('[name="query"]', 'draft@example.test');
  await delay(1200);
  await check("迟到列表结果不能覆盖新表单或草稿", 'document.getElementById("dialog-title").textContent==="添加好友" && document.querySelector("[name=query]").value==="draft@example.test"');
  await evaluate('friendListDelay=0;emitProduct({userId:"u1"})');
  await delay(700);
  await check("同步通知不覆盖编辑中的表单", 'document.querySelector("[name=query]").value==="draft@example.test"');
  await click('[data-action="dialog-close"]');
  await click('[data-action="friend-requests"]');
  await evaluate('failFriendResponse=true');
  await click('[data-action="reject-friend-request"][data-id="fr2"]');
  await check("申请处理失败保留列表并显示可恢复错误", 'document.querySelectorAll(".friend-request-row").length===3 && document.getElementById("dialog-error").textContent.includes("请刷新")');
  await evaluate('failFriendResponse=false;friendResponseDelay=200');
  await evaluate('document.querySelector("[data-action=accept-friend-request][data-id=fr1]").click();document.querySelector("[data-action=accept-friend-request][data-id=fr1]").click()');
  await delay(400);
  await check("接受申请只提交一次并刷新好友与待处理列表", 'calls.filter(x=>x.command==="product_request" && x.p.resource==="friend-accept" && x.p.id==="fr1").length===1 && !document.querySelector("[data-id=fr1]") && document.querySelectorAll(".friend-request-row").length===2');
  await evaluate('friendResponseDelay=0');
  await click('[data-action="reject-friend-request"][data-id="fr2"]');
  await click('[data-action="reject-friend-request"][data-id="fr3"]');
  await check("拒绝后显示空列表", 'document.querySelector("[role=dialog]").textContent.includes("暂无好友申请") && !document.querySelector(".friend-request-row")');
  await evaluate('incoming=Array.from({length:51},(_,i)=>({id:`page-${i}`,requesterId:`user-${i}`,email:`page-${i}@example.test`,accountStatus:"ACTIVE"}))');
  await click('[data-action="refresh-friend-requests"]');
  await click('[data-action="earlier-friend-requests"]');
  await check("申请可翻页且使用服务端游标", 'document.querySelectorAll(".friend-request-row").length===1 && document.querySelector(".friend-request-row strong").textContent==="page-50@example.test" && calls.some(x=>x.command==="product_request" && x.p.resource==="friend-requests" && x.p.id==="page-49")');
  await evaluate('emitProduct({userId:"u1"})');
  await delay(900);
  await check("自动刷新保留较早申请页的游标", 'document.querySelectorAll(".friend-request-row").length===1 && document.querySelector(".friend-request-row strong").textContent==="page-50@example.test"');
  await click('[data-action="refresh-friend-requests"]');
  await check("分页可返回最新", 'document.querySelectorAll(".friend-request-row").length===50');
  await evaluate('document.querySelector(".modal").scrollTop=200;globalThis.modalScroll=document.querySelector(".modal").scrollTop;emitProduct({userId:"u1"})');
  await delay(900);
  await check("列表自动刷新保留滚动位置", 'document.querySelector(".modal").scrollTop===modalScroll && modalScroll>0');
  await evaluate('failFriendRequests=true;emitProduct({userId:"u1"})');
  await delay(900);
  await check("后台列表暂时失败保留内容并显示错误", 'document.querySelectorAll(".friend-request-row").length===50 && document.getElementById("dialog-error").textContent.includes("无法连接")');
  await evaluate('failFriendRequests=false');
  await delay(900);
  await check("列表读取恢复后自动重试清除错误", 'document.querySelectorAll(".friend-request-row").length===50 && document.getElementById("dialog-error").textContent===""');
  await shot("friend-requests-1440");
  await command("Emulation.setDeviceMetricsOverride", { width: 600, height: 700, deviceScaleFactor: 1, mobile: false });
  await check("长申请列表在窄窗口内滚动且无横向溢出", 'document.querySelector(".modal").scrollHeight>document.querySelector(".modal").clientHeight && document.documentElement.scrollWidth<=innerWidth && document.querySelector(".modal").scrollWidth<=document.querySelector(".modal").clientWidth');
  await shot("friend-requests-600");
  await command("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await click('[data-action="dialog-close"]');
  await click('[data-view="groups"]');
  await evaluate('failInvitations=true');
  await click('[data-action="invitation"]');
  await check("群邀请加载失败不给假收件列表", '!document.querySelector("[role=dialog]") && document.body.innerText.includes("无法连接")');
  await evaluate('failInvitations=false');
  await click('[data-action="invitation"]');
  await check("群邀请显示真实列表转义群名且无需输入ID", 'document.querySelector(".group-member-row strong").textContent==="新群 <&>" && !document.querySelector("[name=id]")');
  await evaluate('invitations[0].groupName="邀请实时更新";emitProduct({userId:"u1"})');
  await delay(900);
  await check("群邀请打开时自动读取变更", 'document.querySelector(".group-member-row strong").textContent==="邀请实时更新"');
  await click('[data-action="accept-invitation"]');
  await click('[data-action="dialog-close"]');
  await check("取消接受群邀请不发送请求", '!calls.some(x=>x.p.resource==="invitation-accept")');
  await click('[data-action="invitation"]');
  await click('[data-action="accept-invitation"]');
  await evaluate('failInvitationDecision=true;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("失效邀请保留确认与中文错误", 'document.getElementById("dialog-error").textContent.includes("邀请已撤销或失效") && invitations.length===1');
  await evaluate('failInvitationDecision=false;invitationDelay=200;document.querySelector("[data-form=dialog]").requestSubmit();document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(400);
  await check("接受邀请防重复并刷新群列表及收件列表", 'calls.filter(x=>x.p.resource==="invitation-accept").length===2 && document.querySelector("[role=dialog]").textContent.includes("暂无待接受邀请")');
  await evaluate('invitationDelay=0;invitations=Array.from({length:51},(_,i)=>({id:`gi-page-${i}`,groupId:`group-${i}`,groupName:`群组 ${i}`,inviterId:"u9",inviteeId:"u1"}))');
  await click('[data-action="refresh-invitations"]');
  await click('[data-action="earlier-invitations"]');
  await check("群邀请支持稳定游标翻页与返回最新", 'document.querySelectorAll(".group-member-row").length===1 && calls.some(x=>x.p.resource==="invitations" && x.p.id==="gi-page-49")');
  await click('[data-action="refresh-invitations"]');
  await shot("group-invitations-1440");
  await command("Emulation.setDeviceMetricsOverride", { width: 600, height: 700, deviceScaleFactor: 1, mobile: false });
  await check("群邀请长列表窄窗口滚动且不溢出", 'document.querySelector(".modal").scrollHeight>document.querySelector(".modal").clientHeight && document.querySelector(".modal").scrollWidth<=document.querySelector(".modal").clientWidth');
  await shot("group-invitations-600");
  await command("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await click('[data-action="dialog-close"]');
  await click('[data-action="open-group"]');
  await shot("group-1440");
  await click('[data-action="manage-group"]');
  await click('[data-action="audit"]');
  await check("管理记录显示完整字段且转义服务端文本", 'document.querySelector(".audit-entry").textContent.includes("修改房间") && document.querySelector(".audit-entry").textContent.includes("actor <&>") && document.querySelector(".audit-entry").textContent.includes("room <img src=x>") && document.querySelector(".audit-entry").textContent.includes("成功") && document.querySelector(".audit-entry time").getAttribute("datetime")==="2026-11-17T08:30:00Z" && !document.querySelector(".audit-entry img")');
  await evaluate('auditEntries[0].action="OWNER_RECOVERY_REVIEW";auditEntries[0].result="DISPUTED"');
  await click('[data-action="refresh-audit"]');
  await check("人工审核管理记录说明争议未改变归属", 'document.querySelector(".audit-entry").textContent.includes("人工恢复审核") && document.querySelector(".audit-entry").textContent.includes("存在争议，归属未变更")');
  await check("管理记录只读无编辑删除入口", '!document.querySelector(".audit-list input,.audit-list textarea,.audit-list button") && !document.querySelector(".modal-actions .primary")');
  await evaluate('auditEntries=Array.from({length:53},(_,i)=>({id:`audit-page-${i}`,actorId:"a".repeat(128),targetType:"GROUP_MEMBER",targetId:"t".repeat(128),action:"GROUP_MEMBER_REMOVE",result:"SUCCESS",occurredAt:"2026-11-17T08:30:00Z"}))');
  await click('[data-action="refresh-audit"]');
  await check("管理记录首页限制50条", 'document.querySelectorAll(".audit-entry").length===50 && !!document.querySelector("[data-action=earlier-audit]")');
  await shot("audit-history-1440");
  await click('[data-action="earlier-audit"]');
  await check("管理记录使用独立游标读取更早同时间记录", 'document.querySelectorAll(".audit-entry").length===3 && calls.some(x=>x.p.resource==="audit" && x.p.id==="g1" && x.p.targetId==="audit-page-49") && !!document.querySelector("[data-action=refresh-audit]")');
  await evaluate('failAudit=true');
  await click('[data-action="refresh-audit"]');
  await check("记录读取失权保留列表并显示错误", 'document.querySelectorAll(".audit-entry").length===3 && document.getElementById("dialog-error").textContent.includes("没有执行")');
  await evaluate('failAudit=false');
  await click('[data-action="refresh-audit"]');
  await command("Emulation.setDeviceMetricsOverride", { width: 600, height: 700, deviceScaleFactor: 1, mobile: false });
  await check("管理记录长ID窄窗口可滚动且不溢出", 'document.querySelector(".modal").scrollHeight>document.querySelector(".modal").clientHeight && document.querySelector(".modal").scrollWidth<=document.querySelector(".modal").clientWidth');
  await shot("audit-history-600");
  await command("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await evaluate('auditEntries=[]');
  await click('[data-action="refresh-audit"]');
  await check("管理记录空列表可继续刷新", 'document.querySelector("[role=dialog]").textContent.includes("暂无管理记录") && !!document.querySelector("[data-action=refresh-audit]")');
  await evaluate('failAudit=true;emitProduct({userId:"u1"})');
  await delay(900);
  await check("后台读取失权关闭不可访问的记录列表", '!document.querySelector("[role=dialog]") && !document.getElementById("app").inert');
  await evaluate('failAudit=false');
  await click('[data-action="manage-group"]');
  await click('[data-action="audit"]');
  await click('[data-action="dialog-close"]');
  await click('[data-action="manage-group"]');
  await click('[data-action="invite-member"]');
  await fill('[name="email"]', 'friend@example.test');
  await evaluate('document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("群邀请通过邮箱查找账号并发送无需传递邀请ID", 'calls.some(x=>x.p.resource==="group-invite" && x.p.id==="g1" && x.p.body.inviteeId==="u2") && document.getElementById("toast").textContent.includes("群邀请")');
  await click('[data-action="manage-group"]');
  await click('[data-action="group-invitations"]');
  await check("群主可查看待接受邀请管理列表", 'document.querySelector(".group-member-row strong").textContent==="u8" && !!document.querySelector("[data-action=revoke-invitation]")');
  await click('[data-action="revoke-invitation"]');
  await evaluate('failInvitationDecision=true;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("撤销失效错误保留确认", 'document.getElementById("dialog-error").textContent.includes("已撤销或失效") && sentInvitations.length===1');
  await evaluate('failInvitationDecision=false;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("撤销请求分离群和邀请ID且刷新列表", 'calls.some(x=>x.p.resource==="invitation-revoke" && x.p.id==="g1" && x.p.targetId==="sent1") && document.querySelector("[role=dialog]").textContent.includes("暂无待接受邀请")');
  await evaluate('sentInvitations=Array.from({length:51},(_,i)=>({id:`sent-page-${i}`,groupId:"g1",groupName:"周末远征队",inviterId:"u1",inviteeId:`user-${i}`}))');
  await click('[data-action="refresh-invitations"]');
  await click('[data-action="earlier-invitations"]');
  await check("邀请管理翻页保留群上下文", 'document.querySelectorAll(".group-member-row").length===1 && calls.some(x=>x.p.resource==="group-invitations" && x.p.id==="g1" && x.p.targetId==="sent-page-49")');
  await click('[data-action="dialog-close"]');
  await click('[data-action="manage-group"]');
  await click('[data-action="group-members"]');
  await check("群主可管理其他成员且不能移除自己", 'document.querySelectorAll(".group-member-row").length===3 && document.querySelectorAll("[data-action=change-member-role]").length===2 && !document.querySelector("[data-action=remove-group-member][data-id=u1]")');
  await evaluate('groupMembers.find(m=>m.userId==="u2").role="ADMIN";emitProduct({userId:"u1"})');
  await delay(900);
  await check("成员列表自动更新远端角色", 'document.querySelector("[data-action=change-member-role][data-id=u2]").textContent==="取消管理员"');
  await evaluate('groupMembers.find(m=>m.userId==="u2").role="MEMBER";emitProduct({userId:"u1"})');
  await delay(900);
  await click('[data-action="remove-group-member"][data-id="u2"]');
  await check("移除先显示失权与房主接管提示且不发送请求", 'document.querySelector("[role=dialog]").textContent.includes("房间将由群主接管") && !calls.some(x=>x.p.resource==="group-member-remove")');
  await evaluate('emitProduct({userId:"u1"})');
  await delay(700);
  await check("同步通知不会替换移除确认框", 'document.getElementById("dialog-title").textContent==="移除成员" && !calls.some(x=>x.p.resource==="group-member-remove")');
  await click('[data-action="dialog-close"]');
  await check("取消移除保留成员", 'groupMembers.some(m=>m.userId==="u2") && !calls.some(x=>x.p.resource==="group-member-remove")');
  await click('[data-action="manage-group"]');
  await click('[data-action="group-members"]');
  await click('[data-action="change-member-role"][data-id="u2"]');
  await evaluate('failMemberChange=true;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("失权错误保留确认且不伪造角色变更", 'document.getElementById("dialog-error").textContent.includes("没有执行") && groupMembers.find(m=>m.userId==="u2").role==="MEMBER"');
  await evaluate('failMemberChange=false;memberChangeDelay=200;document.querySelector("[data-form=dialog]").requestSubmit();document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(400);
  await check("角色变更防重复并通过独立目标参数刷新结果", 'calls.filter(x=>x.p.resource==="group-member-role").length===2 && calls.some(x=>x.p.resource==="group-member-role" && x.p.id==="g1" && x.p.targetId==="u2" && x.p.body.role==="ADMIN") && document.querySelector("[data-action=change-member-role][data-id=u2]").textContent==="取消管理员"');
  await evaluate('memberChangeDelay=0');
  await click('[data-action="change-member-role"][data-id="u2"]');
  await evaluate('document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("群主可以取消管理员", 'groupMembers.find(m=>m.userId==="u2").role==="MEMBER"');
  await shot("group-members-1440");
  await command("Emulation.setDeviceMetricsOverride", { width: 600, height: 700, deviceScaleFactor: 1, mobile: false });
  await check("成员管理窄窗口无横向溢出", 'document.documentElement.scrollWidth<=innerWidth && document.querySelector(".modal").scrollWidth<=document.querySelector(".modal").clientWidth');
  await shot("group-members-600");
  await command("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await evaluate('groupMembers.find(m=>m.userId==="u1").role="ADMIN";groupMembers.push({userId:"u4",role:"OWNER"})');
  await click('[data-action="group-members"]');
  await check("管理员仅能移除普通成员且没有角色变更入口", 'document.querySelectorAll("[data-action=remove-group-member]").length===1 && !!document.querySelector("[data-action=remove-group-member][data-id=u2]") && !document.querySelector("[data-action=change-member-role]")');
  await click('[data-action="remove-group-member"][data-id="u2"]');
  await evaluate('document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("移除成功刷新列表", '!document.querySelector("[data-action=remove-group-member][data-id=u2]") && calls.some(x=>x.p.resource==="group-member-remove" && x.p.targetId==="u2")');
  await evaluate('groupMembers.find(m=>m.userId==="u1").role="MEMBER"');
  await click('[data-action="group-members"]');
  await check("普通成员只读成员列表", '!document.querySelector("[data-action=change-member-role]") && !document.querySelector("[data-action=remove-group-member]")');
  await click('[data-action="dialog-close"]');
  await click('[data-action="manage-group"]');
  await check("普通成员群管理不展示保存或解散", '!document.querySelector("[name=name]") && !document.querySelector("[data-action=dissolve-group]") && !document.querySelector(".modal-actions .primary")');
  await click('[data-action="dialog-close"]');
  await evaluate('groupMembers=[{userId:"u1",role:"OWNER"},{userId:"u2",role:"MEMBER"},{userId:"u3",role:"ADMIN"}]');
  await click('[data-action="manage-group"]');
  await click('[data-action="group-members"]');
  await click('[data-action="start-owner-transfer"][data-id="u2"]');
  await check("转让群主先确认并说明接受前归属不变", 'document.querySelector("[role=dialog]").textContent.includes("对方接受前你仍是群主") && !calls.some(x=>x.p.resource==="owner-transfer-create")');
  await evaluate('ownerDecisionDelay=200;document.querySelector("[data-form=dialog]").requestSubmit();document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(400);
  await check("发起转让防重复并显示待处理与撤销入口", 'calls.filter(x=>x.p.resource==="owner-transfer-create").length===1 && calls.some(x=>x.p.resource==="owner-transfer-create" && x.p.body.targetUserId==="u2") && group.ownerUserId==="u1" && !!document.querySelector("[data-action=cancel-owner-transfer]") && !document.querySelector("[data-action=accept-owner-transfer]")');
  await evaluate('globalThis.pendingTransfer=structuredClone(ownerTransfers);ownerTransfers=[];emitProduct({userId:"u1"})');
  await delay(900);
  await check("打开的转让列表自动移除远端已处理事项", 'document.querySelector("[role=dialog]").textContent.includes("暂无待处理转让")');
  await evaluate('ownerTransfers=pendingTransfer;emitProduct({userId:"u1"})');
  await delay(900);
  await shot("owner-transfer-1440");
  await click('[data-action="cancel-owner-transfer"]');
  await evaluate('failOwnerDecision=true;ownerDecisionDelay=0;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("转让已结束错误保留确认且不伪造成功", 'document.getElementById("dialog-error").textContent.includes("已结束") && ownerTransfers.length===1');
  await evaluate('failOwnerDecision=false;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("撤销成功后清空列表且不更改群主", 'document.querySelector("[role=dialog]").textContent.includes("暂无待处理转让") && group.ownerUserId==="u1"');
  await evaluate('group.ownerUserId="u2";groupMembers.find(m=>m.userId==="u1").role="MEMBER";groupMembers.find(m=>m.userId==="u2").role="OWNER";ownerTransfers=[{id:"ot2",fromUserId:"u2",toUserId:"u1",createdAt:new Date().toISOString()}]');
  await click('[data-action="owner-transfers"]');
  await check("指定接收人展示接受且不展示撤销", '!!document.querySelector("[data-action=accept-owner-transfer]") && !document.querySelector("[data-action=cancel-owner-transfer]")');
  await command("Emulation.setDeviceMetricsOverride", { width: 600, height: 700, deviceScaleFactor: 1, mobile: false });
  await check("转让列表窄窗口不溢出", 'document.querySelector(".modal").scrollWidth<=document.querySelector(".modal").clientWidth');
  await shot("owner-transfer-600");
  await command("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await click('[data-action="accept-owner-transfer"]');
  await check("接受前二次确认角色后果", 'document.querySelector("[role=dialog]").textContent.includes("原群主将成为普通成员")');
  await evaluate('document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await click('[data-action="group-members"]');
  await check("接受后刷新群主角色和成员操作", 'group.ownerUserId==="u1" && !!document.querySelector("[data-action=start-owner-transfer][data-id=u2]") && document.querySelector(".group-member-row").textContent.includes("群主")');
  await click('[data-action="dialog-close"]');
  await click('[data-action="manage-group"]');
  await click('[data-action="owner-transfers"]');
  await click('[data-action="owner-recovery"]');
  await fill('[name="evidenceRef"]', 'support-ticket-123');
  await evaluate('failRecovery=true;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("人工恢复失败保留证据输入", 'document.querySelector("[name=evidenceRef]").value==="support-ticket-123" && document.getElementById("dialog-error").textContent.includes("无法连接")');
  await evaluate('failRecovery=false;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("人工恢复仅提交申请且显示编号不更改归属", 'document.querySelector("[role=dialog]").textContent.includes("recovery-1") && document.querySelector("[role=dialog]").textContent.includes("归属保持不变") && group.ownerUserId==="u1"');
  await click('[data-action="dialog-close"]');
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
    'document.querySelector(".message-text").textContent==="草稿<&>" && !document.querySelector(".message-text > *") && document.getElementById("message-input").value===""',
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
  await check('群主在历史页可删除他人消息但不能重复删除', `JSON.stringify(Array.from(document.querySelectorAll('[data-message-history] [data-action=delete-message]'),node=>node.dataset.id))===JSON.stringify(['page-0','page-1'])`);
  await evaluate('globalThis.deleteStart=calls.length;globalThis.failDelete=true');
  await click('[data-message-history] [data-id="page-1"]');
  await check('历史消息删除等待明确确认', `document.querySelector('#dialog-title').textContent==='删除消息' && !calls.slice(deleteStart).some(x=>x.p.resource==='message-delete')`);
  await click('.modal-actions .primary');
  await check('服务端拒绝时保留消息并显示错误', `!messages[1].deleted && document.querySelector('#dialog-error').textContent.length>0`);
  await evaluate('failDelete=false;failHistory=true'); await click('.modal-actions .primary');
  await check('删除成功后读取失败可单独刷新', `messages[1].deleted && document.querySelector('.modal-actions .primary').textContent==='刷新消息' && document.querySelector('#dialog-error').textContent.length>0`);
  await evaluate('failHistory=false'); await click('.modal-actions .primary');
  await check('仅重读原历史页不再次提交删除', `JSON.stringify(calls.slice(deleteStart).filter(x=>x.p.resource==='message-delete').map(x=>x.p.id))===JSON.stringify(['page-1','page-1']) && JSON.stringify(Array.from(document.querySelectorAll('[data-message-history] .message-text'),node=>node.textContent))===JSON.stringify(['历史 <0>','此消息已被删除','此消息已被删除']) && !document.querySelector('[data-message-history] [data-id="page-1"]')`);
  await click('[role=dialog] [data-action="dialog-close"].ghost');
  await check('返回最新后草稿与最新消息保持', `document.querySelector('#message-input').value==='翻页草稿' && Array.from(document.querySelectorAll('.group-chat-body .message-text')).at(-1).textContent==='历史 <102>'`);
  await evaluate('messages=originalMessages'); await click('[data-view="groups"]'); await click('[data-action="open-group"]');
  await fill("#message-input", "实时同步草稿");
  await evaluate('document.getElementById("message-input").focus();globalThis.noticeStart=calls.length;emitProduct({userId:"u1",messageGroupId:"another-group"})');
  await delay(700);
  await check("其他群新消息不触发列表读取", 'calls.slice(noticeStart).every(x=>!["product_request","group_list","room_list"].includes(x.command))');
  await evaluate('globalThis.noticeStart=calls.length;messages.push({id:"remote-1",authorId:"u2",body:"远端新消息",createdAt:new Date().toISOString()});for(let n=0;n<20;n++)emitProduct({userId:"u1",messageGroupId:"g1"})');
  await delay(1200);
  await check("编辑期间自动接收消息并保留草稿和焦点", 'document.activeElement.id==="message-input" && document.getElementById("message-input").value==="实时同步草稿" && document.body.innerText.includes("远端新消息")');
  await check("同群二十条通知合并为一次消息读取", 'JSON.stringify(calls.slice(noticeStart).filter(x=>["product_request","group_list","room_list"].includes(x.command)).map(x=>x.p.resource))===JSON.stringify(["messages"])');
  await evaluate(`globalThis.throttledReads=0;globalThis.throttleMessages=true;emitProduct({userId:'u1'});
    new Promise((resolve,reject)=>{const deadline=performance.now()+3000;function poll(){if(throttledReads)return resolve();if(performance.now()>deadline)return reject(Error('first limited read missing'));setTimeout(poll,20);}poll();})`);
  await evaluate(`emitProduct({userId:'u1'})`);
  await delay(1100);
  await check('限流后同步通知不触发半秒重复请求', 'throttledReads===1');
  await evaluate(`globalThis.throttleMessages=false;messages.push({id:'recovered-after-limit',authorId:'u2',body:'限流后补读',createdAt:new Date().toISOString()})`);
  await evaluate(`new Promise((resolve,reject)=>{const deadline=performance.now()+5000;function poll(){if(document.body.innerText.includes('限流后补读'))return resolve(true);if(performance.now()>deadline)return reject(Error('limited read did not recover'));setTimeout(poll,50);}poll();})`);
  await check('限流后自动补读且保留草稿焦点', 'document.activeElement.id==="message-input" && document.activeElement.value==="实时同步草稿"');
  await evaluate('document.activeElement.blur()');
  await delay(1200);
  await check("结束编辑后自动读取远端消息且保留草稿", 'document.body.innerText.includes("远端新消息") && document.getElementById("message-input").value==="实时同步草稿"');
  await evaluate('globalThis.syncCalls=calls.length;emitProduct({userId:"other-account"})');
  await delay(700);
  await check("其他账号同步通知不触发资源读取", 'calls.slice(syncCalls).every(x=>x.command!=="group_list" && x.command!=="room_list" && x.p.resource!=="messages")');
  await click('[data-view="lobby"]');
  await evaluate('globalThis.holdVoice=true');
  await click('[data-action="join-room"]');
  await evaluate(
    'document.querySelector("[data-form=dialog]").requestSubmit()',
  );
  await delay(300);
  await check('语音等待期间联机已独立尝试且房间可用', 'typeof resumeVoice==="function" && calls.some(call=>call.command==="network_connect" && call.p.leaseId==="lease-room") && !!document.querySelector(".voice-dock")');
  await evaluate('globalThis.holdVoice=false;resumeVoice()');
  await delay(150);
  await check(
    "入房后保留独立语音失败和网络未连接",
    '!!document.querySelector(".voice-dock") && document.querySelectorAll(".room-flags [data-connection=failed]").length===2',
  );
  await click('[data-tab="network"]');
  await check(
    "未接入网络不伪造队友地址",
    'document.querySelector(".network-panel").innerText.includes("尚未就绪") && document.querySelectorAll(".ip-address").length===0',
  );
  await click('[data-action="retry-network"]');
  await check("独立重试联机不重建语音", 'calls.filter(x=>x.command==="network_connect").length===2 && calls.filter(x=>x.command==="voice_connect").length===1');
  await shot("room-1440");
  await evaluate('globalThis.failVoiceRead=true;globalThis.networkState="CONNECTED"');
  await delay(1300);
  await check('语音状态不可读不阻挡联机恢复', `document.querySelector('.room-flags').innerText.includes('语音 · 状态未知') && document.querySelector('.network-panel .ip-address')?.textContent==='10.66.0.2'`);
  await evaluate('failVoiceRead=false;globalThis.failNetworkRead=true;voice.state="CONNECTED";voice.failureCode=null');
  await delay(1300);
  await check('联机状态不可读不阻挡语音恢复且收起旧地址', `document.querySelector('.room-flags').innerText.includes('语音 · 已连接') && document.querySelector('.room-flags').innerText.includes('联机 · 状态未知') && !document.querySelector('.network-panel .ip-address')`);
  await evaluate('failNetworkRead=false;networkState="FAILED"');
  await delay(1200);
  await check('读取恢复后联机状态由未知变为真实失败', `document.querySelector('.room-flags').innerText.includes('联机 · 连接失败') && !!document.querySelector('[data-action=retry-network]')`);
  await check("媒体重连事件自动刷新房间状态", 'document.querySelector(".room-flags").innerText.includes("已连接")');
  await evaluate('voice.members=[{userId:"u2",memberGeneration:3,speaking:true,muted:false,subscribed:true}]');
  await delay(1200);
  await check("成员显示真实媒体发言状态", 'document.querySelector(".party-grid").innerText.includes("正在说话")');
  await click('[data-tab="members"]');
  await check("成员侧栏与房间卡片共用发言状态", 'document.querySelector(".network-panel").innerText.includes("正在说话")');
  await evaluate('voice.members[0].muted=true;voice.members[0].speaking=false');
  await delay(1200);
  await check("远端静音状态更新", 'document.querySelector(".party-grid").innerText.includes("已静音")');
  await check("成员侧栏显示远端静音", 'document.querySelector(".network-panel").innerText.includes("已静音")');
  await evaluate('voice.members[0].memberGeneration=2;voice.members[0].muted=false;voice.members[0].speaking=true');
  await delay(1200);
  await check("旧成员代次不显示发言", '!document.querySelector(".party-grid").innerText.includes("正在说话")');
  await evaluate('voice.members[0].memberGeneration=3;voice.state="RETRYING"');
  await delay(1200);
  await check("重连期间不显示旧发言", '!document.querySelector(".party-grid").innerText.includes("正在说话")');
  await evaluate('voice.state="CONNECTED";voice.members=[]');
  await click('[data-tab="chat"]');
  await fill("#message-input", "轮询期间保留草稿");
  await evaluate('document.getElementById("message-input").focus();voice.state="RETRYING"');
  await delay(1200);
  await check("媒体轮询不覆盖正在编辑的草稿", 'document.getElementById("message-input").value==="轮询期间保留草稿" && document.activeElement.id==="message-input"');
  await evaluate('document.activeElement.blur()');
  await delay(1200);
  await check("停止编辑后补上延迟的媒体状态", 'document.querySelector(".room-flags").innerText.includes("重试中")');
  await click('[data-tab="members"]');
  await evaluate('(()=>{const slider=document.querySelector("[data-member-volume=u2]");slider.value="0.35";slider.dispatchEvent(new Event("change",{bubbles:true}));})()');
  await delay(200);
  await check("成员音量通过账号传给原生层并保留设置", 'calls.some(x=>x.command==="voice_set_volume" && x.p.memberId==="u2" && x.p.volume===0.35) && document.querySelector("[data-member-volume=u2]").value==="0.35"');
  await check("群主可踢管理员与成员但不能踢自己", 'document.querySelectorAll("[data-action=kick-activity-member]").length===2 && !document.querySelector("[data-action=kick-activity-member][data-id=u1]")');
  await click('[data-action="kick-activity-member"][data-id="u2"]');
  await check("踢人先确认且说明允许重入", 'document.querySelector("[role=dialog]").textContent.includes("仍可按当前房间规则重新加入") && !calls.some(x=>x.p.resource==="activity-kick")');
  await click('[data-action="dialog-close"]');
  await check("取消踢人不提交", '!calls.some(x=>x.p.resource==="activity-kick")');
  await click('[data-action="kick-activity-member"][data-id="u2"]');
  await evaluate('failRoomGovernance=true;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("成员代次改变时保留确认与刷新提示", 'document.getElementById("dialog-error").textContent.includes("重新加入") && activityMembers.some(m=>m.userId==="u2")');
  await evaluate('failRoomGovernance=false;roomGovernanceDelay=200;document.querySelector("[data-form=dialog]").requestSubmit();document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(400);
  await check("踢人绑定成员代次防重复且成功刷新", 'calls.filter(x=>x.p.resource==="activity-kick").length===2 && calls.some(x=>x.p.resource==="activity-kick" && x.p.id==="a1" && x.p.targetId==="u2" && x.p.body.memberGeneration===3) && !document.querySelector("[data-member-volume=u2]")');
  await evaluate('roomGovernanceDelay=0;activityMembers.push({userId:"u2",state:"ACTIVE",memberGeneration:4})');
  await click('[data-action="refresh-room-members"]');
  await check("重新加入成员显示新的代次", 'document.querySelector("[data-action=kick-activity-member][data-id=u2]").dataset.generation==="4"');
  await click('[data-action="manage-room"]');
  await click('[data-action="assign-room-owner"]');
  await check("指定房主列出当前群成员", 'document.querySelectorAll("[name=targetUserId] option").length===3 && document.querySelector("[role=dialog]").textContent.includes("权限仅限该房间")');
  await fill('[name="targetUserId"]', 'u2');
  await evaluate('failRoomGovernance=true;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("指定房主失权保留选择", 'document.querySelector("[name=targetUserId]").value==="u2" && document.getElementById("dialog-error").textContent.includes("没有执行") && room.ownerUserId==="u1"');
  await evaluate('failRoomGovernance=false;document.querySelector("[data-form=dialog]").requestSubmit()');
  await delay(200);
  await check("指定房主成功后刷新显示", 'room.ownerUserId==="u2" && calls.some(x=>x.p.resource==="room-owner" && x.p.id==="r1" && x.p.body.targetUserId==="u2") && document.querySelector(".room-side").textContent.includes("房主")');
  await shot("room-governance-1440");
  await command("Emulation.setDeviceMetricsOverride", { width: 600, height: 900, deviceScaleFactor: 1, mobile: false });
  await check("成员治理窄窗口不溢出", 'document.documentElement.scrollWidth<=innerWidth');
  await shot("room-governance-600");
  await command("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await evaluate('groupMembers.find(m=>m.userId==="u1").role="MEMBER";groupMembers.find(m=>m.userId==="u3").role="ADMIN";room.ownerUserId="u1"');
  await click('[data-action="refresh-room-members"]');
  await check("普通房主仅可踢普通成员", 'document.querySelectorAll("[data-action=kick-activity-member]").length===1 && !!document.querySelector("[data-action=kick-activity-member][data-id=u2]")');
  await click('[data-action="manage-room"]');
  await check("普通房主可管理房间但不能指定房主", '!!document.querySelector("[name=name]") && !document.querySelector("[data-action=assign-room-owner]")');
  await click('[data-action="dialog-close"]');
  await evaluate('room.ownerUserId="u2"');
  await click('[data-action="refresh-room-members"]');
  await check("普通成员不显示踢人操作", '!document.querySelector("[data-action=kick-activity-member]")');
  await click('[data-action="manage-room"]');
  await check("普通成员房间设置只读", '!document.querySelector("[name=name]") && !document.querySelector("[data-action=delete-room]") && !document.querySelector(".modal-actions .primary")');
  await click('[data-action="dialog-close"]');
  await evaluate('groupMembers.find(m=>m.userId==="u1").role="OWNER";room.ownerUserId="u1"');
  await click('[data-action="refresh-room-members"]');
  await evaluate('voice.state="CONNECTED"');
  await delay(1200);
  await click('[data-view="settings"]');
  await check("语音设置读取真实桥接设备并转义名称", 'calls.some(x=>x.command==="audio_devices") && document.getElementById("audio-input").options[1].textContent==="麦克风 <USB>" && !document.querySelector("#audio-input USB")');
  await check("未选择输出设备不能试听", 'document.querySelector("[data-action=test-output]").disabled');
  await evaluate('voice.outputDevice="speaker"');
  await delay(1200);
  await click('[data-action="test-output"]');
  await delay(200);
  await check("输出测试完成保留可听人工确认", 'calls.some(x=>x.command==="audio_test_output") && document.querySelector("[role=dialog]").textContent.includes("是否听到了测试音") && document.querySelector("[role=dialog]").textContent.includes("不代表设备可听")');
  await click('[data-action="dialog-close"]');
  await evaluate('outputTestError="AUDIO_OUTPUT_TEST_FAILED"');
  await click('[data-action="test-output"]');
  await delay(200);
  await check("输出测试失败提示且允许重试", 'document.body.innerText.includes("输出测试未完成") && !document.querySelector("[data-action=test-output]").disabled');
  await evaluate('outputTestError=null;voice.outputDevice=null');
  await click('[data-action="test-microphone"]');
  await delay(200);
  await check("麦克风检测显示实际返回电平", 'calls.some(x=>x.command==="audio_test_microphone") && document.getElementById("toast").textContent.includes("峰值 50.0%")');
  await evaluate('globalThis.microphoneTestSilent=true');
  await click('[data-action="test-microphone"]');
  await delay(200);
  await check("静音采样不冒充检测到声音", 'document.getElementById("toast").textContent.includes("未检测到明显声音")');
  await evaluate('globalThis.microphoneTestError="AUDIO_TEST_NO_FRAMES"');
  await click('[data-action="test-microphone"]');
  await delay(200);
  await check("采集失败准确提示并可重试", 'document.body.innerText.includes("未收到麦克风采样") && !document.querySelector("[data-action=test-microphone]").disabled');
  await evaluate('globalThis.microphoneTestError=null;globalThis.microphoneTestSilent=false');
  await shot("microphone-test-1440");
  await command("Emulation.setDeviceMetricsOverride", { width: 600, height: 900, deviceScaleFactor: 1, mobile: false });
  await check("麦克风检测在600宽度无溢出", 'document.documentElement.scrollWidth<=innerWidth && !!document.querySelector("[data-action=test-microphone]")');
  await shot("microphone-test-600");
  await command("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await evaluate('(()=>{const select=document.getElementById("audio-input");select.value="mic";select.dispatchEvent(new Event("change",{bubbles:true}));})()');
  await delay(200);
  await check("输入设备通过稳定身份切换后更新选择", 'calls.some(x=>x.command==="audio_select" && x.p.deviceId==="mic" && !x.p.output) && document.getElementById("audio-input").value==="mic"');
  await evaluate('inputDevices=[{index:0,id:"other",name:"另一设备"},{index:4,id:"mic",name:"麦克风 <USB>"}]');
  await click('[data-action="refresh-audio"]');
  await check("设备重排不把选择指向新序号占用者", 'document.getElementById("audio-input").value==="mic" && document.getElementById("audio-input").selectedOptions[0].textContent==="麦克风 <USB>"');
  await evaluate('inputDevices=[{index:0,id:"other",name:"另一设备"}];voice.inputFailureCode="AUDIO_INPUT_DEVICE_REMOVED"');
  await click('[data-action="refresh-audio"]');
  await check("设备拔出显示不可用且语音与网络状态独立", 'document.getElementById("audio-input").value==="" && document.body.innerText.includes("输入设备已不可用") && voice.state==="CONNECTED"');
  await evaluate('inputDevices=[{index:6,id:"mic",name:"麦克风 <USB>"}];voice.inputFailureCode=null');
  await click('[data-action="refresh-audio"]');
  await check("设备重新出现仍按原身份恢复选择", 'document.getElementById("audio-input").value==="mic"');
  await evaluate('(()=>{const select=document.getElementById("audio-output");select.value="speaker";select.dispatchEvent(new Event("change",{bubbles:true}));})()');
  await delay(200);
  await check("输出切换失败保留原状态并显示错误", 'document.getElementById("audio-output").value==="" && document.body.innerText.includes("输出设备切换失败")');
  await evaluate('voice.state="FAILED"');
  await delay(1200);
  await click('[data-action="refresh-audio"]');
  await check("断开后设备选择禁用且清除旧列表", 'document.getElementById("audio-input").disabled && document.getElementById("audio-input").options.length===1');
  await check("语音断开时禁止检测旧设备", 'document.querySelector("[data-action=test-microphone]").disabled');
  await click('[data-action="open-room"]');
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
  await click('[data-tab="network"]');
  await evaluate('globalThis.holdNetwork=true;globalThis.beforeRetries=calls.length');
  await click('[data-action="retry-network"]');
  await click('[data-action="retry-network"]');
  await click('[data-action="retry-voice"]');
  await check('联机重试等待期间仍可重试语音，重复联机请求合并', 'typeof resumeNetwork==="function" && calls.slice(beforeRetries).filter(call=>call.command==="network_connect").length===1 && calls.slice(beforeRetries).some(call=>call.command==="voice_connect" && call.p.leaseId==="lease-room")');
  await evaluate('activity=null');
  await delay(1300);
  await check("活动资格撤销自动返回大厅", '!!document.querySelector(".room-card") && !document.querySelector(".voice-dock")');
  await evaluate('globalThis.afterRevocation=calls.length;globalThis.holdNetwork=false;resumeNetwork()');
  await delay(100);
  await check('旧联机结果不重新读状态或复活房间', '!calls.slice(afterRevocation).some(call=>call.command==="network_snapshot") && !document.querySelector(".voice-dock")');
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
  await check("主动退出返回登录且不误报会话失效", '!!document.querySelector(".auth-card") && document.querySelector("#feedback").textContent===""');
  await check("本地退出后显示尚未确认的清理提示", 'document.getElementById("toast").textContent.includes("已退出本机账号") && document.getElementById("toast").textContent.includes("尚未确认")');
  await click('[data-action="auth-register"]');
  await fill('[name="email"]', "user@example.test");
  await fill('[name="password"]', "test-password-123");
  await evaluate('document.querySelector("[data-form=auth]").requestSubmit()');
  await delay(200);
  await check(
    "邮件未接入不显示假验证码或成功",
    'document.getElementById("feedback").textContent.includes("邮件服务尚未接入") && !document.body.innerText.includes("000000")',
  );
  await click('[data-action="auth-login"]');
  await fill('[name="password"]', "test-password-123");
  await evaluate('document.querySelector("[data-form=auth]").requestSubmit()');
  await delay(300);
  await evaluate('globalThis.failedStateReads=0;globalThis.failStateRead=true');
  await delay(1300);
  await check("暂时无法读取会话不等于登录失效", 'failedStateReads>0 && !!document.querySelector(".rail") && !document.querySelector(".auth-card")');
  await evaluate('globalThis.failStateRead=false;sessionExpired=true');
  await delay(1300);
  await check("会话失效时告知原因且不保留密码", 'document.querySelector("#feedback")?.textContent.includes("登录状态已失效，请重新登录") && !document.querySelector(".rail") && document.querySelector("[name=password]").value===""');
  await evaluate('sessionExpired=false');
  await fill('[name="password"]', "test-password-123");
  await evaluate('document.querySelector("[data-form=auth]").requestSubmit()');
  await delay(300);
  await check("失效后重新登录恢复大厅并清除旧提示", '!!document.querySelector(".rail") && !document.body.innerText.includes("登录状态已失效")');
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
  await browserClosed;
  await rm(profile, { recursive: true, force: true, maxRetries: 8, retryDelay: 250 });
}
