// 邀请码只从片段读取；本页不请求账号、访问控制面或核销邀请。
const message = document.getElementById("message");
const link = document.getElementById("open"), fallback = document.getElementById("fallback"), field = document.querySelector("input");
function present() {
  const code = location.hash.slice(1), hasQuery = !!location.search;
  history.replaceState(null, "", location.pathname);
  const valid = !hasQuery && code.length === 43 && /^[A-Za-z0-9_-]+$/.test(code);
  if (valid) {
    link.href = `zhijie://room/${code}`;
    link.hidden = false;
    fallback.hidden = false;
    field.value = code;
    message.textContent = "正在打开织界。若浏览器未启动应用，请点击下方按钮。确认加入后才会进入房间。";
    location.assign(link.href);
  } else {
    link.hidden = fallback.hidden = true;
    link.removeAttribute("href");
    field.value = "";
    message.textContent = "邀请链接不完整，请让邀请人重新分享。";
  }
}
window.addEventListener("hashchange", present);
present();
