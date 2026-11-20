# macOS 桌面麦克风验收

桌面应用声明麦克风用于房间通话和本机麦克风测试，录音授权只配在桌面签名配置中，不添加到特权网络 Helper。不申请摄像头或系统声音捕获权限。候选支持基线是 macOS 13+，Intel 与 Apple Silicon 都还需要实机验证。

从 `workspace` 执行 `python3 -B "packaging/macos/check-audio-bundle.py"` 仅检查源码配置。在 Mac 上对实际候选执行 `python3 -B "packaging/macos/check-audio-bundle.py" --app "/绝对路径/织界.app" --arch universal`。按预期交付物选择 `x86_64`（仅 Intel）、`arm64`（仅 Apple Silicon）或 `universal`（两者都包含），不要按检查机器选择。检查器核对应用标识、应用版本、最低系统版本、麦克风用途、主程序的实际架构及各架构的签名和麦克风授权，不会修改或重签应用。通过不等于公证、发行身份验证或已在两个架构运行。

`python3 -B "packaging/macos/test_audio_bundle.py" -v` 验证检查器本身。在有 Xcode 命令行工具的 Mac 上，它还会编译临时双架构程序，在临时 `.app` 上做本机临时签名并核对结果；其他系统明确跳过该用例。这个夹具不是织界成品。

两种架构都应记录实际版本与成品摘要。用尚未授权的测试用户操作麦克风，核对系统弹窗中的用途；拒绝时验证语音不假报成功且独立联机功能不受影响。再由用户在系统设置中允许麦克风，重新打开应用并检查本地输入电平与另一设备听到的声音。无设备、未授权或未实测均不得记录为通过。
