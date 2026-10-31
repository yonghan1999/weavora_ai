const reviewEnabled = new URLSearchParams(location.search).has('review');
let recoveryTimer;
const channelTimers = {};
const roleNames = { owner: '群主', admin: '管理员', member: '普通成员' };

function initializePrototype() {
  Object.assign(state, { epoch: 0, channelErrors: {}, outcomes: {}, recovery: null, editRoomId: null, activeDevice: '此电脑', reviewRoles: {}, lastIps: {}, service: 'online' });
  state.mediaDraft = null;
  state.mediaReviewOutcome = '';
  groups.forEach(group => Object.assign(group, { owner: me, roles: {}, audit: [], ...group }));
  rooms.forEach(room => { room.password = ''; room.desc ||= '好友组队'; });
  rooms.find(room => room.id === 'racing').members = rooms.find(room => room.id === 'racing').members.filter(member => member.name !== '北屿');
  rooms.find(room => room.id === 'weekend').members = rooms.find(room => room.id === 'weekend').members.filter(member => member.name !== '青禾');
  people['洛川'].online = true;
  groups.push({ id: 'incoming', name: '周五联机小队', icon: '五', owner: '青禾', members: ['青禾','洛川'], roles: {}, roomIds: [], messages: [], audit: [] });
  state.invites.push({ id: 'invite-incoming', groupId: 'incoming', name: me, inviter: '青禾', status: 'pending' });
  document.addEventListener('click', handleDomainAction, true);
  if (reviewEnabled) {
    const control = document.createElement('button');
    control.className = 'review-control button small';
    control.dataset.action = 'review-scenarios';
    control.textContent = '原型复核工具';
    document.body.append(control);
  }
}

function groupRole(groupId, name = me) {
  const group = groups.find(item => item.id === groupId);
  if (!group?.members.includes(name)) return null;
  if (name === me && reviewEnabled && state.reviewRoles[groupId]) return state.reviewRoles[groupId];
  return group.owner === name ? 'owner' : group.roles[name] || 'member';
}

function isGroupMember(groupId, name = me) {
  return !!groupRole(groupId, name);
}

function canManageRoom(room) {
  return !!room && isGroupMember(room.groupId) && (canModerate(room.groupId) || room.host === me);
}

function appendAudit(groupId, action, target, actor = me) {
  const group = groups.find(item => item.id === groupId);
  if (group) group.audit.push({ actor, target, action, time: new Date().toLocaleString('zh-CN'), result: '成功' });
}

function focusModal() {
  const modal = document.querySelector('.modal');
  const target = modal?.querySelector('input:not([type=hidden]),select,textarea') || modal?.querySelector('[data-action=modal-close]');
  (target || modal)?.focus();
}

function labelFields() {
  document.querySelectorAll('.modal .field').forEach((field, index) => {
    const input = field.querySelector('input,select,textarea'), label = field.querySelector('label');
    if (!input || !label) return;
    input.id ||= 'modal-field-' + index;
    label.htmlFor = input.id;
  });
}

function handleModalKey(event) {
  if (!state.modal) return;
  if (event.key === 'Escape') { event.preventDefault(); closeModal(); return; }
  if (event.key !== 'Tab') return;
  const focusable = [...document.querySelectorAll('.modal button:not(:disabled),.modal input:not(:disabled),.modal select:not(:disabled),.modal textarea:not(:disabled),.modal a[href]')].filter(element => element.getClientRects().length);
  const first = focusable[0], last = focusable.at(-1);
  if (!first) { event.preventDefault(); document.querySelector('.modal')?.focus(); }
  else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}

function channelLabel(type, value) {
  const prefix = type === 'voice' ? '语音' : '虚拟网络';
  return prefix + ({ connected: '已连接', waiting: '等待虚拟网络', connecting: '连接中', retrying: '重试中', failed: '连接失败', disconnected: '已断开', limited: '受限' }[value] || '未连接');
}

function renderChannelStatus(type, value) {
  const retry = ['failed', 'disconnected', 'limited'].includes(value) && state.joined;
  return `<span class="status-chip ${value === 'connected' ? 'good' : value === 'failed' ? 'error' : ''}" title="${esc(state.channelErrors?.[type] || '')}">${value === 'connected' ? '<i class="status-dot"></i>' : ''}${channelLabel(type, value)}${retry ? `<button class="text-button" data-action="${type}-retry">重试</button>` : ''}</span>`;
}

function joinError(room, password, checkPassword = true) {
  if (!state.authenticated || state.verified===false) return '请先登录并验证邮箱。';
  if (state.service==='offline') return '服务不可达，请恢复网络后重试。';
  if (!room || !rooms.includes(room)) return '房间已删除。';
  if (!isGroupMember(room.groupId)) return '请先接受群组邀请，再加入本群房间。';
  if (!room.open) return '房间已暂停加入，请联系房主。';
  if (room.members.filter(member => member.name !== me).length >= 16) return '房间已满（16 人），请稍后再试。';
  if (checkPassword && room.password && !canModerate(room.groupId) && password !== room.password) return '房间密码错误，请重试。';
  return '';
}

