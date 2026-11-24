# 织界桌面客户端

## 本地构建与预览

在 `workspace/desktop/ui/` 执行：

```text
node scripts/build.mjs
node --test tests/bridge.test.js tests/contract.test.js
node tests/browser.mjs
```

浏览器回归会启动独立本地 HTTP 服务与无头浏览器，输出截图到 `workspace/target/desktop-ui-browser/`（不计入产品代码摘要），结束后关闭服务。测试夹具只由测试进程注入，正式客户端没有模拟登录或演示数据入口。

在 `workspace/` 执行：

```text
cargo test --offline -p zhijie-core -p zhijie-shell
cargo build --offline -p zhijie-shell --features tauri/custom-protocol
```

构建前须先生成 UI。`tauri/custom-protocol` 将本地界面嵌入可执行文件，直接运行 `target/debug/zhijie-shell.exe`（Windows）即可预览，无须启动 5173 前端服务器。其他平台使用对应无扩展名可执行文件，仍须各自编译和实机验证。

真实账号服务默认连接 `http://127.0.0.1:8080`，可由进程环境变量 `ZHIJIE_CONTROL_PLANE_URL` 配置。未启动后端时，窗口正常展示并报告连接失败，不提供模拟登录。当前任务不搭建数据库或服务环境。

## 设计与职责

- 构建直接复制 `design/prototype/styles.css` 和 `design/ui/ui.css`；产品样式不再维护第二份副本。
- `ui/src/views.js`：从真实状态生成页面；所有服务文本转义。
- `ui/public/app.js`：导航、请求、确认、表单、草稿与桌面窗口事件。
- `ui/src/bridge.js`：受限 Tauri 命令；WebView 不持有后端令牌。
- `shell/src/main.rs`：固定资源名到 HTTP API 的映射和活动/语音生命周期。
- `ui/public/desktop.css`：桌面容器与窄窗口适配，不修改已批准设计源文件。

页面覆盖登录/注册/验证/找回、大厅、好友、群组/群聊、房间与设置。待处理申请列表、完整成员地址、网络 Helper 编排及部分治理操作尚无完整桌面实现，不能据此认定原型全部功能已验收。用户资料目前受现有 API 限制，部分成员以账号 ID 展示。

原生窗口检查脚本 `ui/tests/window-smoke.mjs` 仅用于开发验证，需要显式以 WebView2 调试参数开启本地 11999 端口；正常运行不设置该参数。它验证真实桥接、最大化/还原并保存 WebView 截图；`--close` 额外执行最小化与关闭。不应在正常预览或发布时开放调试端口。
