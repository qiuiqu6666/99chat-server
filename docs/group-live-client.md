# 群直播 — 对接文档（初步）

> **App 接口请改用 [group-live-app-api.md](./group-live-app-api.md)**（现网已实现，含 curl / JSON 示例）。本文是早期草案，部分路径与字段已过时。
>
> 版本：v0.3（草案）  
> 适用：99chat iOS / Android Flutter 客户端、运营后台、运维  
> 媒体：腾讯云 **云直播 CSS**（推流 RTMP / 观众快直播 WebRTC）  
> 关联：[group-live-app-api.md](./group-live-app-api.md)、[group-live-implementation-plan.md](./group-live-implementation-plan.md)、[wallet-client.md](./wallet-client.md)、[livekit-call-client.md](./livekit-call-client.md)

---

## 1. 一眼看清

| 能力 | 谁做 | 说明 |
|------|------|------|
| **推流（直播端）** | **用户自备 OBS** | App **不做**摄像头/推流 SDK；主播复制 RTMP 地址到 OBS |
| **观众观看** | App | 快直播 WebRTC 拉流（失败可降级 FLV/HLS） |
| **预约开播** | App + 服务端 | 群主指定成员 + **房间昵称** + 计划开播时间 |
| **权限** | 服务端 | 一群一场；仅群主可授权；仅指定主播可取推流地址 |
| **打赏 v1** | App + 钱包 | **自定义金额**，多币种；无礼物 catalog |
| **关播** | 主播 / 群主 / 群管 / 平台管理员 | 见 [§5](#5-关播权限) |
| **音视频通话** | LiveKit | 与群直播 **互斥**（见 [§11](#11-与-livekit-互斥)） |

### 1.1 能力边界（明确不做）

- App 内集成推流 SDK（LiteAV / WebRTC 推流等）
- App 内摄像头预览、美颜、一键开播
- 自研 PC 直播客户端
- v1 礼物 catalog / 礼物动画资源后台

### 1.2 现网地址（与其它文档一致）

| 用途 | 地址 |
|------|------|
| 业务 API | **`http://47.239.60.107:8081/group-live/api/v1`**（经主服代理） |
| HTTPS 反代（可选） | `https://api99chat.99chat.vip` → 同机 `8081` |
| 鉴权 | `Authorization: Bearer <App JWT>` |
| 腾讯云 CSS 推拉流域名 | **待运维配置**（见 [group-live-architecture.md §9](./group-live-architecture.md#9-腾讯云-css-集成)） |

### 1.3 端到端流程

```text
群主 POST .../live/authorize（指定 anchorUserId + roomName + scheduledStartAt）
        │
        ├─► IM group_live_scheduled（群预约卡片）
        │
        ▼
到点：status SCHEDULED → AUTHORIZED
        │
        ├─► IM group_live_ready（提醒指定主播）
        │
        ▼
指定主播 GET .../live/{sessionId}/push-info
        │
        ├─► 复制 RTMP 服务器 + 串流密钥 → OBS 开始推流
        │
        ▼
腾讯云 CSS 推流回调 → status LIVE
        │
        ├─► IM group_live_started
        │
        ▼
观众 GET .../play-info → WebRTC 快直播播放 + 打赏
        │
        ▼
OBS 停推 / 主播·群主·群管 stop / 平台 ban → ENDED | BANNED
```

> **重要：** `LIVE` 状态以 **腾讯云推流回调** 为准，不是 App 按钮。

---

## 2. 产品规则（已冻结）

| 规则 | 决策 |
|------|------|
| 并发 | 同一群同时最多 **1** 场活跃直播 |
| 活跃态 | `SCHEDULED` \| `AUTHORIZED` \| `LIVE` |
| 谁可授权 | **仅群主** |
| 谁可推流 | **群主指定的** `anchorUserId`（OBS） |
| 房间昵称 | 授权时必填 `roomName`（服务端 1–40 字；新 UI 限制 10 字以兼容旧包服务端仍接受 40） |
| 描述 | 选填 `description`（最多 30 字）；旧客户端可不传 |
| 预约时间 | 选填 `scheduledStartAt`；不传 / 已过视为即时 `AUTHORIZED`。旧客户端传未来时间则仍预约。群主可改期（仅 `SCHEDULED`） |
| 改昵称 | 仅 `SCHEDULED` 可 PATCH 修改 `roomName` |
| 开播宽限期 | 到点后 **30 分钟**内必须 OBS 推流成功，否则 `SCHEDULE_EXPIRED`（可配置） |
| 打赏 | 自定义金额；币种 `USDT` / `99` / `TRX` / `CNY` |
| 平台抽成 | 按币种配置 `LIVE_TIP` 手续费（运营后台） |
| 观众拉流 | 默认 **快直播 WebRTC** |
| 关播 | 主播、群主、群管理员、平台管理员（ban） |

---

## 3. 会话状态机

```text
IDLE
  └─ authorize ──► SCHEDULED（已预约，未到点）
                      └─ 到点 ──► AUTHORIZED（可取 push-info，等 OBS 推流）
                                      └─ CSS 推流回调 ──► LIVE
                                      └─ 宽限期过期 ──► ENDED(SCHEDULE_EXPIRED)
                      └─ revoke ──► IDLE
  LIVE ── stop ──► ENDED
  LIVE ── admin ban ──► BANNED
  ENDED | BANNED ──► IDLE（槽位释放）
```

### 3.1 end_reason 枚举

| 值 | 含义 |
|----|------|
| `NORMAL` | 主播 OBS 停推或主动 stop |
| `OWNER_STOP` | 群主 stop |
| `ADMIN_STOP` | 群管理员 stop |
| `DISCONNECT` | CSS 断流回调 |
| `SCHEDULE_EXPIRED` | 超过计划时间 + 宽限期仍未推流 |
| `REVOKED` | 预约阶段 revoke |
| `ADMIN_BAN` | 平台管理员禁播 |
| `LIVEKIT_CONFLICT` | 互斥兜底（极少） |

---

## 4. HTTP API（草案，均需 JWT）

**对外 Base：** `http://47.239.60.107:8081/group-live/api/v1`  
**路径说明：** 与 [sangong-service](../sangong-service/README.md) 相同，App **只打主服**；主服剥 `/group-live` 前缀转发到内网 `group-live-service :8092`。

> 成功响应可能被包装为 `{ "code": 0, "message": "ok", "data": { ... } }`，客户端取 `data`。

### 4.1 群主：预约直播（授权指定主播）

```http
POST /group-live/api/v1/groups/{groupId}/live/authorize
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "anchorUserId": "user01abcd",
  "roomName": "今晚策略分享",
  "scheduledStartAt": "2026-08-17T10:00:00+07:00"
}
```

**字段说明：**

| 字段 | 必填 | 说明 |
|------|------|------|
| `anchorUserId` | 是 | 群主指定的主播（须为群成员） |
| `roomName` | 是 | **房间昵称**（直播标题），trim 后 1–40 字符；禁止纯空白。新 UI 限制 10 字，服务端为兼容旧包仍接受 40 |
| `description` | 否 | 直播描述，trim 后最多 30 字；不传 / 空白视为没有 |
| `scheduledStartAt` | 否 | 计划开播时间（ISO-8601）。不传 / 已过 / 现在 → 即时 `AUTHORIZED`；未来 → 预约 `SCHEDULED`（不再要求提前 5 分钟，最远 7 天） |

**校验：**

- 调用方为 **群主**（`NOT_GROUP_OWNER`）
- `roomName` 合法（`ROOM_NAME_REQUIRED` / `ROOM_NAME_TOO_LONG`）
- `anchorUserId` 为 **群成员**
- 群内无活跃 session（`GROUP_LIVE_SLOT_TAKEN`）
- `scheduledStartAt` 为未来时间时不再要求最小提前量；超过 7 天 `SCHEDULE_TOO_FAR`。不传或已过视为即时，不返回 `SCHEDULE_TOO_SOON` / `SCHEDULE_IN_PAST`
- 指定主播无进行中的 LiveKit 通话（`ANCHOR_IN_CALL`）

**成功 200 `data`（示例）：**

```json
{
  "liveSessionId": "gl_abc123",
  "groupId": "m123456",
  "roomName": "今晚策略分享",
  "anchorUserId": "user01abcd",
  "scheduledStartAt": "2026-08-17T10:00:00+07:00",
  "expireAt": "2026-08-17T10:30:00+07:00",
  "status": "SCHEDULED"
}
```

`expireAt` = `scheduledStartAt` + 开播宽限期（默认 30 分钟）。

---

### 4.2 群主：改期 / 改房间昵称（仅 SCHEDULED）

```http
PATCH /group-live/api/v1/groups/{groupId}/live/schedule
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "scheduledStartAt": "2026-08-17T11:00:00+07:00",
  "roomName": "今晚策略分享（改期）"
}
```

- `scheduledStartAt`、`roomName` **至少传一项**；未传字段保持不变。
- 仅 `SCHEDULED` 可修改；`AUTHORIZED` / `LIVE` 不可改房间昵称。

成功后 IM 发 `group_live_schedule_updated`。

---

### 4.3 群主：撤销预约

```http
POST /group-live/api/v1/groups/{groupId}/live/revoke
Authorization: Bearer <JWT>
```

仅 `SCHEDULED` 或 `AUTHORIZED` 可撤销。

---

### 4.4 指定主播：获取 OBS 推流地址

```http
GET /group-live/api/v1/live/{liveSessionId}/push-info
Authorization: Bearer <JWT>
```

**权限：** 当前用户 == `anchorUserId`，且 status == `AUTHORIZED`（已到计划开播时间）。

**成功 200 `data`（示例）：**

```json
{
  "liveSessionId": "gl_abc123",
  "roomName": "今晚策略分享",
  "streamId": "live_m123456_gl_abc123",
  "rtmpServer": "rtmp://push.example.com/live/",
  "streamKey": "live_m123456_gl_abc123?txSecret=...&txTime=...",
  "expiresAt": "2026-08-17T10:30:00+07:00",
  "obsHint": "OBS → 设置 → 推流 → 服务选「自定义」"
}
```

**客户端 UI：**

- 按钮文案：**「获取推流地址」** / **「复制到 OBS」**（勿写「开始直播」）
- 提供「复制服务器」「复制串流密钥」两个操作
- 可选静态帮助：OBS 推流配置步骤

**常见错误：**

| code | 含义 |
|------|------|
| `NOT_DESIGNATED_ANCHOR` | 非指定主播 |
| `LIVE_NOT_AUTHORIZED_YET` | 仍为 SCHEDULED，未到点 |
| `LIVE_SESSION_EXPIRED` | 已过 expireAt |
| `LIVE_NOT_ACTIVE` | session 已结束 |

---

### 4.5 观众：获取播放地址

```http
GET /group-live/api/v1/live/{liveSessionId}/play-info
Authorization: Bearer <JWT>
```

**权限：** 群成员；status == `LIVE`。

**成功 200 `data`（示例）：**

```json
{
  "liveSessionId": "gl_abc123",
  "roomName": "今晚策略分享",
  "protocol": "webrtc",
  "playUrl": "webrtc://play.example.com/live/live_m123456_gl_abc123?...",
  "fallbackFlvUrl": "https://play.example.com/live/....flv?...",
  "fallbackHlsUrl": "https://play.example.com/live/....m3u8?...",
  "anchorUserId": "user01abcd"
}
```

直播间顶部标题展示 `roomName`，副标题可展示主播昵称。

播放器：**优先 WebRTC 快直播**；失败再试 FLV / HLS。

---

### 4.6 查询群当前直播

```http
GET /group-live/api/v1/groups/{groupId}/live/current
Authorization: Bearer <JWT>
```

**成功 200 `data`：**

- 无活跃直播：`{ "active": false }`
- 有：`{ "active": true, "liveSessionId": "...", "roomName": "...", "status": "LIVE", "anchorUserId": "...", "scheduledStartAt": "...", "startedAt": "..." }`

---

### 4.7 结束直播

```http
POST /group-live/api/v1/groups/{groupId}/live/stop
Authorization: Bearer <JWT>
```

**权限：** 指定主播 **或** 群主 **或** 群管理员。

服务端会尝试断开 CSS 流；App 应提示主播：**「若使用 OBS，请在 OBS 内停止推流」**。

---

### 4.8 打赏（v1：仅自定义金额）

```http
POST /group-live/api/v1/live/{liveSessionId}/tip
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "currency": "USDT",
  "amount": 1500000,
  "payPin": "123456",
  "clientOrderId": "tip_20260817_001",
  "memo": "加油"
}
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `currency` | `USDT` / `99` / `TRX` / `CNY`（见 [wallet-client.md §1.1](./wallet-client.md#11-金额单位必读)） |
| `amount` | USDT 为 **micro**（6 位小数）；`99`/`CNY` 为 **分**；TRX 单位以后端 wallet 约定为准 |
| `payPin` | 6 位支付密码 |
| `clientOrderId` | 幂等键，同转账/红包 |
| `memo` | 可选，≤ 50 字 |

**校验：**

- status == `LIVE`
- 打赏者为群成员
- 收款方固定为 **指定主播**
- 不能给自己打赏
- 余额、限额、PayPin

**成功 200 `data`（示例）：**

```json
{
  "tipId": 1001,
  "liveSessionId": "gl_abc123",
  "fromUserId": "...",
  "toUserId": "...",
  "currency": "USDT",
  "amount": 1500000,
  "feeAmount": 0,
  "memo": "加油",
  "createdAt": "2026-08-17T10:15:00+07:00"
}
```

成功后服务端群发 IM `live_tip`（建议 **仅在直播间内** 飘屏，避免群聊刷屏）。

**常见错误：**

| code | 含义 |
|------|------|
| `LIVE_NOT_LIVE` | 未在直播中 |
| `NOT_GROUP_MEMBER` | 非群成员 |
| `CANNOT_TIP_SELF` | 不能打赏自己 |
| `INSUFFICIENT_BALANCE` | 余额不足 |
| `PAY_PIN_INVALID` | 支付密码错误 |
| `CLIENT_ORDER_ID_CONFLICT` | 幂等冲突 |

---

## 5. 关播权限

| 操作 | 操作者 | 接口 | 本地状态 | 腾讯云 |
|------|--------|------|----------|--------|
| **stop** | 指定主播 | `POST .../live/stop` | `ENDED` | 可选断流 |
| **stop** | 群主 | 同上 | `ENDED(OWNER_STOP)` | 可选断流 |
| **stop** | 群管理员 | 同上 | `ENDED(ADMIN_STOP)` | 可选断流 |
| **ban** | **平台管理员** | `POST /admin/api/v1/group-live/{id}/ban` | `BANNED` | **必须** `ForbidLiveStream` |

---

## 6. IM 自定义消息

群聊 / 直播间内自定义消息 `businessID` 约定（**待客户端实现监听**）：

### 6.1 `group_live_scheduled`

预约成功（群主 authorize 后）。

```json
{
  "businessID": "group_live_scheduled",
  "liveSessionId": "gl_abc123",
  "groupId": "m123456",
  "roomName": "今晚策略分享",
  "anchorUserId": "user01abcd",
  "scheduledStartAt": "2026-08-17T10:00:00+07:00",
  "expireAt": "2026-08-17T10:30:00+07:00"
}
```

**UI：** 群聊插入「直播预约卡片」；**主标题 = `roomName`**，副文案显示计划时间与主播昵称。

---

### 6.2 `group_live_schedule_updated`

改期后。

```json
{
  "businessID": "group_live_schedule_updated",
  "liveSessionId": "gl_abc123",
  "roomName": "今晚策略分享（改期）",
  "scheduledStartAt": "2026-08-17T11:00:00+07:00",
  "expireAt": "2026-08-17T11:30:00+07:00"
}
```

---

### 6.3 `group_live_ready`

到点进入 `AUTHORIZED`，提醒指定主播配置 OBS。

```json
{
  "businessID": "group_live_ready",
  "liveSessionId": "gl_abc123",
  "groupId": "m123456",
  "roomName": "今晚策略分享",
  "scheduledStartAt": "2026-08-17T10:00:00+07:00",
  "expireAt": "2026-08-17T10:30:00+07:00"
}
```

**UI：** 仅指定主播强提醒；可 deep link 到「推流地址」页。

---

### 6.4 `group_live_started`

CSS 推流回调确认开播。

```json
{
  "businessID": "group_live_started",
  "liveSessionId": "gl_abc123",
  "groupId": "m123456",
  "roomName": "今晚策略分享",
  "anchorUserId": "user01abcd",
  "startedAt": "2026-08-17T10:05:00+07:00"
}
```

**UI：** 群聊卡片变为「直播中」（标题 = `roomName`），点击进入直播间。

---

### 6.5 `group_live_ended`

```json
{
  "businessID": "group_live_ended",
  "liveSessionId": "gl_abc123",
  "groupId": "m123456",
  "roomName": "今晚策略分享",
  "endReason": "NORMAL",
  "endedAt": "2026-08-17T11:00:00+07:00"
}
```

`endReason` 见 [§3.1](#31-end_reason-枚举)。

---

### 6.6 `live_tip`

```json
{
  "businessID": "live_tip",
  "liveSessionId": "gl_abc123",
  "fromUserId": "user02",
  "fromNickname": "Alice",
  "currency": "USDT",
  "amount": 1500000,
  "memo": "加油"
}
```

**UI：** 直播间内飘屏；可选短动画。

---

## 7. OBS 配置指引（给主播）

### 7.1 推荐步骤

1. 打开 OBS → **设置 → 推流**
2. 服务：**自定义**
3. **服务器**：粘贴 `rtmpServer`（如 `rtmp://push.example.com/live/`）
4. **串流密钥**：粘贴 `streamKey`（含鉴权参数）
5. 到计划时间后点击 **开始推流**
6. App 群卡片变为「直播中」后，观众可进入观看

### 7.2 推荐编码参数（建议值）

| 项 | 建议 |
|----|------|
| 输出分辨率 | 1280×720 或 1920×1080 |
| 帧率 | 30 fps |
| 视频码率 | 720p: 2500–4000 kbps；1080p: 4500–6000 kbps |
| 关键帧间隔 | 2 s |
| 音频 | AAC 128 kbps |

### 7.3 注意事项

- 推流地址鉴权有过期时间，过期后在 App **重新获取 push-info**
- OBS 显示「正在推流」但 App 仍非 LIVE：检查服务器/密钥是否填反、鉴权是否过期
- 服务端 stop 后，请在 OBS **手动停止推流**，避免 OBS 自动重连

---

## 8. 客户端页面清单（Flutter）

### 8.1 群主

| 页面/入口 | 功能 |
|-----------|------|
| 群成员列表 | 选择成员 → 填写 **房间昵称** → 选择计划开播时间 → authorize |
| 预约管理 | 改期、改房间昵称、撤销 |
| 群聊直播卡片 | 进入观看；直播中可 **结束直播** |

### 8.2 指定主播

| 页面/入口 | 功能 |
|-----------|------|
| 预约详情 | 显示 roomName / scheduledStartAt / expireAt |
| 推流地址页 | `GET push-info`；复制 RTMP；OBS 帮助 |
| **不做** | 摄像头预览、美颜、App 内推流 |

### 8.3 观众

| 页面/入口 | 功能 |
|-----------|------|
| 群聊直播卡片 | 进入直播间 |
| 直播间 | WebRTC 播放器 + 聊天 + 自定义金额打赏 |

### 8.4 登录后建议订阅

```text
1. 腾讯 IM 登录（与线上一致）
2. 监听群自定义消息 businessID：
   group_live_scheduled | group_live_schedule_updated | group_live_ready
   | group_live_started | group_live_ended | live_tip
3. 群资料/群聊顶栏：轮询或事件刷新 GET /group-live/api/v1/groups/{groupId}/live/current
4. LiveKit 通话模块保持独立，不与 GroupLiveRoom 共用状态机
```

---

## 9. 运营后台（草案）

| 接口 | 权限 | 说明 |
|------|------|------|
| `GET /group-live/api/v1/admin/group-live?status=LIVE&page=1` | `group_live.read` | 直播列表（含 `roomName`、群名、主播） |
| `POST /group-live/api/v1/admin/group-live/{id}/ban` | `group_live.write` | 强制禁播 |

**ban 请求体：**

```json
{ "reason": "违规内容" }
```

钱包手续费：在现有 `PUT /api/v1/wallet/fee-config/{id}` 增加 scene **`LIVE_TIP`**（按币种配置，见 [group-live-server.md](./group-live-server.md)）。

---

## 10. 错误码汇总（草案）

| code | HTTP | 说明 |
|------|------|------|
| `GROUP_LIVE_SLOT_TAKEN` | 409 | 群已有活跃直播/预约 |
| `NOT_GROUP_OWNER` | 403 | 非群主 |
| `NOT_GROUP_ADMIN` | 403 | 非群主/群管 |
| `NOT_DESIGNATED_ANCHOR` | 403 | 非指定主播 |
| `NOT_GROUP_MEMBER` | 403 | 非群成员 |
| `ROOM_NAME_REQUIRED` | 400 | 房间昵称为空 |
| `ROOM_NAME_TOO_LONG` | 400 | 房间昵称超过 40 字 |
| `DESCRIPTION_TOO_LONG` | 400 | 描述超过 30 字 |
| `ROOM_NAME_NOT_EDITABLE` | 409 | 非 SCHEDULED，不可改房间昵称 |
| `SCHEDULE_TOO_SOON` | 400 | 旧错误码，创建已不再返回 |
| `SCHEDULE_IN_PAST` | 400 | 旧错误码，创建已不再返回 |
| `LIVE_NOT_AUTHORIZED_YET` | 409 | 未到点，不能取 push-info |
| `LIVE_NOT_LIVE` | 409 | 未在 LIVE，不能观看/打赏 |
| `LIVE_SESSION_EXPIRED` | 409 | 预约宽限期已过 |
| `ANCHOR_IN_CALL` | 409 | 指定主播正在 LiveKit 通话 |
| `ANCHOR_LIVE_ACTIVE` | 409 | 主播直播中，不可接听通话 |
| `GROUP_LIVE_ACTIVE` | 409 | 群直播中（群通话等扩展场景） |
| `CANNOT_TIP_SELF` | 400 | 不能给自己打赏 |
| `CSS_NOT_CONFIGURED` | 503 | 服务端未配置腾讯云直播 |

---

## 11. 与 LiveKit 互斥

| 场景 | 行为 |
|------|------|
| 指定主播有 `RINGING` / `ANSWERED` 通话 | 拒绝 authorize / 取 push-info（`ANCHOR_IN_CALL`） |
| 主播 status == `LIVE` | 拒绝 LiveKit invite/accept（`ANCHOR_LIVE_ACTIVE`） |
| 群内有 `LIVE` session | 若未来有「群通话」，拒绝并提示 `GROUP_LIVE_ACTIVE` |

LiveKit 对接见 [livekit-call-client.md](./livekit-call-client.md)。**群直播与 LiveKit 使用独立 UI 与状态机。**

---

## 12. 实施分期

| 阶段 | 内容 | 状态 |
|------|------|------|
| **P0** | authorize / schedule / revoke / push-info / play-info / current / stop + CSS webhook + IM 消息 | ⬜ 待开发 |
| **P1** | 自定义金额打赏 + `LIVE_TIP` 手续费/限额 + `live_tip` | ⬜ 待开发 |
| **P2** | 定时 Job（到点/过期）+ LiveKit 互斥 + Admin ban | ⬜ 待开发 |
| **P3** | 礼物 catalog、榜单、录制回放 | ⬜ 后续 |

---

## 13. 相关文档

| 文档 | 说明 |
|------|------|
| [group-live-architecture.md](./group-live-architecture.md) | **完整架构设计（主文档）** |
| [group-live-server.md](./group-live-server.md) | 服务端落地清单与配置速查 |
| [wallet-client.md](./wallet-client.md) | 金额单位、PayPin、幂等 |
| [livekit-call-client.md](./livekit-call-client.md) | 音视频通话（互斥） |
| [group-governance-client.md](./group-governance-client.md) | 群主/管理员角色 |
