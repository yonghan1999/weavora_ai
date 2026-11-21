# Windows 候选包只读核对

## 从桌面程序核对嵌入页面

`verify-embedded-ui.mjs` 不运行桌面程序。它用本次 Tauri 构建的 Brotli 缓存定位程序内字节，再解压程序中对应的片段，逐个比对 `dist` 的资源摘要。`index.html` 在嵌入时会被 Tauri 改写，故需单独提供**已审阅的嵌入 HTML 摘要**，不能直接使用未经转换的源 HTML 摘要。HTML 或 Tauri 转换流程变化时须重新审阅，不要从待验证程序临时取一个摘要来让检查通过。

在 D 盘保留同一提交的 `dist`、同次构建的 `tauri-codegen-assets`，以及从最终 ZIP 读出的 `zhijie-shell.exe`。将提取结果写入一个不存在的 `dist` 目录（父目录须已存在），再从它的父目录运行工作区的 `desktop/ui/tests/browser.mjs`，页面测试才会使用成品资源而非开发目录。参数顺序如下：

```powershell
node "packaging/windows/verify-embedded-ui.mjs" "$shellFromZip" "$buildDist" "$assetCache" "$reviewedHtmlSha256" "$reviewDir/dist"
```

成功时标准输出为文件名、程序内偏移与解压后 SHA-256 的 JSON。错版或资源缺失时不创建输出目录，已有目录不覆盖。此项验证不等于原生窗口、Rust 重连行为或安装升级已在成品上实测。

要验证成品页面在真实群变更事件后的行为，在工作区的测试进程中设置 `ZHIJIE_TEST_GROUP_BROWSER=true`，并把 `ZHIJIE_TEST_EXTRACTED_UI` 指向上一步提取的 `dist` 绝对路径（须位于本项目 `target` 内）。运行 `./mvnw.cmd -o -pl control-plane "-Dtest=GroupBrowserIntegrationTest#dissolvingViewedGroupKeepsOtherGroupsActiveRoom" test`。此模式跳过源码页面构建，测试控制面从指定目录提供页面，不改试用包或现有试用数据库。未指定该路径时仍构建并使用源码页面；错误路径不会回退到源码。用同一命令分别指定旧包、新包的已核对页面，比较聊天页和管理弹窗两条路径的结果。这里的原生桥接来自同提交的 Rust 测试客户端，不是启动包内 WebView 窗口。

## 核对 NSIS 产品文件

需要与便携桌面配套的离线后端时，见 [Windows 本机试用后端](preview-backend.md)。

从项目的 `workspace` 运行。检查对象为本机生成的 NSIS 安装包，不需要管理员权限，不运行安装器、桌面、Helper 或驱动。先保留与**同一次构建**对应的二进制和脚本；之后重编译再比对可能得到不同结果。

```powershell
python "packaging/windows/verify-package.py" "target/release/bundle/nsis/织界_0.1.0_x64-setup.exe" --binaries "target/release" --scripts "scripts/local-preview" --driver "target/windows-bundle"
```

使用 Python 3.12+、7-Zip（可用 `--seven-zip` 指定程序路径）。调试包须将 `--binaries` 指向对应的 `target/debug` 或其留存副本。不要用共用暂存目录里的 Helper 代替构建输出：该文件可能是另一个配置的版本。

要从核对通过的构建准备便携试用，在上述命令末尾加 `--portable-name trial-local`。工具把八个已经与包内字节比对的构建输入复制到项目 `target` 的临时目录，再验证每个副本，最后一次性发布 `target/trial-local/app/` 和 `manifest.json`。目标必须在非系统盘；已有试用目录、用户配置和会话不会被替换。可选参数省略时仍只读。生成后先执行 `python -B "scripts/local-preview/trial-desktop.py" "target/trial-local" --check-only`；需要实际试用时再按本地试用说明准备后端、手动启动桌面。

检查器将包内的桌面、Helper、启动脚本、权限模块、Wintun DLL、许可、头文件和来源清单逐一与外部输入比对，并输出包及文件的 SHA-256。缺失、重复、额外产品文件或任一摘要不符时退出非零。7-Zip 展示的 NSIS 插件目录不属于这八项产品输入。驱动应先通过 `prepare-wintun.py` 独立核对锁文件、归档、架构及签名；本工具不认证安装器签名、插件和安装逻辑，也不验证安装、升级、卸载或跨机互通。
