import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { memberTestLink } from './member-test-link.mjs';
import { browserExecutable } from './browser-executable.mjs';

const { port, password, scenario = 'social' } = JSON.parse(readFileSync(0, 'utf8'));
assert.ok(Number.isInteger(port) && port > 0 && port <= 65535);
const origin = `http://127.0.0.1:${port}`;
const executable = process.argv[3];
assert.ok(executable && existsSync(executable), 'prepared Shell test binary required');
const clients = new Map();
const chrome = browserExecutable();
const profile = mkdtempSync(join(process.argv[2], 'profile-'));
const browser = spawn(chrome, ['--headless=new', '--no-first-run', '--disable-background-networking',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { windowsHide: true, stdio: 'ignore' });
const browserClosed = new Promise(resolve => browser.once('close', resolve));
let socket, nextId = 0;
const pending = new Map();
async function until(name, probe, timeout = 10000) {
  const deadline = Date.now() + timeout;
  do { if (await probe()) return; await delay(50); } while (Date.now() < deadline);
  throw Error(`deadline: ${name}`);
}
function command(method, params = {}, sessionId) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(Error(`deadline: ${method}`)); }, 8000);
    pending.set(id, message => {
      clearTimeout(timer);
      if (message.error) reject(Error(`CDP failed: ${method}`)); else resolve(message.result);
    });
    socket.send(JSON.stringify({ id, method, params, sessionId }));
  });
}
function installProbeBridge() {
  const replies = new Map();
  let id = 0, listener;
  globalThis.deliverProbe = message => {
    if (message.changed) { listener?.({ payload: message.changed }); return; }
    const reply = replies.get(message.id);
    if (!reply) return;
    replies.delete(message.id);
    if (message.error) reply.reject(message.error); else reply.resolve(message.value);
  };
  globalThis.__TAURI__ = {
    core: { invoke(command, args = {}) { return new Promise((resolve, reject) => {
      replies.set(++id, { resolve, reject }); globalThis.invokeProbe(JSON.stringify({ id, command, args }));
    }); } },
    event: { listen(name, callback) { if (name === 'product-changed') listener = callback; } },
  };
}
async function page(email, clientPort = port) {
  const { browserContextId } = await command('Target.createBrowserContext');
  const { targetId } = await command('Target.createTarget', { url: 'about:blank', browserContextId });
  const { sessionId } = await command('Target.attachToTarget', { targetId, flatten: true });
  const call = (method, params) => command(method, params, sessionId);
  const helperSocket = process.platform === 'win32' ? `\\\\.\\pipe\\zhijie-test-${randomUUID()}` : join(process.argv[2], 'absent-helper.sock');
  const client = spawn(executable, ['--exact', 'sync::browser_probe::product_page_client', '--ignored', '--nocapture'],
    { windowsHide: true, stdio: ['pipe', 'pipe', 'inherit'], env: { ...process.env, ZHIJIE_HELPER_SOCKET: helperSocket, ZHIJIE_TEST_GROUP_PORT: String(clientPort) } });
  const contexts = new Map();
  let requestId = 0;
  clients.set(sessionId, { client, send({ executionContextId, payload }) {
    const request = JSON.parse(payload);
    contexts.set(++requestId, { contextId: executionContextId, id: request.id });
    client.stdin.write(JSON.stringify({ ...request, id: requestId }) + '\n');
  } });
  createInterface({ input: client.stdout }).on('line', line => {
    if (line.startsWith('BROWSER_PROBE ')) {
      const message = JSON.parse(line.slice('BROWSER_PROBE '.length));
      const context = contexts.get(message.id);
      if (!message.changed && !context) throw Error('Unknown native response');
      if (context) { contexts.delete(message.id); message.id = context.id; }
      void call('Runtime.evaluate', { ...(context && { contextId: context.contextId }),
        expression: `globalThis.deliverProbe?.(${JSON.stringify(message)})` }).catch(() => {});
    }
  });
  await call('Runtime.enable');
  await call('Runtime.addBinding', { name: 'invokeProbe' });
  await call('Page.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', { source: `if(location.origin===${JSON.stringify(origin)}) (${installProbeBridge})()` });
  await call('Page.navigate', { url: `${origin}/index.html` });
  async function evaluate(expression) {
    const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw Error('page action failed');
    return result.result.value;
  }
  const wait = (label, expression) => until(`${email}: ${label}`, () => evaluate(expression));
  const select = css => `document.querySelector(${JSON.stringify(css)})`;
  async function click(css) {
    await wait(css, `${select(css)} && !${select(css)}.disabled`);
    await evaluate(`${select(css)}.click()`);
  }
  const action = name => click(`[data-action="${name}"]`);
  const submit = () => click('[data-form=dialog] .modal-actions .primary');
  async function login() {
    await wait('login', select('[data-form=auth]') + ' !== null');
    await evaluate(`document.querySelector('[name=email]').value=${JSON.stringify(email)};document.querySelector('[name=password]').value=${JSON.stringify(password)};document.querySelector('[data-form=auth]').requestSubmit()`);
    await click('[data-view=groups]');
    await wait('group list loaded', select('.group-list-toolbar') + ' !== null');
  }
  await login();
  return { action, click, wait, submit, evaluate, async relogin() { await call('Page.reload'); await login(); } };
}
async function friendLifecycle(owner, member, link) {
  const inboxEmpty = `document.querySelector('[role=dialog]')?.textContent.includes('暂无好友申请')`;
  const online = email => `document.querySelector('.friend-row strong')?.textContent===${JSON.stringify(email)} && document.querySelector('.friend-row small')?.textContent==='在线'`;
  async function send() {
    await owner.action('add-friend');
    await owner.evaluate(`document.querySelector('[role=dialog] [name=query]').value='member@example.test'`);
    await owner.submit();
    await owner.wait('friend request sent', `!document.querySelector('[role=dialog]') && document.querySelector('#toast').textContent.includes('好友申请已发送')`);
    await member.wait('live request from expected account', `document.querySelector('.friend-request-row strong')?.textContent==='owner@example.test'`);
  }
  await Promise.all([owner.click('[data-view=friends]'), member.click('[data-view=friends]')]);
  await member.action('friend-requests'); await member.wait('initial empty inbox', inboxEmpty);
  await send(); await member.action('reject-friend-request'); await member.wait('rejected request removed', inboxEmpty);
  await send(); await member.action('accept-friend-request'); await member.wait('accepted request removed', inboxEmpty);
  await member.action('dialog-close');
  await until('both friends online after native heartbeats', async () =>
    await owner.evaluate(online('member@example.test')) && await member.evaluate(online('owner@example.test')), 25000);
  assert.deepEqual(await owner.evaluate(`Array.from(document.querySelectorAll('.friend-row button, .friend-row a')).map(node => node.dataset.action)`), ['delete-friend']);
  const disconnectedAt = performance.now();
  link.disconnect();
  await until('friend appears offline without refresh or deletion', () => owner.evaluate(`document.querySelector('.friend-row strong')?.textContent==='member@example.test' && document.querySelector('.friend-row small')?.textContent==='离线'`), 75000);
  const offlineMs = Math.round(performance.now() - disconnectedAt);
  const restoredAt = performance.now();
  link.restore();
  await until('friend returns online without resending request', () => owner.evaluate(online('member@example.test')), 25000);
  console.log(`FRIEND_PRESENCE_OFFLINE_ONLINE offlineMs=${offlineMs} recoveredMs=${Math.round(performance.now() - restoredAt)}`);
  await member.click('[data-view=groups]');
  await member.wait('friendship does not expose private group', `document.querySelector('.group-grid')?.textContent.includes('暂无群组') && !document.querySelector('[data-action=open-group]')`);
  await member.click('[data-view=friends]');
  await member.wait('friend still present before remote deletion', online('owner@example.test'));
  await owner.action('delete-friend'); await owner.submit();
  const empty = `document.querySelector('.friend-list')?.textContent.includes('暂无好友') && !document.querySelector('.friend-row')`;
  await Promise.all([owner.wait('deleted locally', empty), member.wait('remote deletion synchronized', empty)]);
  console.log('FRIEND_REQUEST_REJECT_ACCEPT_ONLINE_DELETE_SYNCED');
  await Promise.all([owner.click('[data-view=groups]'), member.click('[data-view=groups]')]);
}
async function ownershipLifecycle(owner, member) {
  const empty = `document.querySelector('[role=dialog]')?.textContent.includes('暂无待处理转让')`;
  const ownRole = role => `Array.from(document.querySelectorAll('.group-member-row p')).some(node=>node.textContent===${JSON.stringify(role + ' · 你')})`;
  async function transfers(client) {
    await client.action('manage-group'); await client.action('owner-transfers');
  }
  async function offer(client) {
    await client.action('group-members'); await client.action('start-owner-transfer'); await client.submit();
    await client.wait('sender can revoke pending transfer', `!!document.querySelector('[data-action=cancel-owner-transfer]')`);
  }
  await Promise.all([transfers(owner), transfers(member)]);
  await member.wait('recipient watching empty transfer list', empty);
  await offer(owner);
  await member.action('accept-owner-transfer');
  await owner.action('cancel-owner-transfer'); await owner.submit(); await owner.wait('revoked transfer removed', empty);
  await member.submit(); await member.wait('revoked transfer cannot be accepted', `document.querySelector('#dialog-error')?.textContent.includes('此转让已结束或不可用')`);
  await member.action('dialog-close'); await transfers(member); await member.wait('recipient sees no pending transfer', empty);
  console.log('REVOKED_OWNER_TRANSFER_CONFIRMATION_REJECTED');
  await offer(owner); await member.action('accept-owner-transfer');
  await owner.action('group-members'); await owner.wait('pending sender remains owner', ownRole('群主'));
  await owner.action('change-member-role');
  await member.submit(); await member.wait('accepted transfer removed', empty);
  await owner.submit(); await owner.wait('former owner cannot use stale role confirmation', `document.querySelector('#dialog-error')?.textContent.includes('当前没有执行此操作的权限')`);
  await owner.action('dialog-close'); await transfers(owner); await owner.action('group-members');
  await owner.wait('former owner has no governance actions', `${ownRole('成员')} && !document.querySelector('[data-action=start-owner-transfer], [data-action=change-member-role]')`);
  await member.action('group-members'); await member.wait('recipient is owner', ownRole('群主'));
  console.log('NEW_OWNER_CONFIRMED_FORMER_OWNER_WRITE_DENIED');
  await owner.action('dialog-close'); await transfers(owner); await owner.wait('former owner watching for return', empty);
  await member.action('start-owner-transfer'); await member.submit();
  await member.wait('new owner can revoke return transfer', `!!document.querySelector('[data-action=cancel-owner-transfer]')`);
  await owner.action('accept-owner-transfer'); await owner.submit(); await owner.wait('return transfer accepted', empty);
  await member.wait('return acceptance synchronized', empty);
  await Promise.all([owner.action('dialog-close'), member.action('dialog-close')]);
}
async function groupProfile(client, phase) {
  const card = `document.querySelector('.group-card')`;
  const picture = `${card}?.querySelector('.group-avatar-image')`;
  async function edit(name, description, color) {
    await client.evaluate(`(async () => {
      const form = document.querySelector('[data-form=dialog]');
      form.elements.name.value=${JSON.stringify(name)}; form.elements.description.value=${JSON.stringify(description)};
      const canvas = document.createElement('canvas'); canvas.width=canvas.height=4;
      const ctx=canvas.getContext('2d'); ctx.fillStyle=${JSON.stringify(color)}; ctx.fillRect(0,0,4,4);
      const transfer=new DataTransfer(); transfer.items.add(new File([await new Promise(done=>canvas.toBlob(done))], 'avatar.png', {type:'image/png'}));
      form.elements.avatar.files=transfer.files;
    })()`);
    await client.submit();
    await client.wait('profile saved', `!document.querySelector('[role=dialog]')`);
  }
  async function expectProfile(name, description, red) {
    await client.wait('saved group name, description and decoded thumbnail', `${card}?.querySelector('h3').textContent===${JSON.stringify(name)} && ${card}?.querySelector('p').textContent===${JSON.stringify(description)} && ${picture}?.naturalWidth===64`);
    assert.deepEqual(await client.evaluate(`(() => {const ctx=document.createElement('canvas').getContext('2d');ctx.drawImage(${picture},0,0);return [...ctx.getImageData(5,5,1,1).data]})()`), [red, 0, 0, 255]);
  }
  if (phase === 'profile-write') {
    await client.action('create-group'); await edit('初始私密小队', '创建时简介', '#ff0000');
    await expectProfile('初始私密小队', '创建时简介', 255);
    await client.action('open-group');
    await client.wait('owner context', `document.querySelector('.right-panel .friend-copy small')?.textContent==='群主'`);
    await client.action('manage-group'); await edit('重启验证小队', '修改后的群简介', '#800000');
    await client.wait('updated group rendered after save', `document.querySelector('.group-chat-head h1')?.textContent==='重启验证小队'`);
    await client.click('[data-view=groups]');
  }
  await expectProfile('重启验证小队', '修改后的群简介', 128);
  console.log(phase === 'profile-write' ? 'GROUP_PROFILE_CREATED_AND_CHANGED' : 'GROUP_PROFILE_READ_AFTER_SERVICE_RESTART');
}

