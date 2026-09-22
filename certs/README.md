# 推送凭证目录（勿提交 Git）

将以下文件放在本目录（权限建议 `chmod 600`）：

| 文件 | 说明 |
|------|------|
| `apns.p12` | Apple **Apple Push Services** 证书（普通通知） |
| `apns-voip.p12` | Apple **VoIP Services** 证书（PushKit 来电） |
| `jpush-app-key.txt` | 极光 AppKey（单行文本，可选） |
| `jpush-master-secret.txt` | 极光 Master Secret（单行文本，可选） |

密码通过环境变量 `PUSH_APNS_P12_PASSWORD` / `PUSH_APNS_VOIP_P12_PASSWORD` 配置，不要写进文件名。

导出 `.p12`：Keychain Access → 证书 → 导出，或 Apple Developer 下载 `.cer` 后 Keychain 导出 `.p12`。