function showJoinRoom(id) {
  const room = rooms.find(item => item.id === id);
  const error = joinError(room, '', false);
  if (error) { toast(error); return; }
  const passwordNeeded = room.password && !canModerate(room.groupId);
  openModal(`加入「${esc(room.name)}」`, state.joined ? '切换后将离开当前房间，并连接新房间的语音和联机。' : '', `${passwordNeeded ? '<div class="field"><label for="room-password">房间密码</label><input id="room-password" type="password" autocomplete="off"></div>' : ''}`, `<button class="button" data-action="modal-close">取消</button><button class="button primary" data-action="confirm-join" data-room="${room.id}">${state.joined ? '切换房间' : '加入并连接'}</button>`);
}

function revokeActivity() {
  if (state.mediaDraft?.scope === 'room') discardMediaDraft();
  clearTimeout(recoveryTimer);
  Object.values(channelTimers).forEach(clearTimeout);
  state.epoch++;
  const room = currentRoom();
  if (room) {
    const member = myMember(room);
    if (member) state.lastIps[room.id] = member.ip;
    room.members = room.members.filter(member => member.name !== me);
  }
  Object.assign(state, { roomId: null, sessionStatus: 'idle', voice: 'disconnected', network: 'disconnected', speaking: false, recovery: null, joinedAt: null, view: 'lobby', channelErrors: {} });
}

function enterRoom(id, password = '') {
  const room = rooms.find(item => item.id === id);
  if (state.joined && state.roomId === id) { closeModal(); state.view = 'room'; render(); return true; }
  const error = joinError(room, password);
  if (error) { toast(error); return false; }
  revokeActivity();
  let address = state.lastIps[id];
  if (!address || room.members.some(member => member.ip === address)) {
    let host = 10;
    const subnet = rooms.indexOf(room);
    while (room.members.some(member => member.ip === `10.66.${subnet}.${host}`)) host++;
    address = `10.66.${subnet}.${host}`;
  }
  room.members.push({ name: me, ip: address, voice: 'connecting', muted: state.muted });
  Object.assign(state, { roomId: id, sessionStatus: 'joining', voice: 'waiting', network: 'connecting', joinedAt: Date.now(), view: 'room', roomTab: 'network', activeDevice: '此电脑' });
  closeModal(); render();
  retryChannel('network', true);
  return true;
}

function retryChannel(type, initial = false) {
  if (!state.joined || !isGroupMember(currentRoom()?.groupId)) return;
  if (state.recovery) { recoverActivity(); return; }
  if (type === 'voice' && state.network !== 'connected') {
    state.voice = 'waiting';
    state.channelErrors.voice = '等待虚拟网络恢复后自动连接语音。';
    if (myMember(currentRoom())) myMember(currentRoom()).voice = 'waiting';
    render(); return;
  }
  const epoch = state.epoch, id = state.roomId;
  if (type === 'network') {
    clearTimeout(channelTimers.voice);
    state.voice = 'waiting'; state.speaking = false;
    if (myMember(currentRoom())) myMember(currentRoom()).voice = 'waiting';
  }
  state[type] = initial ? 'connecting' : 'retrying';
  state.channelErrors[type] = '';
  render();
  clearTimeout(channelTimers[type]);
  channelTimers[type] = setTimeout(() => {
    if (state.epoch !== epoch || state.roomId !== id || !state.joined) return;
    if (type === 'voice' && state.network !== 'connected') return;
    const failure = state.outcomes[type];
    delete state.outcomes[type];
    state[type] = failure ? 'failed' : 'connected';
    state.channelErrors[type] = failure || '';
    if (type === 'network' && failure) state.channelErrors.voice = '虚拟网络不可用，语音等待恢复。';
    if (type === 'network' && !failure) { retryChannel('voice', initial); return; }
    if (type === 'voice' && myMember(currentRoom())) myMember(currentRoom()).voice = state.voice;
    state.sessionStatus = ['connecting','retrying'].includes(state.voice) || ['connecting','retrying'].includes(state.network) ? 'joining' : 'active';
    render();
  }, initial ? type === 'voice' ? 250 : 450 : type === 'voice' ? 200 : 300);
}

function disconnectActivity() {
  if (!state.joined) return;
  Object.values(channelTimers).forEach(clearTimeout);
  state.epoch++;
  state.recovery = { until: Date.now() + 60000, ip: myMember(currentRoom()).ip };
  state.voice = state.network = 'disconnected';
  state.speaking = false;
  myMember(currentRoom()).voice = 'disconnected';
  state.channelErrors = { voice: '连接中断，名额与地址保留 60 秒。', network: '连接中断，名额与地址保留 60 秒。' };
  const epoch = state.epoch;
  clearTimeout(recoveryTimer);
  recoveryTimer = setTimeout(() => { if (state.epoch === epoch && state.recovery) expireRecovery(); }, 60000);
  render();
}

function expireRecovery() {
  revokeActivity(); closeModal(); render(); toast('断线保留已超时，名额已释放。请按当前规则重新加入。');
}

function recoverActivity() {
  const room = currentRoom();
  if (state.service==='offline') {toast('服务仍不可达，请恢复网络后重试。');return;}
  if (!state.recovery || state.recovery.until <= Date.now() || !room || !isGroupMember(room.groupId)) { expireRecovery(); return; }
  clearTimeout(recoveryTimer);
  state.recovery = null;
  retryChannel('network');
}

