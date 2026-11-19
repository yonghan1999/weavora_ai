# Cargo 许可材料收集

## 当前材料

从当前离线、锁定 Cargo metadata 的 575 个 crates.io 包收集许可材料，全部先核对 Cargo.lock 中的归档 SHA-256，再核对归档 Cargo.toml 的包名、版本和许可声明。523 项有已读取原文，960 条许可/NOTICE/COPYRIGHT 引用按摘要去重为 310 份原始字节。入口为 [cargo-license-materials/README.md](cargo-license-materials/README.md)，完整坐标、归档摘要及成员来源见同目录 manifest.json。

52 项没有已读取的匹配文档，保持显式缺口。自有五个包、两个本地 WebRTC 补丁包单列，后者来源及修改见 vendor/README.md。Cargo.lock 另有 anstyle 1.0.14、clap 4.6.7、clap_builder 4.6.7、clap_lex 1.1.1、cxxbridge-cmd 1.0.205 五项未进入当前 metadata 解析；进一步确认缓存归档存在后，也核对锁摘要并补收其十条原文引用，未新增去重文本，总计 580 个归档、970 条引用、310 份原文。manifest 的 unselectedLockedPackages 保留解析范围区别，未改当前依赖清单。首次全锁集合比较失败日志 target/cargo-license-materials-collect.log 保留，初版材料保留于 target/cargo-license-materials-before-lock-only。

所有条目保持 unreviewed、releaseApproved=false。许可表达式按上游原样保留，不选择 OR 分支。完整解析含构建、开发和其他平台依赖，不能推定它们均包含于最终制品；shaded/嵌套依赖、WebRTC 预编译库、驱动和实际分发/NOTICE/源码义务仍须单独审核。npm 当前锁文件只有自有 UI 包，没有第三方 npm 条目；不以此推定外部构建工具或最终运行环境已审核。

## 精确提交上游补充

52 项归档再次搜索文件名确认没有独立许可候选；其中 49 项的 Cargo.toml repository 与 .cargo_vcs_info.json 可追溯至精确 GitHub 提交，三项来源不完整。补收入口为 scripts/collect-cargo-upstream-licenses.py，输出 [cargo-upstream-license-materials/README.md](cargo-upstream-license-materials/README.md)。只用归档记录的提交，不使用默认分支、最新版或推测标签，不将仓库根许可自动判定适用于每个包。

初次 GitHub tree API 全部因匿名额度返回 403，结果保留于 target/cargo-upstream-license-before-raw-fallback 与 target/cargo-upstream-license-collect.log。后续保留 tree 获取失败，同时尝试同一精确提交的固定根文件名；没有 tree 响应时 gitBlobSha1=null，明确只有提交 URL、HTTPS 原文与 SHA-256，不能声称已完成 blob 交叉验证或签名验证。所有失败和未找到项继续保留，未据收集结果选择许可分支或批准发布。

实际 43 项补收到 58 条引用、13 份去重原文，其中 LiveKit 相关包的根 NOTICE 同时保留。其余九项为 from_variants/from_variants_impl 1.0.2、r-efi 5.3.0/6.0.0、selectors 0.38.0、tun 0.8.14（固定根文件名未收取成功），libappindicator-sys 0.9.0（无 repository 声明）与两个 winapi GNU 0.4.0（无精确 VCS 提交）。根目录固定候选未找到不等于上游没有许可证；子目录、其他命名或源码头后续核查。原始 crate 的 52 项缺口未被补充材料覆盖改写。

```powershell
python "scripts/collect-cargo-upstream-licenses.py" --check --output-directory "docs/engineering/cargo-upstream-license-materials"
python "scripts/test-cargo-upstream-licenses.py"
```

首次生成去掉 --check，指定不存在的新目录，实际下载仅访问 GitHub 固定 API/raw HTTPS 地址；不发送凭据，不允许仓库声明指定任意下载主机。--check 不联网，重新读取摘要匹配的 crate 来源，再以保存的响应重走收集流程，核对 tree、成员、文本、目录、来源关联和统计。九项工具测试覆盖原字节、blob 不匹配、来源无法解析、主机限制、归档/输出篡改、删除材料、改来源/统计、失败回放、拒绝覆盖及 403 后精确提交固定文件回退。复用已有 SHA/路径边界与同一收集流程（DRY/KISS），原文收集和适用性审核保持各自职责，不引入自动许可批准。

