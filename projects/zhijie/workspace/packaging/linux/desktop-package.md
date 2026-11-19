# Linux 桌面候选包

Linux 使用 `zhijie` 作为稳定的 Debian 包名，窗口标题和 Debian 中文菜单名仍为「织界」。此配置不安装特权 Helper，不创建网卡或系统服务，不改变 Windows/macOS 包名。AppImage 和 Debian 包均为候选，未在 Ubuntu 上实测。

在目标 Ubuntu x86_64 机器上构建后，先对实际 `.deb` 做只读核对（用实际路径替换示例）：

```sh
deb="target/release/bundle/deb/实际文件名.deb"
dpkg-deb --field "$deb" Package Version Architecture
dpkg-deb --contents "$deb"
dpkg-deb --fsys-tarfile "$deb" | tar -xO ./usr/share/applications/zhijie.desktop
```

核对包名 `zhijie`、架构 `amd64`、版本、中文名，以及 `Exec` 和 `Icon` 对应的实际包内文件。分别在支持矩阵中的 Ubuntu 22.04/24.04 记录安装、菜单启动、升级后配置保留和卸载；不要以包内容检查代替这些操作，也不要以桌面启动代替真实语音或 TUN 验收。