function showCreateRoom() {
  const eligible = groups.filter(group => canModerate(group.id));
  if (!eligible.length) { toast('只有群主或管理员可以创建房间。'); return; }
  const selected = state.view === 'groupChat' && eligible.some(group => group.id === state.groupId) ? state.groupId : eligible[0].id;
  openModal('开一个新房间', '最多 16 人。', `<div class="field"><label for="new-room-name">房间名称</label><input id="new-room-name" maxlength="60" placeholder="给这次组队起个名字"></div><div class="field"><label for="new-room-game">游戏名称</label><input id="new-room-game" maxlength="60" placeholder="输入游戏名称"></div><div class="field"><label for="new-room-group">所属群组</label><select id="new-room-group">${eligible.map(group => `<option value="${group.id}" ${group.id === selected ? 'selected' : ''}>${esc(group.name)}</option>`).join('')}</select></div><div class="field"><label for="new-room-desc">描述</label><input id="new-room-desc" maxlength="120" placeholder="玩法或集合时间"></div><div class="field"><label for="new-room-password">房间密码（可选）</label><input id="new-room-password" type="password" maxlength="64" autocomplete="new-password"></div>`, '<button class="button" data-action="modal-close">取消</button><button class="button primary" data-action="confirm-create-room">创建并进入</button>');
}

function showRoomSettings(id) {
  const room = rooms.find(item => item.id === id);
  if (!canManageRoom(room)) { toast('当前身份无权管理此房间。'); return; }
  state.editRoomId = id;
  const group = groups.find(item => item.id === room.groupId);
  openModal('房间设置', '暂停只阻止新加入；结束活动保留配置。', `<div class="field"><label for="edit-room-name">房间名称</label><input id="edit-room-name" maxlength="60" value="${esc(room.name)}"></div><div class="field"><label for="edit-room-game">游戏名</label><input id="edit-room-game" maxlength="60" value="${esc(room.game)}"></div><div class="field"><label for="edit-room-desc">描述</label><input id="edit-room-desc" maxlength="120" value="${esc(room.desc)}"></div><div class="field"><label for="edit-room-password">新密码（留空保持；勾选可清除）</label><input id="edit-room-password" type="password" maxlength="64" autocomplete="new-password"><label><input id="clear-room-password" type="checkbox">清除密码</label></div>${canModerate(room.groupId) ? `<div class="field"><label for="edit-room-host">指定房主</label><select id="edit-room-host">${group.members.map(name => `<option ${name === room.host ? 'selected' : ''}>${esc(name)}</option>`).join('')}</select></div>` : ''}<p>${room.open ? '当前开放加入' : '当前暂停加入'}</p>`, `<button class="button" data-action="pause-room">${room.open ? '暂停加入' : '重新开放'}</button><button class="button danger" data-action="end-room">结束活动</button><button class="button danger" data-action="delete-room">删除房间</button><button class="button primary" data-action="save-room">保存</button>`);
}

function createInvitation(name, groupId, roomId) {
  const group = groups.find(item => item.id === groupId);
  if (!group || !isGroupMember(groupId) || !people[name]) { toast('无法发送邀请，请确认你仍在该群组中。'); return false; }
  if (state.invites.some(invite => invite.groupId === groupId && invite.name === name && invite.roomId === roomId && invitationStatus(invite) === 'pending')) { toast('已有待接受邀请，请勿重复发送。'); return false; }
  state.invites.push({ id: 'invite-' + state.nextId++, name, groupId, roomId, inviter: me, status: 'pending' });
  return true;
}

function invitationStatus(invite) {
  if (invite.status !== 'pending') return invite.status;
  const group = groups.find(item => item.id === invite.groupId);
  if (!group?.members.includes(invite.inviter) || invite.roomId && !rooms.some(room => room.id === invite.roomId)) invite.status = 'expired';
  return invite.status;
}

function showGroupManagement() {
  const group = currentGroup();
  if (!group || !isGroupMember(group.id)) { toast('你已不在此群中。'); return; }
  const role = groupRole(group.id);
  openModal('群组管理', `${esc(group.name)} · ${roleNames[role]}`, `${reviewEnabled ? `<div class="field"><label>复核身份（仅当前群）</label><select data-change="review-role"><option value="owner" ${role === 'owner' ? 'selected' : ''}>群主</option><option value="admin" ${role === 'admin' ? 'selected' : ''}>管理员</option><option value="member" ${role === 'member' ? 'selected' : ''}>普通成员</option></select></div>` : ''}<div class="setting-row"><strong>成员与邀请</strong><button class="button small" data-action="manage-members">管理</button></div>${canModerate(group.id) ? '<div class="setting-row"><strong>群资料</strong><button class="button small" data-action="edit-group">编辑</button></div><div class="setting-row"><strong>只读管理记录</strong><button class="button small" data-action="audit">查看</button></div>' : ''}${role === 'owner' ? '<button class="button danger" data-action="owner-actions">群主治理</button>' : '<button class="button danger" data-action="leave-group">退出群组</button><button class="button" data-action="owner-recovery">群主不可用？</button>'}`, '<button class="button" data-action="modal-close">关闭</button>');
}

function showIncomingInvites() {
  const incoming = state.invites.filter(invite => invite.name === me);
  openModal('收到的群邀请', '', incoming.map(invite => {
    const group = groups.find(item => item.id === invite.groupId), status = invitationStatus(invite);
    return `<div class="member-management"><strong>${esc(group?.name || '已失效群组')}</strong><span>${esc(invite.inviter)} 邀请 · ${{pending:'待接受',accepted:'已接受',declined:'已拒绝',revoked:'已撤销',expired:'已失效'}[status]}</span>${status === 'pending' ? `<button class="button primary small" data-action="accept-group-invite" data-id="${invite.id}">接受</button><button class="button small" data-action="decline-group-invite" data-id="${invite.id}">拒绝</button>` : ''}</div>`;
  }).join('') || '<p>暂无收到的邀请。</p>', '<button class="button" data-action="modal-close">关闭</button>');
}

