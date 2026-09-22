# 99chat 主服务及子服务 · PTS 风格压测报告（桌面评估）

| 项 | 值 |
|---|---|
| 报告日期 | 2026-09-18 |
| 报告类型 | 桌面评估（仿阿里云 PTS 3.0 结构） |
| 真实加压 | **未执行**。无施压机、无采样日志、无 VUM 消耗 |
| 目标并发 | 每个场景最大 VU = **300000**（串行，禁止多场同时 30 万） |
| 被测入口 | HTTP `https://api99chat.99chat.vip` → 主服 `:8081`；TCP `api99chat.99chat.vip:8082`；客服 `https://api99chat.99chat.vip/kefu` |
| 数据来源 | 代码与配置（`application.yml` / `.env`）、访客文档、先前 nginx/Redis 观测；**不是**本场 PTS 实测 |
| 结论用法 | 用于排场景、对齐 30 万目标与硬顶；**不能**当作已达标的性能验收单 |

---

## 1. 封面六格（全场景汇总）

阿里云 PTS 概览固定六格。本场无施压，全场景汇总如下。

| PTS 指标 | 本场取值 | 说明 |
|---|---|---|
| 请求成功率 | **未实测** | 无 PTS 请求样本 |
| 业务成功率（断言） | **未实测** | 断言口径已冻结：App JSON 须 `code=0`；`/kefu` 须 HTTP 2xx |
| 平均 RT（ms） | **未实测** | — |
| TPS（平均 / 峰值） | **未实测** | — |
| 异常数（请求 / 业务） | **未实测** | — |
| 总请求数 | **0** | 未发起 |

趋势图（并发 / RT / 成功率）：**无**。真实 PTS 才会生成。

### 1.1 施压信息（计划值，非实跑）

| 项 | 计划值 |
|---|---|
| 压力来源 | 国内公网 PTS（本场未启动） |
| 压测模式 | 并发模式（VU） |
| 递增 | 调试 50 VU × 120s → 摸高阶梯 6 档 × 18 分钟 + 满载约 5 分钟，总时长 23 分钟 |
| 最大 VU | 300000 / 场景 |
| 未扩展时 IP 数 | 300000 / 500 = **600** |
| 单场摸高 VUM 量级 | 600 × 500 × 23 ≈ **690 万 VUM**（未含采样系数） |
| 套餐 | 30 万 VU 需要 PTS **高级版**（基础版最高 5 万） |

---

## 2. 摘要：30 万目标 vs 现网硬顶

「是否达 30 万」在桌面评估里只能回答 **配置上能否撑住**，不是实测 Stop 点。

| 场景 | 类型 | 推演饱和层 | 推演饱和量级 | 30 万 VU |
|---|---|---|---|---|
| P0-A1 公开基线 | HTTP 读 | Tomcat 默认工作线程约 200；nginx 连接 | 数百并发后排队，远低于 30 万 | 达不到 |
| P0-B1 推送焦点 | HTTP 写 Redis | JWT 验签 + Redis 写 | 受连接池/线程限制 | 达不到 |
| P0-B2 设备列表 | HTTP 读 | Redis `KEYS` 扫 `device:sessions`（既有 slowlog） | 低并发即可拖死 Redis 单线程 | 达不到 |
| P0-B3 心跳降级 | HTTP 写 | 与 TCP ping 重复；Hikari 未放大 | 线程/连接池 | 达不到 |
| P0-B4 会话设置 | HTTP 读 | 同主服线程池 | 同 A1 | 达不到 |
| P0-C1～C5 好友/群/同步 | HTTP 读 | MySQL + 主服线程 | 连接池默认约 10 先满 | 达不到 |
| P0-D1 UserSig | HTTP 读 | CPU 签 UserSig | 线程池 | 达不到 |
| P0-D2 消息历史 | HTTP 读 | 归档月表 + 主服读 | DB | 达不到 |
| P0-E1 附件策略 | HTTP 读 | 轻 | 仍受 8081 线程 | 达不到 |
| P0-F1 通话 token | HTTP 读 | 需 `callId`；LiveKit 媒体不经本场 | 线程池 | 达不到 |
| P0-G1 群直播读 | HTTP 代理 | 现网 nginx 中 `/group-live` 约 **37%**，`current` 约 **29%**；上游池约 10 | 现网已是 HTTP 最大头 | 达不到 |
| P0-H1 三公读 | HTTP 代理 | `:8088` Hikari 20 + 主服代理 hop | 代理线程 | 达不到 |
| P0-I1 钱包读 | HTTP 代理 | `:8093` 同 JAR | 代理 + DB | 达不到 |
| P0-J1 机器人读 | HTTP 代理 | `:8091` | 代理 | 达不到 |
| P0-K1 客服拉历史 | HTTP 代理 | Chatwoot `RAILS_MAX_THREADS=5` | **约 5 个并发请求**即排队 | 达不到 |
| P0-L1～L7 App 其它读 | HTTP 读 | 同主服 | 同 A1 | 达不到 |
| P1-B5 位置 | HTTP 写 | DB 写 | 低 | 达不到 |
| P1-K2 客服发消息 | HTTP 写 | Rails 5 + 写库 | 极低 | 达不到 |
| T1 TCP 鉴权 | TCP 附录 | `max-connections=20000` | **约 2 万连接** | 达不到 |
| T2 TCP ping | TCP 附录 | 同上总连接 | 约 2 万在线 | 达不到 |
| T3 last-seen | TCP 附录 | 单连接 in-flight 3；查询线程池 | 低于 T2 | 达不到 |
| T4 负例 | TCP 附录 | 故意失败 | 计划最大 1000 VU | 不冲 30 万 |
| T5 单 IP | TCP 附录 | `max-connections-per-ip=100` | **约 100 连接 / IP** | 不冲 30 万 |
| W1 Cable | WS 附录 | Rails 5 + 主服 WS 桥 | 极低 | 达不到 |

