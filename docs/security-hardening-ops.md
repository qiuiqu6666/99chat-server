# 安全加固运维说明（2026-07-10）

## 已落地

- 代理头：仅可信代理 IP 才解析 XFF；公网直连 8081 伪造 `X-Forwarded-For: 127.0.0.1` 无法绕过 `/admin/**`
- 短信万能码：默认关闭（`SMS_MASTER_CODE` 空）
- 后台 JWT：Redis `jti` 会话；logout/改密立即失效；账号禁用 fail-closed
- 钱包/推送配置 API：需 `system.config`/`admin.manage`，秘密字段仅返回 `configured`
- TRTC 回调：强制 Token（`.env` `TRTC_CALLBACK_TOKEN`，启动 bootstrap 已写入带 token 的回调 URL）
- IM 回调：按运维选择**暂不**开启控制台鉴权；服务端空 Token 时跳过校验（配置 Token 后立即强制校验）
- SSRF：`FavoriteMediaFetcher` 阻断私网/回环/链路本地/CGNAT/ULA，重定向重新解析 DNS
- 实时连接：8082 全局/单 IP 连接上限
- 文件权限：`.env` 600，`logs` 750
- 数据库：应用账号密码已轮换；JDBC `sslMode=REQUIRED`
- 钱包密钥：AES-GCM 信封加密（`WALLET_DATA_ENCRYPTION_KEY`），启动迁移明文 → `v1:` 密文
- UFW：`3306`/`9092` 仅本机与主机私网 IP；`8081`/`8082` 仍公网开放
- Nginx：公网站点拒绝 `/admin`

## 待办 / 可选

1. ~~腾讯云 IM 控制台 Token~~：**已按运维决定暂不开启**；日后若要开启，把控制台 Token 与 `IM_CALLBACK_TOKEN` / `im.callback.callback_token` 配成一致即可
2. **如需远程 MySQL/Kafka**：先把来源 IP 加入 UFW，再开账号；当前观测活跃来源仅为 localhost
3. **Kafka SASL/ACL**：UFW 已挡住公网扫描；完整 SASL_SSL+ACL 建议在维护窗继续（配置模板见下）
4. **MySQL root**：面板库中的 root 密码与实例不一致，未能启用 `require_secure_transport` / 清理远程账号；请用正确 root 密码补做

## 回滚

- 配置备份：`/root/security-backup-20260710-0841/`
- 应用：旧 jar 在 Maven `target/` 构建前请另存；`.env` 备份见同目录 `app.env`
- 钱包密文：保留 `WALLET_DATA_ENCRYPTION_KEY` 即可解密；勿丢失该密钥

## 远程放行示例

```bash
ufw allow from <CLIENT_IP> to any port 3306 proto tcp
ufw allow from <CLIENT_IP> to any port 9092 proto tcp
```

## Kafka SASL/ACL（待维护窗）

当前已用 UFW 限制 9092 仅本机可达，公网扫描面已关闭。完整 SASL_SSL + ACL 建议维护窗执行：

1. 创建 SCRAM 用户（app / archive-consumer / ops-readonly）
2. `listeners=SASL_PLAINTEXT://127.0.0.1:9092`（或 SSL 证书齐备后 SASL_SSL）
3. `allow.everyone.if.no.acl.founds=false` + Topic ACL
4. `.env` 设置 `KAFKA_SECURITY_PROTOCOL` / `KAFKA_SASL_JAAS_CONFIG` 后滚动重启应用

## 验收快照（2026-07-10）

| 项 | 结果 |
|---|---|
| 公网 8081 伪造 XFF 访问 `/admin/settings` | 403 |
| Nginx `/admin` | 403 |
| IM 回调（未配 Token，按选择跳过） | 放行；配上 Token 后无/错 Token → 403 |
| TRTC 回调无 Token | 403 |
| 钱包密钥密文 | settings=2, wallets=559 全部 `v1:` |
| 本机 MySQL/Kafka | 可用 |
| 安全单测 | ClientContext / WalletCipher / SSRF / WebhookPush 通过 |
