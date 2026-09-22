# 群直播 — 落地清单（独立微服务）

> 版本：v1.0  
> **实施计划：[group-live-implementation-plan.md](./group-live-implementation-plan.md)**  
> **完整架构：[group-live-architecture.md](./group-live-architecture.md)**  
> 微服务 README：[/www/wwwroot/group-live-service/README.md](../../group-live-service/README.md)  
> 客户端 API：[group-live-client.md](./group-live-client.md)

群直播 **不写入主服务 `src/`**，与 [sangong-service](../sangong-service/README.md)、[robot-service](../robot-service/README.md) 相同模式。

---

## 1. 两仓职责

| 仓库路径 | 进程 | 端口 | 数据库 |
|----------|------|------|--------|
| `/www/wwwroot/99chat-server/`（主服） | 99chat-server | 8081 | `chat99` |
| `/www/wwwroot/group-live-service/` | group-live-service | **8092** | **`group_live`** |

---

## 2. 主服改动（最小）

- [x] `GroupLiveServiceProxyController` — `/group-live/**` → `GROUP_LIVE_SERVICE_URL`
- [x] `application.yml` — `group-live.proxy-enabled` / `service-url`
- [ ] Integration API（见架构附录 D）：
  - [x] `POST /integration/v1/groups/live-access`
  - [ ] `POST /integration/v1/wallet/live-tip`
  - [x] `GET /integration/v1/calls/open-session`
  - [x] `POST /integration/v1/im/group-custom-message`
  - [ ] `POST /integration/v1/admin/group-live/check`
- [ ] `WalletFeeScene.LIVE_TIP` / `WalletLimitScene.LIVE_TIP` / ledger 类型
- [ ] `LiveKitCallService` — 互斥查询 group-live internal API
- [ ] **不添加** group_live 表、CSS Client、群直播 Job 到主 JAR

---

## 3. group-live-service 改动

### P0

- [x] `pom.xml` + `GroupLiveServiceApplication`（M1 骨架）
- [x] `sql/migrate-group-live.sql` → 库 `group_live`
- [x] `JwtAuthFilter`（`JWT_SECRET` = 主服）
- [x] `MainServerClient` → Integration
- [x] `/api/v1/groups/{id}/live/*`、`/api/v1/live/{id}/*`（authorize/schedule/revoke/current/stop/push-info/play-info/detail）
- [x] CSS URL 签发 + `/webhook/tencent/css`
- [x] `SchedulePromoteJob` + `ExpireJob`
- [x] `scripts/build.sh` `start.sh` `stop.sh` `smoke.sh`
- [x] `.env.example`

### P1

- [ ] `/api/v1/live/{id}/tip` + `live_tip_order`
- [ ] Admin `/api/v1/admin/group-live`

### P2

- [ ] Disconnect debounce Job
- [ ] `/api/internal/live/anchor-active`（供主服 LiveKit）
- [ ] 群解散/踢人 — 主服回调调 internal stop

---

## 4. 配置速查

### 主服 `.env`

```bash
GROUP_LIVE_PROXY_ENABLED=true
GROUP_LIVE_SERVICE_URL=http://127.0.0.1:8092
```

### group-live-service `.env`

见 [/www/wwwroot/group-live-service/README.md](../../group-live-service/README.md)。

---

## 5. 对外 URL

| 用途 | URL |
|------|-----|
| App | `http://HOST:8081/group-live/api/v1/...` |
| Webhook | `https://api-host/group-live/webhook/tencent/css` |
| Admin | `http://HOST:8081/group-live/api/v1/admin/...` |
| 内网健康 | `http://127.0.0.1:8092/api/v1/health` |

---

## 6. 切流 / 回滚

参照 [sangong-service/scripts/cutover.md](../sangong-service/scripts/cutover.md)：

1. 先起 `group-live-service`，冒烟 `/api/v1/health`
2. 主服开 proxy，App 改打 `/group-live/api/v1/**`
3. 回滚：关 proxy + 停 group-live 进程
