# Cargo 许可索引与锁文件重新绑定

## 已复现问题与原因

前轮同步修复的额外兼容检查失败于 `CARGO_LICENSE_OUTPUT_CHANGED: manifest.json`。本轮在修改前再次直接执行归档材料 `--check`，`target/cargo-license-refresh-ea42650/before.log` 同样退出非零；没有跳过检查或降低一致性要求。

历史只读核对 `lock-history.log` 找到原摘要 `caa46faf...` 对应 `71dd0f0` 的 Cargo.lock；`801e87c` 此后为核心包增加既有 `rustls-native-certs` 依赖边，但未刷新许可索引。当前锁文件与 HEAD 字节一致，摘要为 `9557bab3...`；LF/CRLF 两种编码均不能解释原摘要。没有更改任何依赖版本、锁文件或产品代码。

## 重建与最小更新

复用现有收集器，在 D 盘 `target/cargo-license-refresh-ea42650` 的全新目录重建三层材料：

1. 从当前离线锁定 Cargo metadata、实际本地归档及当前依赖清单重建归档许可材料，逐个验证归档校验和与声明。
2. 先用原检查器核验已有上游材料，再只用保存的响应重放收集，不重新下载、不将原 403/未找到项改为成功。
3. 从新上游索引和实际归档重建剩余源码头声明材料。

`refresh.log` 逐一比较三层全部 339 个文件：仅三份 manifest 的关联摘要不同，README、原文字节、来源、条目、统计和未审核状态全部不变。三个新清单均通过其原生重建检查；按已验证结果仅更新原目录中的 `cargoLockSha256` 和两层 `inputEvidenceSha256`。`applied-comparison.log` 再确认落盘清单与重建副本逐字节一致，而非只把预期摘要填入自证。

新增回归测试模拟只修改锁文件依赖边、所有归档与许可文本均不变的情况：旧材料必须被原检查器拒绝且不被覆盖，新目录重建后必须通过。保留原字节强绑定，未引入“忽略锁变化”、规范化摘要或自动许可批准。复用现有收集/检查职责和回放机制，没有新增依赖或生产模块（KISS、DRY、单一职责、YAGNI）。

## 本机直接验证

- `python -B scripts/test-cargo-license-materials.py -v`：十一项通过，`materials-tests.log`。
- `python -B scripts/test-cargo-upstream-licenses.py -v`：九项通过，`upstream-licenses-tests.log`。
- `python -B scripts/test-cargo-license-headers.py -v`：七项通过，`headers-tests.log`。
- 三个实际完整材料 `--check` 均通过：`archive-check.log`、`upstream-check.log`、`headers-check.log`。
- `node scripts/test-compatibility.mjs --validate-matrix`：通过，`compatibility.log`，修复前失败并未被删除。
- `node scripts/test-release.mjs --contract`：通过，`release.log`；macOS 实机/签名依赖项仍显式跳过，不作为平台验收通过。
- 本轮差异空白检查通过；只提交三份清单、对应回归测试和本说明，不夹带历史未提交清单或工具改动。

全部日志路径位于上述本轮目录。新增缓存与临时文件均位于 D 盘忽略目录，仅设置当前进程环境，未访问根 `tmp/`。收尾删除重建副本的命令被安全策略拦截，未换工具绕过，副本暂保留在本轮 D 盘目录；日志和复现脚本亦保留，未处理其他轮次文件或共享缓存。

## 交付边界

归档范围仍是 575 个注册包、5 个锁定未选中包和 7 个单独审核本地包；523 项有归档原文，52 项归档缺口保留；其中 43 项有上游补充，其余 9 项中 7 项有源码头或说明声明。全部仍 `unreviewed`、`releaseApproved=false`，没有选择许可分支或完成法律/分发义务审核。许可完整性核对不替代 AC-043 完整审核。

正式仍 E_IMPLEMENTATION/in_progress，TASK-019～023、F/G/H/I 及端到端交付保持开放。未重建应用、更新试用 ZIP、运行安装器或原生桌面，没有修改试用数据、音频问题或环境要求。本说明是开发证据索引，不是 CLI 正式执行证据或验收批准。
