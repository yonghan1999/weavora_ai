const baseUi = { render, renderModal, toast, focusModal, networkPanel, groupsView, friendsView, settingsView, roomView, mediaPreview, updateMediaTransfer, messageReceipt, playerTile, membersPanel };

function uiMemberVoice(member) {
  return state.network !== 'connected' ? 'waiting' : member.name === me ? state.voice : member.voice;
}

playerTile = function(member) {
  const voice = uiMemberVoice(member);
  const html = baseUi.playerTile({ ...member, voice, speaking: voice === 'connected' && member.speaking });
  return voice === 'connected' ? html : html.replace('player-tile speaking', 'player-tile');
};

membersPanel = function() {
  if (state.network === 'connected') return baseUi.membersPanel();
  return baseUi.membersPanel().replace(/语音已连接|语音未连接/g, channelLabel('voice', 'waiting'));
};

messageReceipt = function(message) {
  return baseUi.messageReceipt(message).replace('class="message-receipt"', `class="message-receipt" data-delivery="${esc(message.delivery || '')}"`);
};

mediaPreview = function(media) {
  const preview = baseUi.mediaPreview(media);
  if (!media || media.type !== 'image' || state.pendingMedia === media) return preview;
  return `<button type="button" class="media-open" data-action="view-media" data-url="${esc(media.url)}" data-name="${esc(media.name)}" aria-label="查看图片 ${esc(media.name)}">${preview}</button>`;
};

updateMediaTransfer = function() {
  baseUi.updateMediaTransfer();
  const transfer = document.getElementById('media-transfer');
  const modal = transfer?.closest('.modal');
  if (!modal || !state.mediaDraft) return;
  modal.classList.add('media-modal');
  modal.dataset.mediaStatus = state.mediaDraft.status;
  transfer.dataset.status = state.mediaDraft.status;
};

document.addEventListener('click',event => {
  const trigger = event.target.closest('[data-action="view-media"]');
  if (!trigger) return;
  const name = trigger.dataset.name || '图片';
  openModal('查看图片',esc(name),`<div class="media-viewer"><img class="message-media" src="${esc(trigger.dataset.url)}" alt="${esc(name)}"></div>`,'<button class="button primary" data-action="modal-close">关闭</button>');
  document.querySelector('.modal')?.classList.add('media-viewer-modal');
});

function uiHeading(title, description, actions = '', eyebrow = '') {
  return `<div class="page-heading"><div>${eyebrow ? `<div class="eyebrow">${esc(eyebrow)}</div>` : ''}<h1>${esc(title)}</h1><p>${esc(description)}</p></div>${actions ? `<div class="heading-actions">${actions}</div>` : ''}</div>`;
}

function uiAvatarStack(names, limit = 3) {
  return `<div class="avatar-stack">${names.slice(0, limit).map(name => avatar(name)).join('')}${names.length > limit ? `<span class="avatar-overflow">+${names.length - limit}</span>` : ''}</div>`;
}

roomCard = function(room) {
  const current = state.joined && room.id === state.roomId;
  const group = groups.find(item => item.id === room.groupId);
  const full = room.members.length >= 16;
  const availability = !room.open ? '暂停加入' : full ? '房间已满' : room.members.length ? '活动中' : '等待队友';
  const names = room.members.map(member => member.name);
  const action = current ? 'open-room' : 'join-room';
  const label = current ? '回到房间' : state.joined ? '切换房间' : '加入房间';
  return `<article class="room-card ${current ? 'is-current' : ''}"><div class="room-cover ${room.cover}"><span class="game-chip">${esc(room.game)}</span><span class="room-capacity" aria-label="${names.length} 人，最多 16 人">${names.length} / 16</span></div><div class="room-body"><div class="room-provenance">${icon('messages',13)} ${esc(group?.name || '群组')}</div><div class="room-title-row"><h3>${esc(room.name)}</h3>${canManageRoom(room) ? `<button class="icon-button" data-action="room-settings" data-room="${esc(room.id)}" aria-label="管理 ${esc(room.name)}">${icon('more',17)}</button>` : ''}</div><p class="room-description">${esc(room.desc)}</p><div class="room-presence"><span class="availability ${room.open && !full ? 'available' : 'unavailable'}"><i></i>${availability}</span>${room.password ? `<span class="password-label">${icon('shield',12)} 需密码</span>` : ''}</div><div class="room-footer">${names.length ? uiAvatarStack(names) : '<span class="room-waiting">还没有人，等你开场</span>'}<button class="button small ${current ? 'primary' : ''}" data-action="${action}" data-room="${esc(room.id)}" aria-label="${label} ${esc(room.name)}">${label}${icon('arrow',14)}</button></div></div></article>`;
};

