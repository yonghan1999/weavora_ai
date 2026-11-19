# macOS 联机辅助服务：连接地址与验收边界

桌面与 Helper 使用同一段代码选择本地 IPC 地址。macOS 默认 `/var/run/zhijie-helper-v1.sock`，与候选 `app.weavora.zhijie.helper.plist` 一致。Linux 继续使用 `/run/zhijie/helper-v1.sock`，Windows 继续使用名称为 `zhijie-helper-v1` 的命名管道。只有需要自定义地址时才设置 `ZHIJIE_HELPER_SOCKET`，两端须一致；如果启动 Helper 时传了 `--socket`，该参数优先于其环境变量。Unix 不接受相对路径。

地址一致只解决两端找到同一个套接字的问题。当前 Helper 要求启动时绑定非零桌面进程 PID；候选 launchd 模板未提供该值，不能直接作为已可部署的服务。不要移除 PID 检查、让桌面以 root 运行或放宽目录权限来绕过启动条件。

在受授权的 Mac 上仍须核验按需启动及进程身份、套接字归属和权限、实际 TUN 和双端 TCP/UDP 流量，以及退出或卸载后资源清理。在 Windows 运行的路径契约测试不替代这些检查。