function acceptGroupInvitation(invite) {
  if (!invite || invitationStatus(invite) !== 'pending') return false;
  const group = groups.find(item => item.id === invite.groupId);
  if (!group.members.includes(invite.name)) { group.members.push(invite.name); delete group.roles[invite.name]; }
  invite.status = 'accepted';
  return true;
}

function showMembers() {
  const group = currentGroup();
  if (!group) return;
  const role = groupRole(group.id);
  if (!role) return;
  const canChange = name => name !== me && (role === 'owner' || role === 'admin' && groupRole(group.id, name) === 'member');
  openModal('成员与角色', '', `${group.members.map(name => `<div class="member-management"><strong>${esc(name)}</strong><span>${roleNames[groupRole(group.id, name)]}</span>${canChange(name) ? `<button class="button small danger" data-action="remove-member" data-name="${esc(name)}">移出</button>${role === 'owner' ? `<button class="button small" data-action="change-member-role" data-name="${esc(name)}">${group.roles[name] === 'admin' ? '撤销管理员' : '设为管理员'}</button>` : ''}` : ''}</div>`).join('')}<div class="field"><label for="group-invite-name">邀请好友</label><select id="group-invite-name">${state.friends.filter(name => !group.members.includes(name)).map(name => `<option>${esc(name)}</option>`).join('')}</select></div><button class="button" data-action="confirm-member-invite">发送邀请</button><h3>邀请记录</h3>${state.invites.filter(invite => invite.groupId === group.id).map(invite => `<div class="member-management"><span>${esc(invite.name)} · ${{ pending: '待接受', accepted: '已接受', revoked: '已撤销', expired: '已失效' }[invitationStatus(invite)]}</span>${invitationStatus(invite) === 'pending' && canModerate(group.id) ? `<button class="button small" data-action="revoke-invite" data-id="${invite.id}">撤销</button>` : ''}</div>`).join('') || '<p>暂无邀请</p>'}`, '<button class="button" data-action="modal-close">关闭</button>');
}

function removeGroupMember(group, name) {
  if (name === me && state.mediaDraft?.groupId === group.id) discardMediaDraft();
  group.members = group.members.filter(member => member !== name);
  delete group.roles[name];
  if (group.transfer?.target === name) group.transfer = null;
  state.invites.filter(invite => invite.groupId === group.id && invite.status === 'pending' && invite.inviter === name).forEach(invite => { invite.status = 'expired'; });
  rooms.filter(room => room.groupId === group.id).forEach(room => {
    room.members = room.members.filter(member => member.name !== name);
    if (room.host === name) room.host = group.owner;
  });
  if (name === me && currentRoom()?.groupId === group.id) revokeActivity();
}

function showOwnerActions() {
  const group = currentGroup();
  if (groupRole(group.id) !== 'owner') { toast('仅群主可执行此操作。'); return; }
  openModal('群主治理', '对方接受后完成转让。', group.transfer ? `<p>等待 ${esc(group.transfer.target)} 接受转让。</p><button class="button" data-action="cancel-transfer">取消转让</button>` : `<div class="field"><label for="transfer-target">接收成员</label><select id="transfer-target">${group.members.filter(name => name !== me).map(name => `<option>${esc(name)}</option>`).join('')}</select></div><button class="button" data-action="transfer-owner">发送转让请求</button>`, '<button class="button" data-action="leave-group">退出群组</button><button class="button danger" data-action="dissolve-group">解散群组</button>');
}

function showReviewTools() {
  const group = currentGroup();
  openModal('原型复核工具', '仅 ?review=1 可见；模拟行为不代表真实服务通过。', `<div class="review-actions">${[['voice-fail','语音失败'],['network-fail','网络权限不足'],['network-conflict','IP 地址冲突'],['network-nat','NAT 直连失败'],['voice-device','麦克风拔出'],['service-offline','服务不可达'],['auth-expired','登录过期'],['disconnect','短时断线'],['recover','网络恢复'],['device-fail','设备转移预检失败'],['expire','保留期超时'],['lose-membership','当前群资格撤销']].map(([scenario,label]) => `<button class="button" data-action="review-scenario" data-scenario="${scenario}">${label}</button>`).join('')}</div><h3>内容副本与直连</h3><div class="review-actions">${[["online","在线副本可达"],["offline","无在线可达副本"],["partial","部分成员不可达"]].map(([mode,label]) => `<button class="button" data-action="review-peer-mode" data-mode="${mode}">${label}</button>`).join("")}</div><h3>模拟邀请接收方</h3>${state.invites.filter(invite => invite.name !== me && invitationStatus(invite) === 'pending').map(invite => `<div class="member-management"><span>${esc(invite.name)}</span><button class="button small" data-action="review-accept-invite" data-id="${invite.id}">接受邀请</button></div>`).join('') || '<p>暂无待接受邀请</p>'}${group?.transfer ? `<p>转让接收方：${esc(group.transfer.target)}</p><button class="button" data-action="review-accept-transfer">模拟接收方接受</button>` : ''}`, '<button class="button" data-action="modal-close">关闭</button>');
}

