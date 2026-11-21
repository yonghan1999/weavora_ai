# 桌面页面回归

在 `desktop/ui` 执行 `node scripts/build.mjs`、`node --test "tests/*.test.js"`，再执行 `node tests/browser.mjs`。页面回归与 `GroupBrowserIntegrationTest` 共用浏览器查找逻辑：Windows 会检查 Chrome/Edge 的常见安装位置，macOS 检查 `/Applications`，Linux 检查常见的 Chrome、Chromium、Edge 路径。自定义安装目录请用进程环境变量 `ZHIJIE_TEST_CHROME` 指定可信的浏览器可执行文件绝对路径（不是 `.app` 目录，也不是带参数的命令）。显式路径无效会报错，不会换用其他浏览器。

页面回归使用独立的浏览器配置目录和本地 HTTP 服务；其中的桌面桥接是测试夹具。群组集成用例另需真实控制面、PostgreSQL 和测试用 Rust 客户端。两者都不能代替 Tauri 原生窗口、系统授权、真实音频或安装升级的目标平台验收。

### Windows 试用包的页面边框与键盘焦点

登录页通过浏览器实际派发 Tab 按键，检查获得键盘焦点的控件仍有轮廓线，并检查 `html`、`body`、`#app`、`.desktop-app` 没有整页边框或轮廓线。这检查的是页面 CSS，不是 Windows 窗口管理器绘制的外框。

`f6a49f3` 试用包实测：从已校验摘要的 ZIP 读取桌面程序，在程序内定位并解出 10 个页面资源，与上个已核对的嵌入页面逐字节一致。使用解出的实际资源和受控桌面桥接，172 项页面检查通过，脚本错误为零。证据位于工作区 `target/window-shadow-startup-25fdb8d/`：`embedded-assets.log` 含各资源在程序中的偏移和内容摘要，`embedded-browser.json` 列出通过项。对两个独立临时副本分别添加整页内描边、隐藏控件焦点环，两项新增断言分别按预期失败，见 `embedded-negative-controls.json`。未启动产品桌面，不能据此宣称原生蓝边已消失。
