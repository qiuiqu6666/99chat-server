# 99chat-robot-service

外部 Windows 业务机器人同步 + 代理反水，独立微服务（从主服务 `99chat-server` 拆出）。

## 职责与边界

- `POST /api/internal/robot-machines/register`：Windows 首次启动自助申码（无 Header，IP 限流）
- `POST /api/internal/robot-machines/bind-group|enable`：群聊「配对 / 开启」（`X-Machine-Code`）
- `POST /api/internal/robot-sync`：玩家快照 / 每日汇总同步（`X-Machine-Code`）
- `POST /api/internal/robot-rebate-tasks/pull|result`：反水任务领取与回执（`X-Machine-Code`）
- `GET|POST /me/agent/**`、`/me/rebate/**`：App 代理反水（Bearer JWT + `X-Group-Id`）
- `GET|POST /me/robot/groups/**`：App 查看/绑定/开启群与机器码关系（Bearer JWT）
- 数据库：仅 `jiqiren`，不连主库 `chat99`、不连 Redis/Kafka
- 定时任务：代理报表导出过期清理（每日 03:30）

业务代码保持原包名 `com.chat99.server.robot`，与主仓 `src/main/java/com/chat99/server/robot` 保持同步（便于 diff/回迁）。服务自有代码在 `com.chat99.robotservice`。

## 对接文档

- Windows：[docs/robot-windows-integration.md](../docs/robot-windows-integration.md)
- App：[docs/robot-app-integration.md](../docs/robot-app-integration.md)

## 多租户模型

```text
机器码 (API Key)  ──一对一──►  IM 群
       │
       └── player_group_id（快照/任务分区键）= machine_code
```

- Windows：本地持久化 `machineCode`；每次内部请求带 `X-Machine-Code`
- 群聊：`配对XXXX-XXXX-XXXX` → bind-group；`开启@robotId` → enable
- App：在当前群请求反水时带 `X-Group-Id`，后端解析为已开启的机器码后再按 `wxid` 查数（避免多机器人窜数）

`X-Robot-Secret` / `ROBOT_SYNC_SECRET` **已弃用**。

## 鉴权

- `/me/**`：验 JWT 签名 + 过期（HS256，密钥与主服务一致）。
  注意：不校验用户状态与 Redis 会话吊销（已知取舍）。
- `/me/agent/**`、`/me/rebate/**`：额外要求 `X-Group-Id`，且该群已配对并开启。
- `/api/internal/**`（除 register）：校验 `X-Machine-Code` 存在且 `ACTIVE`。
- 响应格式：`/me/**` 包装 `{code,message,data}`；`/api/internal/**` 裸 JSON。

## 构建与运行

```bash
# 构建（复用主仓 mvnw + JDK17）
./scripts/build.sh -DskipTests

# 配置（首次）
cp .env.example .env && vi .env && chmod 600 .env

# 迁移（机器码表）
mysql … jiqiren < sql/migrate-robot-machine.sql

# 启动 / 停止（端口默认 8091）
./scripts/start.sh
./scripts/stop.sh

# 日志
tail -f logs/robot-service.log
```

## 切流 / 联调

1. 执行 `sql/migrate-robot-machine.sql`（会把存量 `player_group_id` 种子为 legacy machine）。
2. 启动 robot-service。
3. Windows：
   - 无本地码：`POST /api/internal/robot-machines/register` → 持久化 `machineCode`
   - 同步 / pull / result：Header `X-Machine-Code: <码>`
   - 群消息 `配对XXXX-XXXX-XXXX` → `POST .../bind-group`；`开启@xxx` → `POST .../enable`
4. App：反水请求加 `X-Group-Id`；可用 `GET /me/robot/groups/{groupId}` 看绑定状态。
5. Nginx：`deploy/nginx-robot-service.conf` 已含 `/me/robot/`；reload 后生效。

存量群需重新配对/开启。Legacy 种子机器码等于原 `player_group_id`（如 `@2HGQG6M5CD`），Windows 也可继续用该字符串作为 `X-Machine-Code` 写旧数据，直到换新码并重新同步。

## SQL

`sql/` 目录为 `jiqiren` 库迁移脚本。新环境需按序执行 `migrate-robot-*.sql`，含 `migrate-robot-machine.sql`。