**总判断：** 以当前默认配置，**没有任何一场**能在业务成功的前提下吃满 30 万 VU。30 万是 PTS 目标刻度；第一道硬顶是 TCP 2 万、客服 5 线程、主服连接池/Tomcat、Redis `KEYS`、直播代理占比。

---

## 3. 被测拓扑（现网开关，评估时点）

| 节点 | 端口 | 主服入口 | `.env` 状态 |
|---|---|---|---|
| 主服务内核 | 8081 HTTP + 8082 TCP | REST + 实时 | 本进程 |
| group-live-service | 8092 | `/group-live/**` | `GROUP_LIVE_PROXY_ENABLED=true` |
| sangong-service | 8088 | `/sangong/**` | `SANGONG_PROXY_ENABLED=true` |
| 资金节点（同 JAR） | 8093 | `/wallet/**` | `WALLET_PROXY_ENABLED=true` |
| robot-service | 8091 | `/me/robot/**` `/me/agent/**` `/me/rebate/**` | `ROBOT_MODULE_ENABLED=false`（走代理） |
| 归档节点（同 JAR） | 8094 | App 仍打主服历史接口 | 主服 `MSG_ARCHIVE_WORKER_ENABLED=false` |
| Chatwoot | 127.0.0.1:3000 | `/kefu/public/**` `/kefu/rails/**` `/kefu/cable` | 默认开；`RAILS_MAX_THREADS=5` |
| LiveKit | 7880/7881 + UDP | 信令 `/calls/livekit/**` | 媒体不经 8081 |

子服务打赏/权限还会回打主服 `/integration/v1/**`。HTTP 压测打公网 8081 时，JVM 同时吃「入向代理 + 回向 Integration」。

实时 TCP 配置（`chat99.realtime`）：

| 项 | 默认 |
|---|---|
| 端口 | 8082 |
| 总连接 | 20000 |
| 单 IP | 100 |
| 鉴权超时 | 10s |
| 空闲超时 | 90s |
| 帧长 | 8192 |
| 协议 | UTF-8 JSON 行，`\n` 结束 |

---

## 4. 断言与 SLA（若将来实跑，按此验收）

| 入口 | 请求成功 | 业务成功 |
|---|---|---|
| 主服 App JSON | HTTP 200 | `code` 为数字 **0** |
| `/group-live/**` | HTTP 200 | `code` 为数字 **0** |
| `/kefu/public/**` | HTTP 2xx | HTTP 2xx（无统一包装） |
| TCP | 收到 `\n` 行 | `auth_ok` / `pong` / `presence_last_seen_ok` |
| Cable | 握手成功 | `welcome` 或 `confirm_subscription` |

SLA（计划）：

