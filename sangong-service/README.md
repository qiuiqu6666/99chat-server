# sangong-service（三公 Java 服务）

PHP `sangong/api`（Lumen）的 Java 移植版：Spring Boot 3.5 + Java 17，直连同一 MySQL 库（`sangong`），路由、下注/定庄/开彩/结算数学与 PHP 版一致。

## 技术栈

- Java 17（`/www/server/java/jdk-17.0.8`）
- Spring Boot 3.5.14（web / security / jdbc），`NamedParameterJdbcTemplate` 直写 SQL
- jjwt（JWT 鉴权）、okhttp（腾讯 IM REST）、tls-sig-api-v2（UserSig）、spring-kafka（直连主服务消息 Topic）

## 目录

- `src/main/java/com/chat99/sangong/`
  - `controller/` 全部路由（对照 `sangong/api/routes/web.php`，含管理别名路由）
  - `service/` 业务：`RoundService`、`BetService`、`SettleService`（结算数学）、`ImMessageService` / `ImKafkaConsumer`（消息幂等入库）、`MainUserProfileService`（主服务资料）等
  - `repository/` JDBC 数据访问
  - `security/` 主服务 JWT / 游戏特权用户 / X-Im-Callback-Key 过滤器
- `scripts/` 构建与运维脚本
- `deploy/nginx-sangong.conf` 8088 反代配置

## 构建与运行

```bash
cd /www/wwwroot/99chat-server/sangong-service
./scripts/build.sh    # ../mvnw -DskipTests package → target/sangong-service.jar
./scripts/start.sh    # 读取 .env 后台启动
./scripts/status.sh   # 运行状态 + 健康检查
./scripts/smoke.sh            # 冒烟：/ health settings admin/session snapshot 等
./scripts/contract-smoke.sh   # 契约：主路径/别名/鉴权/报表路由（禁 404/501）
./scripts/stop.sh
```

切流说明见 [`scripts/cutover.md`](scripts/cutover.md)。

单元测试：

```bash
export JAVA_HOME=/www/server/java/jdk-17.0.8 && export PATH=$JAVA_HOME/bin:$PATH
../mvnw test   # 牌型/解析/结算数学/报表生图
```

## 配置（.env）

由 PHP `sangong/api/.env` 生成，关键项：

- `SANGONG_PORT` / `SANGONG_BIND`：服务端口与绑定地址（默认只绑 127.0.0.1）
- `DB_*`：MySQL（sangong/sangong/sangong）
- `CHAT99_JWT_SECRET`：主服务 App JWT 密钥（`app_setting` 表 `JWT_SECRET`），玩家直接用主服务登录 Token
- `SANGONG_SETTINGS_WRITE_KEY`：已废弃；管理接口改为主服务 JWT + `users.game_privileged=true`
- `SANGONG_IM_CALLBACK_KEY`：兼容旧 HTTP 回调 `X-Im-Callback-Key`（Kafka 模式下可不使用）
- `IM_SDK_APP_ID` / `IM_KEY` / `IM_BOT_USER_ID` / `IM_GROUP_*`：腾讯 IM
- `INTEGRATION_API_*`：主服务直连（撤回 `/integration/v1/im/messages/recall` + 用户资料 `/integration/v1/users/profiles`），不再对接外部 IM 集成服务
- `SANGONG_KAFKA_ENABLED` / `KAFKA_BOOTSTRAP`：直连 Kafka（`chat99.im.after-send` / `chat99.im.group-recall`）

## 路由（与 PHP 完全对齐）

前端统一入口、鉴权、多租户、SSE、完整接口字段和联调流程见
[`docs/frontend-integration.md`](docs/frontend-integration.md)。

- 公共：`GET /`、`GET /api/v1/health`、`GET|PUT /api/v1/settings`
- 玩家（Bearer = **主服务登录 JWT**，另带 `X-Tenant-Id`）：`GET /api/v1/me/balance`、`GET /api/v1/rounds/current`、`POST /api/v1/bets`
- `POST /api/v1/auth/token` 已下线（410）：不再换发三公自有 Token
- IM 消息入口（二选一，推荐 Kafka）：
  - Kafka：`ImKafkaConsumer` 消费主服务 `chat99.im.after-send` / `chat99.im.group-recall`
  - HTTP 兼容：`POST /api/v1/im/callback`（旧 monitor 转发 / 手工调试）
