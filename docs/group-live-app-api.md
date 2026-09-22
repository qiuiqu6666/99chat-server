# 群直播 — App 客户端接口（单端）

> 适用：99chat iOS / Android Flutter  
> 版本：v1.0（与现网 `group-live-service` Controller 对齐）  
> 媒体：腾讯云 CSS（主播 **OBS RTMP 推流** / 观众 **快直播 WebRTC**）  
> 关联：[wallet-client.md](./wallet-client.md)（金额单位、PayPin）、[livekit-call-client.md](./livekit-call-client.md)（通话互斥）

App **只打主服**，不要直连 `:8092`。Admin / 内网 / Webhook **不是** App 接口。

---

## 1. 约定

| 项 | 值 |
|---|---|
| Base | `https://api99chat.99chat.vip` |
| 前缀 | `/group-live` |
| 鉴权 | `Authorization: Bearer <App JWT>`（与登录接口同一套） |
| Content-Type | `application/json` |
| 时间 | ISO-8601，如 `2026-08-17T10:00:00Z` 或带偏移 `2026-08-17T17:00:00+07:00` |
| 空字段 | 成功体 `non_null`，`null` 字段不出现 |

**成功（HTTP 200）：**

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

客户端取 `data`。注意成功 `code` 是 **数字 0**。

**失败（4xx/5xx）：**

```json
{
  "code": "UNAUTHORIZED",
  "message": "请先登录"
}
```

失败 `code` 是 **字符串**。

下文 curl 里：

```bash
BASE=https://api99chat.99chat.vip
TOKEN='<App JWT>'
GID='m123456'
SID='gl_abc123'
```

---

## 2. 产品规则（客户端必懂）

| 规则 | 说明 |
|---|---|
| 一群一场 | 同一群同时最多 1 个活跃会话：`SCHEDULED` / `AUTHORIZED` / `LIVE` |
| 谁授权 | **群主 / 群管理员** 指定某个群成员当主播 |
| 谁推流 | **仅指定主播** 用 OBS；App **不做**推流 SDK / 摄像头预览 |
| `LIVE` 从哪来 | **腾讯云推流回调**，不是 App 按钮 |
| 预约 / 即时 | 授权必填 `roomName`（服务端 1–40）；`description` 选填（最多 30）。`scheduledStartAt` **选填**：不传 / 已过 / 等于现在 → 即时 `AUTHORIZED`；未来时刻 → 预约 `SCHEDULED`（不再要求提前 1 分钟，最远 7 天）。旧客户端继续传未来时间则仍是预约 |
| 房间名 | 服务端仍接受 1–40 字（兼容旧包）。新版本 UI 输入限制 **10** 字；超 10 但 ≤40 服务端仍接受 |
| 宽限期 | 到点后 30 分钟内必须 OBS 推上，否则 `ENDED(SCHEDULE_EXPIRED)` |
| 打赏 v1 | 自定义金额，无礼物 catalog |
| 关播 | 指定主播 / 群主 / 群管调 `stop`；平台 ban 不是 App 接口 |

```text
IDLE
  └─ authorize（不传时间 / 已到点）──► AUTHORIZED ── CSS 推流回调 ──► LIVE
  └─ authorize（未来时间）──► SCHEDULED
                      ├─ 到点 ──► AUTHORIZED ── CSS 推流回调 ──► LIVE
                      │                └─ 宽限期过 ──► ENDED(SCHEDULE_EXPIRED)
                      └─ revoke ──► ENDED(REVOKED)
  AUTHORIZED ── 宽限期过 ──► ENDED(SCHEDULE_EXPIRED)
  AUTHORIZED ── revoke ──► ENDED(REVOKED)
  LIVE ── stop ──► ENDED
  LIVE ── 平台 ban ──► BANNED
```

---

## 3. 接口一览（9 个）

