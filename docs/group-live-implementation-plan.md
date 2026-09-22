# 群直播 — 完整实施计划

> 版本：v1.0（2026-08-17）  
> 状态：执行中（M1 骨架联通）  
> 前提：[group-live-architecture.md](./group-live-architecture.md) 设计已冻结（独立微服务 + OBS + 预约 + 多币种打赏 v1）

---

## 目录

1. [目标与范围](#1-目标与范围)
2. [角色与协作面](#2-角色与协作面)
3. [里程碑总览](#3-里程碑总览)
4. [阶段 M0：环境与基础设施](#4-阶段-m0环境与基础设施)
5. [阶段 M1：骨架与联通（P0-α）](#5-阶段-m1骨架与联通p0-α)
6. [阶段 M2：核心直播链路（P0）](#6-阶段-m2核心直播链路p0)
7. [阶段 M3：客户端与 IM（P0-β）](#7-阶段-m3客户端与-imp0-β)
8. [阶段 M4：打赏与钱包（P1）](#8-阶段-m4打赏与钱包p1)
9. [阶段 M5：治理与稳定性（P2）](#9-阶段-m5治理与稳定性p2)
10. [阶段 M6：生产切流与验收](#10-阶段-m6生产切流与验收)
11. [Workstream 任务分解](#11-workstream-任务分解)
12. [测试门禁](#12-测试门禁)
13. [切流与回滚](#13-切流与回滚)
14. [风险登记册](#14-风险登记册)
15. [v2  backlog（不在 v1）](#15-v2-backlog不在-v1)

---

## 1. 目标与范围

### 1.1 v1 交付定义（Done 的标准）

用户可完成以下闭环：

1. **群主** 在 App 内：选成员 → 填 **房间昵称** → 选 **计划开播时间** → 提交预约  
2. **指定主播** 到点后：App 复制 **OBS RTMP 地址** → OBS 推流  
3. **群成员** 收到 IM 卡片 → 进入直播间 **WebRTC 快直播** 观看  
4. **观众** 可 **自定义金额、多币种** 打赏（走主服钱包账本）  
5. **主播 / 群主 / 群管** 可结束直播；**平台 Admin** 可禁播  
6. **LiveKit 通话** 与 **群直播** 互斥生效  

### 1.2 技术交付物

| 交付物 | 路径/说明 |
|--------|-----------|
| 独立微服务 | `/www/wwwroot/group-live-service/` |
| 主服代理 + Integration | `99chat-server/src/.../grouplive/`、`integration/` |
| 数据库 | MySQL 库 **`group_live`**（与 `chat99` 分离） |
| 文档 | 已有 client / architecture / server；本计划 |
| Flutter 客户端 | 预约、推流地址页、直播间、打赏 |
| 腾讯云 CSS | 推拉流域名、鉴权、回调 |
| Admin | 直播列表 + ban |

### 1.3 明确不在 v1

礼物 catalog、录制回放、自动鉴黄、App 内推流 SDK、PC Web 推流页 —— 见架构 §2.2。

---

## 2. 角色与协作面

| Workstream | 负责内容 | 关键产出 |
|------------|----------|----------|
| **WS-A 运维/腾讯云** | CSS 域名、鉴权、回调 URL、API 密钥 | 控制台配置单、回调可达验证 |
| **WS-B group-live-service** | 会话状态机、CSS、Webhook、Job | `:8092` 可运行 JAR |
| **WS-C 主服务 Integration** | 代理、群权限、钱包 tip、IM 代发、LiveKit 互斥 | Integration API + Proxy |
| **WS-D Flutter 客户端** | UI + API + IM 监听 | 可联调包 |
| **WS-E Admin 前端** | 列表、ban | 运营可操作 |
| **WS-QA** | 冒烟、联调清单、回归 | 测试报告 |

**协作规则：**

- 对外 URL **统一** `{BASE}/group-live/api/v1/**`（主服代理），禁止客户端直连 `:8092`
- 接口契约以 [group-live-client.md](./group-live-client.md) 为准；变更须同步 architecture + client 文档
- 钱包账本 **只在主服** 写入；group-live 只存 `live_tip_order` 编排记录

---

## 3. 里程碑总览

```text
M0 环境          ████                          1 周
M1 骨架联通      ████                          1 周
M2 核心直播      ████████                      2 周
M3 客户端+IM     ████████                      2 周
M4 打赏          ████                          1 周
M5 治理稳定      ████                          1 周
M6 切流验收      ██                            3–5 天
                                      合计约 8–9 周（可并行压缩）
```

| 里程碑 | 周期（参考） | 退出标准 |
|--------|--------------|----------|
| **M0** | 3–5 天 | DB、CSS 域名、内网 `:8092` 可 listen |
| **M1** | 3–5 天 | 主服 proxy 通；health 200；Integration stub 通 |
| **M2** | 7–10 天 | OBS 推流 → webhook → LIVE；观众 play-info 可用 |
| **M3** | 7–10 天 | App 预约 + 复制地址 + 观看 + IM 卡片（与 M2 可并行） |
| **M4** | 5–7 天 | 打赏扣款 + ledger + 飘屏 |
| **M5** | 5–7 天 | Admin ban、LiveKit 互斥、断流 debounce |
| **M6** | 3–5 天 | 生产切流、监控、回滚演练 |

> 人力允许时：**M2（后端）与 M3（客户端）并行**；M4 依赖 M2 LIVE 状态。

---

## 4. 阶段 M0：环境与基础设施

**目标：** 三方环境就绪，后续开发不 blocked。

### 4.1 任务清单

| ID | 任务 | 负责 | 依赖 | 验收 |
|----|------|------|------|------|
| M0-01 | 创建 MySQL 库 `group_live` + 账号 + 权限 | 运维 | — | 可从本机 JDBC 连接 |
| M0-02 | 编写并执行 `/www/wwwroot/group-live-service/sql/migrate-group-live.sql` | WS-B | M0-01 | 表 + 活跃槽唯一索引存在 |
| M0-03 | 腾讯云：开通 CSS、添加 **推流/播放域名**、HTTPS/RTMP | WS-A | — | 控制台域名「已启用」 |
| M0-04 | 配置推流/播放 **URL 鉴权 Key** | WS-A | M0-03 | 可手动拼测试 URL |
| M0-05 | 配置 CSS **推流/断流回调** → `https://{api}/group-live/webhook/tencent/css` | WS-A | M6 前可先指 staging | curl 可达（可先 502） |
| M0-06 | 申请/配置 `TENCENT_SECRET_ID/KEY`（API 3.0） | WS-A | — | 可调 DescribeLiveStreamState |
| M0-07 | 主服 `.env` 预留 `GROUP_LIVE_PROXY_*`；group-live `.env.example` | WS-B/C | — | 文档与样例齐全 |
| M0-08 | 防火墙：`8092` 仅 `127.0.0.1`（不对公网） | 运维 | — | nmap 验证 |

### 4.2 M0 退出检查

- [x] `group_live` 库 migration 成功（表 `group_live_session` / `live_tip_order` + 活跃槽唯一索引）  
- [ ] CSS 推拉流域名与鉴权 Key 就绪（运维项 M0-03～06）  
- [ ] 回调 URL 已在腾讯云登记（可先指向测试环境）  
- [x] 密钥注入方案确定（不写进 Git；`.env.example` + `.gitignore`）

---

## 5. 阶段 M1：骨架与联通（P0-α）

**目标：** `group-live-service` 与主服 **proxy + Integration 骨架** 跑通。

### 5.1 group-live-service

| ID | 任务 | 验收 |
|----|------|------|
| M1-GL-01 | 新建 `pom.xml`（对齐 robot-service：Spring Boot 3.5、Java 17） | `../mvnw -f pom.xml package` 成功 |
| M1-GL-02 | `GroupLiveServiceApplication` + `application.yml` | 启动监听 `:8092` |
| M1-GL-03 | `GET /api/v1/health` | 200 `{ "ok": true }` |
| M1-GL-04 | `JwtAuthFilter`（`JWT_SECRET` = 主服） | 无 token 401；合法 token 通过 |
| M1-GL-05 | `GlobalResponseWrapper`（`/api/v1/**` → `{code,message,data}`） | 与 App 信封一致 |
| M1-GL-06 | `MainServerClient` + Integration token 配置 | 可调主服 health/integration 测试端点 |
| M1-GL-07 | `scripts/build.sh` `start.sh` `stop.sh` `status.sh` | 与 sangong/robot 同风格 |
| M1-GL-08 | JPA/MyBatis + `GroupLiveSession` entity（仅 CRUD 骨架） | 可 insert/select |

### 5.2 主服务

| ID | 任务 | 验收 |
|----|------|------|
| M1-MS-01 | `GroupLiveServiceProxyController`（剥 `/group-live` 前缀） | `GET /group-live/api/v1/health` → 8092 |
| M1-MS-02 | `application.yml`：`group-live.proxy-enabled` / `service-url` | 配置生效 |
| M1-MS-03 | Integration 骨架 + `IntegrationAuthService` 复用 | token 错拒、token 对过 |
| M1-MS-04 | `POST /integration/v1/groups/live-access`（接 `GroupAccessService`） | Owner/Member/Admin 用例单测 |
| M1-MS-05 | `POST /integration/v1/im/group-custom-message`（接 `ImAdminClient`） | 测试群收到自定义消息 |
| M1-MS-06 | `GET /integration/v1/calls/open-session`（查 `CallSessionRepository`） | 有/无通话正确 |

### 5.3 M1 退出检查

- [x] `/www/wwwroot/group-live-service/scripts/smoke.sh` 通过（health 200 + 无 JWT 401；带 JWT ping 需填 `GROUP_LIVE_SMOKE_JWT`，SKIP）  
- [x] 主服 `curl /group-live/api/v1/health` 200（经 proxy 到 `:8092`，`db:true`）  
- [x] Integration live-access / im / open-session 单测绿

---

## 6. 阶段 M2：核心直播链路（P0）

**目标：** 不含打赏的 **预约 → OBS → LIVE → 观看 → 结束** 全链路。

### 6.1 业务 API（group-live-service）

| ID | 接口 | 要点 |
|----|------|------|
| M2-GL-01 | `POST /api/v1/groups/{gid}/live/authorize` | roomName、scheduledStartAt、活跃槽、Integration Owner/Member |
| M2-GL-02 | `PATCH .../live/schedule` | 仅 SCHEDULED；重算 expireAt |
| M2-GL-03 | `POST .../live/revoke` | SCHEDULED/AUTHORIZED |
| M2-GL-04 | `GET /api/v1/live/{sid}/push-info` | 仅 anchor + AUTHORIZED；RTMP 拆分 |
| M2-GL-05 | `GET .../play-info` | 仅 member + LIVE；WebRTC + FLV/HLS |
| M2-GL-06 | `GET .../groups/{gid}/live/current` | active false/true |
| M2-GL-07 | `GET /api/v1/live/{sid}` | 详情 |
| M2-GL-08 | `POST .../live/stop` | anchor/owner/admin；调 DropLiveStream |

### 6.2 状态机与 Job

| ID | 任务 | 验收 |
|----|------|------|
| M2-GL-09 | `GroupLiveSessionService` 完整状态迁移 + `@Version` | 非法迁移 409 |
| M2-GL-10 | `GroupLiveSchedulePromoteJob` | SCHEDULED → AUTHORIZED + IM ready |
| M2-GL-11 | `GroupLiveExpireJob` | 宽限期 → SCHEDULE_EXPIRED |
| M2-GL-12 | `GroupLivePushPlayService` txSecret/txTime 对齐 expireAt | 过期后 push-info 刷新 |

### 6.3 腾讯云

| ID | 任务 | 验收 |
|----|------|------|
| M2-GL-13 | `GroupLiveCssWebhookController` 验签 + 幂等表/缓存 | 重复回调不重复 LIVE |
| M2-GL-14 | 推流开始 → LIVE；断流 → ENDED（v1 可先无 debounce） | 与 OBS 行为一致 |
| M2-GL-15 | `GroupLiveCssClient` DropLiveStream / ForbidLiveStream | stop/ban 可断流 |

### 6.4 IM（经主服 Integration）

| ID | businessID | 触发 |
|----|------------|------|
| M2-MS-07 | `group_live_scheduled` | authorize |
| M2-MS-08 | `group_live_schedule_updated` | PATCH |
| M2-MS-09 | `group_live_ready` | promote Job |
| M2-MS-10 | `group_live_started` | CSS 推流 |
| M2-MS-11 | `group_live_ended` | stop/expire/断流 |

### 6.5 M2 退出检查（后端联调）

- [x] 测试群：authorize → 到点 → push-info → OBS 推流 → `current.status=LIVE` — **代码完成**；OBS/CSS 真推流待域名密钥  
- [ ] play-info WebRTC 在测试 App/VLC/腾讯云工具可播 — 待 CSS 域名  
- [x] stop 后 status=ENDED，IM ended — **代码完成**（IM 经 Integration；CSS Drop 密钥空则跳过）  
- [x] 同群第二次 authorize 在活跃态被拒绝 — 活跃槽 UNIQUE + `GROUP_LIVE_SLOT_TAKEN`  
- [ ] 集成测试：并发 authorize、webhook 幂等 — webhook dedup 表已建；并发压测 NOT RUN  

---

## 7. 阶段 M3：客户端与 IM（P0-β）

**目标：** Flutter 完成用户可见闭环（可与 M2 并行开发，联调在 M2 完成后）。

### 7.1 客户端任务

| ID | 模块 | 任务 |
|----|------|------|
| M3-APP-01 | API 层 | Base `/group-live/api/v1`；信封 `{code,data}` |
| M3-APP-02 | 群主 | 选成员 + roomName + 时间 → authorize |
| M3-APP-03 | 群主 | 改期/改昵称/撤销 |
| M3-APP-04 | 主播 | 推流地址页：复制 rtmpServer + streamKey + OBS 帮助 |
| M3-APP-05 | 群聊 | 预约/直播中卡片；监听 IM `group_live_*` |
| M3-APP-06 | 观众 | 直播间：WebRTC 播放器 + FLV/HLS 降级 |
| M3-APP-07 | 关播 | 主播/群主/群管 stop 按钮 + OBS 提示文案 |
| M3-APP-08 | 顶栏 | `live/current` 刷新「直播中」入口 |

### 7.2 IM 监听

| businessID | 客户端动作 |
|------------|------------|
| `group_live_scheduled` | 插入/更新预约卡片 |
| `group_live_started` | 卡片变「直播中」 |
| `group_live_ended` | 卡片结束态 |
| `group_live_ready` | 主播端强提醒 |

### 7.3 M3 退出检查

- [ ] 真机：群主预约 → 群卡片可见  
- [ ] 真机：主播复制地址 → OBS 推流 → 卡片变 LIVE  
- [ ] 真机：观众进入播放 ≥30s 稳定  
- [ ] 弱网：降级 FLV/HLS 可播放  

---

## 8. 阶段 M4：打赏与钱包（P1）

**目标：** 自定义金额、多币种打赏 + 平台抽成配置。

### 8.1 主服务

| ID | 任务 | 验收 |
|----|------|------|
| M4-MS-01 | `WalletFeeScene.LIVE_TIP` / `WalletLimitScene.LIVE_TIP` | Admin 可配 |
| M4-MS-02 | `WalletLedgerType.LIVE_TIP_OUT/IN` + `WalletFeeService` USDT 例外 | 单测 |
| M4-MS-03 | `POST /integration/v1/wallet/live-tip` | PayPin、幂等、ledger |
| M4-MS-04 | Admin seed：`wallet_fee_config` / `wallet_limit_config` 各币种 | 后台可见 |

### 8.2 group-live-service

| ID | 任务 | 验收 |
|----|------|------|
| M4-GL-01 | `live_tip_order` 表 + Repository | migration |
| M4-GL-02 | `POST /api/v1/live/{sid}/tip` | 调 Integration；存 ledger 引用 |
| M4-GL-03 | IM `live_tip`（Integration 代发） | 直播间飘屏 |

### 8.3 M4 退出检查

- [ ] USDT / 99 各测一笔：余额、ledger、fee 正确  
- [ ] 相同 `clientOrderId` 重试不双扣  
- [ ] 非 LIVE 状态 tip 拒绝  
- [ ] 非群成员 tip 拒绝  

---

## 9. 阶段 M5：治理与稳定性（P2）

**目标：** 生产可运维、可治理。

| ID | 任务 | 负责 | 验收 |
|----|------|------|------|
| M5-GL-01 | `GroupLiveDisconnectDebounceJob`（30s） | WS-B | OBS 闪断不立刻 ENDED |
| M5-GL-02 | `GET /api/internal/live/anchor-active` | WS-B | 主服可查 |
| M5-MS-01 | `LiveKitCallService` invite/accept 互斥 | WS-C | ANCHOR_LIVE_ACTIVE |
| M5-MS-02 | authorize/pushInfo 前 `open-session` 检查 | WS-B | ANCHOR_IN_CALL |
| M5-GL-03 | Admin `GET/POST /api/v1/admin/group-live` | WS-B | 列表 + ban |
| M5-MS-03 | `POST /integration/v1/admin/group-live/check` | WS-C | 权限校验 |
| M5-MS-04 | 群解散 / 主播被踢 → internal stop（可选 hook） | WS-C | session 结束 |
| M5-OPS-01 | 结构化日志 + 基础指标 | WS-B | 可按 liveSessionId 搜 |
| M5-ADM-01 | Admin 菜单 + ban 页 | WS-E | 运营可禁播 |

### M5 退出检查

- [ ] 直播中主播无法接听 LiveKit  
- [ ] 通话中主播无法 authorize  
- [ ] Admin ban 后 OBS 无法重推  
- [ ] debounce 场景人工验证通过  

---

## 10. 阶段 M6：生产切流与验收

**目标：** 生产上线，可回滚。

### 10.1 切流步骤（建议顺序）

参照 [sangong cutover](../sangong-service/scripts/cutover.md)：

| 步骤 | 动作 |
|------|------|
| 1 | 生产执行 `group_live` migration |
| 2 | 部署 group-live-service（`8092` 仅本机） |
| 3 | `./scripts/smoke.sh` + 内网 authorize 冒烟（测试群） |
| 4 | 腾讯云回调 URL 指生产 `/group-live/webhook/tencent/css` |
| 5 | 部署主服（Proxy + Integration + Wallet + LiveKit 互斥） |
| 6 | `GROUP_LIVE_PROXY_ENABLED=true`，重启主服 |
| 7 | 发布 Flutter（API 前缀 `/group-live/api/v1`） |
| 8 | 选 1–2 个群灰度 24h |
| 9 | 全量开放 |

### 10.2 生产验收清单

- [ ] 预约 + OBS + 观看 + stop 全链路  
- [ ] 打赏 USDT + 平台币各 1 笔  
- [ ] Admin ban 有效  
- [ ] LiveKit 互斥有效  
- [ ] 回调 5xx 率正常；Job 心跳正常  
- [ ] 回滚演练：关 proxy + 停 group-live，主 App 其他功能正常  

### 10.3 回滚

| 级别 | 动作 |
|------|------|
| L1 | `GROUP_LIVE_PROXY_ENABLED=false`，重启主服（Instant） |
| L2 | 停 `group-live-service` 进程 |
| L3 | Admin 对活跃 LIVE ban / CSS ForbidLiveStream |
| L4 | Flutter 隐藏入口（需发版，尽量避免） |

---

## 11. Workstream 任务分解

### 11.1 WS-B group-live-service 文件清单（建议顺序）

根目录：`/www/wwwroot/group-live-service/`

```text
1. pom.xml, Application, application.yml, .env.example
2. security/JwtAuthFilter, web/GlobalResponseWrapper
3. integration/MainServerClient
4. domain/* + repository/*
5. service/GroupLivePushPlayService
6. service/GroupLiveSessionService + Controller
7. webhook/* + GroupLiveCssClient
8. job/SchedulePromoteJob, ExpireJob
9. service/GroupLiveTipService（M4）
10. admin/*, internal/*（M5）
11. scripts/*, deploy/nginx-group-live.conf
```

### 11.2 WS-C 主服务文件清单

```text
1. grouplive/GroupLiveServiceProxyController
2. integration/IntegrationGroupLiveAccessController
3. integration/IntegrationGroupLiveImController
4. integration/IntegrationGroupLiveCallController
5. integration/IntegrationGroupLiveWalletController（M4）
6. integration/IntegrationAdminGroupLiveController（M5）
7. wallet/* LIVE_TIP 枚举 + WalletFeeService 调整（M4）
8. livekit/LiveKitCallService 互斥（M5）
9. application.yml group-live.proxy-*
```

### 11.3 WS-D Flutter 文件清单（建议）

```text
lib/group_live/
  group_live_api.dart
  group_live_models.dart
  group_live_im_handler.dart
  pages/
    group_live_schedule_page.dart      # 群主预约
    group_live_push_info_page.dart     # 主播 OBS 地址
    group_live_room_page.dart          # 观众播放+打赏
  widgets/
    group_live_chat_card.dart          # 群聊卡片
```

---

## 12. 测试门禁

每个里程碑合并前必须满足：

| 门禁 | M1 | M2 | M4 | M6 |
|------|----|----|----|----|
| 单元测试 | 骨架 | 状态机+URL | tip 幂等 | 全绿 |
| Integration 测试 | live-access | webhook | wallet/live-tip | — |
| smoke.sh | health | +authorize 模拟 | +tip | 生产 |
| 客户端真机 | — | 可选 | 打赏 | 必须 |
| 安全：8092 不暴露公网 | ✓ | ✓ | ✓ | ✓ |

**关键集成用例（自动化优先）：**

1. 同群并发 authorize → 1 成功  
2. webhook 重复推流 → 1 次 LIVE  
3. tip clientOrderId 幂等  
4. ANCHOR_IN_CALL / ANCHOR_LIVE_ACTIVE  

---

## 13. 切流与回滚

详细步骤见 [§10](#10-阶段-m6生产切流与验收)。补充：

- **写端唯一：** 仅 `group-live-service` 写 `group_live` 库；主服 **不** 直连该库  
- **钱包唯一：** 仅主服写 `wallet_ledger`  
- **回调 URL：** 切环境时只改腾讯云控制台，不改 App  

建议新增：`/www/wwwroot/group-live-service/scripts/cutover.md`（从 sangong 模板改写，M6 前完成）。

---

## 14. 风险登记册

| 风险 | 影响 | 缓解 | 负责人 |
|------|------|------|--------|
| CSS 回调丢失 | 卡在 AUTHORIZED | Stale reconcile Job；Describe 对账 | WS-B |
| OBS 闪断 | 误 ENDED | M5 debounce | WS-B |
| WebRTC 播放失败率高 | 观看差 | FLV/HLS 降级；客户端重试 | WS-D |
| JWT 不校验 Redis 吊销 | 安全 | 文档已知；v2 introspect | WS-C |
| 打赏双扣 | 资金 | clientOrderId 幂等 + Integration 事务 | WS-C |
| 带宽成本超预期 | 费用 | 灰度群；监控播放量 | WS-A |
| Integration 主服不可用 | group-live 全挂 | 超时快速失败；健康检查告警 | WS-C |
| 客户端仍调旧路径 | 404 | 发版前 grep；M6 前强制升级 | WS-D |

---

## 15. v2 backlog（不在 v1）

| 项 | 说明 |
|----|------|
| 礼物 catalog | 后台配置 + 动画 |
| 录制回放 | CSS 录制 + 播放页 |
| 鉴黄 | CSS 截图 + 第三方审核 |
| PC Web 推流地址页 | 免手机复制 |
| TCP realtime 顶栏 | 不依赖 IM 轮询 |
| group-live 会话 introspect | 强 JWT 吊销 |

---

## 附录：文档索引

| 文档 | 用途 |
|------|------|
| [group-live-architecture.md](./group-live-architecture.md) | 架构与设计 |
| [group-live-client.md](./group-live-client.md) | API / IM 契约 |
| [group-live-server.md](./group-live-server.md) | 双服务 checklist |
| [group-live-service/README.md](../../group-live-service/README.md) | 微服务运维（`/www/wwwroot/group-live-service`） |
| [wallet-client.md](./wallet-client.md) | 金额单位 / PayPin |
| [livekit-call-client.md](./livekit-call-client.md) | 互斥相关 |

## 附录：建议 PR / 分支策略

| 分支/PR | 内容 | 合并门槛 |
|---------|------|----------|
| `feat/group-live-service-scaffold` | M1 | smoke + proxy |
| `feat/group-live-core` | M2 | 集成测试 + OBS 联调 |
| `feat/group-live-integration-main` | M1–M2 主服 | Integration 单测 |
| `feat/group-live-flutter` | M3 | 真机截图 |
| `feat/group-live-tip` | M4 | 钱包对账 |
| `feat/group-live-ops` | M5–M6 | Admin + 切流文档 |

---

**下一步建议：** 从 **M0 + M1** 开始，先让 `group-live-service` 与主服 proxy 跑通，再进入 M2 OBS 联调。
