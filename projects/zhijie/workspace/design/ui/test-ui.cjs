const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { pathToFileURL } = require('node:url');

const root = __dirname;
const outputDir = fs.mkdtempSync(path.join(root, '.ui-test-artifacts-'));
const entry = path.join(outputDir, 'weavora-ui.html');
const build = spawnSync(process.execPath, [path.join(root, 'build-share.cjs')], { encoding: 'utf8', env: { ...process.env, WEAVORA_UI_OUTPUT_DIR: outputDir } });
if (build.status !== 0) throw new Error(build.stderr || 'UI 构建失败');
const regression = spawnSync(process.execPath, [path.join(root, '../prototype/test-prototype.js')], {
  encoding: 'utf8',
  env: { ...process.env, PROTOTYPE_ENTRY: entry, PROTOTYPE_SCREENSHOTS: '0' },
  timeout: 180000,
  maxBuffer: 4 * 1024 * 1024
});
if (regression.status !== 0) throw new Error(regression.stderr || 'UI 交互回归失败');
const baseline = JSON.parse(regression.stdout);
const chrome = ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe', 'C:/Program Files/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(file => fs.existsSync(file));
if (!chrome) throw new Error('未找到 Chrome 或 Edge');
const port = 10500 + Math.floor(Math.random() * 500);
const profile = path.join(root, `.chrome-ui-profile-${process.pid}`);
const pending = new Map();
const checks = [];
const errors = [];
let browser, socket, nextId = 0;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function command(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result.value;
}
async function assert(name, expression) {
  if (!await evaluate(expression)) throw new Error(name);
  checks.push(name);
}
async function screenshot(name) {
  const result = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  fs.writeFileSync(path.join(root, name + '.png'), Buffer.from(result.data, 'base64'));
}
async function main() {
  browser = spawn(chrome, ['--headless=new', '--disable-gpu', '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--no-first-run', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, pathToFileURL(path.join(root, 'index.html')).href], { stdio: 'ignore' });
  let page;
  for (let attempt = 0; attempt < 60; attempt++) {
    try { page = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(item => item.type === 'page'); } catch {}
    if (page) break;
    await delay(100);
  }
  if (!page) throw new Error('无法连接 UI 测试浏览器');
  socket = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (pending.has(message.id)) {
      const handler = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) handler.reject(new Error(message.error.message)); else handler.resolve(message.result);
    }
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text);
  };
  await command('Runtime.enable');
  await command('Page.enable');
  await command('Network.enable');
  await command('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: 0, uploadThroughput: 0 });
  for (let attempt = 0; attempt < 60; attempt++) {
    if (await evaluate('document.readyState === "complete" && !!window.state')) break;
    await delay(100);
  }
  await assert('UI 入口加载独立样式与批准的交互实现', 'document.title.includes("UI") && document.styleSheets.length===2 && !reviewEnabled && !document.querySelector(".review-control")');
  await evaluate(`window.contrast=(foreground,background)=>{
    const luminance=color=>{
      const channels=color.match(/[\\d.]+/g).slice(0,3).map(Number).map(value=>{const normalized=value/255;return normalized<=0.04045?normalized/12.92:((normalized+0.055)/1.055)**2.4});
      return channels[0]*0.2126+channels[1]*0.7152+channels[2]*0.0722;
    };
    const first=luminance(foreground),second=luminance(background);
    return (Math.max(first,second)+0.05)/(Math.min(first,second)+0.05);
  }`);
  await assert('正文与次级文字令牌对比度至少4.5', '(()=>{const style=getComputedStyle(document.body);return ["--text","--muted","--dim"].every(name=>{const probe=document.createElement("span");probe.style.color=style.getPropertyValue(name);document.body.append(probe);const color=getComputedStyle(probe).color;probe.remove();return ["rgb(11,13,15)","rgb(18,23,27)","rgb(26,32,38)"].every(background=>window.contrast(color,background)>=4.5)})})()');
  await assert('主按钮文字对比度至少4.5', '(()=>{const style=getComputedStyle(document.querySelector(".button.primary"));return contrast(style.color,style.backgroundColor)>=4.5})()');
  await assert('操作按钮有键盘焦点环', '(()=>{const button=document.querySelector("[data-action=create-room]");button.focus();const style=getComputedStyle(button);return button.matches(":focus-visible")&&style.outlineStyle==="solid"&&parseFloat(style.outlineWidth)>=2})()');
  await assert('默认好友邀请入口不依赖悬停', 'getComputedStyle(document.querySelector(".friend-item .icon-button")).opacity==="1"');
  await assert('大厅房卡包含所属群与明确加入操作', '[...document.querySelectorAll(".room-card")].every(card=>!!card.querySelector(".room-provenance")&&!!card.querySelector(".availability")&&card.querySelector(".room-footer button").textContent.includes("加入房间"))');
  await assert('大厅内容区仅一个创建主操作', 'document.querySelectorAll(".main-scroll [data-action=create-room]").length===1');
  await evaluate('document.querySelector("[data-action=create-room]").click();document.querySelector("[data-action=confirm-create-room]").click()');
  await assert('名称错误内联关联字段且不清空对话框', 'document.getElementById("new-room-name").getAttribute("aria-invalid")==="true"&&document.activeElement.id==="new-room-name"&&document.querySelector(".inline-feedback").textContent.includes("名称")');
  await screenshot('ui-inline-error');
  await evaluate('document.getElementById("new-room-name").value="朋友的房间";document.getElementById("new-room-name").dispatchEvent(new Event("input",{bubbles:true}))');
  await assert('修改错误字段清除过时错误', '!document.querySelector(".inline-feedback")&&!document.querySelector("[aria-invalid=true]")');
  await evaluate('closeModal();groups[0].owner="阿岚";rooms[0].password="demo-secret";showJoinRoom("weekend");document.getElementById("room-password").value="wrong";document.querySelector("[data-action=confirm-join]").click()');
  await assert('密码错误保留输入且不重复全局播报', 'document.getElementById("room-password").value==="wrong"&&document.getElementById("room-password").getAttribute("aria-invalid")==="true"&&!document.querySelector("#toast .toast")&&!!document.querySelector(".inline-feedback")');
  await evaluate('closeModal();groups[0].owner=me;rooms[0].password=""');
  await evaluate('closeModal();state.view="settings";render();document.querySelector("[data-action=logout]").click()');
  await assert('退出初始焦点在取消按钮而非关闭图标', 'document.activeElement.closest(".modal-actions")!==null&&document.activeElement.dataset.action==="modal-close"');
  await evaluate('closeModal();state.view="lobby";render()');
  for (const width of [1440, 900, 600]) {
    await command('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    for (const view of ['lobby', 'friends', 'groups', 'settings']) {
      await evaluate(`state.view=${JSON.stringify(view)};render()`);
      await assert(`${width}px ${view} 无页面横向溢出`, 'document.documentElement.scrollWidth<=innerWidth');
      if (width === 1440 || width === 600) await screenshot(`ui-${view}-${width}`);
    }
    await evaluate('enterRoom("weekend")');await delay(800);
    await assert(`${width}px 房间联机可复制全部成员地址`, 'state.network==="connected"&&document.querySelectorAll(".copy-button").length===currentRoom().members.length&&[...document.querySelectorAll(".member-ip-row")].every(row=>{const ip=row.querySelector(".ip-address").getBoundingClientRect(),box=row.getBoundingClientRect();return ip.top>=box.top&&ip.bottom<=box.bottom})');
    if (width === 1440 || width === 600) await screenshot(`ui-room-${width}`);
    if(width===1440){
      await evaluate('state.view="lobby";render()');await screenshot('ui-current-session');
      await assert('当前活动区显示两个真实模拟通道状态而非固定成功', 'document.querySelector(".current-session").innerText.includes("语音已连接")&&document.querySelector(".current-session").innerText.includes("虚拟网络已连接")');
      await evaluate('state.view="room";render();window.membersBefore=JSON.stringify(currentRoom().members);currentRoom().members=Array.from({length:16},(_,index)=>({name:index===0?me:"长昵称队友"+index,voice:"connected",ip:"10.66.0."+(index+10)}));state.roomTab="chat";render()');
      await assert('16人卡片可滚动查看且不挤压聊天发送区', 'document.querySelectorAll(".player-tile:not(.empty-player)").length===16&&document.querySelector(".room-stage").scrollHeight>document.querySelector(".room-stage").clientHeight&&document.querySelector(".chat-compose").getBoundingClientRect().bottom<=innerHeight');
      await screenshot('ui-room-capacity');
      await evaluate('currentRoom().members=JSON.parse(window.membersBefore);state.roomTab="network";render()');
    }
    await evaluate('state.network="failed";state.voice="waiting";state.channelErrors.network="NAT 或防火墙阻止直连，不会转由服务器传输。";render()');
    await assert(`${width}px 隧道失败有原因、重试且语音等待`, '!!document.querySelector(".status-chip.error")&&!!document.querySelector("[data-action=network-retry]")&&document.body.innerText.includes("不会转由服务器传输")&&state.voice==="waiting"&&[...document.querySelectorAll(".status-chip")].some(chip=>chip.textContent.includes("等待虚拟网络")&&chip.dataset.connection==="pending")');
    await assert(`${width}px 隧道失败成员卡不虚报连接或说话`, '!document.querySelector(".player-tile.speaking")&&[...document.querySelectorAll(".player-name small")].every(label=>label.textContent.includes("等待虚拟网络"))');
    await evaluate('state.roomTab="members";render()');
    await assert(`${width}px 成员列表同步显示语音等待`, '!document.querySelector(".room-side").innerText.includes("语音已连接")&&document.querySelector(".room-side").innerText.includes("等待虚拟网络")');
    await evaluate('state.roomTab="network";render()');
    if (width === 1440) await screenshot('ui-network-failure');
    await evaluate('retryChannel("network")');await delay(650);
    await assert(`${width}px 隧道恢复后语音自动连接`, 'state.network==="connected"&&state.voice==="connected"');
    await evaluate('state.view="settings";state.settingsTab="account";render();document.querySelector("[data-action=logout]").click()');
    await assert(`${width}px 退出确认保持语义与取消焦点`, 'document.querySelector(".modal").getAttribute("role")==="dialog"&&document.activeElement.dataset.action==="modal-close"&&state.modal.desc.includes("断开语音和联机")');
    if (width === 1440) await screenshot('ui-logout-confirm');
    await evaluate('closeModal();revokeActivity();state.view="lobby";render()');
  }
  await command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await evaluate('enterRoom("weekend");state.roomTab="chat";render()');await delay(800);
  await evaluate('state.toast="";renderToast();mediaModal("room")');
  await evaluate(`(async()=>{const canvas=document.createElement("canvas");canvas.width=480;canvas.height=270;const context=canvas.getContext("2d");context.fillStyle="#12171b";context.fillRect(0,0,480,270);context.fillStyle="#d3f65b";context.fillRect(40,40,400,120);context.fillStyle="#f2f4f1";context.font="24px sans-serif";context.fillText("WEAVORA",40,220);const blob=await new Promise(resolve=>canvas.toBlob(resolve,"image/png"));await selectMediaFile(new File([blob],"战术截图.png",{type:"image/png"}))})()`);
  await evaluate('document.getElementById("media-caption").value="看这条进攻路线";document.getElementById("media-caption").dispatchEvent(new Event("input",{bubbles:true}))');
  await assert('媒体选择后呈现高保真预览与待发送状态', 'state.mediaDraft.status==="selected"&&document.querySelector(".modal.media-modal")&&document.querySelector("#media-preview img")&&!document.querySelector("#media-preview .media-open")&&!document.querySelector("[data-action=confirm-media]").disabled');
  await screenshot('ui-media-selected');
  await evaluate('document.querySelector("[data-action=confirm-media]").click()');
  await assert('媒体处理中防止重复提交且保留取消', 'document.querySelector(".modal").dataset.mediaStatus==="uploading"&&document.getElementById("media-file").disabled&&document.querySelector("[data-action=confirm-media]").getAttribute("aria-busy")==="true"&&!document.querySelector(".modal-actions [data-action=modal-close]").disabled&&!!document.querySelector("progress[aria-label=模拟上传进度]")');
  await delay(1200);
  await assert('已发送图片提供明确查看入口', '!state.modal&&!!document.querySelector(".chat-scroll .media-open")&&document.querySelector(".chat-scroll .media-open").getAttribute("aria-label").includes("战术截图.png")');
  await screenshot('ui-media-message');
  await evaluate('document.querySelector(".chat-scroll .media-open").focus();document.querySelector(".chat-scroll .media-open").click()');
  await assert('图片查看对话框可操作并管理焦点', 'document.querySelector(".modal.media-viewer-modal").getAttribute("role")==="dialog"&&!!document.querySelector(".media-viewer img")&&document.activeElement.dataset.action==="modal-close"');
  await screenshot('ui-media-viewer');
  await evaluate('closeModal()');
  await assert('关闭图片查看后焦点返回原消息', 'document.activeElement.classList.contains("media-open")');
  await evaluate('state.toast="";renderToast();mediaModal("room")');
  await evaluate(`(async()=>{const canvas=document.createElement("canvas");canvas.width=480;canvas.height=270;const context=canvas.getContext("2d");context.fillStyle="#242d35";context.fillRect(0,0,480,270);context.fillStyle="#d3f65b";context.fillRect(40,40,400,120);const blob=await new Promise(resolve=>canvas.toBlob(resolve,"image/png"));await selectMediaFile(new File([blob],"失败保留.png",{type:"image/png"}));document.getElementById("media-caption").value="保留这段附言";document.getElementById("media-caption").dispatchEvent(new Event("input",{bubbles:true}));state.service="offline";startMediaDelivery()})()`);
  await delay(300);
  await assert('媒体失败保留预览附言与重试入口', 'document.querySelector(".media-transfer").dataset.status==="failed"&&!!document.querySelector(".media-error")&&document.getElementById("media-caption").value==="保留这段附言"&&!!document.querySelector("#media-preview img")&&!!document.querySelector("[data-action=retry-media]")');
  await screenshot('ui-media-failure');
  await evaluate('state.service="online";document.querySelector("[data-action=retry-media]").click()');await delay(1200);
  await assert('媒体失败重试成功后才进入消息列表', '!state.modal&&currentRoom().messages.some(message=>message.media?.name==="失败保留.png")');
  await command('Emulation.setDeviceMetricsOverride', { width: 600, height: 900, deviceScaleFactor: 1, mobile: false });
  await evaluate('state.toast="";renderToast();mediaModal("room")');
  await evaluate(`(async()=>{const canvas=document.createElement("canvas");canvas.width=480;canvas.height=270;const context=canvas.getContext("2d");context.fillStyle="#242d35";context.fillRect(0,0,480,270);context.fillStyle="#d3f65b";context.fillRect(40,40,400,120);const blob=await new Promise(resolve=>canvas.toBlob(resolve,"image/png"));await selectMediaFile(new File([blob],"窄窗口.png",{type:"image/png"}))})()`);
  await assert('600px 媒体对话框无横向溢出且操作可达', 'document.documentElement.scrollWidth<=innerWidth&&(()=>{const modal=document.querySelector(".modal").getBoundingClientRect(),actions=document.querySelector(".modal-actions").getBoundingClientRect();return modal.left>=0&&modal.right<=innerWidth&&actions.bottom<=innerHeight})()');
  await screenshot('ui-media-600');
  await evaluate('closeModal();revokeActivity();state.view="lobby";render()');
  await evaluate('state.view="groupChat";state.groupId="expedition";state.peerMode="offline";render();document.getElementById("group-message-input").value="UI待同步";sendTextMessage("group")');
  await assert('600px 无副本提示保持可见，收据标为本机待同步', 'document.documentElement.scrollWidth<=innerWidth&&document.querySelector(".sync-notice").getBoundingClientRect().top>=document.querySelector(".group-chat-head").getBoundingClientRect().bottom&&!!document.querySelector(".message-receipt[data-delivery=pending]")&&document.querySelector(".sync-notice").innerText.includes("暂不可用")');
  await screenshot('ui-peer-offline-600');
  await evaluate('state.peerMode="partial";syncPeerMessages("group")');
  await assert('部分送达保留其他成员待同步说明', '!!document.querySelector(".message-receipt[data-delivery=partial]")&&document.querySelector(".group-chat-body").innerText.includes("其他成员待同步")');
  await screenshot('ui-peer-partial-600');
  await evaluate('state.peerMode="online";syncPeerMessages("group")');
  await assert('未入房可同步群内容且保留模拟标识', '!state.joined&&!!document.querySelector(".message-receipt[data-delivery=received]")&&document.querySelector(".group-chat-body").innerText.includes("对端已接收（模拟）")');
  await evaluate('delete state.peerMode');
  await command('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await evaluate('state.view="settings";render();document.querySelector("[data-action=logout]").click()');
  await assert('减少动态模式关闭弹框动画', 'getComputedStyle(document.querySelector(".modal")).animationName==="none"');
  await evaluate('closeModal();state.authenticated=false;render()');
  await assert('登录页保留模拟凭据安全边界', 'document.body.innerText.includes("请勿输入真实凭据")&&document.documentElement.scrollWidth<=innerWidth');
  await screenshot('ui-auth-600');
  await command('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
  await command('Page.navigate',{url:pathToFileURL(path.join(root,'system.html')).href});await delay(300);
  await assert('组件工作台可离线打开并复用产品令牌', '!!document.getElementById("form-demo")&&getComputedStyle(document.body).getPropertyValue("--accent").trim()==="#d3f65b"');
  await evaluate('document.getElementById("pending-action").click()');
  await assert('组件提交中禁用并播报忙碌状态', 'document.getElementById("pending-action").disabled&&document.getElementById("pending-action").getAttribute("aria-busy")==="true"');
  await delay(1000);
  await assert('组件完成后恢复可操作', '!document.getElementById("pending-action").disabled&&document.getElementById("action-result").textContent.includes("完成")');
  await evaluate('document.getElementById("retry-demo").click()');
  await assert('组件重试过程等待共享隧道', 'document.getElementById("network-state").textContent.includes("重试中")&&document.getElementById("voice-state").textContent.includes("等待虚拟网络")&&document.getElementById("network-reason").textContent.includes("无服务器回退")');
  await delay(1000);
  await assert('组件隧道就绪后连接语音', 'document.getElementById("voice-state").textContent.includes("已连接")&&document.getElementById("network-state").textContent.includes("已连接")');
  await assert('组件无副本不冒充对端送达', 'document.getElementById("replica-notice").textContent.includes("暂不可用")&&document.getElementById("replica-receipt").textContent.includes("待同步")');
  await evaluate('document.getElementById("replica-mode").value="partial";document.getElementById("replica-mode").dispatchEvent(new Event("change"))');
  await assert('组件部分送达有文字状态', 'document.getElementById("replica-receipt").dataset.delivery==="partial"&&document.getElementById("replica-receipt").textContent.includes("其他成员待同步")');
  await evaluate('document.getElementById("replica-mode").value="online";document.getElementById("replica-mode").dispatchEvent(new Event("change"))');
  await assert('组件对端接收说明校验落盘判据', 'document.getElementById("replica-receipt").textContent.includes("校验落盘确认")');
  await evaluate('document.getElementById("form-demo").requestSubmit()');
  await assert('组件表单空值有错误与关联', 'document.getElementById("room-name-demo").getAttribute("aria-invalid")==="true"&&!document.getElementById("field-feedback").hidden');
  await evaluate('document.getElementById("room-name-demo").value="今晚联机";document.getElementById("room-name-demo").dispatchEvent(new Event("input",{bubbles:true}));document.getElementById("form-demo").requestSubmit()');
  await assert('组件表单修正可完成', '!document.querySelector("[aria-invalid=true]")&&document.getElementById("form-result").textContent.includes("通过")');
  await evaluate('scrollTo(0,0)');await screenshot('ui-design-system');
  await command('Emulation.setDeviceMetricsOverride',{width:600,height:900,deviceScaleFactor:1,mobile:false});
  await assert('组件工作台窄屏无横向溢出', 'document.documentElement.scrollWidth<=innerWidth');
  await command('Page.navigate',{url:pathToFileURL(path.join(path.dirname(entry),'weavora-design-system.html')).href});await delay(300);
  await assert('设计系统分享版完全内联且可离线校验', 'document.querySelectorAll("style").length===1&&!document.querySelector("script[src],link[rel=stylesheet]")&&!!document.getElementById("form-demo")');
  await evaluate('document.getElementById("form-demo").requestSubmit()');
  await assert('设计系统分享版脚本可实际运行', 'document.getElementById("room-name-demo").getAttribute("aria-invalid")==="true"');
  if (errors.length) throw new Error(errors.join('; '));
  console.log(JSON.stringify({ ok: true, regression: baseline.checks.length, uiChecks: checks.length, total: baseline.checks.length + checks.length, checks, consoleErrors: errors }, null, 2));
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; }).finally(async () => {
  if (socket) socket.close();
  if (browser) {
    if (process.platform === 'win32') await new Promise(resolve => {
      const killer = spawn('taskkill', ['/pid', String(browser.pid), '/T', '/F'], { stdio: 'ignore' });
      killer.on('exit', resolve);
      killer.on('error', () => { browser.kill(); resolve(); });
    }); else browser.kill();
  }
  await delay(500);
  if (path.dirname(path.resolve(profile)) !== path.resolve(root) || !path.basename(profile).startsWith('.chrome-ui-profile-')) throw new Error('Unsafe profile path');
  fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  if (path.dirname(path.resolve(outputDir)) !== path.resolve(root) || !path.basename(outputDir).startsWith('.ui-test-artifacts-')) throw new Error('Unsafe output path');
  fs.rmSync(outputDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
});