| # | 方法 | 路径 | 角色 |
|---|---|---|---|
| 1 | `POST` | `/group-live/api/v1/groups/{groupId}/live/authorize` | 群主 / 群管理员 |
| 2 | `PATCH` | `/group-live/api/v1/groups/{groupId}/live/schedule` | 群主 / 群管理员 |
| 3 | `POST` | `/group-live/api/v1/groups/{groupId}/live/revoke` | 群主 / 群管理员 |
| 4 | `POST` | `/group-live/api/v1/groups/{groupId}/live/stop` | 指定主播 / 群主 / 群管 |
| 5 | `GET` | `/group-live/api/v1/groups/{groupId}/live/current` | 群成员（支持 ETag/304） |
| 6 | `GET` | `/group-live/api/v1/me/live-index` | 登录用户（会话列表徽标 · ETag/304） |
| 7 | `GET` | `/group-live/api/v1/live/{liveSessionId}` | 群成员 |
| 8 | `GET` | `/group-live/api/v1/live/{liveSessionId}/push-info` | 指定主播 / 群主 / 群管（`SCHEDULED` 起可取） |
| 9 | `GET` | `/group-live/api/v1/live/{liveSessionId}/play-info` | 群成员 |
| 10 | `POST` | `/group-live/api/v1/live/{liveSessionId}/tip` | 群成员（不能给自己） |

进群、进出直播间时打 `current` / `detail`；**群聊 Tab** 打 `live-index`。在线 UI 优先听 TCP `group_changed` + `action=group_live_changed`（见 §5）。

---

## 4. 接口示例

### 4.1 群主 / 群管理员：预约开播 / 即时开播

```http
POST /group-live/api/v1/groups/{groupId}/live/authorize
Authorization: Bearer <JWT>
Content-Type: application/json
```

```bash
curl -sS -X POST "$BASE/group-live/api/v1/groups/$GID/live/authorize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "anchorUserId": "user01abcd",
    "roomName": "今晚策略分享",
    "description": "今晚讲策略",
    "scheduledStartAt": "2026-08-17T10:00:00+07:00"
  }'
```

即时开播（新客户端不传 `scheduledStartAt`）：

```bash
curl -sS -X POST "$BASE/group-live/api/v1/groups/$GID/live/authorize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "anchorUserId": "user01abcd",
    "roomName": "今晚策略分享",
    "description": "今晚讲策略"
  }'
```

**Body**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `anchorUserId` | string | 是 | 指定主播 userId，须为群成员 |
| `roomName` | string | 是 | 房间昵称，trim 后 1–40 字，禁止纯空白。新版本 UI 限制 10 字；服务端为兼容旧包仍接受最多 40 字 |
| `scheduledStartAt` | string | 否 | 计划开播时间。不传 / `null` / 已过 / 等于现在 → 即时开播（`AUTHORIZED`）。未来时刻 → 预约（`SCHEDULED`），不要求提前 1 分钟，不晚于 7 天。旧客户端传未来时间则行为与改前一致 |
| `description` | string | 否 | 直播描述，trim 后最多 30 字。不传 / `null` / 空白视为没有描述。旧客户端可不传 |

