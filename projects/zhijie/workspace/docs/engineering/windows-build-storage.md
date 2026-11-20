# Windows 候选构建的 D 盘隔离

## 问题与实现

用户明确要求避免 C 盘新增大体积写入并及时清理。此前 `build-candidate.ps1` 虽然把产品放在项目 `target`，但沿用调用环境的 Cargo 缓存和系统临时目录；Windows Tauri 配置也未启用本地打包工具缓存。仅设置构建输出不足以约束这些写入。

现在候选构建要求 D 盘工作区，拒绝重定向的输出及缓存目录；当前进程临时目录、Cargo/npm 缓存和 Cargo 输出均指向项目忽略目录 `target`。每轮使用独立临时目录，在 `finally` 恢复原有或原本不存在的环境变量和工作目录，只删除本轮临时文件，不删除共享缓存、交付物、试用数据或其他运行目录。已有 Rust 工具链可读取，但禁止缺失工具链的自动安装；依赖缺失时按原离线规则失败，不自动复制 C 盘缓存。

Windows 配置开启 `bundle.useLocalToolsDir`，与仓库使用的 Tauri CLI 2.12.5 配置定义一致，打包工具使用项目 `target/.tauri`。源码查阅只在内存中完成，没有把上游文件保存到系统盘；本轮没有运行真实打包下载，也不宣称工具缓存已完成迁移或操作系统零写入。已有试用 ZIP、安装器和产品二进制不变。

环境设置和恢复集中在构建入口，子命令仍复用既有调用函数，无新模块、依赖或全局配置（KISS、DRY、YAGNI）；环境生命周期与产品编译职责分离。准备和复现命令见 `packaging/windows/candidate-build.md`，配置摘要仅更新真实变化的两个条目。

## 直接开发验证

新增 `packaging/windows/test-build-candidate.ps1`，在 D 盘一次性夹具中执行真实构建入口，使用假 node/cargo/python 避免重编译或运行安装器。逐命令检查 TEMP/TMP/TMPDIR、Cargo/npm 路径和自动安装设置；覆盖正常返回、第一步失败、嵌套 UI 工作目录失败、嵌套桌面工作目录失败、最终包校验失败。全部要求环境与位置还原、临时文件实际删除、共享缓存哨兵保留。原本不存在的环境变量也必须恢复为不存在。

首次夹具比较混合路径分隔符失败；第二、三次计数断言失败分别暴露了错误调用次数和 PowerShell `script:` 在被调用脚本中的作用域变化。改为规范化夹具根目录及共享可变测试状态后通过；未放宽生产隔离或成功/失败要求。失败只发生在测试夹具，没有编译或发布产品。

- `node scripts/test-release.mjs --contract`：通过，日志 `target/network-pump-diagnostic-d8ffab9/candidate-release-final.log`；五个候选构建环境场景全部通过，配置摘要匹配，既有 Linux 检查通过；macOS 五项中一项依赖真实 macOS/签名环境而跳过，其余通过。
- `python -B packaging/windows/test-verify-package.py -v`：六项通过，日志 `target/network-pump-diagnostic-d8ffab9/candidate-package-verifier.log`。
- 测试创建的 `target/candidate-environment-*` 和其独立临时目录均由测试收尾删除；已有网络回归留下的约 98 MiB 临时目录不属于本次构建夹具，不重新绕过之前被拒绝的清理。

这些证据证明脚本设置、错误传播、收尾和配置契约，不证明真实构建产物、签名、安装升级卸载、真实 TUN、跨机或目标服务器验收。没有推进阶段或将 TASK-019～023 标为完成；正式工作流证据仍须使用 CLI。
