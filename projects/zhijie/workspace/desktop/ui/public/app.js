import { CoreBridge } from "./src/index.js";
import { avatarField, readGroupAvatar } from "./src/group-avatar.js";
import {
  renderApp,
  escapeHtml as e,
  field,
  button,
  empty,
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
  dialogSubmit;
const errors = {
  GROUP_AVATAR_INVALID: "头像未保存，请选择 2 MiB 以内的有效 PNG 或 JPEG 图片。",
  SERVICE_UNAVAILABLE: "无法连接服务，请稍后重试。",
  TAURI_UNAVAILABLE: "请在织界桌面程序中使用账号服务。",
  INVALID_CREDENTIALS: "邮箱或密码不正确。",
  EMAIL_VERIFICATION_REQUIRED: "请先验证邮箱。",
  EMAIL_DELIVERY_UNAVAILABLE: "邮件服务尚未接入，暂时无法完成此操作。",
  AUTH_REQUIRED: "邮箱或密码错误、账号尚未验证，或登录已失效。",
  ACCOUNT_ALREADY_EXISTS: "此邮箱已注册，请登录或找回密码。",
  WEAK_PASSWORD: "密码至少需要 12 个字符。",
  CURRENT_GRANT_REQUIRED: "当前没有执行此操作的权限。",
  VOICE_CAPACITY_LIMITED: "语音服务尚未接入或暂不可用。",
  ROOM_PASSWORD_REQUIRED: "请输入房间密码。",
  ROOM_PASSWORD_INVALID: "房间密码不正确。",
  ROOM_CAPACITY_LIMITED: "房间已满，请稍后再试。",
  RESOURCE_NOT_ALLOWED: "此操作尚未接入。",
};
const errorText = (error) =>
  errors[String(error?.message || error)] ||
  "操作未完成，请检查输入或稍后重试。";
function render() {
  const active=document.activeElement;
  const focusKey=root.contains(active) ? ["data-action","data-view","data-settings"].find(key=>active.hasAttribute(key)) : null;
  const focusValue=focusKey ? active.getAttribute(focusKey) : null;
  const scroll=root.querySelector(".chat-scroll")?.scrollTop;
  root.innerHTML = renderApp(s);
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
function closeDialog() {
  modalRoot.replaceChildren();
  root.inert = false;
  dialogSubmit = null;
  if (returnFocus?.isConnected) returnFocus.focus();
  else root.querySelector(`[data-view="${s.view}"]`)?.focus();
}
function dialog(title, description, body, submit, label = "确认") {
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
async function run(work, { modal = false } = {}) {
  if (s.busy) return;
  s.busy = true;
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
    if (modalRoot.firstChild && modal)
      document.getElementById("dialog-error").textContent = text;
    else s.error = text;
  } finally {
    s.busy = false;
    if (modalRoot.firstChild)
      modalRoot
        .querySelectorAll("button,input,select,textarea")
        .forEach((x) => (x.disabled = false));
    render();
  }
}
const request = (resource, id = null, body = null, targetId = null) =>
  bridge
    ? bridge.request(resource, id, body, targetId)
    : Promise.reject(new Error("TAURI_UNAVAILABLE"));
async function refresh(current = epoch) {
  const groups = await bridge.groups();
  const rooms = (
    await Promise.all(groups.map((g) => bridge.rooms(g.id)))
  ).flat();
  const friends = await request("friends");
  if (current !== epoch) return;
  Object.assign(s, { groups, rooms, friends });
  if (s.view === "group" || s.view === "room") await loadContext(current);
}
async function loadContext(current = epoch) {
  const groupId = s.groupId,
    activityId = s.activity?.activityId;
  const [messages, groupMembers, members] = await Promise.all([
    groupId ? request("messages", groupId) : [],
    groupId ? request("group-members", groupId) : [],
    s.view === "room" && activityId
      ? request("activity-members", activityId)
      : [],
  ]);
  if (current !== epoch || groupId !== s.groupId) return;
  Object.assign(s, {
    messages: [...messages].reverse(),
    groupMembers,
    members,
  });
}
function navigate(view) {
  capture();
  s.view = view;
  s.error = "";
  epoch++;
  render();
}
async function joinRoom(id, password = null) {
  const r = s.rooms.find((r) => r.id === id);
  if (!r) throw new Error("RESOURCE_NOT_ALLOWED");
  if (s.activity && s.roomId === id) {
    closeDialog();
    navigate("room");
    await loadContext();
    return;
  }
  if (!r.currentActivityId) await request("activity-start", id, {});
  const result = await bridge.join(id, deviceId, password, crypto.randomUUID());
  Object.assign(s, result, {
    roomId: id,
    groupId: r.groupId,
    view: "room",
    voice: null,
  });
  closeDialog();
  await loadContext();
  try {
    s.voice = await bridge.connectVoice(result.activity.activityId);
  } catch (error) {
    s.voice = await bridge.voiceSnapshot();
    notify(errorText(error));
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
async function showMessageHistory(groupId, cursor, applicable = () => true) {
  const current = epoch, userId = s.userId;
  if (!groupId || !cursor) return;
  const previous = modalRoot.firstChild;
  let rows;
  try { rows = await request("messages", groupId, null, cursor); }
  catch (error) {
    if (epoch === current && previous === modalRoot.firstChild && applicable() && ["RESOURCE_NOT_AVAILABLE", "CURRENT_GRANT_REQUIRED"].includes(error?.message || error)) closeDialog();
    throw error;
  }
  if (epoch !== current || groupId !== s.groupId || userId !== s.userId || previous !== modalRoot.firstChild || !applicable()) return false;
  const entries = rows.slice().reverse().map(message => `<article class="message-content"><div class="message-meta"><strong>${e(message.authorId === userId ? "我" : message.authorId)}</strong><time>${e(new Date(message.createdAt).toLocaleString("zh-CN"))}</time></div><div class="message-text">${e(message.deleted ? "此消息已被删除" : message.body)}</div></article>`).join("");
  const older = rows.length === 50 ? button("更早的消息", "message-history", "ghost", `data-id="${e(rows.at(-1).id)}"`) : "";
  dialog("群聊历史", "按发送时间排列；关闭后返回实时群聊。", `<div data-message-history>${entries || empty("没有更早的消息", "你已经看完了这个群的历史消息。")}</div>${older}${button("返回最新消息", "dialog-close", "ghost")}`, null);
  return true;
}
const actions = {
  "message-history": target => run(() => showMessageHistory(s.groupId, target.dataset.id), { modal: true }),
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
  "friend-requests": () =>
    dialog(
      "好友申请",
      "当前服务尚未提供待处理申请列表。",
      field("id", "已有申请 ID", "text", "", 'required maxlength="128"'),
      async (data) => {
        await request("friend-accept", data.get("id"), {});
        closeDialog();
        await refresh();
      },
      "接受申请",
    ),
  invitation: () =>
    dialog(
      "接受群邀请",
      "输入朋友发给你的邀请 ID。",
      field("id", "邀请 ID", "text", "", 'required maxlength="128"'),
      async (data) => {
        await request("invitation-accept", data.get("id"), {});
        closeDialog();
        await refresh();
      },
      "接受邀请",
    ),
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
    navigate("group");
    s.groupId = b.dataset.id;
    return run((current) => loadContext(current));
  },
  "open-room": () => {
    navigate("room");
    return run((current) => loadContext(current));
  },
  "join-room": (b) =>
    dialog(
      "加入房间",
      s.activity && s.roomId !== b.dataset.id
        ? "切换房间会断开当前房间的语音和联机。房间有密码时请填写。"
        : "房间有密码时请填写；没有密码可以留空。",
      field(
        "password",
        "房间密码（可选）",
        "password",
        "",
        'autocomplete="off"',
      ),
      (data) => joinRoom(b.dataset.id, data.get("password") || null),
      "加入房间",
    ),
  leave: () =>
    confirmation("离开房间", "将断开当前房间的语音和联机连接。", async () => {
      Object.assign(s, await bridge.leave(), {
        roomId: null,
        voice: null,
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
        await bridge.logout();
        epoch++;
        Object.assign(s, {
          userId: null,
          activity: null,
          voice: null,
          groups: [],
          rooms: [],
          friends: [],
          messages: [],
          members: [],
          groupMembers: [],
          drafts: {},
          roomId: null,
          groupId: null,
          view: "lobby",
          authMode: "login",
        });
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
  "retry-voice": () =>
    run(async () => {
      try {
        s.voice = await bridge.connectVoice(s.activity.activityId);
      } finally {
        s.voice = await bridge.voiceSnapshot();
      }
    }),
  "delete-message": (b) =>
    confirmation("删除消息", "删除后正文将不可见，保留删除标记。", async () => {
      await request("message-delete", b.dataset.id);
      await loadContext();
    }),
  "invite-member": () =>
    dialog(
      "邀请队友",
      "邀请账号加入当前群组。",
      field("userId", "队友账号 ID", "text", "", 'required maxlength="128"'),
      async (data) => {
        const result = await request("group-invite", s.groupId, {
          inviteeId: data.get("userId"),
        });
        closeDialog();
        dialog(
          "邀请已创建",
          "将邀请 ID 发送给队友。",
          `<p class="invite-id">${e(result.id)}</p>`,
          null,
        );
      },
      "创建邀请",
    ),
  "manage-group": () => {
    const g = s.groups.find((g) => g.id === s.groupId);
    dialog(
      "群组管理",
      "权限由服务端在执行时校验。",
      field("name", "群名称", "text", g.name, 'required maxlength="80"') +
        field(
          "description",
          "群简介",
          "text",
          g.description,
          'maxlength="500"',
        ) + avatarField +
        `<div class="management-actions">${button("邀请队友", "invite-member")}${button("管理记录", "audit")}${button("退出群组", "leave-group", "danger")}${g.ownerUserId === s.userId ? button("解散群组", "dissolve-group", "danger") : ""}</div>`,
      async (data) => {
        await request("group-update", g.id, {
          name: data.get("name"),
          description: data.get("description"),
          avatar: await readGroupAvatar(data.get("avatar"), g.avatar),
        });
        closeDialog();
        await refresh();
      },
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
  audit: () =>
    run(async () => {
      const entries = await request("audit", s.groupId);
      dialog(
        "管理记录",
        "仅展示服务端返回的记录。",
        entries.length
          ? `<div class="audit-list">${entries.map((x) => `<p>${e(x.action || x.eventType)} · ${e(x.createdAt)}</p>`).join("")}</div>`
          : empty("暂无管理记录", "当前没有可展示的记录。"),
        null,
      );
    }),
  "manage-room": () => {
    const r = s.rooms.find((r) => r.id === s.roomId);
    dialog(
      "房间设置",
      "房间资料与活动分开管理。",
      field("name", "房间名称", "text", r.name, 'required maxlength="80"') +
        field("gameName", "游戏名称", "text", r.gameName, 'maxlength="80"') +
        field("description", "介绍", "text", r.description, 'maxlength="500"') +
        field("password", "新的房间密码（留空清除）", "password") +
        `<div class="management-actions">${button(r.openState === "OPEN" ? "暂停加入" : "开放加入", "toggle-room")}${button("结束活动", "end-activity", "danger")}${button("删除房间", "delete-room", "danger")}</div>`,
      async (data) => {
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
      },
      "保存",
    );
  },
  "toggle-room": () =>
    run(async () => {
      const r = s.rooms.find((r) => r.id === s.roomId);
      await request(
        r.openState === "OPEN" ? "room-pause" : "room-open",
        r.id,
        {},
      );
      closeDialog();
      await refresh();
    }),
  "end-activity": () =>
    confirmation("结束活动", "所有成员将退出活动，房间资料保留。", async () => {
      await request("activity-end", s.activity.activityId, {});
      Object.assign(s, await bridge.leave(), {
        roomId: null,
        voice: null,
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
  if (event.target.id === "voice-volume") {
    const value = Number(event.target.value);
    run(async () => {
      s.voice = await bridge.setVolume(value);
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