**成功 `data`**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "liveSessionId": "gl_abc123",
    "groupId": "m123456",
    "roomName": "今晚策略分享",
    "anchorUserId": "user01abcd",
    "scheduledStartAt": "2026-08-17T03:00:00Z",
    "expireAt": "2026-08-17T03:30:00Z",
    "status": "SCHEDULED"
  }
}
```

`expireAt` = 生效开播时间 + 30 分钟（即时则为创建时刻 + 30 分钟）。无描述时成功体因 `non_null` **不出现** `description` 键（旧客户端忽略未知键即可）。即时开播成功 `status=AUTHORIZED`，可立刻调 `push-info` 去 OBS；预约成功仍是 `SCHEDULED`。服务端**不发送**预约/开播/关播 IM，客户端用接口响应或 `current` 刷新 UI。

**常见失败**

| HTTP | code | 含义 |
|---|---|---|
| 400 | `ROOM_NAME_REQUIRED` | 房间名为空 |
| 400 | `ROOM_NAME_TOO_LONG` | 超过 40 字 |
| 400 | `DESCRIPTION_TOO_LONG` | 描述超过 30 字 |
| 400 | `SCHEDULE_TOO_FAR` | 超过最大提前天数 |
| 403 | `NOT_GROUP_ADMIN` | 非群主/群管理员 |
| 409 | `GROUP_LIVE_SLOT_TAKEN` | 该群已有预约/直播 |
| 409 | `ANCHOR_IN_CALL` | 指定主播正在通话 |

---

### 4.2 群主 / 群管理员：改期 / 改房间名 / 改描述（仅 SCHEDULED）

```http
PATCH /group-live/api/v1/groups/{groupId}/live/schedule
```

```bash
curl -sS -X PATCH "$BASE/group-live/api/v1/groups/$GID/live/schedule" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "scheduledStartAt": "2026-08-17T11:00:00+07:00",
    "roomName": "今晚策略分享（改期）"
  }'
```

`scheduledStartAt`、`roomName`、`description` **至少传一项**；未传字段保持不变。空白 `description` 不会清空已有描述。旧客户端只传时间/房间名时行为与现网一致。  
将时间改到现在或过去会立刻变为 `AUTHORIZED`。`AUTHORIZED` / `LIVE` 改名会 `409 ROOM_NAME_NOT_EDITABLE`。

成功 `data` 同 4.1 会话对象。服务端不发送 IM。

---

### 4.3 群主 / 群管理员：撤销预约

```http
POST /group-live/api/v1/groups/{groupId}/live/revoke
```

```bash
curl -sS -X POST "$BASE/group-live/api/v1/groups/$GID/live/revoke" \
  -H "Authorization: Bearer $TOKEN"
```

无 body。仅 `SCHEDULED` / `AUTHORIZED`。成功 `data` 含 `status=ENDED`、`endReason=REVOKED`。

---

### 4.4 结束直播

```http
POST /group-live/api/v1/groups/{groupId}/live/stop
```

```bash
curl -sS -X POST "$BASE/group-live/api/v1/groups/$GID/live/stop" \
  -H "Authorization: Bearer $TOKEN"
```

无 body。调用方须为 **指定主播 / 群主 / 群管**，且当前为 `LIVE`。

| 调用方 | `endReason` |
|---|---|
| 指定主播 | `NORMAL` |
| 群主 | `OWNER_STOP` |
| 群管 | `ADMIN_STOP` |

未在播：`409 LIVE_NOT_LIVE`。  
UI 提示主播：服务端会断流，**OBS 里也要点停止推流**，避免自动重连。

---

### 4.5 查询本群当前直播

```http
GET /group-live/api/v1/groups/{groupId}/live/current
```

```bash
curl -sS "$BASE/group-live/api/v1/groups/$GID/live/current" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'If-None-Match: "live-m123456-v42"'
```

**无活跃场**

```json
{
  "code": 0,
  "message": "ok",
  "data": { "active": false, "session": null }
}
```

**有活跃场**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "active": true,
    "session": {
      "liveSessionId": "gl_abc123",
      "groupId": "m123456",
      "roomName": "今晚策略分享",
      "description": "今晚讲策略",
      "status": "LIVE",
      "anchorUserId": "user01abcd",
      "scheduledStartAt": "2026-08-17T03:00:00Z",
      "startedAt": "2026-08-17T03:05:12Z",
      "expireAt": "2026-08-17T03:30:00Z",
      "endedAt": null,
      "endReason": null,
      "version": 42
    }
  }
}
```

响应头：`ETag: "live-{groupId}-v{version}"`、`X-Live-Revision`。未变时 **304** 无 body。无描述时 `session` 不出现 `description` 键。