let memberLink;
try {
  if (scenario === 'social') memberLink = await memberTestLink(port);
  await until('browser ready', () => existsSync(join(profile, 'DevToolsActivePort')));
  const [debugPort, path] = readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').trim().split(/\r?\n/);
  socket = new WebSocket(`ws://127.0.0.1:${debugPort}${path}`);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  socket.onmessage = ({ data }) => { const message = JSON.parse(data);
    if (message.method === 'Runtime.bindingCalled' && message.params.name === 'invokeProbe') {
      clients.get(message.sessionId).send(message.params);
    } else { pending.get(message.id)?.(message); pending.delete(message.id); }
  };
  if (scenario === 'social') await socialScenario();
  else if (scenario === 'history-delete') await historyDelete();
  else if (scenario === 'room-channels') await roomChannels();
  else {
    assert.ok(['profile-write', 'profile-read'].includes(scenario));
    await groupProfile(await page('owner@example.test'), scenario);
  }
} finally {
  for (const { client } of clients.values()) client.stdin.end();
  await memberLink?.close();
  if (socket?.readyState === WebSocket.OPEN) {
    await command('Browser.close').catch(() => {});
    socket.close();
    await Promise.race([browserClosed, delay(3000, undefined, { ref: false })]);
  }
  if (browser.exitCode === null && browser.signalCode === null) browser.kill();
  await browserClosed;
  await rm(profile, { recursive: true, force: true, maxRetries: 8, retryDelay: 250 });
}