实际下载、九项工具测试及离线重建核对日志为 target/cargo-upstream-license-collect-final.log、target/cargo-upstream-license-tests-final.log、target/cargo-upstream-license-check.log。全部退出零，但每项仍 unreviewed、releaseApproved=false。兼容契约复用该离线核对入口，不在检查时重试网络。

最终兼容/发布契约、工作区及变更空白检查通过，日志 target/cargo-upstream-license-compatibility.log、target/cargo-upstream-license-release.log、target/cargo-upstream-license-workspace.log；开发摘要 target/cargo-upstream-license-development-summary.json。制品摘要保持不变，后端健康 UP，未启动桌面、安装或更改试用数据；正式任务和总体交付状态未提升。

## 九项剩余材料与 Windows 试用构建

[归档声明索引](cargo-license-header-materials/README.md) 中七项有源码首注释或上下文材料，共 67 条引用、10 份去重材料；`from_variants` 和 `from_variants_impl` 未匹配到此类声明。`python "scripts/test-cargo-license-headers.py"` 七项通过，`python "scripts/collect-cargo-license-headers.py" --check --output-directory "docs/engineering/cargo-license-header-materials"` 从锁定归档离线核对通过。九项均未审核，声明不是完整许可。

按普通试用桌面的实际特性复核：

```powershell
cargo tree --locked --offline --target x86_64-pc-windows-msvc -p zhijie-shell --features tauri/custom-protocol -e normal,no-proc-macro --prefix none
cargo tree --locked --offline --target x86_64-pc-windows-msvc -p zhijie-shell --features tauri/custom-protocol -e normal,build --prefix none
```

在这九项中，第一条仅命中 `tun 0.8.14`、`from_variants 1.0.2`；包含构建依赖及过程宏的第二条还命中 `selectors 0.38.0`、`from_variants_impl 1.0.2`。同目标改为 `-p zhijie-helper` 并去掉桌面特性，两种检查均仅命中 `tun`。该结果用于安排审核顺序，不是最终 EXE 链接清单，也不排除其他平台或构建工具的义务。

## 复核归档材料

从 workspace 执行：

```powershell
python "scripts/collect-cargo-license-materials.py" --check --output-directory "docs/engineering/cargo-license-materials"
python "scripts/test-cargo-license-materials.py"
```

首次生成去掉 --check，必须选择不存在的新目录；工具拒绝覆盖。--registry-directory 可指定 Cargo registry 根目录。工具不下载或运行包代码，不向文件系统提取归档成员路径；只把匹配原文字节写入摘要命名文件。链接成员不跟随，超大文档保留未读取状态，越界路径、重复成员、声明不符、缺失缓存或摘要不符拒绝；路径/缓存来源不写入机器绝对目录。

--check 重新解析当前 metadata 和清单、读取全部归档、核对锁校验和并重建材料，逐字节比较输出和目录成员，不能通过删除文档或自改 manifest 摘要自证通过。兼容契约直接复用此入口；依赖 Python 3.11+、Cargo 及完整本地缓存，不访问网络。十项工具测试覆盖原字节保持、去重、声明绑定、摘要篡改、缺失缓存、路径越界、链接、license-file、未选中包、拒绝覆盖和输出删改。

使用标准库及现有 Cargo metadata，收集、归档读取、索引渲染和写入/复核职责分开；生成与检查复用相同流程（KISS/DRY/单一职责）。未增加许可自动批准或通用策略引擎（YAGNI）。本轮没有产品行为变化，不需重建制品；此开发记录不替代 CLI 正式任务和阶段证据。

最终收集、十项工具测试和实际归档重建核对退出零，日志 target/cargo-license-materials-collect-complete.log、target/cargo-license-materials-tests-complete.log、target/cargo-license-materials-check-complete.log。兼容契约、工作区、发布契约和变更空白检查通过，日志 target/cargo-license-materials-compatibility-complete.log、target/cargo-license-materials-workspace.log、target/cargo-license-materials-release.log。开发摘要见 target/cargo-license-materials-development-summary.json。release 包与普通桌面 SHA-256 保持 sync-cursor-operation-lock.md 的版本，试用后端健康 UP；未启动桌面、安装或修改试用数据，未推进正式任务和阶段。
