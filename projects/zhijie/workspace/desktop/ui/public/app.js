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
    const item = document.createElement("article");
    item.className = "room-card";
    const heading = document.createElement("h3");
    heading.textContent = group.name;
    item.append(heading);
    list.append(item);
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