- 管理（主服务特权用户 JWT + `X-Tenant-Id`，前缀 `/api/v1/admin`）：
  - `tenants`（列表/注册/更新游戏群，列表不需 `X-Tenant-Id`）
  - `session`（show/start/stop）、`sessions`（开机/关机业务批次历史）、`events/snapshot`、`events/stream`（SSE）
  - `rounds`、`banker/setup`、`banker/quick-setup`、`door-count`、`banker/send`
  - `betting/submit`、`betting/preview`（含 `rounds/{id|current}/...` 别名）
  - `draws`（GET/POST）、`rounds/{id}/settle|void-settlement|resettle`
  - `co-bank`（add/remove/close/send）、`im/send`
  - `users/credit|debit`（含 `users/{id}/...`）、`bets`（代录）
  - `user-groups`（CRUD + 用户分组指派）
  - `reports/users`、`reports/user-flow`
  - 五类图片报表：`reports/users/points-image`、`trend-image`、`bet-image`、`settle-image`、`settle-bill`、`preview-images/send`

## 与 PHP 版的差异

- 报表图片用 Java2D 复刻语义版式（橙标题/绿底积分/走势色块），像素级与 PHP GD 可能有抗锯齿差异。
- **图片不落本地磁盘**：内存生成 JPG → 经主服务 `/integration/v1/oss/report-images` 直传 OSS（对象前缀 `sangong/bet-reports/yyyyMMdd/`）→ 用 OSS URL 经腾讯 IM `TIMImageElem` 发送；主服务每天 04:30 定时清理超过 7 天的对象（`SANGONG_REPORT_IMAGE_RETENTION_DAYS` / `SANGONG_REPORT_IMAGE_CLEANUP_CRON`）。本地 `/bet-reports/**` 静态目录仅保留历史文件。
- 用户昵称优先走主服务 Integration 资料接口，不再依赖腾讯 IM `portrait_get`（失败时仍可兜底）。
- **玩家鉴权复用主服务 JWT**：本地用共享密钥验签，`sub` 即主服务用户 ID，按当前租户自动建档；不再自签发 Token。
- **多租户**：每个 IM 游戏群是独立租户（`sangong_tenants`），余额/局/账本/设置互不互通；统一后台用 `X-Tenant-Id`（=游戏群 ID）选群；Kafka 按 `groupId` 自动路由。
- **按账号隔离**：管理端每个特权账号只能看到/操作自己有访问权的游戏群（`sangong_tenant_access`）。群主用 `GET/PUT /api/v1/admin/my-config` 自填下注群/报表群/机器人；可把其他特权用户加成 `admin` 帮忙上下分（admin 不能改配置）。
- 撤回消息直接调用主服务（含全局响应包装 `data` 解包），同时接受 `imSuccess` 与旧契约 `ok`。
- 结算分摊使用 `Math.floorDiv`（向负无穷取整）匹配 PHP `floor()`。

## 统一入口（对外只暴露主服务）

- 三公服务只绑 `127.0.0.1:8088`（`.env` 中 `SANGONG_BIND`），外网不可直连。
- 主服务（8081）把 `/sangong/**` 原样转发到本服务（`SangongServiceProxyController`，剥掉 `/sangong` 前缀），SSE、报表图片一并支持。
- 对外地址 = `http://主服务/sangong` + 原路径，例如：
  - `GET  http://HOST:8081/sangong/api/v1/health`
  - `GET  http://HOST:8081/sangong/api/v1/admin/session`（带主服务 Bearer JWT + `X-Tenant-Id`）
  - `GET  http://HOST:8081/sangong/api/v1/admin/events/stream`（SSE）