---

### 4.5b 会话列表：批量直播索引

```http
GET /group-live/api/v1/me/live-index
```

```bash
curl -sS "$BASE/group-live/api/v1/me/live-index" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'If-None-Match: "live-index-v128"'
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "revision": 128,
    "updatedAt": "2026-08-18T10:05:00Z",
    "items": [
      {
        "groupId": "m123456",
        "liveSessionId": "gl_abc123",
        "status": "LIVE",
        "roomName": "今晚策略分享",
        "description": "今晚讲策略",
        "anchorUserId": "user01abcd",
        "scheduledStartAt": "2026-08-17T03:00:00Z",
        "startedAt": "2026-08-17T03:05:12Z",
        "version": 42
      }
    ]
  }
}
```

仅返回用户所在群且存在 active slot 的条目；支持 **304**。

---

### 4.6 会话详情

```http
GET /group-live/api/v1/live/{liveSessionId}
```

```bash
curl -sS "$BASE/group-live/api/v1/live/$SID" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "liveSessionId": "gl_abc123",
    "groupId": "m123456",
    "roomName": "今晚策略分享",
    "description": "今晚讲策略",
    "anchorUserId": "user01abcd",
    "scheduledStartAt": "2026-08-17T03:00:00Z",
    "expireAt": "2026-08-17T03:30:00Z",
    "status": "LIVE",
    "startedAt": "2026-08-17T03:05:12Z",
    "endedAt": null,
    "endReason": null,
    "version": 42
  }
}
```

未结束时 `endedAt` / `endReason` 因 `non_null` 可能省略。不存在：`404 LIVE_SESSION_NOT_FOUND`。

---

### 4.7 指定主播：取 OBS 推流地址

```http
GET /group-live/api/v1/live/{liveSessionId}/push-info
```

```bash
curl -sS "$BASE/group-live/api/v1/live/$SID/push-info" \
  -H "Authorization: Bearer $TOKEN"
```

**权限：** 指定主播、**群主** 或 **群管理员**。  
`SCHEDULED`（未到点）也可取地址，便于提前配置 OBS；**计划时间后再在 OBS 点「开始推流」**。  
`LIVE` 中也可重新获取（重连 OBS）。已结束/过期：`409`。  
普通群成员：`403 NOT_GROUP_ADMIN`。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "liveSessionId": "gl_abc123",
    "roomName": "今晚策略分享",
    "description": "今晚讲策略",
    "streamId": "live_m123456_gl_abc123",
    "rtmpServer": "rtmp://234258.push.tlivecloud.com/live/",
    "streamKey": "live_m123456_gl_abc123?txSecret=...&txTime=...",
    "expiresAt": "2026-08-17T03:30:00Z",
    "status": "SCHEDULED",
    "scheduledStartAt": "2026-08-17T03:00:00Z",
    "obsHint": "OBS → 设置 → 推流 → 服务选「自定义」"
  }
}
```

**OBS**

1. 设置 → 推流 → 服务选 **自定义**
2. **服务器** = `rtmpServer`
3. **串流密钥** = `streamKey`（含 `txSecret`，不要拆）
4. **预约阶段可先复制保存**；到 `scheduledStartAt` 后再点「开始推流」
5. `current` / `detail` 返回 `status=LIVE` 后，观众可调 `play-info` 进房

UI 文案用「获取推流地址 / 复制到 OBS」，**不要**写「开始直播」。  
提供「复制服务器」「复制串流密钥」两个按钮。鉴权过期后重新调本接口。

| HTTP | code | 含义 |
|---|---|---|
| 403 | `NOT_GROUP_ADMIN` | 非群主/群管（普通成员不能取推流地址） |
| 409 | `LIVE_SESSION_EXPIRED` | 宽限期已过 |
| 409 | `LIVE_NOT_ACTIVE` | 已结束 |
| 409 | `ANCHOR_IN_CALL` | 主播正在通话 |
| 503 | `CSS_NOT_CONFIGURED` | 推流域名未配 |

---

### 4.8 观众：取播放地址

```http
GET /group-live/api/v1/live/{liveSessionId}/play-info
```

```bash
curl -sS "$BASE/group-live/api/v1/live/$SID/play-info" \
  -H "Authorization: Bearer $TOKEN"