function authView() {
  const mode = state.authMode;
  const email = '<div class="field"><label for="auth-email">邮箱</label><input id="auth-email" type="email" autocomplete="off" value="demo@example.test"></div>';
  const password = '<div class="field"><label for="auth-password">密码（至少 8 位）</label><input id="auth-password" type="password" minlength="8" autocomplete="off" value="prototype-only"></div>';
  const actions = '<div class="auth-links"><button data-action="auth-mode" data-mode="login">返回登录</button><button data-action="auth-mode" data-mode="register">注册账号</button><button data-action="auth-mode" data-mode="forgot">找回密码</button></div>';
  let body;
  if (mode === 'login') body = email + password + '<button class="button primary" data-action="login">进入玩家大厅</button>';
  else if (mode === 'register') body = email + password + '<button class="button primary" data-action="register">发送模拟验证码</button>';
  else if (mode === 'verify') body = '<p>演示验证码：000000，有效期 5 分钟。</p><div class="field"><label for="auth-code">6 位验证码</label><input id="auth-code" maxlength="6" inputmode="numeric" autocomplete="off"></div><button class="button primary" data-action="verify">验证并进入大厅</button><button class="button" data-action="resend-code">重新发送</button>';
  else if (mode === 'reset') body = '<p>模拟邮件已生成重置步骤；仅演示已验证邮箱的找回流程。</p><div class="field"><label for="auth-code">模拟邮件验证码（000000）</label><input id="auth-code" maxlength="6" inputmode="numeric"></div><div class="field"><label for="auth-password">新密码（至少 8 位）</label><input id="auth-password" type="password" minlength="8" autocomplete="off"></div><button class="button primary" data-action="reset-password">确认模拟重置</button>';
  else body = email + '<button class="button primary" data-action="forgot">发送模拟重置邮件</button>';
  return `<div class="auth"><section class="auth-card"><div class="brand"><span class="brand-mark"></span><span class="brand-name">织界<small>WEAVORA</small></span></div><h1>${mode === 'login' ? '欢迎回来，玩家。' : mode === 'register' ? '创建你的玩家账号' : '验证你的身份'}</h1><p>和朋友在同一个世界，随时开局。</p>${body}${actions}<p class="prototype-note">输入只用于本页模拟校验，不发送、不保存。请勿输入真实凭据。</p></section></div>`;
}

function handleAuthAction(action) {
  if (['login','register','forgot'].includes(action)) {
    const email = document.getElementById('auth-email');
    if (!email?.validity.valid || !email.value.trim()) { toast('请输入有效邮箱地址。'); email?.focus(); return; }
  }
  if (['login','register','reset-password'].includes(action) && (document.getElementById('auth-password')?.value.length || 0) < 8) { toast('密码至少需要 8 位。'); return; }
  if (['verify','reset-password'].includes(action) && (document.getElementById('auth-code')?.value !== '000000' || !state.codeUntil || state.codeUntil < Date.now())) { toast('验证码错误或已过期，请重新发送。'); return; }
  if (action === 'register') { state.authMode = 'verify'; state.codeUntil = Date.now() + 300000; state.verified = false; render(); }
  else if (action === 'forgot') { state.authMode = 'reset'; state.codeUntil = Date.now() + 300000; render(); }
  else if (action === 'resend-code') { state.codeUntil = Date.now() + 300000; toast('模拟验证码已重新生成：000000，有效期 5 分钟。'); }
  else if (action === 'reset-password') { state.authMode = 'login'; render(); toast('模拟密码重置流程完成；未保存或变更真实密码。'); }
  else {
    if (action === 'login' && state.verified === false) { state.authMode = 'verify'; render(); toast('请先完成邮箱验证。'); return; }
    state.verified = true; state.authenticated = true; state.service='online'; state.view = 'lobby'; render();
  }
}

