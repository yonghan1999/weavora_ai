# 桌面页面回归

在 `desktop/ui` 执行 `node scripts/build.mjs`、`node --test "tests/*.test.js"`，再执行 `node tests/browser.mjs`。页面回归与 `GroupBrowserIntegrationTest` 共用浏览器查找逻辑：Windows 会检查 Chrome/Edge 的常见安装位置，macOS 检查 `/Applications`，Linux 检查常见的 Chrome、Chromium、Edge 路径。自定义安装目录请用进程环境变量 `ZHIJIE_TEST_CHROME` 指定可信的浏览器可执行文件绝对路径（不是 `.app` 目录，也不是带参数的命令）。显式路径无效会报错，不会换用其他浏览器。

页面回归使用独立的浏览器配置目录和本地 HTTP 服务；其中的桌面桥接是测试夹具。群组集成用例另需真实控制面、PostgreSQL 和测试用 Rust 客户端。两者都不能代替 Tauri 原生窗口、系统授权、真实音频或安装升级的目标平台验收。
