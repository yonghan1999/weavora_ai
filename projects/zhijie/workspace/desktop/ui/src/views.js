import { groupAvatarImage } from './group-avatar.js';
import { networkErrorText } from './network-errors.js';
export const escapeHtml = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const e = escapeHtml;
const paths = {
  home: '<path d="M3 10.5 12 3l9 7.5M5.5 9.5V21h13V9.5M9 21v-7h6v7"/>',
  users:
    '<circle cx="9" cy="7" r="4"/><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8"/>',
  messages:
    '<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  settings:
    '<circle cx="12" cy="12" r="3"/><path d="m9 3-1 3-3 1-2 3 2 2-1 4 3 2 3-1 2 4 3-2 1-3 4-1 1-4-3-2V5l-4-1-2 2z"/>',
  mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5M8 22h8"/>',
  headphones:
    '<path d="M4 14v-2a8 8 0 0 1 16 0v2"/><rect x="3" y="13" width="4" height="7" rx="2"/><rect x="17" y="13" width="4" height="7" rx="2"/>',
  network:
    '<rect x="9" y="9" width="6" height="6" rx="2"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4M5 5l3 3M16 16l3 3M19 5l-3 3M8 16l-3 3"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  back: '<path d="M19 12H5M11 18l-6-6 6-6"/>',
  game: '<path d="M8 6h8a6 6 0 0 1 5.7 7.8l-1.4 4.3a2 2 0 0 1-3.3.8L14.5 16h-5L7 18.9a2 2 0 0 1-3.3-.8l-1.4-4.3A6 6 0 0 1 8 6zM7 10v4M5 12h4M16 11h.01M18 13h.01"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.6 2.6 0 1 1 4.4 1.9c-1 .8-1.9 1.2-1.9 2.6M12 17h.01"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
  logout:
    '<path d="m10 17 5-5-5-5M15 12H3M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/>',
};
export const icon = (name, size = 20) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.game}</svg>`;
export const button = (label, action, cls = "", extra = "") =>
  `<button type="button" class="button ${cls}" data-action="${action}" ${extra}>${label}</button>`;
const microphoneTest = (s) => `${button(s.busy ? "检测中…" : "检测麦克风（3 秒）", "test-microphone", "", s.busy || s.voice?.state !== "CONNECTED" ? "disabled" : "")}<p class="runtime-note">点击后请说话，检测仅在本机读取当前输入电平，不保存录音；通话中的静音设置保持不变。</p>`;
const outputTest = (s) => `${button("试听输出（1.5 秒）", "test-output", "", s.busy || s.voice?.state !== "CONNECTED" || !s.voice?.outputDevice ? "disabled" : "")}<p class="runtime-note">先选择输出设备，再点击播放轻柔测试音；音量跟随总音量，仅在本机播放。请自行确认是否听到声音。</p>`;
const brand = () =>
  '<div class="brand"><span class="brand-mark" aria-hidden="true"></span><span class="brand-name">织界<small>WEAVORA</small></span></div>';
const heading = (
  title,
  description,
  actions = "",
  eyebrow = "一起开局 / PLAY TOGETHER",
) =>
  `<div class="page-heading"><div><div class="eyebrow">${eyebrow}</div><h1>${title}</h1><p>${description}</p></div><div class="heading-actions">${actions}</div></div>`;
export const empty = (title, description, action = "", symbol = "game") =>
  `<div class="empty-state ui-empty">${icon(symbol, 28)}<h3>${title}</h3><p>${description}</p>${action}</div>`;
export const field = (name, label, type = "text", value = "", extra = "") =>
  `<div class="field"><label for="field-${name}">${label}</label><input id="field-${name}" name="${name}" type="${type}" value="${e(value)}" ${extra}></div>`;
const avatar = (name) =>
  `<span class="avatar mo">${e((name || "?").slice(0, 1))}</span>`;
export const statusLabel = (value) =>
  ({
    CONNECTED: "已连接",
    CONNECTING: "连接中",
    RETRYING: "重试中",
    FAILED: "连接失败",
    REVOKED: "已撤销",
    UNKNOWN: "状态未知",
    DISCONNECTED: "未连接",
  })[value] || "未连接";
const chip = (name, value) =>
  `<span class="status-chip" data-connection="${value === "CONNECTED" ? "connected" : value === "FAILED" ? "failed" : "pending"}">${icon(name === "语音" ? "headphones" : "network", 14)} ${name} · ${statusLabel(value)}</span>`;
export function serviceIndicator(s) {
  if (!s.userId) return "织界桌面端";
  return s.service === "RETRYING"
    ? `服务暂不可用 · 自动重连中 ${button("重试", "refresh", "ghost small", 'aria-label="重试服务连接"')}`
    : `服务 · ${statusLabel(s.service)}`;
}
export function titlebar(s) {
  return `<header class="titlebar desktop-titlebar"><div class="titlebar-drag" data-window-drag>${brand()}<span class="window-title">和喜欢的人，玩同一个世界</span></div><div class="top-actions"><span class="connection-pill" role="status" data-service="${e(s.service)}">${serviceIndicator(s)}</span><div class="window-controls"><button data-window="minimize" aria-label="最小化" title="最小化"><svg width="12" height="12" viewBox="0 0 12 12"><path d="M1 6h10" stroke="currentColor"/></svg></button><button data-window="maximize" aria-label="最大化" title="最大化"><svg width="12" height="12" viewBox="0 0 12 12"><rect x="1.5" y="1.5" width="9" height="9" fill="none" stroke="currentColor"/></svg></button><button data-window="close" aria-label="关闭" title="关闭">${icon("close", 16)}</button></div></div></header>`;
}
export function authView(s) {
  const mode = s.authMode;
  const titles = {
    login: ["和队友一起开局", "登录织界，回到你们的集合地。", "进入玩家大厅"],
    register: ["创建你的账号", "和熟悉的朋友，开启下一局。", "发送验证码"],
    verify: ["验证邮箱", "输入邮件中的验证码。", "验证邮箱"],
    forgot: ["找回密码", "我们会向你的邮箱发送重置步骤。", "发送重置邮件"],
    reset: ["设置新密码", "使用邮件验证码完成密码重置。", "重置密码"],
  };
  const [title, desc, submit] = titles[mode];
  return `<main class="auth desktop-auth"><section class="auth-card">${brand()}<h1>${title}</h1><p>${desc}</p><form data-form="auth">${field("email", "邮箱", "email", s.email || "", 'required autocomplete="username" maxlength="254"')}${["verify", "reset"].includes(mode) ? field("code", "邮箱验证码", "text", "", 'required inputmode="numeric" autocomplete="one-time-code" maxlength="12"') : ""}${["login", "register", "reset"].includes(mode) ? field("password", mode === "reset" ? "新密码" : "密码", "password", "", `required minlength="12" maxlength="128" autocomplete="${mode === "login" ? "current-password" : "new-password"}"`) : ""}<p id="feedback" class="inline-feedback" role="alert">${e(s.error)}</p><button class="button primary auth-submit" ${s.busy || !s.desktop ? "disabled" : ""}>${s.busy ? "请稍候…" : submit}</button></form><div class="auth-links">${mode !== "login" ? button("返回登录", "auth-login", "ghost small") : button("注册账号", "auth-register", "ghost small")}${button("找回密码", "auth-forgot", "ghost small")}${mode === "login" ? button("验证邮箱", "auth-verify", "ghost small") : ""}</div>${!s.desktop ? '<p class="runtime-note">请在织界桌面程序中使用账号服务。</p>' : ""}</section></main>`;
}
function rail(s) {
  const nav = (view, name, label) =>
    `<button class="nav-item ${s.view === view || (view === "groups" && s.view === "group") ? "active" : ""}" data-view="${view}" aria-label="${label}" ${s.busy ? "disabled" : ""}>${icon(name)}<span>${label}</span></button>`;
  return `<aside class="rail"><nav aria-label="主导航">${nav("lobby", "home", "大厅")}${nav("friends", "users", "好友")}${nav("groups", "messages", "群组")}<div class="rail-separator"></div><button class="nav-item" data-action="create-room" aria-label="创建房间">${icon("plus")}<span>创建</span></button></nav><div class="rail-bottom"><button class="nav-item" data-action="help" aria-label="联机帮助">${icon("help")}</button>${nav("settings", "settings", "设置")}<button class="mini-me" data-action="account" aria-label="我的账号">${e((s.email || "我")[0])}</button></div></aside>`;
}
function roomEntry(state, room, withArrow = false) {
  const current = state.activity && state.roomId === room.id;
  const paused = room.openState !== "OPEN";
  const label = current ? "回到房间" : paused ? "暂停加入" : "加入房间";
  return button(label + (withArrow ? ` ${icon("arrow", 14)}` : ""), current ? "open-room" : "join-room", "small",
    `data-id="${e(room.id)}" ${state.busy || (!current && paused) ? "disabled" : ""}`);
}
function roomCard(s, r) {
  const g = s.groups.find((g) => g.id === r.groupId);
  return `<article class="room-card"><div class="room-cover cubes"><span class="game-chip">${e(r.gameName || "游戏房间")}</span><span class="room-capacity">最多 12 人</span></div><div class="room-body"><div class="room-provenance">${icon("messages", 13)} ${e(g?.name)}</div><div class="room-title-row"><h3>${e(r.name)}</h3></div><p class="room-description">${e(r.description || "约好下一局，随时来集合。")}</p><div class="room-presence"><span class="availability ${r.openState === "OPEN" ? "available" : "unavailable"}"><i></i>${r.openState === "OPEN" ? "开放加入" : "暂停加入"}</span></div><div class="room-footer"><span class="room-waiting">${r.currentActivityId ? "房间活动中" : "等待开启活动"}</span>${roomEntry(s, r, true)}</div></div></article>`;
}
const friendRow = (f) =>
  `<div class="friend-row">${avatar(f.email)}<div class="friend-copy"><strong>${e(f.email)}</strong><small>${f.presence === "ONLINE" ? "在线" : "离线"}</small></div>${button("删除好友", "delete-friend", "ghost small", `data-id="${e(f.userId)}"`)}</div>`;
function aside(s) {
  const online = s.friends.filter((f) => f.presence === "ONLINE");
  return `<aside class="right-panel"><div class="panel-title"><h2>在线好友</h2><span class="count-badge">${online.length} 在线</span></div>${online.map((f) => `<div class="friend-item">${avatar(f.email)}<span class="friend-copy"><strong>${e(f.email)}</strong><small>在线</small></span></div>`).join("") || '<div class="empty-state">暂无在线好友</div>'}${button(`查看全部好友 ${icon("arrow", 14)}`, "friends", "ghost")}<div class="aside-divider"></div><div class="tip-card"><strong>联机小贴士</strong><p>进入房间后，在「联机」页查看当前网络状态，再回到游戏中连接。</p></div></aside>`;
}
function lobby(s) {
  const current = s.rooms.find((r) => r.id === s.roomId);
  return `<div class="content-grid"><main class="main-scroll">${heading("玩家大厅", "语音、聊天与虚拟局域网，和队友一起就绪。", button("邀请码加入", "invitation", "") + button(`${icon("plus", 16)} 创建房间`, "create-room", "primary"))}${s.activity ? `<section class="hero current-session"><div class="hero-copy"><span class="live-label">${icon("headphones", 15)} 当前房间</span><h2>${e(current?.name || "当前活动")}</h2><div class="session-channels">${chip("语音", s.voice?.state)}${chip("联机", s.activity.network)}</div></div><div class="session-return">${button(`回到房间 ${icon("arrow", 16)}`, "open-room", "primary", `data-id="${e(s.roomId)}"`)}</div></section>` : ""}<div class="section-head"><div><h2>群内房间 <span class="section-count">${s.rooms.length}</span></h2><p>来自你已加入的群组</p></div>${button("刷新", "refresh", "ghost small")}</div><div class="room-grid">${s.rooms.map((r) => roomCard(s, r)).join("") || empty("还没有可加入的房间", "先创建群组，或接受朋友的群邀请。", button(`前往群组 ${icon("arrow", 15)}`, "groups"))}</div></main>${aside(s)}</div>`;
}
function friends(s) {
  const list = s.friends.filter(
    (f) =>
      (s.friendFilter !== "online" || f.presence === "ONLINE") &&
      f.email.toLowerCase().includes(s.friendQuery.toLowerCase()),
  );
  return `<main class="main-scroll"><div class="center-panel">${heading("好友", "看看谁在线，邀请好友一起玩。", button(`${icon("plus", 16)} 添加好友`, "add-friend", "primary"), "保持联系 / FRIENDS")}<div class="filter-row"><input class="search-field" id="friend-search" placeholder="搜索好友邮箱" aria-label="搜索好友" value="${e(s.friendQuery)}"><div class="segmented">${button("在线", "friends-online", s.friendFilter === "online" ? "active" : "")}${button(`全部 ${s.friends.length}`, "friends-all", s.friendFilter === "all" ? "active" : "")}${button("好友申请", "friend-requests")}</div></div><div class="friend-list">${list.map(friendRow).join("") || empty("暂无好友", "添加好友，约好下一局。", button("添加好友", "add-friend"), "users")}</div></div></main>`;
}
function groups(s) {
  return `<main class="main-scroll"><div class="center-panel">${heading("群组", "熟悉的朋友，固定的集合地。", button(`${icon("plus", 16)} 创建群组`, "create-group", "primary"), "私密小队 / YOUR CREWS")}<div class="group-list-toolbar"><h2>我的群组</h2>${button("群邀请", "invitation", "ghost small group-invites")}</div><div class="group-grid">${s.groups.map((g) => `<button class="group-card" data-action="open-group" data-id="${e(g.id)}"><div class="group-card-top"><span class="group-icon">${groupAvatarImage(g.avatar) || e(g.name[0])}</span><span class="group-members-label">${s.rooms.filter((r) => r.groupId === g.id).length} 个游戏房间</span></div><h3>${e(g.name)}</h3><p>${e(g.description || "约好下一局，随时来集合。")}</p><div class="group-card-foot"><span>进入群组</span>${icon("arrow", 14)}</div></button>`).join("") || empty("暂无群组", "创建自己的小队，或接受朋友发来的邀请。", button("创建群组", "create-group"), "messages")}</div></div></main>`;
}
export function messageDeleteControl(s) {
  const role = s.groupMembers.find(member => member.userId === s.userId)?.role;
  const canModerate = role === "OWNER" || role === "ADMIN";
  return message => !message.deleted && (message.authorId === s.userId || canModerate) ? `<button type="button" class="icon-button" data-action="delete-message" data-id="${e(message.id)}" aria-label="删除消息">${icon("close", 14)}</button>` : "";
}
function chat(s) {
  const deleteControl = messageDeleteControl(s);
  return `<div class="chat-scroll group-chat-body">${s.messages.length === 50 ? button("更早的消息", "message-history", "ghost small", `data-id="${e(s.messages[0].id)}"`) : ""}${s.messages.map((m) => `<div class="message ${m.authorId === s.userId ? "own" : ""}">${avatar(m.authorId === s.userId ? "我" : m.authorId)}<div class="message-content"><div class="message-meta"><strong>${m.authorId === s.userId ? "我" : e(m.authorId)}</strong><time>${e(new Date(m.createdAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }))}</time>${deleteControl(m)}</div><div class="message-text">${e(m.deleted ? "此消息已被删除" : m.body)}</div></div></div>`).join("") || '<div class="empty-state">还没有消息，和队友打个招呼吧。</div>'}</div><form class="chat-compose" data-form="message"><input name="body" id="message-input" value="${e(s.drafts[s.groupId] || "")}" required maxlength="4000" autocomplete="off" aria-label="群文字消息" placeholder="发消息到群组"><button class="button primary" ${s.busy ? "disabled" : ""}>发送</button></form>`;
}
function groupView(s) {
  const g = s.groups.find((g) => g.id === s.groupId);
  if (!g) return groups(s);
  return `<div class="group-chat-layout"><main class="group-chat"><div class="group-chat-head"><div><h1>${e(g.name)}</h1><p>${e(g.description)}</p></div>${button("群组管理", "manage-group", "ghost small")}</div>${chat(s)}</main><aside class="right-panel"><div class="panel-title"><h2>群内房间</h2></div>${
    s.rooms
      .filter((r) => r.groupId === g.id)
      .map(
        (r) =>
          `<div class="group-room-item"><strong>${e(r.name)}</strong>${r.openState === "PAUSED" && !(s.activity && s.roomId === r.id) && roomPermissions(s, r).manage
            ? button("重新开放", "reopen-room", "small", `data-id="${e(r.id)}" ${s.busy ? "disabled" : ""}`) : roomEntry(s, r)}</div>`,
      )
      .join("") || '<div class="empty-state">暂无房间</div>'
  }${button("创建房间", "create-room", "ghost")}<div class="aside-divider"></div><div class="panel-title"><h2>群组成员</h2></div>${s.groupMembers.map((m) => `<div class="friend-item">${avatar(m.userId)}<span class="friend-copy"><strong>${e(m.userId === s.userId ? "我" : m.userId)}</strong><small>${{ OWNER: "群主", ADMIN: "管理员", MEMBER: "成员" }[m.role] || e(m.role)}</small><span class="member-presence">${m.presence === "ONLINE" ? "在线" : m.presence === "OFFLINE" ? "离线" : "状态未知"}</span></span></div>`).join("")}</aside></div>`;
}
export function roomPermissions(s, room) {
  const role = s.groupMembers.find(member => member.userId === s.userId)?.role;
  return { role, assign: role === "OWNER", manage: !!role && (role === "OWNER" || role === "ADMIN" || room.ownerUserId === s.userId) };
}
function activityMemberRow(s, room, member) {
  const permissions = roomPermissions(s, room);
  const targetRole = s.groupMembers.find(item => item.userId === member.userId)?.role;
  const canKick = member.userId !== s.userId && targetRole && targetRole !== "OWNER"
    && (permissions.role === "OWNER" || (targetRole === "MEMBER" && permissions.manage));
  return `<div class="friend-item">${avatar(member.userId)}<div class="friend-copy"><strong>${member.userId === s.userId ? "我" : e(member.userId)}</strong><small>${e(member.state)}${room.ownerUserId === member.userId ? " · 房主" : ""} · ${memberVoice(s, member)}</small>${member.userId !== s.userId ? `<label>音量 <input type="range" min="0" max="2" step="0.05" value="${s.voice?.memberVolumes?.[member.userId] ?? 1}" data-member-volume="${e(member.userId)}" aria-label="${e(member.userId)} 的音量"></label>` : ""}${canKick ? button("踢出活动", "kick-activity-member", "danger small", `data-id="${e(member.userId)}" data-generation="${e(member.memberGeneration)}" data-activity="${e(s.activity.activityId)}"`) : ""}</div></div>`;
}
function memberVoice(s, member) {
  if (s.voice?.state !== "CONNECTED") return "语音状态暂不可用";
  const media = s.voice.members?.find(item => item.userId === member.userId && item.memberGeneration === member.memberGeneration);
  if (!media) return "语音状态暂不可用";
  if (media.muted) return "已静音";
  if (media.speaking) return "正在说话";
  return media.subscribed ? "语音已连接" : "语音连接中";
}
function room(s) {
  const r = s.rooms.find((r) => r.id === s.roomId);
  if (!r || !s.activity) return lobby(s);
  const status = s.network?.status;
  const networkFault = status?.activityId === s.activity.activityId && status?.state === s.activity.network
    && ["FAILED", "REVOKED"].includes(s.activity.network) && status.errorCode
    ? networkErrorText(status.errorCode) || "联机失败，请重试；若仍失败，请查看辅助服务诊断。" : null;
  const side =
    s.roomTab === "chat"
      ? chat(s)
      : s.roomTab === "members"
        ? `<div class="network-panel">${s.members.map(member => activityMemberRow(s, r, member)).join("")}${button("刷新成员", "refresh-room-members", "ghost small")}</div>`
        : `<div class="network-panel"><div class="network-hero">${chip("联机", s.activity.network)}<h3>${statusLabel(s.activity.network)}</h3><p>虚拟网络建立后，才能通过成员地址连接游戏。</p></div>${s.activity.network === "CONNECTED" ? `<div class="member-ip-heading"><strong>我的虚拟地址</strong></div><div class="member-ip-row"><span class="ip-address">${e(s.activity.virtualIpv4 || "暂不可用")}</span></div>${(s.network?.peers || []).map(peer => `<div class="member-ip-row"><span>${e(peer.userId)}</span><span class="ip-address">${e(peer.virtualIpv4)}</span></div>`).join("")}` : ""}<p class="runtime-note">${s.activity.network === "CONNECTED" ? "通过队友的虚拟地址连接游戏。" : "当前联机功能尚未就绪，暂时无法通过虚拟地址连接游戏。"}</p>${networkFault ? `<p class="runtime-note">${e(networkFault)}</p>` : ""}${button("重试联机", "retry-network", "ghost small")}</div>`;
  return `<div class="room-layout"><main class="room-stage"><div class="room-top"><div class="room-heading"><button class="back-button" data-view="lobby" aria-label="返回大厅">${icon("back", 17)}</button><div><h1>${e(r.name)}</h1><p>${e(r.gameName || "游戏房间")} · ${e(s.groups.find((g) => g.id === r.groupId)?.name)}</p></div></div><div class="room-flags">${chip("语音", s.voice?.state)}${chip("联机", s.activity.network)}${button("房间设置", "manage-room", "ghost small")}</div></div><div class="room-alert">${e(s.voice?.failureCode ? "语音服务暂不可用，可单独重试。" : "")}</div><div class="stage-heading"><div><span class="stage-label">语音房间</span><h2>队友已就位</h2></div><span>${s.members.length} / 12 人</span></div><div class="party-grid">${s.members.map((m) => `<article class="player-tile">${avatar(m.userId === s.userId ? "我" : m.userId)}<div class="player-name"><div><strong>${m.userId === s.userId ? "我" : e(m.userId)}</strong><small>${memberVoice(s, m)}</small></div>${icon("mic", 15)}</div></article>`).join("")}<article class="player-tile empty-player"><button data-action="invite-member">${icon("plus", 24)}<span>邀请队友</span></button></article></div></main><aside class="room-side"><div class="side-tabs">${[
    ["chat", "群聊"],
    ["network", "联机"],
    ["members", `成员 ${s.members.length}`],
  ]
    .map(
      ([id, name]) =>
        `<button class="side-tab ${s.roomTab === id ? "active" : ""}" data-tab="${id}">${name}</button>`,
    )
    .join("")}</div>${side}</aside></div>`;
}
function audioDeviceSelect(s, output = false) {
  const kind = output ? "output" : "input", label = output ? "输出设备" : "输入设备";
  const devices = s.voice?.state === "CONNECTED" ? s.audioDevices?.[output ? "outputs" : "inputs"] || [] : [];
  const selected = s.voice?.[output ? "outputDevice" : "inputDevice"];
  const missing = selected != null && !devices.some(device => device.id === selected);
  const failure = s.voice?.state === "CONNECTED" && s.voice?.[output ? "outputFailureCode" : "inputFailureCode"];
  return `<label for="audio-${kind}">${label}</label><select id="audio-${kind}" data-audio-device="${kind}" ${!devices.length || s.busy ? "disabled" : ""}><option value="" disabled ${selected == null || missing ? "selected" : ""}>${missing ? "所选设备已不可用，请重新选择" : devices.length ? "当前使用系统默认设备" : "暂无可用设备，请连接语音后刷新"}</option>${devices.map(device => `<option value="${e(device.id)}" ${device.id === selected ? "selected" : ""}>${e(device.name || "未命名设备")}</option>`).join("")}</select>${failure ? `<p role="status">${label}${failure.endsWith("DEVICE_REMOVED") ? "已不可用" : "切换失败"}，请刷新并重新选择；设备恢复后可重试。</p>` : ""}`;
}
function settings(s) {
  const tab = s.settingsTab;
  const body =
    tab === "voice"
      ? `<h2>语音设置</h2><div class="settings-card"><div class="setting-row"><div>${audioDeviceSelect(s)}</div>${button(s.voice?.muted ? "取消静音" : "静音", "mute")}</div><div class="setting-row"><div>${audioDeviceSelect(s, true)}</div>${button(s.voice?.receiveEnabled === false ? "恢复接收" : "关闭接收", "receive")}</div>${button("刷新设备", "refresh-audio")}${microphoneTest(s)}${outputTest(s)}<div class="setting-row"><label for="voice-volume">总音量</label><input id="voice-volume" type="range" min="0" max="2" step="0.05" value="${s.voice?.masterVolume ?? 1}" aria-label="总音量"></div><p class="runtime-note">${s.voice?.state === "CONNECTED" ? "音频设置来自当前语音连接。" : "入房连接语音后，设备状态会在这里更新。"}</p></div>`
      : tab === "account"
        ? `<h2>账号</h2><div class="settings-card"><div class="setting-row"><div><strong>${e(s.email)}</strong><p>账号 ID：${e(s.userId)}</p></div></div>${button(`${icon("logout", 16)} 退出登录`, "logout", "danger")}</div>`
        : `<h2>帮助与支持</h2><div class="settings-card"><h3>织界 0.1.0</h3><p>语音与虚拟网络独立连接，一项异常时可单独重试。</p><p>Windows、macOS、Linux 的安装更新、真实语音和虚拟网络能力仍需实机验证。</p>${button("联机帮助", "help")}</div>`;
  return `<main class="main-scroll"><div class="center-panel">${heading("设置", "调整你的语音与账号偏好。", "", "你的偏好 / PREFERENCES")}<div class="settings-layout"><nav class="settings-menu" aria-label="设置分类">${[
    ["voice", "语音"],
    ["account", "账号"],
    ["support", "支持"],
  ]
    .map(
      ([id, name]) =>
        `<button class="${tab === id ? "active" : ""}" data-settings="${id}">${name}</button>`,
    )
    .join(
      "",
    )}</nav><section class="settings-content">${body}</section></div></div></main>`;
}
function dock(s) {
  if (!s.activity) return "";
  return `<div class="voice-dock"><button class="voice-now" data-action="open-room" data-id="${e(s.roomId)}"><span class="voice-room-icon">${icon("headphones")}</span><span><strong>${e(s.rooms.find((r) => r.id === s.roomId)?.name || "当前活动")}</strong><small>${statusLabel(s.voice?.state)} · ${statusLabel(s.activity.network)}</small></span></button><div class="voice-controls"><button class="voice-button ${s.voice?.muted ? "off" : ""}" data-action="mute" aria-pressed="${!!s.voice?.muted}">${icon("mic")}<span>${s.voice?.muted ? "已静音" : "麦克风"}</span></button><button class="voice-button" data-action="receive" aria-label="${s.voice?.receiveEnabled === false ? "恢复接收" : "关闭接收"}">${icon("headphones")}</button><button class="voice-button" data-action="retry-voice" aria-label="重试语音">重试</button><button class="voice-button leave" data-action="leave" aria-label="离开房间">${icon("logout")}</button></div><div class="voice-right">${chip("联机", s.activity.network)}</div></div>`;
}
export function renderApp(s) {
  const content = s.userId
    ? (
        { lobby, friends, groups, group: groupView, room, settings }[s.view] ||
        lobby
      )(s)
    : authView(s);
  return `<div class="app desktop-app ${s.userId ? "" : "unauthenticated"}">${titlebar(s)}${s.userId ? `<div class="workspace">${rail(s)}<div class="content-shell ${s.activity ? "" : "no-voice"}">${s.error ? `<div class="service-error" role="alert">${e(s.error)} ${button("重试", "refresh", "small")}</div>` : ""}${content}${dock(s)}</div></div>` : content}</div>`;
}
