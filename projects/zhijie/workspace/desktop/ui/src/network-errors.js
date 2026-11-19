const messages = {
  HELPER_UNAVAILABLE: "联机辅助服务未启动，请启动后单独重试联机。",
  IPC_SERVER_NOT_ALLOWED: "联机辅助服务身份不匹配，请使用与桌面配套的辅助服务后重试。",
  IPC_SERVER_IDENTITY_UNAVAILABLE: "无法验证联机辅助服务身份，请检查配套服务安装后重试。",
  IPC_SERVER_EXITED: "联机辅助服务已退出，请重新启动后重试。",
  TUN_PERMISSION_OR_CREATE_FAILED: "无法创建虚拟网卡，请检查辅助服务权限和驱动。",
  TUN_DRIVER_UNAVAILABLE: "缺少配套虚拟网卡驱动，请检查辅助服务目录中的 wintun.dll。",
  HELPER_INSTALLATION_UNPROTECTED: "联机辅助服务安装目录权限不安全，请使用机器级安装包并检查安装权限。",
  NETWORK_SUBNET_CONFLICT: "活动网段与本机已有网络或路由重叠，请检查其他虚拟网络或联系管理员。",
  NETWORK_CONFIGURATION_UNAVAILABLE: "无法读取本机网络配置，请检查辅助服务权限后重试。",
  NETWORK_POLICY_CHANGED: "网络策略已更新，请重新连接联机。",
  RELAY_PATH_UNAVAILABLE: "当前需要中继，尚未配置可用的中继链路。",
  DIRECT_UNAVAILABLE: "直连未成功，当前运营策略不允许中继，请检查网络后重试。",
  RELAY_CONNECTION_FAILED: "无法建立安全中继连接，请检查网络和中继证书。",
  RELAY_CONNECTION_TIMEOUT: "中继连接超时，请稍后单独重试联机。",
  RELAY_CONNECTION_LOST: "中继连接已断开，请单独重试联机。",
  RELAY_AUTH_FAILED: "中继授权失败，请重新连接联机。",
  NETWORK_WRITE_TIMEOUT: "虚拟网络写入超时，请单独重试联机。",
  GRANT_EXPIRED: "联机授权已过期，请重新连接联机。",
};

export const networkErrorText = code => Object.hasOwn(messages, code) ? messages[code] : null;
