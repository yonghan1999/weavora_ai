import { networkErrorText } from "./src/network-errors.js";
import { CoreBridge } from "./src/index.js";
import { avatarField, readGroupAvatar } from "./src/group-avatar.js";
import {
  renderApp,
  connectionDiagnostics,
  roomAddresses,
  serviceIndicator,
  messageDeleteControl,
  escapeHtml as e,
  field,
  button,
  empty,
  roomPermissions,
} from "./src/views.js";

const root = document.getElementById("app"),
  modalRoot = document.getElementById("modal");
const desktop = typeof globalThis.__TAURI__?.core?.invoke === "function";
const bridge = desktop ? new CoreBridge() : null;
const deviceId = crypto.randomUUID();
const s = {
  desktop,
  userId: null,
  email: "",
  activity: null,
  voice: null,
  audioDevices: null,
  network: null,
  view: "lobby",
  roomId: null,
  groupId: null,
  groups: [],
  rooms: [],
  friends: [],
  members: [],
  groupMembers: [],
  messages: [],
  drafts: {},
  authMode: "login",
  roomTab: "chat",
  settingsTab: "voice",
  friendFilter: "all",
  friendQuery: "",
  busy: false,
  error: "",
};
let epoch = 0,
  returnFocus,
  dialogSubmit,
  modalRefresh = null;
