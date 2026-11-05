import { CoreBridge, ProductionStore } from "../src/index.js";

const store = new ProductionStore();
const feedback = document.getElementById("feedback");
const deviceId = crypto.randomUUID();
let bridge;

try {
  bridge = new CoreBridge();
} catch (error) {
  feedback.textContent = "桌面核心不可用，请从签名安装包启动织界。";
}

function showError(error) {
  feedback.textContent = error instanceof Error ? error.message : String(error);
}

store.addEventListener("changed", () => {
  const activity = store.state.activity;
  document.getElementById("connection-summary").textContent = activity
    ? `语音：${activity.voice}；虚拟网络：${activity.network}；IP：${activity.virtualIpv4 ?? "分配中"}`
    : "当前未加入房间";
  const voice = store.state.voice;
  document.getElementById("voice-state").textContent = voice
    ? `语音：${voice.state}；${voice.muted ? "已静音" : "麦克风开启"}；${voice.receiveEnabled ? "接收开启" : "接收关闭"}`
    : "语音未连接";
});

document.getElementById("login-form").addEventListener("submit", async event => {
  event.preventDefault();
  feedback.textContent = "";
  if (!bridge) return;
  const data = new FormData(event.currentTarget);
  try {
    store.apply(await bridge.login(data.get("email"), data.get("password"), deviceId));
    document.getElementById("login-panel").hidden = true;
    document.getElementById("lobby-panel").hidden = false;
    await loadGroups();
  } catch (error) {
    showError(error);
  }
});

async function loadGroups() {
  const groups = await bridge.groups();
  const list = document.getElementById("room-list");
  list.replaceChildren();
  for (const group of groups) {
    for (const room of await bridge.rooms(group.id)) {
      const item = document.createElement("article");
      item.className = "room-card";
      const heading = document.createElement("h3");
      heading.textContent = `${group.name} / ${room.name}`;
      const button = document.createElement("button");
      button.className = "button primary";
      button.textContent = store.state.activity?.activityId === room.currentActivityId ? "回到房间" : "加入房间";
      button.disabled = room.openState !== "OPEN";
      button.addEventListener("click", () => joinRoom(room.id));
      item.append(heading, button);
      list.append(item);
    }
  }
}

async function joinRoom(roomId) {
  try {
    const snapshot = await bridge.join(roomId, deviceId, null, crypto.randomUUID());
    store.apply(snapshot);
    const activityId = store.state.activity?.activityId;
    if (!activityId) return;
    try {
      store.applyVoice(await bridge.connectVoice(activityId));
    } catch (error) {
      store.applyVoice(await bridge.voiceSnapshot());
      showError(error);
    }
  } catch (error) {
    showError(error);
  }
}

document.getElementById("logout").addEventListener("click", async () => {
  if (!bridge) return;
  try {
    store.apply(await bridge.logout());
    document.getElementById("login-panel").hidden = false;
    document.getElementById("lobby-panel").hidden = true;
  } catch (error) {
    showError(error);
  }
});

for (const [id, action] of [
  ["voice-mute", () => bridge.setMuted(!store.state.voice?.muted)],
  ["voice-receive", () => bridge.setReceiveEnabled(!store.state.voice?.receiveEnabled)],
]) {
  document.getElementById(id).addEventListener("click", async () => {
    if (!bridge) return;
    try {
      store.applyVoice(await action());
    } catch (error) {
      showError(error);
    }
  });
}

document.getElementById("voice-volume").addEventListener("change", async event => {
  if (!bridge) return;
  try {
    store.applyVoice(await bridge.setVolume(Number(event.currentTarget.value)));
  } catch (error) {
    showError(error);
  }
});