async function historyDelete() {
  const owner = await page('owner@example.test'), member = await page('member@example.test');
  const entries = `Array.from(document.querySelectorAll('[data-message-history] article'))`;
  const entry = text => `${entries}.find(row=>row.querySelector('.message-text').textContent===${JSON.stringify(text)})`;
  const control = text => `${entry(text)}?.querySelector('[data-action=delete-message]')`;
  for (const client of [owner, member]) {
    await client.action('open-group');
    await client.action('message-history');
    await client.wait('exactly three older messages loaded', `${entries}.length===3 && ${entry('群主保留')}`);
  }
  assert.equal(await member.evaluate(`${control('群主保留')}===null`), true);
  async function remove(client, peer, body, count) {
    await client.wait('permitted historical delete available', control(body));
    await client.evaluate(`${control(body)}.click()`);
    await client.wait('delete confirmation shown', `document.querySelector('#dialog-title')?.textContent==='删除消息'`);
    await client.submit();
    const reconciled = `${entries}.length===3 && !!${entry('群主保留')} && !${entry(body)} && ${entries}.filter(row=>row.querySelector('.message-text').textContent==='此消息已被删除' && !row.querySelector('[data-action=delete-message]')).length===${count}`;
    await Promise.all([client.wait('deleter remains on reconciled page', reconciled), peer.wait('open peer history reflects deletion', reconciled)]);
  }
  await remove(member, owner, '成员自删', 1);
  await remove(owner, member, '群主代删', 2);
  console.log('HISTORY_AUTHOR_AND_MODERATOR_DELETIONS_SYNCED');
}