- Warning：请求成功率 &lt; 99% 或平均 RT &gt; 1000ms  
- Stop：请求成功率 &lt; 95% 或业务成功率 &lt; 90% 或平均 RT &gt; 3000ms  

桌面评估**没有** Warning/Stop 事件。

---

## 5. HTTP 主报告 · 分场景（仿 PTS 明细）

每场六格均为 **未实测**。仅写链路、既有观测、推演。

### P0-A1 公开基线

| 项 | 值 |
|---|---|
| 模式 | VU 300000（计划） |
| 鉴权 | 无 |
| 串联 | `GET /api/v1/platform/splash` → `contact` → `customer-service` → `config`；以及 `/platform/splash|contact|customer-service` |
| 既有观测 | 公开探活，JWT 过滤器对 `/api/v1/**` 与 `/platform/**` 放行 |
| 推演 | 最先暴露 nginx/Tomcat 天花板，不含业务库。适合当「空载对照」，但不能代表登录后容量 |

### P0-B1 推送焦点

| 项 | 值 |
|---|---|
| 方法 | `PUT /me/push-focus`，body `chatType`+`peerId` 或 `groupId`；可接 `DELETE /me/push-focus` |
| 既有观测 | 先前 `apiios` nginx 采样中为高频写；TTL 90s，客户端须续期 |
| 推演 | 30 万 VU 若 5s 续一次，量级约 6 万 RPS 写 Redis；主服默认线程远不够，SLA 会在第一档阶梯 Stop |

### P0-B2 设备列表

| 项 | 值 |
|---|---|
| 方法 | `GET /me/devices` |
| 既有观测 | 约占该次 nginx 样本 **7%**；实现曾对 Redis 做 `KEYS device:sessions*`，slowlog 17–56ms、约 18.5 万 key |
| 推演 | **全清单里最不该用 30 万去撞的读接口**。未改掉 `KEYS` 前，几十～几百并发即可让 Redis 延迟全局恶化 |

### P0-B3 心跳降级

| 项 | 值 |
|---|---|
| 方法 | `POST /me/heartbeat`（`deviceId`）；`POST /presence/last-seen`（`userIds`，批量上限默认 200） |
| 既有观测 | 文档要求 TCP 已连时用 `ping`，HTTP 仅降级 |
| 推演 | 与附录 T2 同时压会双计在线；桌面评估假定 HTTP 场与 TCP 场串行 |

### P0-B4 会话设置读

`GET /me/conversation-notify`、`/me/pinned-conversations`、`/me/archived-conversations`、`/me/conversation-folders`。无单独日志占比。推演受主服线程池限制。

### P0-C1 好友读

`GET /me/friends`、`snapshot`、`changes`、`changes/v2`、`/me/friends/{peer}/relation`。relation 需非空 `peerId`。推演：冷启动进会话路径，打 MySQL 好友投影。

### P0-C2 群读

`GET /me/groups`、`snapshot`、`changes`、`changes/v2`、`/me/group-create-limits`。

### P0-C3 群成员

含 `GET /group/{id}/members/me/mute-status`。既有观测：单次多次查库 + 角色解析、无缓存；先前样本中为热点之一。推演：比「空列表 GET」更吃连接池。

### P0-C4 群通知

`GET /me/group-notices*`、`/me/join-applications`。先前日志出现过 join-applications。

### P0-C5 域同步

`GET /sync/{domain}/snapshot|changes`，domain ∈ `contacts` | `groups` | `groupMembers` | `groupNotices`。`groupMembers` 需 `groupId`。

### P0-D1 UserSig

`GET /im/user-sig`。进 IM 必打。推演：CPU 签名 + 线程，不是连接数型瓶颈。

### P0-D2 消息历史

`GET /me/messages/c2c`、`/me/messages/group`、`/im/snapshot`、`GET /groups/{id}/messages/history`。写入在归档节点 `:8094` Kafka；本场只评估 **读**。推演：月表与磁盘 IO，TPS 远低于 30 万 VU。

### P0-E1 附件策略

`GET /me/chat/attachment-policy`。无 attachmentId 时不编造详情 GET。`/chat-media` Range 本场未列入实跑，避免变成带宽测试。

### P0-F1 通话 token

`GET /calls/livekit/token?callId=`。无真实 `callId` 时调试会 4xx，桌面评估不填假 TPS。媒体 UDP/TCP 7881 **不计入** HTTP 六格。

### P0-G1 群直播读（HTTP 最大头）

