# Windows 桌面 FFmpeg 构建与署名材料

本记录只适用于 `zhijie-shell.exe` SHA-256 `7b201d0097cc15e23129d875c891a615e38d7fda7e37de555f291c306890a9c2`。**随包声明包含 GPL 文本；现有证据不支持“Windows 构建开启了 FFmpeg 的 GPL 或 nonfree 开关”这一推断。** 这不是对整个程序的许可定性，也不是分发批准。

| 问题 | 已核对的证据 |
| --- | --- |
| 对应哪份 FFmpeg？ | 匹配 LiveKit 发布标签的 [WebRTC 提交 DEPS](https://github.com/webrtc-sdk/webrtc/blob/a545de45259b47dfac5f3635863e47cb601f1bb2/DEPS) 固定 Chromium FFmpeg 为 `ad41607c61898cf7150e0fb20fe4bbabd44922a3`。本次 Chromium 原站请求超时，取同一 Git 提交的镜像并校验所读取文件的 Git blob；两个 Windows x64 配置头与本地 SDK 文件仅换行不同。这不是源码重建或厂商签名核验。 |
| 何种配置？ | `target/sdk/args.gn` 指定 `is_clang=true`、`target_cpu="x64"`、`ffmpeg_branding="Chrome"`。该修订 `ffmpeg_options.gni` 仅在 `is_win && !is_clang` 时选择 `win-msvc`；本构建应看 `Chrome/win/x64/config.h`。SDK 文件 SHA-256 `c55c18571912ff2df754f9962b405275b22763d04e9d0f410cd747af37c7d3a6`。`CONFIG_GPL`、`CONFIG_GPLV3`、`CONFIG_VERSION3`、`CONFIG_NONFREE` 均为 `0`，`FFMPEG_LICENSE` 声明 `LGPL version 2.1 or later`。 |
| 为什么总声明仍有 GPL？ | [LiveKit 的发行补丁](https://github.com/livekit/rust-sdks/blob/webrtc-a545de4/webrtc-sys/libwebrtc/patches/add_licenses.patch)与本地补丁逐字节一致，静态列出 `COPYING.GPLv2`、`COPYING.GPLv3`、`COPYING.LGPLv2.1`、`COPYING.LGPLv3`，不按上述开关筛选。对随包 WebRTC 总声明的 FFmpeg 节还原生成器使用的 HTML 转义后，四份正文均能匹配；保留原始总声明，不擅自删掉 GPL 文本。 |
| 什么确实进了二进制？ | Ninja 输入列出的 302 个 `obj/third_party/ffmpeg/` 对象与 `webrtc.lib` 归档成员集合一致；不代表全部进入最终 EXE。`ffmpeg_nasm/h264_weight.o` 的一段 1,525 字节与桌面 EXE 文件偏移 `29068448` 的内容匹配，片段 SHA-256 `a7688654b515883f125f95bc631329d472b9040ed3dc12f4440fd88e6b586ef4`。对应构建源表列有 `libavcodec/x86/h264_weight.asm`，其文件头声明 LGPL 2.1 或更高版本；不能把这一文件头外推至所有对象。 |
| 补了什么？ | 该修订的 `README.chromium` 指定 `CREDITS.chromium` 为许可文件。原文件 Git blob `6ea8213c56635d7aa641b805aa312c6e09f42847`，SHA-256 `a4f057d42d8a93077a37d2381a8ebf47e9e0ce2a80336d6ba1534767634ce0d1`。随包 `third-party/desktop/FFmpeg-ad41607-CREDITS.chromium.txt` 保留全部内容，仅补末尾换行。 |

源码资料可由[固定 Chromium FFmpeg 提交](https://chromium.googlesource.com/chromium/third_party/ffmpeg/+/ad41607c61898cf7150e0fb20fe4bbabd44922a3/)或[同提交的镜像](https://github.com/chromium-full-mirror/chromium_third_party_ffmpeg/tree/ad41607c61898cf7150e0fb20fe4bbabd44922a3)复核。`target/ffmpeg-review-8ac0e84/comparison.json` 保存配置、实际静态库内对象摘要和代码匹配记录。仍未按各编入文件、汇编依赖、本地补丁及静态链接方式完成分发义务审查；可重新链接材料、对应源代码和 H.264 等专利条件应由发布负责人另行核查。
