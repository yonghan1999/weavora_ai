# 固定 SDK 源码补丁

来源为 crates.io 的 `libwebrtc 0.3.55`、`webrtc-sys 0.3.52`，上游仓库 https://github.com/livekit/rust-sdks 。保留上游版权和 NOTICE；Apache 2.0 文本见 LICENSE-APACHE-2.0，WebRTC 及附带第三方声明见 webrtc-sys-0.3.52/NOTICE.md。原始 crate 校验和分别为 `c0399e372f69d3bb3ad8d51f6354f3090c57317de4f61521aa2f5a542c06fe1f`、`bae66695bde7b250a9e5f7976db6015e30fa06033afc1745e5499d55aa51612a`。

本地改动：在既有 AudioTrack CXX 桥接中暴露 WebRTC AudioTrackInterface 的 SetVolume/GetVolume，并通过 native RtcAudioTrack 和公共 RtcAudioTrack 提供安全接口。原生层拒绝非有限值及 [0,10] 外的增益，不创建新指针、观察者、线程或生命周期边。应用层将总音量与成员音量相乘后设置远端音轨，不改变系统全局音量。

音轨清理补丁：`remove_track` 通过既有 `RtcError` 结构返回 WebRTC 原生状态，Rust 层仍提供相同 `Result<(), RtcError>`，保留 InvalidState 和错误消息。关闭后移除音轨原先经 C++ 异常字符串跨 CXX 返回，在本机可独立复现内存前置条件检查中止；改用与异步接口一致的结构化状态，避免该异常桥接路径。不跳过移除、不将原生错误视为成功、不关闭安全检查。底层 CXX 异常字符串故障的完整成因尚未确定，其他使用异常字符串的接口不在本补丁验证范围内。

仅 workspace Cargo patch 使用这些源码，不修改机器上的 Cargo registry。改动限音量方法、必要 include、remove_track 状态桥接、下述本地输出测试及其构建注册、两个 CHANGELOG 的本地修改记录及本说明。升级时重新审查最小补丁、许可证及原生兼容性。Windows 既有真实 SFU 三轮检查包含远端 source 增益读回、克隆共享及非法增益拒绝；每次原生补丁变化均需回归。这不代表物理扬声器振幅或听感已验证。Linux/macOS 构建仍需各平台实际验证。

本次清理补丁回归：独立 32 次关闭后移除均返回 InvalidState 和消息；真实 SFU 双端连续三轮、群解散撤销、十二端 132 方向/75 秒媒体及最终关闭通过，记录见 `docs/engineering/voice-member-state.md`。未改动全局 registry；核对 livekit-0.10.0 的清理源码与缓存 crate 对应文件逐字节一致。

本地输出测试补丁：新增独立 OutputTest（C++/CXX/Rust owned handle），创建专用 RtcRuntime 与平台 ADM，只注册本地播放回调；不创建 PeerConnection，不调用录音初始化/开始，不注册编码或发布。按当前设备 GUID 匹配（无 GUID 使用 SDK 同样的 index:n 回退）；固定 440 Hz，增益只允许 [0,0.15] 且有限，最多 1.5 秒后只填静音，首尾 30ms 渐变。析构在工作线程停止播放、注销回调、终止 ADM 后释放，runtime 最后释放。应用按总音量映射到最大 0.05，不修改系统音量；离开/切换输出后取消测试。新文件和 build.rs 注册属于本地补丁范围；跨平台设备及人耳试听仍须实测，见 docs/engineering/output-test.md。