// 串行读取媒体状态；快照只应用于发起读取时的账号和租约。
let runtimePolling = false, runtimeDirty = false;
async function refreshRuntime() {
  if (!bridge || !s.userId || runtimePolling || s.busy) return;
  runtimePolling = true;
  const userId = s.userId, activity = s.activity, current = epoch;
  try {
    const snapshot = await bridge.snapshot();
    if (s.busy || epoch !== current || s.userId !== userId || s.activity !== activity) return;
    if (!snapshot.userId) {
      clearSession();
      Object.assign(s, snapshot, { error: "登录状态已失效，请重新登录。" });
      epoch++;
      closeDialog();
      render();
      return;
    }
    if (!snapshot.activity && activity) {
      Object.assign(s, snapshot, {voice: null, network: null, roomId: null, members: [], view: "lobby"});
      epoch++;
      closeDialog();
      render();
      return;
    }
    if (s.service !== snapshot.service) {
      s.service = snapshot.service;
      const indicator = root.querySelector(".connection-pill");
      indicator.dataset.service = s.service;
      indicator.innerHTML = serviceIndicator(s);
    }
    if (!activity || snapshot.activity?.leaseId !== activity.leaseId) return;
    const [voiceRead, networkRead] = await Promise.allSettled([bridge.voiceSnapshot(), bridge.networkSnapshot()]);
    if (s.busy || epoch !== current || s.userId !== userId || s.activity !== activity) return;
    const voice = voiceRead.status === "fulfilled" ? voiceRead.value : { state: "UNKNOWN" };
    const network = networkRead.status === "fulfilled" ? networkRead.value : null;
    if (!network) snapshot.activity.network = "UNKNOWN";
    else if (network.status?.activityId === snapshot.activity.activityId) snapshot.activity.network = network.status.state;
    const changed = JSON.stringify(s.voice) !== JSON.stringify(voice)
      || JSON.stringify(s.network) !== JSON.stringify(network)
      || JSON.stringify(s.activity) !== JSON.stringify(snapshot.activity);
    Object.assign(s, snapshot, {voice, network});
    runtimeDirty ||= changed;
    // 不在编辑输入框或模态框时重建页面，避免丢失用户输入。
    if (runtimeDirty && !modalRoot.firstChild && !document.activeElement?.matches("input,textarea:not([readonly]),select")) render();
  } catch {
    // 控制面续期与失权由桌面后台处理；轮询失败保留上次已知状态。
  } finally {
    runtimePolling = false;
  }
}
if (bridge) setInterval(refreshRuntime, 1000);
let productDirty = false, modalDirty = false, productRefreshing = false, productVersion = 0;
let dirtyMessageGroup = null;
let syncRetryAt = 0, syncRetryDelay = 0;
function resetSyncRetry() {
  syncRetryAt = syncRetryDelay = 0;
}
function deferLimitedSync(error) {
  if ((error?.message || error) !== "RATE_LIMITED") return;
  syncRetryDelay = Math.min(8000, syncRetryDelay ? syncRetryDelay * 2 : 2000);
  syncRetryAt = performance.now() + syncRetryDelay + Math.random() * 1000;
}
bridge?.onProductChanged(payload => {
  if (payload?.userId === s.userId) {
    if (payload.messageGroupId) {
      if (payload.messageGroupId === s.groupId && (s.view === "group" || s.view === "room")) {
        dirtyMessageGroup = s.groupId;
        if (modalRoot.querySelector("[data-message-history]")) modalDirty = true;
      }
      return;
    }
    productDirty = modalDirty = true;
    productVersion++;
  }
});
async function refreshProduct() {
  if (dirtyMessageGroup !== s.groupId || (s.view !== "group" && s.view !== "room")) dirtyMessageGroup = null;
  if ((!productDirty && !dirtyMessageGroup) || productRefreshing || s.busy || !s.userId || performance.now() < syncRetryAt) return;
  if (modalRoot.firstChild) {
    if (modalDirty && (modalRefresh || (s.groupId && (s.view === "group" || s.view === "room")))) await refreshOpenDialog();
    return;
  }
  // 仅聊天输入支持恢复光标；其他编辑结束后再刷新。
  if (document.activeElement?.matches("input:not(#message-input),textarea:not(#message-input),select")) return;
  const fullRefresh = productDirty, messageGroup = dirtyMessageGroup;
  productDirty = modalDirty = false; dirtyMessageGroup = null; productRefreshing = true;
  const current = epoch, userId = s.userId;
  try {
    capture();
    const applicable = () => current === epoch && userId === s.userId && !s.busy && !modalRoot.firstChild;
    if (fullRefresh) await refresh(current, applicable);
    else {
      const messages = await request("messages", messageGroup);
      if (applicable() && s.groupId === messageGroup) s.messages = [...messages].reverse();
    }
    if (applicable()) resetSyncRetry();
    if (current === epoch && userId === s.userId && !s.busy && !modalRoot.firstChild
        && !document.activeElement?.matches("input:not(#message-input),textarea:not(#message-input),select")) {
      capture();
      render(true);
    }
    else if (fullRefresh) productDirty = true;
    else if (s.groupId === messageGroup && s.userId === userId) dirtyMessageGroup = messageGroup;
  } catch (error) {
    if (current === epoch && userId === s.userId) {
      deferLimitedSync(error);
      if (fullRefresh) productDirty = true;
      else if (s.groupId === messageGroup) dirtyMessageGroup = messageGroup;
    }
  } finally { productRefreshing = false; }
}
if (bridge) setInterval(refreshProduct, 500);
async function refreshOpenDialog() {
  const list = modalRefresh, current = epoch, userId = s.userId, groupId = s.groupId, opened = modalRoot.firstChild;
  const applicable = () => list === modalRefresh && opened === modalRoot.firstChild && current === epoch && userId === s.userId && groupId === s.groupId && !s.busy;
  const panel = modalRoot.querySelector(".modal"), scroll = panel?.scrollTop;
  const focused = modalRoot.contains(document.activeElement) ? document.activeElement : null;
  const action = focused?.dataset.action, id = focused?.dataset.id;
  modalDirty = false; productRefreshing = true;
  try {
    if (!list) {
      const groups = await bridge.groups();
      if (!applicable()) { modalDirty = true; return; }
      if (!groups.some(group => group.id === groupId)) {
        s.groups = groups;
        s.rooms = s.rooms.filter(room => room.groupId !== groupId);
        clearGroupView();
        closeDialog();
        render();
      }
      resetSyncRetry();
      return;
    }
    if (!await list.reload(applicable)) { modalDirty = true; return; }
    if (current === epoch && userId === s.userId) resetSyncRetry();
    if (action) {
      const selector = `[data-action="${CSS.escape(action)}"]${id ? `[data-id="${CSS.escape(id)}"]` : ""}`;
      modalRoot.querySelector(selector)?.focus({preventScroll: true});
    }
    if (scroll !== undefined && modalRoot.querySelector(".modal")) modalRoot.querySelector(".modal").scrollTop = scroll;
  } catch (error) {
    modalDirty = true;
    if (applicable()) {
      deferLimitedSync(error);
      // 失权时移除已不可读取的列表；暂时失败保留列表供手动重试。
      if (listAccessLost(error)) closeDialog();
      else document.getElementById("dialog-error").textContent = errorText(error);
    }
  } finally { productRefreshing = false; }
}
const errors = {
  RATE_LIMITED: "请求过于频繁，请稍后重试。",
  FRIEND_REQUEST_CLOSED: "此申请已处理，请刷新申请列表。",
  FRIEND_REQUEST_NOT_FOUND: "此申请已不存在，请刷新申请列表。",
  FRIEND_REQUEST_PENDING: "你们之间已有待处理的好友申请。",
  FRIENDSHIP_EXISTS: "你们已经是好友。",
  SERVICE_UNAVAILABLE: "无法连接服务，请稍后重试。",
  LOGOUT_CLEANUP_PENDING: "已退出本机账号；远端退出或联机清理尚未确认，请检查连接状态。",
  TAURI_UNAVAILABLE: "请在织界桌面程序中使用账号服务。",
  INVALID_CREDENTIALS: "邮箱或密码不正确。",
  EMAIL_VERIFICATION_REQUIRED: "请先验证邮箱。",
  EMAIL_DELIVERY_UNAVAILABLE: "邮件服务尚未接入，暂时无法完成此操作。",
  AUTH_REQUIRED: "邮箱或密码错误、账号尚未验证，或登录已失效。",
  ACCOUNT_ALREADY_EXISTS: "此邮箱已注册，请登录或找回密码。",
  WEAK_PASSWORD: "密码至少需要 12 个字符。",
  CURRENT_GRANT_REQUIRED: "当前没有执行此操作的权限。",
  RESOURCE_NOT_AVAILABLE: "此资源已不可用或你已失去访问权限，请返回列表刷新。",
  ROLE_CHANGE_NOT_ALLOWED: "不能直接修改群主角色，请使用群主转让流程。",
  OWNER_MUST_TRANSFER_OR_DISSOLVE: "群主退出前需要先完成转让或解散群组。",
  OWNER_TRANSFER_PENDING: "已有待接受的转让，请先查看或撤销。",
  OWNER_TRANSFER_INVALID: "转让条件已变化，请刷新群组与转让记录。",
  OWNER_TRANSFER_NOT_AVAILABLE: "此转让已结束或不可用，请刷新转让记录。",
  INVITATION_NOT_AVAILABLE: "此邀请已撤销或失效，请刷新邀请列表。",
  GROUP_INVITATION_PENDING: "已有发给该账号的待接受邀请，请在邀请管理中查看。",
  GROUP_MEMBER_EXISTS: "该账号已是群成员。",
  GROUP_AVATAR_INVALID: "头像未保存，请选择 2 MiB 以内的有效 PNG 或 JPEG 图片。",
  ACTIVITY_MEMBER_CHANGED: "成员已离开或重新加入，请刷新成员列表后重试。",
  ACTIVITY_NOT_ACTIVE: "房间当前没有活动，请等管理者开启后再加入。",
  VOICE_CAPACITY_LIMITED: "语音服务尚未接入或暂不可用。",
  ROOM_PASSWORD_REQUIRED: "请输入房间密码。",
  ROOM_REOPEN_OUTSIDE_REQUIRED: "请先离开该房间，再在群页重新开放。",
  ROOM_PASSWORD_INVALID: "房间密码不正确。",
  CAPACITY_FULL: "房间已满，请稍后再试。",
  ROOM_PAUSED: "房间已暂停加入，请等管理者重新开放。",
  RESOURCE_NOT_ALLOWED: "此操作尚未接入。",
  AUDIO_PERMISSION_DENIED: "麦克风访问已被系统拒绝。请在 Windows 设置 → 隐私和安全性 → 麦克风中允许桌面应用访问，然后重试。",
  AUDIO_CAPTURE_FAILED: "未能启动麦克风，请检查系统录音权限、设备连接和占用情况后重试。",
  AUDIO_INPUT_SELECTION_REQUIRED: "请先选择输入设备，再检测麦克风。",
  AUDIO_CONNECTION_BUSY: "语音正在连接，请稍后再切换设备。",
  AUDIO_DEVICE_UNAVAILABLE: "无法读取本机音频设备，请检查系统声音设置后刷新设备。",
  AUDIO_TEST_NO_FRAMES: "未收到麦克风采样，请检查录音权限和输入设备后重试。",
  AUDIO_TEST_CAPTURE_INTERRUPTED: "麦克风采集中断，请检查输入设备后重试。",
  AUDIO_TEST_SUPERSEDED: "语音连接已变化，请在当前连接重新检测。",
  VOICE_NOT_CONNECTED: "请先连接房间语音。",
  AUDIO_INPUT_SWITCH_FAILED: "麦克风切换失败，请刷新设备后重试。",
  AUDIO_OUTPUT_SWITCH_FAILED: "输出设备切换失败，请刷新设备后重试。",
  AUDIO_INPUT_DEVICE_REMOVED: "所选麦克风已不可用，请刷新并重新选择设备。",
  AUDIO_OUTPUT_DEVICE_REMOVED: "所选输出设备已不可用，请刷新并重新选择设备。",
  AUDIO_OUTPUT_SELECTION_REQUIRED: "请先选择输出设备，再试听。",
  AUDIO_OUTPUT_TEST_FAILED: "输出测试未完成，请检查设备和系统声音设置后重试。",
  AUDIO_TEST_BUSY: "另一个音频测试正在进行，请稍后重试。",
};
const errorText = (error) => {
  const code = String(error?.message || error);
  return networkErrorText(code) || (Object.hasOwn(errors, code) ? errors[code] : "操作未完成，请检查输入或稍后重试。");
};
function render(preserveComposer = false) {
  runtimeDirty = false;
  const active=document.activeElement;
  const composer = preserveComposer && active?.id === "message-input" ? {
    value: active.value, start: active.selectionStart, end: active.selectionEnd,
  } : null;
  const focusKey=root.contains(active) ? ["data-action","data-view","data-settings"].find(key=>active.hasAttribute(key)) : null;
  const focusValue=focusKey ? active.getAttribute(focusKey) : null;
  const scroll=root.querySelector(".chat-scroll")?.scrollTop;
  root.innerHTML = renderApp(s);
  if (composer && document.getElementById("message-input")) {
    const input = document.getElementById("message-input");
    input.value = composer.value;
    input.focus({preventScroll: true});
    input.setSelectionRange(composer.start, composer.end);
  }
  if(focusKey)root.querySelector(`[${focusKey}="${CSS.escape(focusValue)}"]`)?.focus();
  if(scroll !== undefined && root.querySelector(".chat-scroll"))root.querySelector(".chat-scroll").scrollTop=scroll;
}
let toastTimer;
function notify(message) {
  document.getElementById("toast").textContent = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    document.getElementById("toast").textContent = "";
  }, 4000);
}
function restoreDialogFocus() {
  if (returnFocus?.isConnected) returnFocus.focus();
  else root.querySelector(`[data-view="${s.view}"]`)?.focus();
}
function closeDialog() {
  modalRefresh = null;
  modalRoot.replaceChildren();
  root.inert = s.busy;
  dialogSubmit = null;
  if (!root.inert) restoreDialogFocus();
  queueMicrotask(presentRoomLink);
}
function dialog(title, description, body, submit, label = "确认") {
  modalRefresh = null;
  if (!modalRoot.firstChild) returnFocus = document.activeElement;
  dialogSubmit = submit;
  root.inert = true;
  modalRoot.innerHTML = `<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="dialog-title" aria-describedby="dialog-description" tabindex="-1"><div class="modal-head"><div><h2 id="dialog-title">${e(title)}</h2><p id="dialog-description">${e(description)}</p></div><button class="icon-button" data-action="dialog-close" aria-label="关闭">×</button></div><form data-form="dialog">${body}<p class="inline-feedback" id="dialog-error" role="alert"></p><div class="modal-actions">${button("取消", "dialog-close")}${submit ? `<button class="button primary">${e(label)}</button>` : ""}</div></form></section></div>`;
  (
    modalRoot.querySelector("input,select,textarea") ||
    modalRoot.querySelector('.modal-actions [data-action="dialog-close"]')
  ).focus();
}
function capture() {
  const input = document.getElementById("message-input");
  if (input) s.drafts[s.groupId] = input.value;
}
function listAccessLost(error) {
  return ["CURRENT_GRANT_REQUIRED", "RESOURCE_NOT_AVAILABLE", "GROUP_NOT_AVAILABLE"].includes(error?.message || error);
}
async function run(work, { modal = false, refreshingList = false } = {}) {
  if (s.busy) return;
  s.busy = true;
  root.setAttribute("aria-busy", "true");
  s.error = "";
  const current = epoch;
  if (modal)
    modalRoot
      .querySelectorAll("button,input,select,textarea")
      .forEach((x) => (x.disabled = true));
  else render();
  try {
    await work(current);
  } catch (error) {
    if (current !== epoch) return;
    const text = errorText(error);
    if (refreshingList && listAccessLost(error)) {
      closeDialog();
      productDirty = true;
      notify(text);
    } else if (modalRoot.firstChild && modal)
      document.getElementById("dialog-error").textContent = text;
    else s.error = text;
  } finally {
    s.busy = false;
    root.removeAttribute("aria-busy");
    const restoreFocus = root.inert && !modalRoot.firstChild;
    root.inert = !!modalRoot.firstChild;
    if (modalRoot.firstChild)
      modalRoot
        .querySelectorAll("button,input,select,textarea")
        .forEach((x) => (x.disabled = false));
    render();
    if (restoreFocus) restoreDialogFocus();
    void presentRoomLink();
  }
}
const request = (resource, id = null, body = null, targetId = null) =>
  bridge
    ? bridge.request(resource, id, body, targetId)
    : Promise.reject(new Error("TAURI_UNAVAILABLE"));