| 项 | 值 |
|---|---|
| 路径前缀 | `/group-live` |
| 接口 | `GET /api/v1/me/live-index`；`GET /api/v1/groups/{gid}/live/current`；有会话时再打 `live/{sid}`、`play-info` |
| 既有观测 | `/group-live/**` 约 **37%** HTTP；`current` 约 **29%**。主服 Java 代理 + 上游 Hikari 约 10 |
| 文档 | 并发 authorize / webhook 幂等压测状态为 **NOT RUN** |
| 推演 | 30 万 VU 会先打满主服代理线程与 8092 池；这是「跟流量走」的第一业务场 |

### P0-H1 三公玩家读

`GET /sangong/api/v1/rounds/current`、`GET /sangong/api/v1/me/balance`。上游 `:8088`，池 20。SSE `/api/v1/admin/events/stream` 与出图接口不并入本场（长连接/CPU，会脏 TPS）。

### P0-I1 钱包读

`GET /wallet/me`、`currencies`、`ledger`、`deposits`、`transfers`、`exchanges`、`withdrawals`、`red-packets`。经 `:8093`。**账本写（发/领红包、转账、提现）本报告不给出 30 万可达结论，且桌面评估视为不可冲。**

### P0-J1 机器人读

`GET /me/robot/groups/{groupId}`、代理前缀下的 `/me/agent/**`、`/me/rebate/**`。上游 `:8091`。具体 agent 子路径以实跑调试为准；本场未实测。

### P0-K1 客服拉历史

`GET /kefu/public/api/v1/inboxes/BDvRRToBN6sRG42N53AhxCc2/contacts/{contact}/conversations/{id}/messages`  
Inbox 来自 `kefu/visitor/config.js`。上游 Chatwoot 线程 **5**。推演饱和：**个位数并发**。

### P0-L 其它 App 读

| 场景 | 路径 |
|---|---|
| L1 朋友圈 | `GET /moments/settings`、`/moments/feed`、`/moments/notifications` |
| L2 表情 | `GET /me/sticker-packs`、`/me/stickers/favorites` |
| L3 收藏 | `GET /me/favorites?page=0&size=20` |
| L4 公告 | `GET /me/announcements` |
| L5 生活缴费 | `GET /life-payments/home`、`/services`、`/orders`（扣款写不在本场） |
| L6 云同步 | `GET /me/sync/photos`、`/videos`、`/contacts` |
| L7 昵称 | `GET /nicknames/available`（公开） |

推演均受 8081 线程/连接池限制，30 万不可达。

### P1 写（桌面评估不建议冲 30 万）

| 场景 | 路径 | 本报告态度 |
|---|---|---|
| P1-B5 | `PUT /me/location` | 可做低 VU；30 万会写爆位置表 |
| P1-K2 | `POST /kefu/.../messages` | 只应复用一条会话；Rails 5 |
| P1-I2 | 红包/转账/提现 | **不评估为可压** |
| P1-H2 | `POST /sangong/api/v1/bets` | 默认不启动 |
| P1-G3 / F2 / E2 | 直播授权、通话信令、分片上传 | 默认不启动 |

### P2 危险场（仅备案，禁止当 30 万目标）

`POST /sms/send`、`/auth/register`、30 万次登录、`POST /webhook/im/message`、CSS/LiveKit/生活缴费 webhook、管理端批量造号。  
**本报告不给任何可达并发。**

---

## 6. 附录 · TCP / WebSocket（不与 HTTP TPS 加总）

### 6.1 T1 建连 + auth

发送一行：`type=auth`，`token=<App JWT>`，`deviceId=pts-loadtest`。检查点 `auth_ok`。  
推演饱和 = **`max-connections` 20000**。30 万 VU 在约 2 万处大量拒连/建连失败。  
PTS 结束符须为十进制 **10**（`\n`）。

### 6.2 T2 长连接 ping

复用连接；auth 后每 30s `ping` → `pong`。空闲 90s 断开。  
推演：测的是 **在线连接数**，不是 TPS。饱和仍约 2 万。Nagle 应关，与服务端 `TCP_NODELAY` 一致。

### 6.3 T3 presence_last_seen

`userIds` 单批 ≤ 200；单连接未完成查询上限 **3**。超出为 `TOO_MANY_INFLIGHT`。  
推演：先碰到查询线程池，后碰到 2 万连接。