activeHero = function() {
  if (!state.joined) return '';
  const room = currentRoom();
  return `<section class="hero current-session"><div class="hero-copy"><span class="live-label">${icon('headphones',15)} 当前房间</span><h2>${esc(room.name)}</h2><span class="hero-game">${esc(room.game)} · ${room.members.length} 人在房间</span><div class="session-channels">${statusChip('voice',state.voice)}${statusChip('network',state.network)}</div></div><div class="session-return"><button class="button primary" data-action="open-room" data-room="${esc(room.id)}">回到房间 ${icon('arrow',16)}</button></div></section>`;
};

lobby = function() {
  const visible = rooms.filter(room => isGroupMember(room.groupId));
  return `<div class="content-grid"><main class="main-scroll">${uiHeading('玩家大厅','语音、聊天与虚拟局域网，和队友一起就绪。',`<button class="button" data-action="join-code">邀请码加入</button><button class="button primary" data-action="create-room">${icon('plus',16)} 创建房间</button>`,'一起开局 / PLAY TOGETHER')}${activeHero()}<div class="section-head"><div><h2>群内房间 <span class="section-count">${visible.length}</span></h2><p>来自你已加入的群组</p></div></div><div class="room-grid">${visible.map(roomCard).join('') || `<div class="empty-state ui-empty">${icon('game',28)}<h3>还没有可加入的房间</h3><p>先创建群组，或接受朋友的群邀请。</p><button class="button" data-action="nav" data-view="groups">前往群组 ${icon('arrow',15)}</button></div>`}</div></main>${aside()}</div>`;
};

groupsView = function() {
  const visible = groups.filter(group => isGroupMember(group.id));
  const pending = state.invites.filter(invite => invite.name === me && invitationStatus(invite) === 'pending').length;
  return `<main class="main-scroll"><div class="center-panel">${uiHeading('群组','熟悉的朋友，固定的集合地。',`<button class="button primary" data-action="create-group">${icon('plus',16)} 创建群组</button>`,'私密小队 / YOUR CREWS')}<div class="group-list-toolbar"><h2>我的群组</h2><button class="button ghost small group-invites" data-action="incoming-invites" aria-label="群邀请${pending ? `，${pending} 条待处理` : ''}">${icon('userPlus',15)} 群邀请${pending ? `<span class="count-badge">${pending}</span>` : ''}</button></div><div class="group-grid">${visible.map(group => {
    const active = rooms.filter(room => room.groupId === group.id && room.members.length).length;
    const online = group.members.filter(name => people[name]?.online).length;
    return `<button class="group-card" data-action="open-group" data-group="${esc(group.id)}"><div class="group-card-top"><span class="group-icon">${esc(group.icon)}</span><span class="group-members-label">${group.members.length} 位成员 · ${group.roomIds.length} 个游戏房间</span></div><h3>${esc(group.name)}</h3><p>${esc(group.desc || '约好下一局，随时来集合。')}</p><div class="group-card-foot"><span class="group-social">${uiAvatarStack(group.members,3)}<span>${online} 人在线</span></span><span class="group-room-state">${active ? `${active} 个房间活动中` : '暂无房间活动'} ${icon('arrow',14)}</span></div></button>`;
  }).join('') || `<div class="empty-state ui-empty">${icon('messages',28)}<h3>暂无群组</h3><p>创建自己的小队，或查看朋友发来的邀请。</p><button class="button" data-action="incoming-invites">查看群邀请</button></div>`}</div></div></main>`;
};

networkPanel = function() {
  if (state.network !== 'connected') return baseUi.networkPanel();
  const room = currentRoom();
  return `<div class="network-panel"><div class="network-hero">${statusChip('network',state.network)}<h3>准备好，连接游戏</h3><p>织界房主不一定是游戏内主机。找到实际创建游戏世界的队友，复制其地址。</p></div><div class="connect-instruction"><span>${icon('copy',16)}</span><p>复制队友 IP <span aria-hidden="true">→</span> 在游戏中选择“直接连接”<small>如需端口，请向创建游戏的队友确认。</small></p></div><div class="member-ip-heading"><strong>成员虚拟 IP</strong><span>${room.members.length} 个地址</span></div><div class="member-ip-list">${room.members.map(memberIpRow).join('')}</div><button class="button ghost small connection-help" data-action="network-help">${icon('help',14)} 连接失败怎么办？</button></div>`;
};

