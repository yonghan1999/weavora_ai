# 如何登记支持矩阵证据

`support-matrix.json` 区分每个平台的构建（`shellBuild`）、安装/更新/卸载（`installUpdateUninstall`）和实际语音与虚拟网络（`realVoiceAndTun`）。没有完成的测试保持 `unverified`。Windows 既有的 `checked_on_windows` 只表示构建检查，不代表安装或互通验证。

- 实测后，平台的 `evidence` 数组每条记录使用 `claim`、`path`、`sha256`。`claim` 必须对应上述某一字段；`verified`、`passed` 或 `failed` 必须有该项证据，不得用构建日志替代安装或互通记录。
- `testedGames` 中每个游戏分别填入非空的 `name`、`version` 和 `evidence` 数组。游戏证据项包含 `path`、`sha256`；只做过工具互通时，不填写游戏通过记录。
- `path` 是相对于项目业务 `workspace/` 的普通文件路径；`sha256` 是该文件的 64 位小写十六进制摘要。登记前检查记录已脱敏，包含系统/架构、制品版本、测试步骤、实际观察和结论；游戏互通还需说明参与的平台组合及游戏版本。

在业务 `workspace/` 运行 `node scripts/test-compatibility.mjs --validate-matrix` 和 `node scripts/test-release.mjs --contract`，两者使用同一矩阵校验。它们核对记录结构、文件和摘要，不判断记录内容是否足以证明结论，也不替代真实测试或人工验收。不要因校验通过而提升状态。