async function roomChannels() {
  const owner = await page('owner@example.test'), member = await page('member@example.test');
  const native = (client, command, args = {}) => client.evaluate(`globalThis.__TAURI__.core.invoke(${JSON.stringify(command)}, ${JSON.stringify(args)})`);
  async function join(client) {
    await client.click('[data-view=lobby]');
    await client.action('join-room');
    await client.submit();
    await client.wait('joined the seeded room', `document.querySelector('.room-heading h1')?.textContent==='通道故障试用房'`);
    return (await native(client, 'state_snapshot')).activity;
  }
  const [ownerActivity, previous] = await Promise.all([join(owner), join(member)]);
  assert.equal(previous.activityId, ownerActivity.activityId);
  assert.notEqual(previous.leaseId, ownerActivity.leaseId);
  for (const client of [owner, member]) {
    await client.wait('both real failures are visible', `document.querySelectorAll('.room-flags [data-connection=failed]').length===2`);
    assert.equal((await native(client, 'voice_snapshot')).failureCode, 'VOICE_CAPACITY_LIMITED');
    assert.equal((await native(client, 'network_snapshot')).status.errorCode, 'HELPER_UNAVAILABLE');
    await client.click('[data-tab=network]');
    await client.wait('helper failure remains explained beside retry', `document.querySelector('.network-panel')?.innerText.includes('联机辅助服务未启动') && !!document.querySelector('.network-panel [data-action=retry-network]') && !document.querySelector('.network-panel .ip-address')`);
    await client.click('[data-tab=chat]');
  }
  await member.evaluate(`document.querySelector('#message-input').value='通道故障时仍可聊天';document.querySelector('[data-form=message]').requestSubmit()`);
  await owner.wait('chat delivered despite both channel failures', `Array.from(document.querySelectorAll('.message-text')).some(node=>node.textContent==='通道故障时仍可聊天')`);
  await owner.click('[data-tab=members]');
  await owner.wait('live member generation visible', `document.querySelector('[data-action=kick-activity-member]')?.dataset.generation===${JSON.stringify(String(previous.memberGeneration))}`);
  await owner.action('kick-activity-member');
  const started = performance.now();
  await owner.submit();
  await member.wait('native revocation closed room', `!!document.querySelector('.room-card') && !document.querySelector('.voice-dock')`);
  const elapsed = Math.round(performance.now() - started);
  assert.ok(elapsed <= 5000, `kick convergence exceeded 5s: ${elapsed}ms`);
  assert.equal((await native(member, 'state_snapshot')).activity, null);
  const current = await join(member);
  assert.equal(current.activityId, previous.activityId);
  assert.ok(current.memberGeneration > previous.memberGeneration && current.leaseId !== previous.leaseId);
  await member.wait('voice denial on new lease', `document.querySelector('.room-flags [data-connection=failed]')`);
  const retry = await member.evaluate(`globalThis.__TAURI__.core.invoke('network_connect',${JSON.stringify({activityId:current.activityId,leaseId:current.leaseId})}).then(()=>null,error=>error)`);
  assert.equal(retry, 'HELPER_UNAVAILABLE');
  const failedNetwork = (await native(member, 'network_snapshot')).status;
  assert.equal(failedNetwork.memberGeneration, current.memberGeneration);
  assert.equal((await native(member, 'voice_snapshot')).failureCode, 'VOICE_CAPACITY_LIMITED');
  const stale = { activityId: previous.activityId, leaseId: previous.leaseId };
  for (const command of ['voice_connect', 'network_connect']) {
    const error = await member.evaluate(`globalThis.__TAURI__.core.invoke(${JSON.stringify(command)},${JSON.stringify(stale)}).then(()=>null,error=>error)`);
    assert.equal(error, 'CURRENT_GRANT_REQUIRED');
  }
  assert.equal((await native(member, 'state_snapshot')).activity.leaseId, current.leaseId);
  await member.action('leave');
  await member.submit();
  await member.wait('voluntary leave released second membership', `!!document.querySelector('.room-card') && !document.querySelector('.voice-dock')`);
  assert.equal((await native(member, 'state_snapshot')).activity, null);
  assert.equal((await native(owner, 'state_snapshot')).activity.leaseId, ownerActivity.leaseId);
  console.log(`ROOM_CHANNEL_FAILURE_CHAT_KICK_REJOIN_LEAVE_OK kick_ms=${elapsed}`);
}