friendsView = function() {
  return baseUi.friendsView().replace('下次开局，不用再找人。','好友').replace('FRIENDS · 好友','保持联系 / FRIENDS');
};

settingsView = function() {
  return baseUi.settingsView().replace('按你的方式，舒服地玩。','设置').replace('PREFERENCES · 设置','你的偏好 / PREFERENCES');
};

roomView = function() {
  const html = baseUi.roomView();
  const room = currentRoom();
  if (!room) return html;
  return html.replace('<div class="party-grid">',`<div class="stage-heading"><div><span class="stage-label">语音房间</span><h2>队友已就位</h2></div><span>${room.members.length} / 16 人</span></div><div class="party-grid">`);
};

focusModal = function() {
  const modal = document.querySelector('.modal');
  const field = modal?.querySelector('input:not([type=hidden]),select,textarea');
  const cancel = modal?.querySelector('.modal-actions [data-action=modal-close]');
  if (field || cancel) (field || cancel).focus(); else baseUi.focusModal();
};

renderModal = function() {
  baseUi.renderModal();
  const modal = document.querySelector('.modal');
  if (!modal) return;
  const description = modal.querySelector('.modal-head p');
  if (description?.textContent) {
    description.id = 'modal-description';
    modal.setAttribute('aria-describedby',description.id);
  }
  modal.querySelectorAll('input,textarea').forEach(input => {
    if (input.id.includes('password')) return;
    input.spellcheck = false;
  });
};

function uiErrorField(text) {
  const candidates = text.includes('密码') ? ['room-password','auth-password']
    : text.includes('验证码') ? ['auth-code']
    : text.includes('邮箱') ? ['auth-email']
    : text.includes('群名称') ? ['dissolve-name','new-group-name','edit-group-name']
    : text.includes('房间名称') ? ['new-room-name','edit-room-name']
    : text.includes('邀请码') ? ['join-code'] : [];
  return candidates.map(id => document.getElementById(id)).find(Boolean);
}

function uiClearFeedback() {
  document.querySelectorAll('.inline-feedback').forEach(element => element.remove());
  document.querySelectorAll('[aria-invalid=true]').forEach(input => {
    input.removeAttribute('aria-invalid');
    input.removeAttribute('aria-describedby');
  });
}

toast = function(text) {
  baseUi.toast(text);
  const field = uiErrorField(text);
  const error = /错误|无效|过期|不匹配|须为|请填写|请输入|至少|失效|无权|不能|无法|已满|暂停|失败|不可达/.test(text);
  if (!error) return;
  const container = field?.closest('.field') || document.querySelector('.modal > div:nth-child(2)');
  if (!container) return;
  uiClearFeedback();
  const feedback = document.createElement('p');
  feedback.className = 'inline-feedback';
  feedback.id = 'inline-feedback';
  feedback.setAttribute('role','alert');
  feedback.textContent = text;
  container.append(feedback);
  document.getElementById('toast').replaceChildren();
  if (field) {
    field.setAttribute('aria-invalid','true');
    field.setAttribute('aria-describedby',feedback.id);
    field.focus();
  }
};

render = function() {
  baseUi.render();
  document.querySelectorAll('.nav-item[data-view]').forEach(button => {
    if (button.classList.contains('active')) button.setAttribute('aria-current','page');
  });
  document.querySelectorAll('.side-tab').forEach(button => button.setAttribute('aria-pressed',String(button.classList.contains('active'))));
  document.querySelectorAll('[data-action=invite-friend]').forEach(button => {
    if (button.dataset.name) button.setAttribute('aria-label',`邀请 ${button.dataset.name} 组队`);
  });
  document.querySelectorAll('.status-chip').forEach(element => {
    element.dataset.connection = /已连接/.test(element.textContent) ? 'connected' : /等待虚拟网络|连接中|重试中/.test(element.textContent) ? 'pending' : 'other';
  });
};

document.addEventListener('input',event => {
  const input = event.target;
  if (input.matches('input,textarea') && input.getAttribute('aria-invalid') === 'true') uiClearFeedback();
});

render();
