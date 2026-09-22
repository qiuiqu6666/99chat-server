# 99chat-server

99chat 项目的后端服务，基于 Spring Boot 3.5 + Java 17。

## 职责

- 通过腾讯云 IM 密钥（控制台保管，不下发客户端）签发 UserSig
- 业务侧用户/会话数据存储（开发用 H2 内存库，生产改 MySQL）
- 后续：IM 消息回调（前/后置审核、归档）、推送协调

## 启动

环境变量提供 IM 密钥（不要写进代码）：

```bash
export IM_SDK_APP_ID=20041957
export IM_KEY=<你的控制台密钥>
./mvnw spring-boot:run
```

访问 `http://localhost:8081`，H2 控制台在 `/h2-console`（JDBC URL: `jdbc:h2:mem:chat99`）。

## 对接文档

完整 API 契约见 **[docs/](docs/)** 目录，入口：[docs/README.md](docs/README.md)。

| 模块 | 文档 |
|------|------|
| 注册 / 登录 | [docs/registration-and-login.md](docs/registration-and-login.md) |
| 钱包 / USDT / 平台币 | [docs/wallet.md](docs/wallet.md) |
| 公众号 | [docs/official-account.md](docs/official-account.md) |

## 接口（摘要）

### POST /auth/login

请求：

```json
{ "userId": "qiu666" }
```

响应：

```json
{
  "sdkAppId": 20041957,
  "userId": "qiu666",
  "userSig": "eJw...",
  "expiresIn": 7776000
}
```

`userSig` 3 个月（90 天）有效，Flutter 端拿到后调 `_coreInstance.login(userID, userSig)`。

## 切换到 MySQL

在 `application.yml` 中替换 datasource 部分，把 `ddl-auto` 改成 `validate`，用 Flyway/Liquibase 管理 schema。