```

须为群成员，且 `status == LIVE`。未开播：`409 LIVE_NOT_LIVE`。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "liveSessionId": "gl_abc123",
    "roomName": "今晚策略分享",
    "description": "今晚讲策略",
    "protocol": "webrtc",
    "latencyMode": "ultra-low",
    "playUrl": "webrtc://live.99chat.vip/live/live_m123456_gl_abc123?txSecret=...&txTime=...",
    "webrtcPlayUrl": "webrtc://live.99chat.vip/live/live_m123456_gl_abc123?txSecret=...&txTime=...",
    "playerSdk": "V2TXLivePlayer",
    "fallbackFlvUrl": "https://live.99chat.vip/live/live_m123456_gl_abc123.flv?txSecret=...&txTime=...",
    "fallbackHlsUrl": "https://live.99chat.vip/live/live_m123456_gl_abc123.m3u8?txSecret=...&txTime=...",
    "playDomain": "live.99chat.vip",
    "httpsPlayDomain": "live.99chat.vip",
    "anchorUserId": "user01abcd"
  }
}
```

### 低延迟播放（必做）

观众端**必须**走腾讯云 **快直播 WebRTC**，不要用 AVPlayer / 系统播放器直接播 HLS/FLV（延迟通常 3–10 秒）。

| 项 | 说明 |
|---|---|
| SDK | **V2TXLivePlayer**（直播 SDK ≥ 10.7 需先 `setLicence`） |
| 播放地址 | `playUrl` / `webrtcPlayUrl`（`webrtc://live.99chat.vip/live/{streamId}?...`） |
| 调用 | `startLivePlay(playUrl)` |
| 预期延迟 | 通常 **≤ 1s** |
| 降级 | 仅 WebRTC 连续失败后再试 `fallbackFlvUrl`，最后才 `fallbackHlsUrl` |

**iOS / Android 示例（概念）**

```objc
// iOS：V2TXLivePlayer
[player setRenderView:videoView];
[player startLivePlay:playUrl]; // webrtc://...
```

```kotlin
// Android：V2TXLivePlayer
player.setRenderView(surfaceView)
player.startLivePlay(playUrl) // webrtc://...
```

**Flutter**：使用腾讯云 `super_player` / `live_flutter_plugin`，同样传入 `webrtc://` 地址。

**禁止**：把 `playUrl` 交给 AVPlayer；默认播 `fallbackHlsUrl`（高延迟）。

直播间标题用 `roomName`；有描述时副文案用 `description`。

---

### 4.9 打赏（自定义金额）

```http
POST /group-live/api/v1/live/{liveSessionId}/tip
```

```bash
curl -sS -X POST "$BASE/group-live/api/v1/live/$SID/tip" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "currency": "USDT",
    "amount": 1500000,
    "payPin": "123456",
    "clientOrderId": "tip_20260817_001",
    "memo": "加油"
  }'
```

**Body**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `currency` | string | 是 | `USDT` / `99` / `TRX` / `CNY`；`PLATFORM` 会当成 `99` |
| `amount` | long | 是 | 必须 `> 0`。USDT = **micro**（6 位，`1500000` = 1.5 USDT）；`99` / `CNY` = **分** |
| `payPin` | string | 是 | 6 位支付密码 |
| `clientOrderId` | string | 是 | 幂等键，≤ 64；同一用户同一单号重试会返回原成功单 |
| `memo` | string | 否 | ≤ 50 字 |