async function socialScenario() {
  const owner = await page('owner@example.test'), member = await page('member@example.test', memberLink.port);
  await friendLifecycle(owner, member, memberLink);
  await owner.action('open-group');
  await owner.wait('group context loaded', `document.querySelector('.right-panel .friend-copy small')?.textContent==='群主'`);
  async function invite() {
    await owner.action('manage-group'); await owner.action('invite-member');
    await owner.evaluate(`document.querySelector('[role=dialog] [name=email]').value='member@example.test'`);
    await owner.submit(); await owner.wait('invitation sent', `!document.querySelector('[role=dialog]') && document.querySelector('#toast').textContent.includes('邀请已发送')`);
  }
  async function accept() {
    await member.action('invitation'); await member.action('accept-invitation'); await member.submit();
    await member.wait('inbox empty', `document.querySelector('[role=dialog]')?.textContent.includes('暂无待接受邀请')`);
    await member.action('dialog-close');
    await member.wait('joined group', `document.querySelector('[data-action=open-group]')?.textContent.includes('双端试用群')`);
  }
  await member.action('invitation');
  await member.wait('empty inbox before first invitation', `document.querySelector('[role=dialog]')?.textContent.includes('暂无待接受邀请')`);
  await invite(); await member.action('accept-invitation'); console.log('OPEN_INBOX_UPDATED_LIVE');
  await owner.action('manage-group'); await owner.action('group-invitations'); await owner.action('revoke-invitation'); await owner.submit();
  await owner.wait('revoked', `document.querySelector('[role=dialog]')?.textContent.includes('暂无待接受邀请')`);
  await member.submit(); await member.wait('rejected stale confirmation', `document.querySelector('#dialog-error')?.textContent.includes('邀请已撤销或失效')`);
  await member.action('dialog-close'); await owner.action('dialog-close'); console.log('STALE_CONFIRMATION_REJECTED');
  await invite(); await accept(); await member.action('open-group');
  await member.wait('member context loaded', `document.querySelectorAll('.right-panel .friend-copy').length===2`);
  const peerPresence = `Array.from(document.querySelectorAll('.right-panel .friend-copy')).find(row=>row.querySelector('strong').textContent!=='我')?.querySelector('.member-presence')?.textContent`;
  await Promise.all([owner.wait('nonfriend member is online', `${peerPresence}==='在线'`), member.wait('nonfriend owner is online', `${peerPresence}==='在线'`)]);
  await member.evaluate(`document.querySelector('#message-input').value='群管理删除验收';document.querySelector('[data-form=message]').requestSubmit()`);
  const moderationMessage = `Array.from(document.querySelectorAll('.message')).find(node=>node.querySelector('.message-text')?.textContent==='群管理删除验收')`;
  await owner.wait('owner can moderate member message', `${moderationMessage}?.querySelector('[data-action=delete-message]')`);
  const deletedId = await owner.evaluate(`${moderationMessage}.querySelector('[data-action=delete-message]').dataset.id`);
  await owner.evaluate(`${moderationMessage}.querySelector('[data-action=delete-message]').click()`);
  await owner.submit();
  const tombstone = `Array.from(document.querySelectorAll('.message-text')).some(node=>node.textContent==='此消息已被删除') && !(${moderationMessage})`;
  await owner.wait('owner sees deletion marker', tombstone);
  await member.wait('author receives moderator deletion', tombstone);
  await owner.action('manage-group'); await owner.action('audit');
  await owner.wait('matching successful moderation audit', `Array.from(document.querySelectorAll('.audit-entry')).some(row=>row.querySelector('strong')?.textContent==='管理删除消息' && row.textContent.includes(${JSON.stringify(deletedId)}) && row.textContent.includes('成功'))`);
  await owner.action('dialog-close');
  console.log('MODERATOR_DELETION_SYNCED_AND_AUDITED');
  await member.evaluate(`const draft=document.querySelector('#message-input');draft.focus();draft.value='未发送的草稿';draft.setSelectionRange(2,3)`);
  await owner.evaluate(`document.querySelector('#message-input').value='来自群主的消息';document.querySelector('[data-form=message]').requestSubmit()`);
  await member.wait('live message preserves draft and selection', `Array.from(document.querySelectorAll('.message-text')).some(node=>node.textContent==='来自群主的消息') && document.activeElement.id==='message-input' && document.activeElement.value==='未发送的草稿' && document.activeElement.selectionStart===2 && document.activeElement.selectionEnd===3`);
  assert.equal(await member.evaluate(`Array.from(document.querySelectorAll('.message')).find(row=>row.querySelector('.message-text')?.textContent==='来自群主的消息')?.querySelector('[data-action=delete-message]') === null`), true);
  console.log('LIVE_CHAT_PRESERVED_DRAFT');
  const handshakes = memberLink.cursors.length;
  const rejectionsBeforeChatLoss = memberLink.rejectedUpgrades;
  const before = memberLink.cursors.at(-1);
  memberLink.disconnect();
  await owner.evaluate(`document.querySelector('#message-input').value='断线期间的消息';document.querySelector('[data-form=message]').requestSubmit()`);
  const offlineMessageCount = `Array.from(document.querySelectorAll('.message-text')).filter(node=>node.textContent==='断线期间的消息').length`;
  await owner.wait('offline message persisted', `${offlineMessageCount}===1`);
  await until('automatic reconnect attempted while offline', () => memberLink.rejectedUpgrades > rejectionsBeforeChatLoss);
  await until('open group shows disconnected nonfriend offline without navigation', () => owner.evaluate(`${peerPresence}==='离线'`), 75000);
  assert.equal(await member.evaluate(offlineMessageCount), 0);
  const restored = performance.now();
  memberLink.restore();
  await member.wait('recovered message and draft without navigation', `${offlineMessageCount}===1 && document.activeElement.id==='message-input' && document.activeElement.value==='未发送的草稿' && document.activeElement.selectionStart===2 && document.activeElement.selectionEnd===3`);
  await until('new member WebSocket established', () => memberLink.cursors.length > handshakes);
  const after = memberLink.cursors[handshakes];
  assert.ok(/^\d+$/.test(before) && /^\d+$/.test(after) && BigInt(after) > BigInt(before), 'reconnect from advanced cursor');
  const recoveryMs = Math.ceil(performance.now() - restored);
  assert.ok(recoveryMs <= 15000, 'local chat recovery within 15 seconds');
  console.log(`OFFLINE_CHAT_RECOVERED elapsedMs=${recoveryMs} rejectedUpgrades=${memberLink.rejectedUpgrades}`);
  await until('open group shows reconnected nonfriend online', () => owner.evaluate(`${peerPresence}==='在线'`), 25000);
  console.log('NONFRIEND_GROUP_PRESENCE_ONLINE_OFFLINE_ONLINE');
  await owner.action('manage-group'); await owner.action('group-members');
  await owner.action('change-member-role'); await owner.submit();
  await owner.wait('promoted', `document.querySelector('[data-action=change-member-role]')?.dataset.role === 'MEMBER'`);
  await owner.action('remove-group-member'); await owner.submit();
  await owner.wait('removed', `document.querySelector('#dialog-title')?.textContent==='群组成员' && !document.querySelector('[data-action=remove-group-member]')`);
  await owner.action('dialog-close'); console.log('FORMER_ADMIN_REMOVED');
  await member.wait('removed member leaves open chat without interaction', `document.querySelector('h1')?.textContent==='玩家大厅' && !document.querySelector('#message-input') && !document.querySelector('.message-text')`);
  console.log('REMOVED_FROM_OPEN_CHAT_LIVE');
  const beforeLogin = memberLink.cursors.length;
  await member.relogin();
  await until('new login has its own sync connection', () => memberLink.cursors.length > beforeLogin);
  assert.equal(await member.evaluate(`document.querySelector('[data-action=open-group]') === null`), true);
  console.log('REMOVED_GROUP_ABSENT_AFTER_LOGIN');
  await invite(); await accept(); await owner.action('manage-group'); await owner.action('group-members');
  await owner.wait('rejoined without admin', `document.querySelector('[data-action=change-member-role]')?.dataset.role === 'ADMIN'`);
  console.log('REJOINED_AS_MEMBER');
  await member.action('open-group');
  await member.wait('rejoined chat open', `document.querySelectorAll('.right-panel .friend-copy').length===2`);
  await owner.action('dialog-close');
  await owner.evaluate(`document.querySelector('#message-input').value='新会话同步确认';document.querySelector('[data-form=message]').requestSubmit()`);
  await member.wait('rejoined session received a live update', `Array.from(document.querySelectorAll('.message-text')).some(node=>node.textContent==='新会话同步确认')`);
  await ownershipLifecycle(owner, member);
  await owner.action('manage-group'); await owner.action('group-members');
  const priorRejections = memberLink.rejectedUpgrades, priorHandshakes = memberLink.cursors.length;
  memberLink.disconnect();
  await owner.action('remove-group-member'); await owner.submit();
  await owner.wait('removed while offline', `document.querySelector('#dialog-title')?.textContent==='群组成员' && !document.querySelector('[data-action=remove-group-member]')`);
  await until('disconnected member attempted reconnect', () => memberLink.rejectedUpgrades > priorRejections);
  const recoveryStart = performance.now(); memberLink.restore();
  await member.wait('offline revocation reconciled', `document.querySelector('h1')?.textContent==='玩家大厅' && !document.querySelector('#message-input') && !document.querySelector('.message-text')`);
  await until('revoked member sync reconnected', () => memberLink.cursors.length > priorHandshakes);
  const revocationMs = Math.ceil(performance.now() - recoveryStart);
  assert.ok(revocationMs <= 15000, 'offline group removal reconciled within 15 seconds');
  console.log(`OFFLINE_GROUP_REMOVAL_RECONCILED elapsedMs=${revocationMs}`);
}