function handleDomainAction(event) {
  const button = event.target.closest('[data-action]');
  if (!button) return;
  const action = button.dataset.action;
  const authActions = ['login','register','forgot','verify','reset-password','resend-code'];
  const handled = new Set([...authActions,'confirm-create-room','confirm-join-code','room-settings','save-room','pause-room','end-room','confirm-end-room','delete-room','confirm-delete-room','manage-members','confirm-member-invite','remove-member','confirm-remove-member','change-member-role','revoke-invite','audit','owner-actions','transfer-owner','cancel-transfer','dissolve-group','confirm-dissolve-group','leave-group','confirm-leave-group','edit-group','save-group','review-scenarios','review-scenario','review-accept-invite','review-accept-transfer','confirm-device-transfer','kick-player','confirm-kick-player','owner-recovery']);
  ['incoming-invites','accept-group-invite','decline-group-invite'].forEach(item => handled.add(item));
  if (!handled.has(action)) return;
  event.preventDefault(); event.stopImmediatePropagation();
  if (authActions.includes(action)) { handleAuthAction(action); return; }
  if (!state.authenticated) { toast('请先登录。'); return; }
  const group = currentGroup();
  const room = rooms.find(item => item.id === (button.dataset.room || state.editRoomId || state.roomId));
  const roomActions = ['save-room','pause-room','end-room','confirm-end-room','delete-room','confirm-delete-room'];
  if (roomActions.includes(action) && !canManageRoom(room)) { toast('房间已删除或当前管理权限已失效。'); return; }
  const ownerActions = ['owner-actions','transfer-owner','cancel-transfer','dissolve-group','confirm-dissolve-group','change-member-role'];
  if (ownerActions.includes(action) && (!group || groupRole(group.id) !== 'owner')) { toast('当前身份不是群主。'); return; }
  if (['remove-member','confirm-remove-member','revoke-invite','audit','edit-group','save-group'].includes(action) && (!group || !canModerate(group.id))) { toast('当前管理权限已失效。'); return; }
  if (action.startsWith('review-') && !reviewEnabled) return;
  if (action === 'incoming-invites') showIncomingInvites();
  else if (action === 'accept-group-invite' || action === 'decline-group-invite') {
    const invite = state.invites.find(item => item.id === button.dataset.id && item.name === me);
    if (!invite || invitationStatus(invite) !== 'pending') { toast('邀请已失效或已处理。'); showIncomingInvites(); return; }
    if (action === 'decline-group-invite') { invite.status = 'declined'; showIncomingInvites(); return; }
    if (acceptGroupInvitation(invite)) { state.groupId = invite.groupId; state.view = 'groupChat'; closeModal(); render(); toast('已接受邀请，加入群组。'); }
  } else if (action === 'confirm-create-room') {
    const groupId = document.getElementById('new-room-group').value;
    const name = document.getElementById('new-room-name').value.trim();
    if (!canModerate(groupId)) { toast('只有群主或管理员可以创建房间。'); return; }
    if (!name || name.length > 60) { toast('房间名称须为 1–60 个字符。'); return; }
    const id = 'room-' + state.nextId++;
    rooms.push({ id, name, game: document.getElementById('new-room-game').value.trim() || '未指定游戏', desc: document.getElementById('new-room-desc').value.trim() || '好友组队', password: document.getElementById('new-room-password').value, groupId, host: me, open: true, members: [], messages: [], cover: 'cubes' });
    groups.find(item => item.id === groupId).roomIds.push(id); appendAudit(groupId, '创建房间', name); enterRoom(id);
  } else if (action === 'confirm-join-code') {
    const code = document.getElementById('join-code').value.trim().toUpperCase();
    const id = { 'WV-7K2M-9P': 'weekend', 'WV-STAR-3K': 'star' }[code];
    if (!id) { toast('邀请码无效，请检查后重试。'); return; }
    const target = rooms.find(item => item.id === id);
    if (joinError(target, '', false)) { toast(joinError(target, '', false)); return; }
    if (target.password && !canModerate(target.groupId)) showJoinRoom(id); else enterRoom(id);
  } else if (action === 'room-settings') showRoomSettings(button.dataset.room || state.roomId);
  else if (action === 'save-room') {
    const name = document.getElementById('edit-room-name').value.trim();
    if (!name || name.length > 60) { toast('房间名称须为 1–60 个字符。'); return; }
    const host = document.getElementById('edit-room-host')?.value;
    if (host && !isGroupMember(room.groupId, host)) { toast('指定房主已不在群中。'); return; }
    room.name = name; room.game = document.getElementById('edit-room-game').value.trim() || '未指定游戏'; room.desc = document.getElementById('edit-room-desc').value.trim();
    if (host && canModerate(room.groupId)) room.host = host;
    const password = document.getElementById('edit-room-password').value;
    if (document.getElementById('clear-room-password').checked) room.password = ''; else if (password) room.password = password;
    appendAudit(room.groupId, '修改房间配置', room.name); closeModal(); render(); toast('已保存。');
  } else if (action === 'pause-room') { room.open = !room.open; appendAudit(room.groupId, room.open ? '开放房间' : '暂停房间', room.name); closeModal(); render(); }
  else if (action === 'end-room' || action === 'delete-room') {
    const deleting = action === 'delete-room';
    openModal(deleting ? '删除房间？' : '结束当前活动？', deleting ? '所有成员将离开房间，语音和联机断开；房间及消息删除后无法恢复。' : '所有成员将离开房间，语音和联机断开。房间保留，可再次使用。', '', `<button class="button" data-action="modal-close">取消</button><button class="button danger" data-action="${deleting ? 'confirm-delete-room' : 'confirm-end-room'}">确认${deleting ? '删除' : '结束'}</button>`);
  } else if (action === 'confirm-end-room' || action === 'confirm-delete-room') {
    if (state.roomId === room.id) revokeActivity();
    room.members = [];
    clearMessageMedia(room.messages);
    room.messages = [];
    delete state.drafts.room[room.id];
    if (action === 'confirm-delete-room') {
      room.messages.forEach(message => { if (message.media) URL.revokeObjectURL(message.media.url); });
      rooms.splice(rooms.indexOf(room), 1);
      const parent = groups.find(item => item.id === room.groupId); parent.roomIds = parent.roomIds.filter(id => id !== room.id);
      state.invites.filter(invite => invite.roomId === room.id && invite.status === 'pending').forEach(invite => { invite.status = 'expired'; });
      delete state.drafts.room[room.id]; delete state.lastIps[room.id];
    }
    appendAudit(room.groupId, action === 'confirm-delete-room' ? '删除房间' : '结束活动', room.name);
    closeModal(); render(); toast(action === 'confirm-delete-room' ? '房间已删除。' : '活动已结束。');
  } else if (action === 'manage-members') showMembers();
  else if (action === 'confirm-member-invite') {
    const name = document.getElementById('group-invite-name')?.value;
    if (!name) { toast('暂无可邀请好友。'); return; }
    if (createInvitation(name, group.id)) { appendAudit(group.id, '发送群邀请', name); showMembers(); toast('邀请已发送，等待对方接受。'); }
  } else if (action === 'revoke-invite') {
    const invite = state.invites.find(item => item.id === button.dataset.id && item.groupId === group.id);
    if (invite && invitationStatus(invite) === 'pending') { invite.status = 'revoked'; appendAudit(group.id, '撤销邀请', invite.name); showMembers(); }
  } else if (action === 'remove-member' || action === 'confirm-remove-member') {
    const name = button.dataset.name;
    if (!group.members.includes(name) || name === me || groupRole(group.id, name) === 'owner' || groupRole(group.id) === 'admin' && groupRole(group.id, name) === 'admin') { toast('无权移除此成员。'); return; }
    if (action === 'remove-member') openModal(`移出 ${esc(name)}？`, '对方将退出群组及本群房间，需重新邀请才能加入。', '', `<button class="button" data-action="modal-close">取消</button><button class="button danger" data-action="confirm-remove-member" data-name="${esc(name)}">确认移出</button>`);
    else { removeGroupMember(group, name); appendAudit(group.id, '移出成员', name); showMembers(); render(); focusModal(); }
  } else if (action === 'change-member-role') {
    const name = button.dataset.name;
    if (name === me || !group.members.includes(name) || name === group.owner) return;
    group.roles[name] = group.roles[name] === 'admin' ? 'member' : 'admin'; appendAudit(group.id, '变更成员角色', name); showMembers();
  } else if (action === 'audit') {
    openModal('管理记录', '', `<div class="table-wrap"><table><thead><tr><th>时间</th><th>操作者</th><th>目标</th><th>操作</th><th>结果</th></tr></thead><tbody>${group.audit.map(item => `<tr><td>${esc(item.time)}</td><td>${esc(item.actor)}</td><td>${esc(item.target)}</td><td>${esc(item.action)}</td><td>${esc(item.result)}</td></tr>`).join('')}</tbody></table>${group.audit.length ? '' : '<p>暂无管理记录</p>'}</div>`, '<button class="button" data-action="modal-close">关闭</button>');
  } else if (action === 'owner-actions') showOwnerActions();
  else if (action === 'transfer-owner') {
    const target = document.getElementById('transfer-target')?.value;
    if (!target || target === me || !group.members.includes(target)) { toast('请选择有效群成员。'); return; }
    group.transfer = { target, from: me }; appendAudit(group.id, '发起群主转让', target); showOwnerActions();
  } else if (action === 'cancel-transfer') { group.transfer = null; showOwnerActions(); }
  else if (action === 'leave-group') {
    if (groupRole(group.id) === 'owner') { toast('群主须先完成转让，或解散群组。'); return; }
    openModal('退出此群组？', `退出后需重新邀请才能加入。${state.joined && currentRoom()?.groupId === group.id ? '同时将离开当前房间，并断开语音和联机。' : ''}`, '', '<button class="button" data-action="modal-close">取消</button><button class="button danger" data-action="confirm-leave-group">确认退出</button>');
  } else if (action === 'confirm-leave-group') {
    if (!isGroupMember(group.id) || groupRole(group.id) === 'owner') { toast('当前不能退出，请先完成群主转让。'); return; }
    removeGroupMember(group, me); appendAudit(group.id, '退出群组', me); closeModal(); state.view = 'groups'; render();
  } else if (action === 'dissolve-group') {
    openModal('解散群组？', '所有成员将退出群组及本群房间；群组、消息、房间和管理记录删除后无法恢复。', '<div class="field"><label for="dissolve-name">输入群名称确认</label><input id="dissolve-name" autocomplete="off"></div>', '<button class="button" data-action="modal-close">取消</button><button class="button danger" data-action="confirm-dissolve-group">确认解散</button>');
  } else if (action === 'confirm-dissolve-group') {
    if (document.getElementById('dissolve-name').value !== group.name) { toast('群名称不匹配。'); return; }
    if (currentRoom()?.groupId === group.id) revokeActivity();
    if (state.mediaDraft?.groupId === group.id) discardMediaDraft();
    clearMessageMedia(group.messages);
    rooms.filter(item => item.groupId === group.id).forEach(item => { clearMessageMedia(item.messages); delete state.lastIps[item.id]; delete state.drafts.room[item.id]; });
    for (let index = rooms.length - 1; index >= 0; index--) if (rooms[index].groupId === group.id) rooms.splice(index, 1);
    state.invites = state.invites.filter(invite => invite.groupId !== group.id);
    delete state.drafts.group[group.id]; groups.splice(groups.indexOf(group), 1);
    state.groupId = groups.find(item => isGroupMember(item.id))?.id || null;
    closeModal(); state.view = 'groups'; render(); toast('群组已解散。');
  } else if (action === 'edit-group') {
    openModal('编辑群资料', '', `<div class="field"><label for="edit-group-name">群名称</label><input id="edit-group-name" maxlength="60" value="${esc(group.name)}"></div><div class="field"><label for="edit-group-desc">简介</label><textarea id="edit-group-desc" maxlength="300">${esc(group.desc || '')}</textarea></div>`, '<button class="button primary" data-action="save-group">保存</button>');
  } else if (action === 'save-group') {
    const name = document.getElementById('edit-group-name').value.trim();
    if (!name || name.length > 60) { toast('请填写 1–60 字群名称。'); return; }
    group.name = name; group.desc = document.getElementById('edit-group-desc').value.trim(); appendAudit(group.id, '修改群资料', name); closeModal(); render();
  } else if (action === 'confirm-device-transfer') {
    if (!state.joined) { toast('当前没有可转移的房间活动。'); return; }
    if (state.outcomes.device) {const error=state.outcomes.device;delete state.outcomes.device;toast(error);return;}
    revokeActivity(); state.activeDevice = '另一台电脑'; state.view = 'settings'; state.settingsTab = 'account'; closeModal(); render(); toast('活动已转移，此电脑已离开房间（模拟）。');
  } else if (action === 'review-scenarios') showReviewTools();
  else if (action === 'review-scenario') {
    const scenario = button.dataset.scenario;
    closeModal();
    if (!state.joined && !['service-offline','auth-expired','recover','expire','lose-membership'].includes(scenario)) { toast('请先加入房间。'); return; }
    if (scenario === 'service-offline') { state.service = 'offline'; if(state.joined)disconnectActivity(); toast('服务不可达，恢复前保留有效房间资格。'); }
    else if (scenario === 'auth-expired') { revokeActivity(); state.authenticated=false; state.authMode='login'; render(); toast('登录已过期，请重新登录。'); }
    else if (scenario === 'device-fail') { state.outcomes.device='目标设备不可达，请稍后重试。'; toast('下一次设备转移将模拟预检失败。'); }
    else if (scenario === 'disconnect') disconnectActivity();
    else if (scenario === 'recover') { state.service='online'; if(state.settings.recover)recoverActivity(); else {render();toast('网络已恢复，自动恢复已关闭，请手动重试。');} }
    else if (scenario === 'expire') expireRecovery();
    else if (scenario === 'lose-membership') {
      const target = currentRoom() ? groups.find(item => item.id === currentRoom().groupId) : group;
      if (!target) {toast('请先选择群组。');return;}
      if (target.owner === me) { toast('请先完成群主转让，再复核资格撤销。'); return; }
      removeGroupMember(target, me); closeModal(); state.view = 'groups'; render();
    } else {
      const type = scenario.startsWith('voice') ? 'voice' : 'network';
      clearTimeout(channelTimers[type]);
      state[type] = 'failed'; state.channelErrors[type] = scenario === 'network-nat' ? '无法与队友直连，请检查 UDP 或防火墙后重试；不会转由服务器传输。' : scenario === 'network-conflict' ? '虚拟 IP 地址冲突，请重试以重新分配。' : scenario === 'network-fail' ? '虚拟网卡权限不足，请在系统中授权后重试。' : scenario === 'voice-device' ? '麦克风已拔出，请重新连接或选择输入设备。' : '语音连接失败，请检查网络后重试。';
      if (type === 'voice') { myMember(currentRoom()).voice = 'failed'; state.speaking = false; }
      else {
        clearTimeout(channelTimers.voice); state.voice = 'waiting'; state.speaking = false;
        state.channelErrors.voice = '虚拟网络不可用，语音等待恢复。';
        myMember(currentRoom()).voice = 'waiting';
      }
      render(); toast(state.channelErrors[type]);
    }
  } else if (action === 'review-accept-invite') {
    const invite = state.invites.find(item => item.id === button.dataset.id);
    if (!invite || invitationStatus(invite) !== 'pending') { toast('邀请已失效。'); return; }
    acceptGroupInvitation(invite); appendAudit(invite.groupId,'接受群邀请',invite.name,invite.name); closeModal(); render(); toast('已模拟接收方接受群邀请，不自动加入房间。');
  } else if (action === 'review-accept-transfer') {
    const transfer = group?.transfer;
    if (!transfer || transfer.from !== group.owner || !group.members.includes(transfer.target)) { toast('转让请求已失效。'); return; }
    const oldOwner = group.owner; group.owner = transfer.target; group.roles[oldOwner] = 'member'; group.transfer = null; delete state.reviewRoles[group.id]; appendAudit(group.id, '接受群主转让', group.owner, group.owner); closeModal(); render();
  } else if (action === 'kick-player' || action === 'confirm-kick-player') {
    const targetRoom = currentRoom(), name = button.dataset.name;
    if (!canKickPlayer(targetRoom, name)) { toast('当前身份不能移除此成员。'); return; }
    if (action === 'kick-player') openModal(`将 ${esc(name)} 移出房间？`, '对方的语音和联机将断开，之后仍可重新加入房间。', '', `<button class="button" data-action="modal-close">取消</button><button class="button danger" data-action="confirm-kick-player" data-name="${esc(name)}">确认移出</button>`);
    else { targetRoom.members = targetRoom.members.filter(member => member.name !== name); appendAudit(targetRoom.groupId, '移出房间', name); closeModal(); render(); }
  } else if (action === 'owner-recovery') {
    openModal('群主不可用时申请恢复', '需人工审核；证据不足或存在争议时，无法变更群主。', '<p>正式客户端将在此提供人工受理渠道与申请进度。本地原型不发送申请，也不自动变更群主。</p>', '<button class="button" data-action="modal-close">关闭</button>');
  }
}

function canKickPlayer(room, name) {
  if (!canManageRoom(room) || !room.members.some(member => member.name === name) || name === me) return false;
  const actorRole = groupRole(room.groupId), targetRole = groupRole(room.groupId, name);
  return actorRole === 'owner' ? targetRole !== 'owner' : actorRole === 'admin' ? targetRole === 'member' : targetRole === 'member' && name !== room.host;
}

document.addEventListener('change', event => {
  if (event.target.dataset.change === 'review-role' && reviewEnabled) { state.reviewRoles[state.groupId] = event.target.value; showGroupManagement(); }
});
