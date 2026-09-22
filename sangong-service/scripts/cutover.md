# 8088 切流说明（PHP → Java）

## 原则

- **同一时刻只允许一个写端**（PHP 或 Java）连 `sangong` 库，避免资金双写。
- 回滚只改流量入口，不改库。

## 切流步骤（本机 Java 直听 8088）

1. 构建：`./scripts/build.sh` 或 `../mvnw -DskipTests package`
2. 确认 PHP 未占用 8088（本机历史上远程 PHP 在 `154.9.25.249:8088`）。
3. 启动 Java：`./scripts/start.sh` → `./scripts/status.sh` → `./scripts/smoke.sh` → `./scripts/contract-smoke.sh`
4. 消息通道（推荐 Kafka，无需 HTTP 回调）：
   - 主服务：`IM_GROUP_MONITOR_ENABLED=false`；确认已创建 Topic `chat99.im.after-send` / `chat99.im.group-recall`（`scripts/kafka-topics-init.sh`）
   - sangong：`SANGONG_KAFKA_ENABLED=true`，`KAFKA_BOOTSTRAP=127.0.0.1:9092`
   - 用户资料与撤回都直连主服务（`INTEGRATION_API_BASE_URL=http://127.0.0.1:8081`），无外部 IM 集成服务
   - 重启主服务 + sangong
5. 验证：Kafka 群消息入库、下注、结算、报表图片、Integration 撤回；昵称来自主服务资料。

## 统一入口（当前生效）

- sangong 只绑 `127.0.0.1:8088`（`SANGONG_BIND`），外部统一访问主服务：`http://HOST:8081/sangong/**`。
- 主服务侧开关：`SANGONG_PROXY_ENABLED` / `SANGONG_SERVICE_URL`；`APP_URL` 已指向 `http://HOST:8081/sangong`（报表图片外链）。
- 回退直连：sangong `.env` 设 `SANGONG_BIND=0.0.0.0`，`APP_URL` 改回 8088 并重启。

## 经 Nginx 反代（可选）

1. `.env` 设 `SANGONG_PORT=18088`
2. 启用 `deploy/nginx-sangong.conf`（8088 → 18088，SSE 关缓冲，`/bet-reports/` 静态）
3. `nginx -t && nginx -s reload`

## 回滚

1. `./scripts/stop.sh` 停 Java；或临时关掉 `SANGONG_KAFKA_ENABLED`
2. 需要 HTTP 回调时：恢复 `IM_GROUP_MONITOR_ENABLED=true` 并把 `IM_GROUP_MONITOR_URL` 指回目标服务，重启主服务
3. 若曾用 nginx，把 upstream 指回旧服务

## 黄金对照

- 单元：`../mvnw test`（牌型/解析/结算数学/报表生图）
- 契约：`./scripts/contract-smoke.sh`
- 写路径黄金：在**克隆库**上对 PHP/Java 并行对照，禁止双边写生产库