收款人固定为 **指定主播**，客户端不用传 `toUserId`。

**成功 `data`**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "tipId": 1001,
    "liveSessionId": "gl_abc123",
    "fromUserId": "user02abcd",
    "toUserId": "user01abcd",
    "currency": "USDT",
    "amount": 1500000,
    "feeAmount": 0,
    "memo": "加油",
    "createdAt": "2026-08-17T03:15:00Z"
  }
}
```

成功后群 IM：`live_tip`。建议 **只在直播间飘屏**，不要刷群聊。

| HTTP | code | 含义 |
|---|---|---|
| 400 | `CANNOT_TIP_SELF` | 主播给自己打赏 |
| 400 | `INVALID_AMOUNT` | 金额非法 |
| 400 | `MEMO_TOO_LONG` | 备注过长 |
| 409 | `LIVE_NOT_LIVE` | 未在播 |
| 409 | `CLIENT_ORDER_ID_CONFLICT` | 幂等键冲突（金额/场次不一致） |
| — | `INSUFFICIENT_BALANCE` | 余额不足 |
| — | `PAY_PIN_INVALID` | 支付密码错误 |
| — | `PAY_PIN_NOT_SET` | 未设置支付密码 |

金额单位细则见 [wallet-client.md §1.1](./wallet-client.md#11-金额单位必读)。

---

## 5. TCP 实时（`group_live_changed`）

在线成员通过现有 TCP 通道接收（**非 IM**）：

```text
event:   group_changed
action:  group_live_changed
groupId: {群 ID}
detail:  { liveSessionId, status, version, roomName, description, anchorUserId, ... }
```

触发：预约 / 改期 / 到点 AUTHORIZED / CSS 推流 LIVE / stop / revoke / 过期 / 断流 / ban。  
`detail.version` 与 `current.session.version`、`live-index.items[].version` **同源**；可选 `indexRevision`。

## 6. IM 自定义消息（可选 · 打赏）

预约、改期、到点、开播、关播**不会**发送群 IM。客户端通过 `authorize` / `schedule` / `revoke` / `stop` 的响应，以及 `current` / `detail` 轮询或刷新 UI。

打赏成功可选监听 `live_tip`（若服务端开启），建议 **只在直播间飘屏**，不要刷群聊。

**`live_tip` 示例**

```json
{
  "businessID": "live_tip",
  "liveSessionId": "gl_abc123",
  "fromUserId": "user02abcd",
  "currency": "USDT",
  "amount": 1500000,
  "memo": "加油"
}
```

没有 `fromNickname`，昵称用现有用户资料接口补。

### `endReason`（`detail` / `current` 响应字段）

| 值 | 含义 |
|---|---|
| `NORMAL` | 主播 stop / OBS 停推 |
| `OWNER_STOP` | 群主 stop |
| `ADMIN_STOP` | 群管 stop |
| `DISCONNECT` | 断流超时 |
| `SCHEDULE_EXPIRED` | 宽限期内未推流 |
| `REVOKED` | 预约撤销 |
| `ADMIN_BAN` | 平台禁播 |

---

## 6. 错误码

| code | HTTP | 说明 |
|---|---|---|
| `UNAUTHORIZED` | 401 | 无 JWT / JWT 无效 |
| `INVALID_INPUT` | 400 | 缺字段或非法 |
| `ROOM_NAME_REQUIRED` | 400 | 房间名为空 |
| `ROOM_NAME_TOO_LONG` | 400 | 房间名过长（服务端仍为超过 40 字） |
| `DESCRIPTION_TOO_LONG` | 400 | 描述过长（超过 30 字） |
| `SCHEDULE_TOO_SOON` | 400 | 旧错误码，现网创建已不再返回（过早/已过视为即时） |
| `SCHEDULE_IN_PAST` | 400 | 旧错误码，现网创建已不再返回（过早/已过视为即时） |
| `SCHEDULE_TOO_FAR` | 400 | 预约过远 |
| `CANNOT_TIP_SELF` | 400 | 不能打赏自己 |
| `INVALID_AMOUNT` | 400 | 打赏金额非法 |
| `MEMO_TOO_LONG` | 400 | 备注过长 |
| `NOT_GROUP_ADMIN` | 403 | 非群主/群管理员（预约、改期、撤销、push-info 等） |
| `NOT_GROUP_MEMBER` | 403 | 非群成员 |
| `LIVE_SESSION_NOT_FOUND` | 404 | session 不存在 |
| `GROUP_LIVE_SLOT_TAKEN` | 409 | 群已有活跃场 |
| `ROOM_NAME_NOT_EDITABLE` | 409 | 非 SCHEDULED 不能改名 |
| `LIVE_NOT_AUTHORIZED_YET` | 409 | 未到点，不能取推流地址 |
| `LIVE_NOT_LIVE` | 409 | 未在 LIVE |
| `LIVE_NOT_ACTIVE` | 409 | 已结束 |
| `LIVE_SESSION_EXPIRED` | 409 | 宽限期已过 |
| `LIVE_STATE_CONFLICT` | 409 | 并发状态冲突，重试 |
| `ANCHOR_IN_CALL` | 409 | 指定主播正在 LiveKit 通话 |
| `CLIENT_ORDER_ID_CONFLICT` | 409 | 打赏幂等冲突 |
| `INSUFFICIENT_BALANCE` | — | 余额不足 |
| `PAY_PIN_INVALID` | — | 支付密码错误 |
| `PAY_PIN_NOT_SET` | — | 未设置支付密码 |
| `CSS_NOT_CONFIGURED` | 503 | 推拉流未配置 |
| `GROUP_LIVE_DISABLED` | 503 | 功能关闭 |
| `INTEGRATION_NOT_CONFIGURED` | 503 | 服务端未配 Integration（运维） |

无 JWT 时也可能是 `401` + `请先登录`。

---

## 7. 建议页面

| 角色 | 页面 | 调用 |
|---|---|---|
| 群主 / 群管理员 | 选成员 → 填房间名 → 可选描述 → 即时不传时间 / 预约再选时间 | `authorize` |
| 群主 / 群管理员 | 预约管理 | `schedule` / `revoke` / `stop` |
| 群主 / 群管 | 推流地址页（复制 RTMP，可代主播配置 OBS） | `push-info` |
| 指定主播 | 推流地址页（复制 RTMP） | `push-info` |
| 全体 | 群顶栏 / 直播间 | `current` + `detail` |
| 观众 | 直播间：WebRTC 播放 + 打赏 | `play-info`（V2TXLivePlayer）+ `tip` |

**明确不做：** App 内推流 SDK、摄像头预览、美颜、礼物 catalog、榜单。

**通话互斥：** 指定主播在通话中，`authorize` / `push-info` 会 `ANCHOR_IN_CALL`。主播 `LIVE` 时拒绝 LiveKit invite/accept（`ANCHOR_LIVE_ACTIVE`）服务端尚未拦截，客户端先自己禁止「直播中接听/发起通话」。

---

## 8. 不要调用（非 App）

| 路径 | 给谁 |
|---|---|
| `/group-live/api/v1/admin/**` | 运营后台（Admin JWT） |
| `/group-live/api/internal/**` | 主服内网 |
| `/group-live/webhook/**` | 腾讯云回调 |
| `/integration/v1/**` | 微服务打主服 |

---

## 9. 联调最小路径

```text
1. 群主 authorize → 刷新顶栏 current
2. 到点 → current 变为 AUTHORIZED → 指定主播 push-info → OBS 推流
3. current 变为 LIVE → 观众 play-info 进房
4. 观众 tip → 直播间 live_tip 飘屏（可选）
5. 主播/群主/群管 stop → current 无 active → 停播放器
```