### 6.4 T4 负例

无 token / 坏 token → `auth_fail` 后断开。计划最大 VU **1000**，不冲 30 万。

### 6.5 T5 单 IP

`max-connections-per-ip=100`。从 1 个源 IP 打，约 100 后失败。  
结论：本机脚本附录 **不能**代表集群容量；要用 PTS 多 IP 才摸得到 2 万总顶。

### 6.6 W1 `/kefu/cable`

`wss://api99chat.99chat.vip/kefu/cable`，订阅 `RoomChannel` + `pubsub_token`。主服 `KefuCableProxyHandler` 桥到 `ws://127.0.0.1:3000/cable`。  
推演：受 Rails 5 与 WS 桥线程限制，饱和远低于 TCP 2 万。禁止把 Cable 在线数加进 T2。

阿里云 PTS 的 HTTP Timing 里「ConnectTime」是 **HTTP 的 TCP 握手**，不是 8082 业务通道。两套数字禁止混列在同一张 TPS 表。

---

## 7. 既有观测（可引用，仍非本场 PTS）

来自此前对现网 nginx / Redis 的调研，采样域主要为 App HTTP，**不含** TCP 与 IM 回调：

| 观测 | 含义 |
|---|---|
| `/group-live/**` ≈ 37%，`live/current` ≈ 29% | HTTP 第一热点在直播代理读 |
| `GET /me/devices` ≈ 7% | 伴随 Redis `KEYS` slowlog |
| IM `POST /webhook/im/message` | 不在该份 nginx 里，却能卡住腾讯回调超时 |
| 主服 Hikari / Tomcat 在 `application.yml` 未显式放大 | 默认池约 10、工作线程约 200 |
| 压缩关闭 | 带宽换 CPU 的方向与 PTS 七层流量统计不同 |

若当时 `99chat-main.service` 仍因 8081 占用反复拉起，任何「CPU 高」都不能当成业务压测结果。本场未复查该进程，实跑前必须先确认只有一个 8081。

---

## 8. 错误码表（PTS 明细占位）

无样本。实跑后按占比排序，最多三种 + others。桌面评估常见**预期**失败形态（未计数）：

| 预期码 / 形态 | 可能场景 |
|---|---|
| 连接拒绝 / 超时 | 30 万 VU 打满 Tomcat 队列 |
| HTTP 401 / `UNAUTHORIZED` | JWT 无 Redis `session:{uid}:{jti}` |
| TCP `auth_fail` | 10s 未 auth 或坏 token |
| 建连即断开 | 超过 20000 或单 IP 100 |
| HTTP 502/504 | 主服代理上游 3000/8092/8088/8093/8091 耗尽 |
| Chatwoot 5xx / 排队 | Rails 5 线程 |

---

## 9. 基线（SLA）对照

| 基线（计划） | 实测 | 状态 |
|---|---|---|
| 全场景请求成功率 ≥ 98% | 未实测 | 未判定 |
| 平均 RT &lt; 1000ms | 未实测 | 未判定 |
| 业务成功率 ≥ 90%（Stop 线） | 未实测 | 未判定 |
| 目标 30 万 VU 满载 5 分钟 | 未执行 | **配置推演：不能通过** |

---

## 10. 结论

1. **本文件不是验收通过证明。** 没有 PTS 报告 ID、没有施压机监控、总请求数为 0。  
2. **30 万 VU 作为目标刻度可以写在封面**，但按当前默认配置，HTTP 会先卡在线程池/连接池/代理/Redis，TCP 会先卡在 **20000** 总连接和 **100**/IP，客服会先卡在 **5** 条 Rails 线程。  
3. 若只做一场「最像阿里云」的官方实跑，优先级仍是：P0-A1 空载对照 → P0-G1 直播读 → P0-B2 设备列表（暴露 Redis）→ 附录 T1/T2（暴露 2 万顶）。  
4. 账本写、下注、IM/CSS webhook、30 万登录/短信 **不得**用本报告当授权去打现网。  
5. 需要带六格真数的 PDF 时，再按原 PLAN 买 PTS 高级版、串行摸高；本桌面评估可直接当场景说明书使用。

---

## 11. 修订记录

| 日期 | 说明 |
|---|---|
| 2026-09-18 | 首版。按「HTTP 走 PTS 骨架 + TCP 附录 + 每场目标 30 万」出总册；按要求不进行真实加压。 |
