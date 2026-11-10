import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";

// 仅连接本次手动开启调试端口的本地 WebView，不注入业务数据或凭据。
const pages = await (await fetch("http://127.0.0.1:11999/json/list")).json();
const page = pages.find((x) => x.type === "page");
assert.ok(page, "未找到织界 WebView");
const socket = new WebSocket(page.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map();
await new Promise((r, j) => {
  socket.onopen = r;
  socket.onerror = j;
});
socket.onmessage = (event) => {
  const m = JSON.parse(event.data),
    p = pending.get(m.id);
  if (p) {
    pending.delete(m.id);
    m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result);
  }
};
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
    awaitPromise: true,
    returnByValue: true,
  });
  if (r.exceptionDetails)
    throw new Error(
      r.exceptionDetails.exception?.description || r.exceptionDetails.text,
    );
  return r.result.value;
}
try {
  const result = await evaluate(`(async()=>{
    const w=window.__TAURI__.window.getCurrentWindow();
    const initial=await w.isMaximized();
    await w.toggleMaximize();const maximized=await w.isMaximized();
    await w.toggleMaximize();const restored=await w.isMaximized();
    return {bridge:typeof window.__TAURI__.core.invoke,initial,maximized,restored,titlebar:!!document.querySelector('.desktop-titlebar'),login:!!document.querySelector('.auth-card'),controls:document.querySelectorAll('[data-window]').length};
  })()`);
  assert.equal(result.bridge, "function");
  assert.equal(result.maximized, !result.initial);
  assert.equal(result.restored, result.initial);
  assert.ok(result.titlebar && result.login);
  assert.equal(result.controls, 3);
  const screenshot = await command("Page.captureScreenshot", { format: "png" });
  writeFileSync(
    ".test-artifacts/native-window.png",
    Buffer.from(screenshot.data, "base64"),
  );
  console.log(JSON.stringify(result));
  if (process.argv.includes("--close")) {
    await evaluate("window.__TAURI__.window.getCurrentWindow().minimize()");
    console.log("Native minimize command accepted");
    // 关闭会终止调试连接，因此只发出命令，随后由调用方检查进程退出。
    socket.send(
      JSON.stringify({
        id: ++seq,
        method: "Runtime.evaluate",
        params: {
          expression: "window.__TAURI__.window.getCurrentWindow().close()",
        },
      }),
    );
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
} finally {
  socket.close();
}