- 报表图片直接存 OSS 并返回 OSS 公网 URL，不再经本服务提供静态访问。
- 五类报表版式与 PHP 原版逐行对齐（同字体 ArialRoundedMT + Noto 回退、同列宽/配色/顶栏 ✨【标题】✨、Twemoji 贴图）；对照脚本见 `scripts/php-render-sample.php` + `scripts/sample-report-data.json`。
- 主服务开关：`.env` 的 `SANGONG_PROXY_ENABLED` / `SANGONG_SERVICE_URL`。

## 多租户用法

```bash
BASE=http://HOST:8081/sangong   # 统一入口；服务器本机调试也可用 http://127.0.0.1:8088

# 1) 群主配置自己的下注群 / 报表群 / 机器人
curl -H "Authorization: Bearer $MAIN_PRIVILEGED_JWT" $BASE/api/v1/admin/my-config
curl -X PUT -H "Authorization: Bearer $MAIN_PRIVILEGED_JWT" -H 'Content-Type: application/json' \
  -d '{"name":"一号厅","imGroupGameId":"@TGS#xxxx","imGroupAdminStatsId":"@TGS#yyyy","imBotUserId":"bot1"}' \
  $BASE/api/v1/admin/my-config

# 2) 后续所有管理操作带上该群
curl -H "Authorization: Bearer $MAIN_PRIVILEGED_JWT" -H "X-Tenant-Id: @TGS#xxxx" \
  $BASE/api/v1/admin/session

# 3) 群主授权帮工（推荐走 my-config/members，避免群 ID 含 # 被截断）
curl -X POST -H "Authorization: Bearer $MAIN_PRIVILEGED_JWT" -H 'Content-Type: application/json' \
  -d '{"imUserId":"other_user","role":"admin"}' \
  $BASE/api/v1/admin/my-config/members
```

迁移脚本：`scripts/migrations/001_multi_tenant.sql`、`scripts/migrations/002_tenant_access.sql`（首次升级需按序执行）。

## 业务日报批次

- 一次开机到关机对应一个 `sangong_sessions.id`，作为经营日报的业务批次；跨越自然日不会拆分。
- `GET /api/v1/admin/sessions?limit=30` 返回当前租户最近的业务批次。
- `GET /api/v1/admin/reports/user-hierarchy?sessionId={id}` 按指定批次汇总流水、上下分、盈亏和已入账返水。
- 不传 `sessionId` 时优先使用当前运行批次，否则使用最近批次；显式传 `date` 时保留原自然日查询兼容行为。
- `POST /api/v1/admin/reports/settle-bill` 可传 `sessionId` 重发历史批次账单。
- 升级迁移为 `scripts/migrations/008_business_session_reporting.sql`；应用启动完成时自动执行，部署前应先备份数据库。

## 返水发放时点

- 每局结算只累计有效流水，不再逐局发放返水。
- 玩家调用 `POST /api/v1/me/rebate/claim` 时，玩家返水进入玩家余额；同一次申请产生的代理差额直接进入所属代理余额。
- 管理端关机时对当前租户全部用户执行同样的未领取流水结算，并在关机响应返回玩家返水、代理差额和涉及用户数。
- 原每日 04:30 自动返水任务已停用。
- `009_deferred_rebate_cutover.sql` 会将切换前可能已经逐局发放的流水设为已领取基线，防止升级后重复返水。

## 部署

- 当前方式：`SANGONG_BIND=127.0.0.1` + `SANGONG_PORT=8088`，对外统一走主服务 `/sangong/**` 入口（见上）。
- 备选（nginx 直连旧方式）：`.env` 设 `SANGONG_BIND=0.0.0.0`，或启用 `deploy/nginx-sangong.conf` 反代。
- 推荐：主服务 `IM_GROUP_MONITOR_ENABLED=false`，sangong `SANGONG_KAFKA_ENABLED=true`，由 Kafka 直连投递群发言/撤回。
- 兼容回退：重新打开 `IM_GROUP_MONITOR_ENABLED` 并把 `IM_GROUP_MONITOR_URL` 指回 `http://127.0.0.1:8088/api/v1/im/callback?...`。
- **回滚**：停 Java；同一时刻只保留一个写端。