function clearSession() {
  inviteSession++;
  heldRoomCode = null;
  inboxDirty = true;
  resetSyncRetry();
  dirtyMessageGroup = null;
  productDirty = modalDirty = false;
  Object.assign(s, {
    userId: null, activity: null, voice: null, network: null,
    groups: [], rooms: [], friends: [], messages: [], members: [],
    groupMembers: [], drafts: {}, roomId: null, groupId: null,
    view: "lobby", authMode: "login",
  });
}
function clearGroupView() {
  delete s.drafts[s.groupId];
  const currentRoom = s.activity && s.rooms.find(room => room.id === s.roomId && room.currentActivityId === s.activity.activityId);
  Object.assign(s, { groupId: null, roomId: currentRoom?.id ?? null, messages: [], groupMembers: [], members: [], view: "lobby" });
}
async function refresh(current = epoch, applicable = () => true) {
  const groups = await bridge.groups();
  const rooms = (
    await Promise.all(groups.map((g) => bridge.rooms(g.id)))
  ).flat();
  const friends = await request("friends");
  if (current !== epoch || !applicable()) return;
  Object.assign(s, { groups, rooms, friends });
  if (s.groupId && !groups.some(group => group.id === s.groupId)) {
    clearGroupView();
    return;
  }
  if (s.view === "group" || s.view === "room") await loadContext(current, applicable);
}
async function loadContext(current = epoch, applicable = () => true) {
  const groupId = s.groupId,
    activityId = s.activity?.activityId;
  const [messages, groupMembers, members] = await Promise.all([
    groupId ? request("messages", groupId) : [],
    groupId ? request("group-members", groupId) : [],
    s.view === "room" && activityId
      ? request("activity-members", activityId)
      : [],
  ]);
  if (current !== epoch || groupId !== s.groupId || !applicable()) return;
  Object.assign(s, {
    messages: [...messages].reverse(),
    groupMembers,
    members,
  });
}
function navigate(view, groupId = s.groupId) {
  capture();
  if (s.groupId !== groupId) Object.assign(s, { groupId, messages: [], groupMembers: [] });
  s.view = view;
  s.error = "";
  epoch++;
  render();
  if (view === "settings" && s.settingsTab === "voice") run(loadAudioDevices);
}
async function loadAudioDevices() {
  const current = epoch, userId = s.userId, leaseId = s.activity?.leaseId;
  s.audioDevices = null;
  const stillCurrent = () => current === epoch && s.userId === userId && s.activity?.leaseId === leaseId;
  const devices = await bridge.audioDevices();
  if (!stillCurrent()) return;
  s.audioDevices = devices;
  const voice = await bridge.voiceSnapshot();
  if (stillCurrent()) s.voice = voice;
}
async function joinRoom(id, password = null) {
  const r = s.rooms.find((r) => r.id === id);
  if (!r) throw new Error("RESOURCE_NOT_ALLOWED");
  if (s.activity && s.roomId === id) {
    closeDialog();
    navigate("room", r.groupId);
    await loadContext();
    return;
  }
  const result = await bridge.join(id, deviceId, password, crypto.randomUUID());
  Object.assign(s, result, {
    roomId: id,
    groupId: r.groupId,
    view: "room",
    voice: null,
    network: null,
  });
  closeDialog();
  void connectChannel("voice");
  void connectChannel("network");
  await loadContext();
}
const pendingChannels = new Map();
async function connectChannel(channel) {
  if (!s.activity?.leaseId) return;
  const {activityId, leaseId} = s.activity, userId = s.userId;
  const current = () => s.userId === userId && s.activity?.activityId === activityId && s.activity?.leaseId === leaseId;
  if (pendingChannels.get(channel) === leaseId) return;
  pendingChannels.set(channel, leaseId);
  try {
    let snapshot;
    try {
      snapshot = channel === "voice" ? await bridge.connectVoice(activityId, leaseId) : await bridge.connectNetwork(activityId, leaseId);
    } catch (error) {
      if (!current()) return;
      notify(errorText(error));
      snapshot = channel === "voice" ? await bridge.voiceSnapshot() : await bridge.networkSnapshot();
    }
    if (!current()) return;
    s[channel] = snapshot;
    if (channel === "network" && snapshot?.status?.activityId === activityId) s.activity.network = snapshot.status.state;
    runtimeDirty = true;
    if (!s.busy && !modalRoot.firstChild && !document.activeElement?.matches("input,textarea:not([readonly]),select")) render();
  } catch (error) {
    if (current()) notify(errorText(error));
  } finally {
    if (pendingChannels.get(channel) === leaseId) pendingChannels.delete(channel);
  }
}
function createGroup() {
  dialog(
    "创建群组",
    "给固定的小队一个集合地。",
    field("name", "群名称", "text", "", 'required maxlength="80"') +
      field("description", "群简介", "text", "", 'maxlength="500"') + avatarField,
    async (data) => {
      await request("group-create", null, {
        name: data.get("name"),
        description: data.get("description"),
        avatar: await readGroupAvatar(data.get("avatar")),
      });
      closeDialog();
      s.view = "groups";
      await refresh();
    },
    "创建群组",
  );
}
function createRoom() {
  if (!s.groups.length) {
    createGroup();
    return;
  }
  dialog(
    "创建房间",
    "在你的群组中创建一个游戏房间。",
    `<div class="field"><label for="field-groupId">所属群组</label><select name="groupId" id="field-groupId">${s.groups.map((g) => `<option value="${e(g.id)}" ${g.id === s.groupId ? "selected" : ""}>${e(g.name)}</option>`).join("")}</select></div>` +
      field("name", "房间名称", "text", "", 'required maxlength="80"') +
      field("gameName", "游戏名称", "text", "", 'maxlength="80"') +
      field("description", "房间介绍", "text", "", 'maxlength="500"') +
      field(
        "password",
        "房间密码（可选）",
        "password",
        "",
        'autocomplete="new-password"',
      ),
    async (data) => {
      await request(
        "room-create",
        data.get("groupId"),
        Object.fromEntries(
          ["name", "description", "gameName", "password"].map((k) => [
            k,
            data.get(k),
          ]),
        ),
      );
      closeDialog();
      s.view = "lobby";
      await refresh();
    },
    "创建房间",
  );
}
function confirmation(title, description, work) {
  dialog(title, description, "", async () => {
    await work();
    closeDialog();
  });
}
function help() {
  dialog(
    "联机帮助",
    "语音与虚拟网络分别连接、分别恢复。",
    '<div class="modal-note">加入房间后，在「联机」页确认网络连接状态。只有网络已连通时，才能在游戏里使用队友的虚拟 IP。<br><br>语音连接失败可点击底部「重试」。若网络尚未接入，不能仅凭分配的 IP 判断可用。</div>',
    null,
  );
}
async function showFriendRequests(beforeId = null, applicable = () => true) {
  const current = epoch, userId = s.userId, version = productVersion;
  const incoming = await request("friend-requests", beforeId);
  if (current !== epoch || userId !== s.userId || !applicable()) return false;
  if (!Array.isArray(incoming)) throw new Error("INVALID_SERVER_RESPONSE");
  const rows = incoming.map(item => `<div class="friend-request-row"><div><strong>${e(item.email)}</strong><p>${item.accountStatus === "ACTIVE" ? "希望添加你为好友" : "该账号当前不可用"}</p></div><div class="friend-request-actions">${item.accountStatus === "ACTIVE" ? button("接受", "accept-friend-request", "primary small", `data-id="${e(item.id)}"`) : ""}${button("拒绝", "reject-friend-request", "ghost small", `data-id="${e(item.id)}"`)}</div></div>`).join("");
  dialog("好友申请", "仅显示发给你的待处理申请。",
    (rows || empty("暂无好友申请", "收到新的申请后会显示在这里。")) +
    `<div class="management-actions">${button("刷新", "refresh-friend-requests")}${incoming.length === 50 ? button("更早的申请", "earlier-friend-requests", "ghost", `data-id="${e(incoming.at(-1).id)}"`) : ""}${beforeId ? button("返回最新", "refresh-friend-requests") : ""}</div>`, null);
  modalRefresh = {reload: guard => showFriendRequests(beforeId, guard)};
  modalDirty = productVersion !== version;
  return true;
}
function respondToFriendRequest(target, accept) {
  return run(async () => {
    await request(accept ? "friend-accept" : "friend-reject", target.dataset.id, {});
    await refresh();
    await showFriendRequests();
  }, { modal: true });
}
async function showGroupMembers(groupId = s.groupId, applicable = () => true) {
  const current = epoch, userId = s.userId, version = productVersion;
  const members = await request("group-members", groupId);
  if (current !== epoch || groupId !== s.groupId || userId !== s.userId || !applicable()) return false;
  s.groupMembers = members;
  const role = members.find(member => member.userId === userId)?.role;
  const names = { OWNER: "群主", ADMIN: "管理员", MEMBER: "成员" };
  const rows = members.map(member => {
    const other = member.userId !== userId && member.role !== "OWNER";
    const attributes = `data-id="${e(member.userId)}" data-group="${e(groupId)}"`;
    const canRemove = other && (role === "OWNER" || (role === "ADMIN" && member.role === "MEMBER"));
    return `<div class="group-member-row"><div><strong>${e(member.userId)}</strong><p>${e(names[member.role] || member.role)}${member.userId === userId ? " · 你" : ""}</p></div><div class="group-member-actions">${other && role === "OWNER" ? button(member.role === "ADMIN" ? "取消管理员" : "设为管理员", "change-member-role", "ghost small", `${attributes} data-role="${member.role === "ADMIN" ? "MEMBER" : "ADMIN"}"`) + button("转让群主", "start-owner-transfer", "ghost small", attributes) : ""}${canRemove ? button("移除成员", "remove-group-member", "danger small", attributes) : ""}</div></div>`;
  }).join("");
  dialog("群组成员", "角色变更和移除操作将在执行时重新校验权限。", rows + `<div class="management-actions">${button("刷新成员", "group-members")}</div>`, null);
  modalRefresh = {reload: guard => showGroupMembers(groupId, guard)};
  modalDirty = productVersion !== version;
  return true;
}
function confirmMemberChange(target, remove) {
  const { id, group, role } = target.dataset;
  const title = remove ? "移除成员" : role === "ADMIN" ? "设为管理员" : "取消管理员";
  dialog(title, remove ? `确认移除成员「${id}」？该成员将失去群组和活动权限；其持有的房间将由群主接管。` : `确认${title}「${id}」？`, "", async () => {
    await request(remove ? "group-member-remove" : "group-member-role", group, remove ? null : { role }, id);
    await refresh();
    await showGroupMembers(group);
  });
}
async function showOwnerTransfers(groupId = s.groupId, applicable = () => true) {
  const current = epoch, userId = s.userId, version = productVersion;
  const transfers = await request("owner-transfers", groupId);
  if (current !== epoch || userId !== s.userId || groupId !== s.groupId || !applicable()) return false;
  const rows = transfers.map(transfer => `<div class="group-member-row"><div><strong>${e(transfer.fromUserId)} → ${e(transfer.toUserId)}</strong><p>等待指定成员接受 · ${e(new Date(transfer.createdAt).toLocaleString("zh-CN"))}</p></div><div class="group-member-actions">${transfer.toUserId === userId ? button("接受转让", "accept-owner-transfer", "primary small", `data-id="${e(transfer.id)}" data-group="${e(groupId)}"`) : ""}${transfer.fromUserId === userId ? button("撤销转让", "cancel-owner-transfer", "danger small", `data-id="${e(transfer.id)}" data-group="${e(groupId)}"`) : ""}</div></div>`).join("");
  dialog("群主转让", "只有指定成员接受后归属才会改变，完成前原群主仍负责群组。",
    (rows || empty("暂无待处理转让", "群主可在群组成员列表选择接收人。")) + `<div class="management-actions">${button("刷新转让", "owner-transfers")}${button("群组成员", "group-members")}${button("人工恢复申请", "owner-recovery")}</div>`, null);
  modalRefresh = {reload: guard => showOwnerTransfers(groupId, guard)};
  modalDirty = productVersion !== version;
  return true;
}
function confirmOwnerTransfer(target, operation) {
  const { id, group } = target.dataset;
  const details = {
    create: ["转让群主", `确认将群主转让给「${id}」？对方接受前你仍是群主，仍可撤销。`],
    accept: ["接受转让", "接受后你将成为群主，原群主将成为普通成员。"],
    cancel: ["撤销转让", "撤销后对方不能再接受此转让，群主归属保持不变。"],
  };
  dialog(...details[operation], "", async () => {
    await request(`owner-transfer-${operation}`, operation === "create" ? group : id, operation === "create" ? { targetUserId: id } : {});
    await refresh();
    await showOwnerTransfers(group);
  });
}
async function showInvitations(groupId = null, beforeId = null, applicable = () => true) {
  const current = epoch, userId = s.userId, version = productVersion;
  const invitations = await request(groupId ? "group-invitations" : "invitations", groupId || beforeId, null, groupId ? beforeId : null);
  if (current !== epoch || userId !== s.userId || (groupId && groupId !== s.groupId) || !applicable()) return false;
  const rows = invitations.map(invitation => `<div class="group-member-row"><div><strong>${e(groupId ? invitation.inviteeId : invitation.groupName)}</strong><p>邀请人：${e(invitation.inviterId)}</p></div><div class="group-member-actions">${button(groupId ? "撤销邀请" : "接受邀请", groupId ? "revoke-invitation" : "accept-invitation", groupId ? "danger small" : "primary small", `data-id="${e(invitation.id)}" data-group="${e(invitation.groupId)}"`)}</div></div>`).join("");
  const context = groupId ? `data-group="${e(groupId)}"` : "";
  dialog(groupId ? "邀请管理" : "群邀请", groupId ? "仅群主和管理员可查看、撤销待接受邀请。" : "仅显示发给你的有效待接受邀请。",
    (rows || empty("暂无待接受邀请", groupId ? "邀请成员后会显示在这里。" : "收到群组邀请后会显示在这里。")) +
    `<div class="management-actions">${button("刷新邀请", "refresh-invitations", "ghost", context)}${invitations.length === 50 ? button("更早的邀请", "earlier-invitations", "ghost", `${context} data-id="${e(invitations.at(-1).id)}"`) : ""}${beforeId ? button("返回最新", "refresh-invitations", "ghost", context) : ""}</div>`, null);
  modalRefresh = {reload: guard => showInvitations(groupId, beforeId, guard)};
  modalDirty = productVersion !== version;
  return true;
}
function confirmInvitation(target, revoke) {
  const { id, group } = target.dataset;
  dialog(revoke ? "撤销邀请" : "接受邀请", revoke ? "撤销后，此邀请将无法再被接受。" : "接受后将加入该群组，获得群内容和房间访问权限。", "", async () => {
    await request(revoke ? "invitation-revoke" : "invitation-accept", revoke ? group : id, revoke ? null : {}, revoke ? id : null);
    await refresh();
    await showInvitations(revoke ? group : null);
  });
}
const auditActions = {
  ACCOUNT_MEMBERSHIP_REVOKE: "账号失效撤销群资格",
  GROUP_CREATE: "创建群组", GROUP_UPDATE: "修改群资料", GROUP_INVITE: "邀请成员",
  GROUP_INVITATION_REVOKE: "撤销邀请", GROUP_INVITATION_ACCEPT: "接受邀请",
  GROUP_ADMIN_GRANT: "设置管理员", GROUP_ADMIN_REVOKE: "取消管理员",
  GROUP_MEMBER_REMOVE: "移除群成员", GROUP_LEAVE: "退出群组", GROUP_DISSOLVE: "解散群组",
  OWNER_TRANSFER_CREATE: "发起群主转让", OWNER_TRANSFER_ACCEPT: "接受群主转让",
  OWNER_TRANSFER_CANCEL: "撤销群主转让", OWNER_RECOVERY_REQUEST: "提交恢复申请", OWNER_RECOVERY_REVIEW: "人工恢复审核",
  ROOM_CREATE: "创建房间", ROOM_UPDATE: "修改房间", ROOM_OWNER_ASSIGN: "指定房主",
  ROOM_OPEN: "开放加入", ROOM_PAUSE: "暂停加入", ROOM_DELETE: "删除房间",
  ACTIVITY_START: "开始活动", ACTIVITY_END: "结束活动", ACTIVITY_MEMBER_KICK: "踢出活动成员",
  MESSAGE_DELETE: "管理删除消息",
};
const auditTargets = { GROUP: "群组", GROUP_MEMBER: "群成员", INVITATION: "邀请", OWNER_TRANSFER: "群主转让", OWNER_RECOVERY: "恢复申请", ROOM: "房间", ACTIVITY: "活动", ACTIVITY_MEMBER: "活动成员", MESSAGE: "消息" };
const auditResults = { SUCCESS: "成功", RECOVER: "已完成恢复", REJECT: "已驳回", DISPUTED: "存在争议，归属未变更", INSUFFICIENT_EVIDENCE: "证据不足，归属未变更" };
async function showAudit(groupId, beforeId = null, applicable = () => true) {
  const current = epoch, userId = s.userId, version = productVersion;
  const entries = await request("audit", groupId, null, beforeId);
  if (current !== epoch || userId !== s.userId || groupId !== s.groupId || !applicable()) return false;
  const rows = entries.map(entry => {
    const time = new Date(entry.occurredAt);
    return `<article class="audit-entry"><strong>${e(auditActions[entry.action] || entry.action)}</strong><time datetime="${e(entry.occurredAt)}">${e(Number.isNaN(time.getTime()) ? "时间不可用" : time.toLocaleString("zh-CN"))}</time><dl><dt>操作者</dt><dd>${e(entry.actorId)}</dd><dt>目标</dt><dd>${e(auditTargets[entry.targetType] || entry.targetType)} · ${e(entry.targetId)}</dd><dt>结果</dt><dd>${e(auditResults[entry.result] || entry.result)}</dd></dl></article>`;
  }).join("");
  const context = `data-group="${e(groupId)}"`;
  dialog("管理记录", "仅当前群主和管理员可查看，记录只读。",
    (rows ? `<div class="audit-list">${rows}</div>` : empty("暂无管理记录", "当前没有可展示的记录。")) +
    `<div class="management-actions">${button("刷新记录", "refresh-audit", "ghost", context)}${entries.length === 50 ? button("更早的记录", "earlier-audit", "ghost", `${context} data-id="${e(entries.at(-1).id)}"`) : ""}${beforeId ? button("返回最新", "refresh-audit", "ghost", context) : ""}</div>`, null);
  modalRefresh = {reload: guard => showAudit(groupId, beforeId, guard)};
  modalDirty = productVersion !== version;
  return true;
}
async function showMessageHistory(groupId, cursor, applicable = () => true) {
  const current = epoch, userId = s.userId;
  if (!groupId || !cursor) return;
  const previous = modalRoot.firstChild;
  let rows, groupMembers;
  try {
    [rows, groupMembers] = await Promise.all([
      request("messages", groupId, null, cursor), request("group-members", groupId),
    ]);
  }
  catch (error) {
    if (epoch === current && previous === modalRoot.firstChild && applicable() && ["RESOURCE_NOT_AVAILABLE", "CURRENT_GRANT_REQUIRED"].includes(error?.message || error)) closeDialog();
    throw error;
  }
  if (epoch !== current || groupId !== s.groupId || userId !== s.userId || previous !== modalRoot.firstChild || !applicable()) return false;
  const deleteControl = messageDeleteControl({ userId, groupMembers });
  const entries = rows.slice().reverse().map(message => `<article class="message-content"><div class="message-meta"><strong>${e(message.authorId === userId ? "我" : message.authorId)}</strong><time>${e(new Date(message.createdAt).toLocaleString("zh-CN"))}</time>${deleteControl(message)}</div><div class="message-text">${e(message.deleted ? "此消息已被删除" : message.body)}</div></article>`).join("");
  const older = rows.length === 50 ? button("更早的消息", "message-history", "ghost", `data-id="${e(rows.at(-1).id)}"`) : "";
  dialog("群聊历史", "按发送时间排列；关闭后返回实时群聊。", `<div data-message-history>${entries || empty("没有更早的消息", "你已经看完了这个群的历史消息。")}</div>${older}${button("返回最新消息", "dialog-close", "ghost")}`, null);
  modalRefresh = {reload: guard => showMessageHistory(groupId, cursor, guard)};
  return true;
}
async function copyCurrent(target, readText) {
  const text = readText();
  if (!text || !target.isConnected) return;
  try {
    await navigator.clipboard.writeText(text);
    if (target.isConnected && readText() === text) notify("已复制");
  } catch {
    if (!target.isConnected || readText() !== text) return;
    const input = document.createElement("textarea");
    input.readOnly = true;
    input.setAttribute("aria-label", "手动复制内容");
    input.value = text;
    // 回退控件随当前视图重绘移除，不将旧房间地址留在全局弹窗。
    target.parentElement.querySelector('[aria-label="手动复制内容"]')?.remove();
    target.after(input);
    input.focus(); input.select();
    notify("剪贴板不可用，内容已选中，请手动复制。");
  }
}
// 仅在已登录、空闲且没有其他弹窗时提取内存里的外部邀请。取消不会核销。
let checkingRoomLink = false, inboxDirty = true, heldRoomCode = null, inviteSession = 0;
function roomInboxChanged() {
  inboxDirty = true;
  void presentRoomLink();
}
async function presentRoomLink() {
  if (!bridge || checkingRoomLink || !s.userId || s.busy || modalRoot.firstChild) return;
  const user = s.userId, session = inviteSession;
  checkingRoomLink = true;
  try {
    while (inboxDirty && user === s.userId && session === inviteSession) {
      inboxDirty = false;
      const next = await bridge.invoke("take_room_invitation");
      if (next && user === s.userId && session === inviteSession) heldRoomCode = next;
    }
    if (heldRoomCode && user === s.userId && session === inviteSession && !s.busy && !modalRoot.firstChild) {
      const code = heldRoomCode;
      heldRoomCode = null;
      openRoomCode(code);
    }
  } catch { inboxDirty = true; } finally { checkingRoomLink = false; }
}
function openRoomCode(code = "") {
  const external = !!code;
  dialog(external ? "打开房间邀请" : "邀请码加入", "使用受邀邮箱确认后加入指定房间；若正在另一间房，成功后才会切换。",
    (external ? "" : field("code", "房间邀请码", "text", "", 'required maxlength="43" autocomplete="off" spellcheck="false"')) +
    field("password", "房间密码（如有）", "password", "", 'autocomplete="off"'), async data => {
      const {roomId} = await request("redeem-room-invite", null, {code: external ? code : data.get("code").trim()});
      await refresh();
      await joinRoom(roomId, data.get("password") || null);
    }, "加入房间");
}
const actions = {
  "copy-diagnostics": target => copyCurrent(target, () => connectionDiagnostics(s)),
  "copy-address": target => copyCurrent(target, () => s.activity?.leaseId === target.dataset.lease && roomAddresses(s).find(row => row.userId === target.dataset.peer)?.virtualIpv4),
  "message-history": target => run(() => showMessageHistory(s.groupId, target.dataset.id), { modal: true }),
  "refresh-audit": target => run(() => showAudit(target.dataset.group), { modal: true, refreshingList: true }),
  "earlier-audit": target => run(() => showAudit(target.dataset.group, target.dataset.id), { modal: true, refreshingList: true }),
  "refresh-room-members": () => run(async () => { await refresh(); }),
  "assign-room-owner": () => run(async () => {
    const current = epoch, userId = s.userId, roomId = s.roomId;
    const room = s.rooms.find(item => item.id === s.roomId);
    if (!room) return;
    const members = await request("group-members", room.groupId);
    if (current !== epoch || userId !== s.userId || roomId !== s.roomId) return;
    s.groupMembers = members;
    dialog("指定房主", "仅群主可指定当前有效群成员，立即生效；房主权限仅限该房间。",
      `<label for="room-owner-target">新的房主</label><select id="room-owner-target" name="targetUserId" required>${members.map(member => `<option value="${e(member.userId)}" ${member.userId === room.ownerUserId ? "selected" : ""}>${e(member.userId)}${member.userId === room.ownerUserId ? "（当前房主）" : ""}</option>`).join("")}</select>`, async data => {
        await request("room-owner", room.id, { targetUserId: data.get("targetUserId") });
        closeDialog();
        await refresh();
      }, "指定房主");
  }, { modal: true }),
  "kick-activity-member": target => {
    const { id, generation, activity } = target.dataset;
    dialog("踢出成员", `确认将「${id}」踢出本次活动？语音与联机会被撤销，但不会移出群组或封禁，仍可按当前房间规则重新加入。`, "", async () => {
      await request("activity-kick", activity, { memberGeneration: Number(generation) }, id);
      closeDialog();
      await loadContext();
    });
  },
  "group-invitations": () => run(() => showInvitations(s.groupId), { modal: true }),
  "refresh-invitations": target => run(() => showInvitations(target.dataset.group || null), { modal: true }),
  "earlier-invitations": target => run(() => showInvitations(target.dataset.group || null, target.dataset.id), { modal: true }),
  "accept-invitation": target => confirmInvitation(target, false),
  "revoke-invitation": target => confirmInvitation(target, true),
  "owner-transfers": () => run(() => showOwnerTransfers(), { modal: true }),
  "start-owner-transfer": target => confirmOwnerTransfer(target, "create"),
  "accept-owner-transfer": target => confirmOwnerTransfer(target, "accept"),
  "cancel-owner-transfer": target => confirmOwnerTransfer(target, "cancel"),
  "owner-recovery": () => {
    const groupId = s.groupId;
    dialog("人工恢复申请", "群主无法操作时可提交证据引用，等待人工审核；申请不会自动变更归属。请勿填写密码或密钥。",
      field("evidenceRef", "证据引用或说明", "text", "", 'required maxlength="2000"'), async data => {
        const result = await request("owner-recovery", groupId, { evidenceRef: data.get("evidenceRef") });
        dialog("申请已提交", "等待人工审核，群主归属保持不变。", `<p class="invite-id">申请编号：${e(result.id)}</p>`, null);
      }, "提交申请");
  },
  "group-members": () => run(() => showGroupMembers(), { modal: true }),
  "change-member-role": target => confirmMemberChange(target, false),
  "remove-group-member": target => confirmMemberChange(target, true),
  "auth-login": () => {
    s.authMode = "login";
    s.error = "";
    render();
  },
  "auth-register": () => {
    s.authMode = "register";
    s.error = "";
    render();
  },
  "auth-forgot": () => {
    s.authMode = "forgot";
    s.error = "";
    render();
  },
  "auth-verify": () => {
    s.authMode = "verify";
    s.error = "";
    render();
  },
  friends: () => navigate("friends"),
  groups: () => navigate("groups"),
  refresh: () => run((current) => refresh(current)),
  "create-group": createGroup,
  "create-room": createRoom,
  help,
  account: () => {
    s.settingsTab = "account";
    navigate("settings");
  },
  "friends-online": () => {
    s.friendFilter = "online";
    render();
  },
  "friends-all": () => {
    s.friendFilter = "all";
    render();
  },
  "friend-requests": () => run(() => showFriendRequests()),
  "refresh-friend-requests": () => run(() => showFriendRequests(), { modal: true }),
  "earlier-friend-requests": target => run(() => showFriendRequests(target.dataset.id), { modal: true }),
  "accept-friend-request": target => respondToFriendRequest(target, true),
  "reject-friend-request": target => respondToFriendRequest(target, false),
  invitation: () => run(() => showInvitations()),
  "add-friend": () =>
    dialog(
      "添加好友",
      "通过准确的邮箱查找账号。",
      field("query", "好友邮箱", "email", "", 'required maxlength="254"'),
      async (data) => {
        const users = await request("friend-search", data.get("query"));
        const user = users.find(
          (u) => u.email.toLowerCase() === data.get("query").toLowerCase(),
        );
        if (!user) {
          document.getElementById("dialog-error").textContent =
            "没有找到此账号。";
          return;
        }
        await request("friend-request", null, { addresseeId: user.userId });
        closeDialog();
        notify("好友申请已发送。");
      },
      "发送申请",
    ),
  "delete-friend": (b) =>
    confirmation("删除好友", "删除后需要重新发送好友申请。", async () => {
      await request("friend-delete", b.dataset.id);
      await refresh();
    }),
  "open-group": (b) => {
    navigate("group", b.dataset.id);
    return run((current) => loadContext(current));
  },
  "open-room": () => {
    const room = s.rooms.find(item => item.id === s.roomId);
    if (!room || !s.activity) return;
    navigate("room", room.groupId);
    return run((current) => loadContext(current));
  },
  "join-room": (b) => {
    const room = s.rooms.find(item => item.id === b.dataset.id);
    if (!room) return;
    const switchNotice = s.activity && s.roomId !== room.id ? "成功切换后才会断开当前房间的语音和联机。" : "";
    const passwordField = required => field("password", required ? "房间密码（必填）" : "房间密码", "password", "",
      required ? 'autocomplete="off" required' : 'autocomplete="off"');
    const required = room.passwordRequired === true;
    const unknown = room.passwordRequired == null && room.passwordProtected !== false;
    const hint = required ? "请输入房间密码。" : unknown ? "如需密码请填写，免密成员可留空。"
      : room.passwordProtected ? "你可以免密码加入此房间。" : "此房间未设置密码。";
    dialog(
      "加入房间",
      switchNotice + hint,
      required || unknown ? passwordField(required) : "",
      async (data) => {
        const form = modalRoot.querySelector("form");
        try { await joinRoom(room.id, data.get("password") || null); }
        catch (error) {
          if ((error?.message || error) === "ROOM_PASSWORD_INVALID" && form.isConnected && !form.elements.password) {
            document.getElementById("dialog-description").textContent = switchNotice + "请输入房间密码。";
            document.getElementById("dialog-error").insertAdjacentHTML("beforebegin", passwordField(true));
            form.elements.password.focus();
            throw new Error("ROOM_PASSWORD_REQUIRED");
          }
          throw error;
        }
      },
      "加入房间",
    );
  },
  leave: () =>
    confirmation("离开房间", "将断开当前房间的语音和联机连接。", async () => {
      Object.assign(s, await bridge.leave(), {
        roomId: null,
        voice: null,
        network: null,
        view: "lobby",
      });
      epoch++;
    }),
  logout: () =>
    confirmation(
      "退出登录",
      s.activity
        ? "退出后，当前房间的语音和联机将中断。"
        : "确定退出当前账号吗？",
      async () => {
        const snapshot = await bridge.logout();
        epoch++;
        clearSession();
        if (snapshot.lastErrorCode) notify(errorText(snapshot.lastErrorCode));
      },
    ),
  mute: () =>
    run(async () => {
      s.voice = await bridge.setMuted(!s.voice?.muted);
    }),
  receive: () =>
    run(async () => {
      s.voice = await bridge.setReceiveEnabled(
        s.voice?.receiveEnabled === false,
      );
    }),
  "retry-voice": () => connectChannel("voice"),
  "authorize-helper": () => {
    if (!s.activity) return;
    const {activityId, leaseId} = s.activity;
    const current = () => s.activity?.activityId === activityId && s.activity?.leaseId === leaseId;
    dialog("允许房间联机？", "Windows 将请求管理员确认，仅用来启动建立虚拟网卡的配套辅助服务。", "<p>桌面端仍以普通权限运行。取消不会退出房间，也不会关闭文字或语音。</p>", async () => {
      if (!current()) { closeDialog(); return; }
      await bridge.authorizeHelper(activityId, leaseId);
      closeDialog();
      if (current()) await connectChannel("network");
    }, "继续系统授权");
  },
  "retry-network": () => connectChannel("network"),
  "delete-message": (b) => {
    const history = b.closest("[data-message-history]") ? modalRefresh : null;
    const current = epoch, groupId = s.groupId;
    dialog("删除消息", "删除后正文将不可见，保留删除标记。", "", async () => {
      const previous = modalRoot.firstChild;
      const applicable = () => current === epoch && groupId === s.groupId && previous === modalRoot.firstChild;
      const reload = async () => {
        if (history) await history.reload(applicable);
        else {
          await loadContext(current, applicable);
          if (applicable()) closeDialog();
        }
      };
      await request("message-delete", b.dataset.id);
      if (!applicable()) return;
      dialogSubmit = reload;
      modalRoot.querySelector(".modal-actions .primary").textContent = "刷新消息";
      document.getElementById("dialog-description").textContent = "消息已删除，正在重新读取列表。";
      await reload();
    });
  },
  "invite-to-room": () => {
    const roomId = s.roomId;
    if (!roomId || !s.activity) return;
    dialog("邀请队友进房", "输入队友将用于验证的邮箱。无需对方先注册。", field("email", "队友邮箱", "email", "", 'required maxlength="254"'), async data => {
      const {code, link} = await request("room-invite", roomId, {email: data.get("email").trim()});
      dialog("分享房间邀请", "把邀请链接私发给这位队友。使用受邀邮箱登录后，打开链接即可确认加入；也可以在大厅输入邀请码。",
        field("code", "房间邀请码", "text", code, 'readonly autocomplete="off"') + button("复制邀请码", "copy-room-code") +
        field("link", "房间邀请链接", "text", link, 'readonly autocomplete="off"') + button("复制邀请链接", "copy-room-link"), null);
    }, "生成邀请码");
  },
  "copy-room-link": target => copyCurrent(target, () => modalRoot.querySelector('input[name="link"][readonly]')?.value),
  "copy-room-code": target => copyCurrent(target, () => modalRoot.querySelector('input[name="code"][readonly]')?.value),
  "room-code": () => openRoomCode(),
  "invite-member": () =>
    dialog(
      "邀请队友",
      "通过准确的邮箱邀请账号加入当前群组。",
      field("email", "队友邮箱", "email", "", 'required maxlength="254"'),
      async (data) => {
        const email = data.get("email").trim();
        const users = await request("friend-search", email);
        const user = users.find(item => item.email.toLowerCase() === email.toLowerCase());
        if (!user) { document.getElementById("dialog-error").textContent = "没有找到此账号。"; return; }
        await request("group-invite", s.groupId, {
          inviteeId: user.userId,
        });
        closeDialog();
        notify("邀请已发送，队友可在「群组 → 群邀请」中接受。");
      },
      "创建邀请",
    ),
  "manage-group": () => {
    const g = s.groups.find((g) => g.id === s.groupId);
    const role = s.groupMembers.find(member => member.userId === s.userId)?.role;
    const canEdit = role === "OWNER" || role === "ADMIN";
    dialog(
      "群组管理",
      "权限由服务端在执行时校验。",
      (canEdit ? field("name", "群名称", "text", g.name, 'required maxlength="80"') +
        field(
          "description",
          "群简介",
          "text",
          g.description,
          'maxlength="500"',
        ) + avatarField : `<div class="modal-note"><strong>${e(g.name)}</strong><p>${e(g.description)}</p></div>`) +
        `<div class="management-actions">${button("群组成员", "group-members")}${button("群主转让", "owner-transfers")}${button("邀请队友", "invite-member")}${canEdit ? button("邀请管理", "group-invitations") + button("管理记录", "audit") : ""}${button("退出群组", "leave-group", "danger")}${role === "OWNER" ? button("解散群组", "dissolve-group", "danger") : ""}</div>`,
      canEdit ? async (data) => {
        await request("group-update", g.id, {
          name: data.get("name"),
          description: data.get("description"),
          avatar: await readGroupAvatar(data.get("avatar"), g.avatar),
        });
        closeDialog();
        await refresh();
      } : null,
      "保存",
    );
  },
  "leave-group": () =>
    confirmation(
      "退出群组",
      "退出后将失去该群组及房间的访问权限。",
      async () => {
        await request("group-leave", s.groupId, {});
        s.view = "groups";
        await refresh();
      },
    ),
  "dissolve-group": () => {
    const g = s.groups.find((g) => g.id === s.groupId);
    dialog(
      "解散群组",
      `请输入群名称「${g.name}」确认，操作无法在产品内撤销。`,
      field("name", "群名称", "text", "", "required"),
      async (data) => {
        if (data.get("name") !== g.name) {
          document.getElementById("dialog-error").textContent =
            "群名称不匹配。";
          return;
        }
        await request("group-dissolve", g.id, {});
        s.view = "groups";
        closeDialog();
        await refresh();
      },
      "解散群组",
    );
  },
  audit: () => run(() => showAudit(s.groupId), { modal: true }),
  "manage-room": () => {
    const r = s.rooms.find((r) => r.id === s.roomId);
    const permissions = roomPermissions(s, r);
    dialog(
      "房间设置",
      "房间资料与活动分开管理。",
      (permissions.manage ? field("name", "房间名称", "text", r.name, 'required maxlength="80"') +
        field("gameName", "游戏名称", "text", r.gameName, 'maxlength="80"') +
        field("description", "介绍", "text", r.description, 'maxlength="500"') +
        field("password", "新的房间密码（留空清除）", "password") +
        `<div class="management-actions">${permissions.assign ? button("指定房主", "assign-room-owner") : ""}${r.openState === "OPEN" ? button("暂停加入", "pause-room") : "<span>已暂停加入，请在房外的群页重新开放。</span>"}${button("结束活动", "end-activity", "danger")}${button("删除房间", "delete-room", "danger")}</div>` : `<div class="modal-note"><strong>${e(r.name)}</strong><p>${e(r.description)}</p><p>房主：${e(r.ownerUserId)}</p></div>`),
      permissions.manage ? async (data) => {
        await request(
          "room-update",
          r.id,
          Object.fromEntries(
            ["name", "description", "gameName", "password"].map((k) => [
              k,
              data.get(k),
            ]),
          ),
        );
        closeDialog();
        await refresh();
      } : null,
      "保存",
    );
  },
  "start-room-activity": target => {
    const room = s.rooms.find(item => item.id === target.dataset.id && item.groupId === s.groupId);
    if (!room || room.openState !== "OPEN" || room.currentActivityId || !roomPermissions(s, room).manage) return;
    confirmation("开启活动", `在「${room.name}」开启一次新活动？成员仍需自行加入。`, async () => {
      await request("activity-start", room.id, {});
      await refresh();
    });
  },
  "reopen-room": target => {
    const room = s.rooms.find(item => item.id === target.dataset.id && item.groupId === s.groupId);
    if (!room || room.openState !== "PAUSED" || (s.activity && s.roomId === room.id) || !roomPermissions(s, room).manage) return;
    confirmation("重新开放房间", `允许成员再次加入「${room.name}」。此操作不会创建活动或替你加入。`, async () => {
      await request("room-open", room.id, {});
      await refresh();
    });
  },
  "pause-room": () =>
    run(async () => {
      const r = s.rooms.find((r) => r.id === s.roomId);
      await request("room-pause", r.id, {});
      closeDialog();
      await refresh();
    }),
  "end-activity": () =>
    confirmation("结束活动", "所有成员将退出活动，房间资料保留。", async () => {
      await request("activity-end", s.activity.activityId, {});
      Object.assign(s, await bridge.leave(), {
        roomId: null,
        voice: null,
        network: null,
        view: "lobby",
      });
      await refresh();
    }),
  "delete-room": () =>
    confirmation(
      "删除房间",
      "房间删除后不能恢复，当前活动也会结束。",
      async () => {
        await request("room-delete", s.roomId);
        Object.assign(s, await bridge.leave(), {
          roomId: null,
          voice: null,
          network: null,
          view: "lobby",
        });
        await refresh();
      },
    ),
};
document.addEventListener("click", (event) => {
  const target = event.target.closest("button");
  if (!target || target.disabled) return;
  if (target.dataset.window) {
    windowAction(target.dataset.window);
    return;
  }
  if (target.dataset.action === "dialog-close") {
    if (!s.busy) closeDialog();
    return;
  }
  if (s.busy) return;
  capture();
  if (target.dataset.view) {
    navigate(target.dataset.view);
    return;
  }
  if (target.dataset.tab) {
    capture();
    s.roomTab = target.dataset.tab;
    render();
    return;
  }
  if (target.dataset.settings) {
    s.settingsTab = target.dataset.settings;
    render();
    if (s.settingsTab === "voice") run(loadAudioDevices);
    return;
  }
  if (target.dataset.action === "refresh-audio") {
    run(loadAudioDevices);
    return;
  }
  if (target.dataset.action === "test-microphone") {
    run(async () => {
      const current = epoch, userId = s.userId, leaseId = s.activity?.leaseId;
      const result = await bridge.testMicrophone();
      if (epoch !== current || s.userId !== userId || s.activity?.leaseId !== leaseId) return;
      if (!Number.isFinite(result.peak) || !Number.isFinite(result.rms) || result.samples < 96000) throw new Error("AUDIO_TEST_CAPTURE_INTERRUPTED");
      notify(result.peak > 0.003
        ? `已收到麦克风信号：峰值 ${(result.peak * 100).toFixed(1)}%，平均电平 ${(result.rms * 100).toFixed(1)}%。`
        : "已收到采样，但未检测到明显声音，请对着麦克风说话后重试。");
    });
    return;
  }
  if (target.dataset.action === "test-output") {
    run(async () => {
      const current = epoch, userId = s.userId, leaseId = s.activity?.leaseId;
      await bridge.testOutput();
      if (epoch !== current || s.userId !== userId || s.activity?.leaseId !== leaseId) return;
      dialog("输出试听已结束", "是否听到了测试音？播放调用完成不代表设备可听。", `<p>若未听到，请检查系统音量、输出设备和耳机连接，然后重新试听。</p>`, null);
    });
    return;
  }
  actions[target.dataset.action]?.(target);
});
document.addEventListener("submit", (event) => {
  event.preventDefault();
  if (s.busy) return;
  const form = event.target,
    data = new FormData(form);
  if (form.dataset.form === "dialog") {
    const submit = dialogSubmit;
    if (submit) run(() => submit(data), { modal: true });
  }
  if (form.dataset.form === "message")
    run(async () => {
      await request("message-send", s.groupId, { body: data.get("body") });
      s.drafts[s.groupId] = "";
      await loadContext();
    });
  if (form.dataset.form === "auth") {
    s.email = data.get("email");
    run(async () => {
      if (!bridge) throw new Error("TAURI_UNAVAILABLE");
      const mode = s.authMode;
      if (mode === "login") {
        Object.assign(
          s,
          await bridge.login(s.email, data.get("password"), deviceId),
        );
        await refresh();
      } else {
        const body = { email: s.email };
        if (mode === "register") body.password = data.get("password");
        if (["verify", "reset"].includes(mode)) body.code = data.get("code");
        if (mode === "reset") body.newPassword = data.get("password");
        await request(mode, null, body);
        s.authMode = {
          register: "verify",
          forgot: "reset",
          verify: "login",
          reset: "login",
        }[mode];
        notify(
          mode === "register" || mode === "forgot"
            ? "请查看邮箱中的验证邮件。"
            : "操作完成，请登录。",
        );
      }
    });
  }
});
document.addEventListener("input", (event) => {
  if (event.target.id === "message-input")
    s.drafts[s.groupId] = event.target.value;
  if (event.target.id === "friend-search") {
    const position = event.target.selectionStart;
    s.friendQuery = event.target.value;
    render();
    const input = document.getElementById("friend-search");
    input.focus();
    input.setSelectionRange(position, position);
  }
});
document.addEventListener("change", (event) => {
  if (event.target.hasAttribute("data-audio-device")) {
    const selectedId = event.target.value, output = event.target.dataset.audioDevice === "output";
    run(async () => {
      const current = epoch, userId = s.userId, leaseId = s.activity?.leaseId;
      const voice = await bridge.selectAudioDevice(selectedId, output);
      if (epoch !== current || s.userId !== userId || s.activity?.leaseId !== leaseId) return;
      s.voice = voice;
      await loadAudioDevices();
    });
  }
  if (event.target.id === "voice-volume") {
    const value = Number(event.target.value);
    run(async () => {
      s.voice = await bridge.setVolume(value);
    });
  }
  if (event.target.hasAttribute("data-member-volume")) {
    const value = Number(event.target.value), memberId = event.target.dataset.memberVolume;
    run(async () => {
      s.voice = await bridge.setVolume(value, memberId);
    });
  }
});
document.addEventListener("keydown", (event) => {
  if (!modalRoot.firstChild) return;
  if (event.key === "Escape" && !s.busy) {
    event.preventDefault();
    closeDialog();
  }
  if (event.key === "Tab") {
    const items = [
      ...modalRoot.querySelectorAll(
        "button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled)",
      ),
    ];
    const first = items[0],
      last = items.at(-1);
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
});
async function windowAction(action) {
  if (!desktop) return;
  try {
    const win = globalThis.__TAURI__.window.getCurrentWindow();
    await {
      minimize: () => win.minimize(),
      maximize: () => win.toggleMaximize(),
      close: () => win.close(),
    }[action]();
    if (action === "maximize") {
      const max = await win.isMaximized();
      document
        .querySelector('[data-window="maximize"]')
        .setAttribute("aria-label", max ? "还原" : "最大化");
    }
  } catch {
    notify("窗口操作失败，请重试。");
  }
}
document.addEventListener("mousedown", (event) => {
  if (
    event.button === 0 &&
    event.detail === 1 &&
    event.target.closest("[data-window-drag]") &&
    desktop
  )
    globalThis.__TAURI__.window
      .getCurrentWindow()
      .startDragging()
      .catch(() => notify("窗口暂时无法拖动。"));
});
document.addEventListener("dblclick", (event) => {
  if (event.target.closest("[data-window-drag]")) windowAction("maximize");
});
render();

if (bridge) {
  globalThis.__TAURI__?.event?.listen("room-invitation-ready", roomInboxChanged);
}
