# TUICallKit 通话回调 & 最近通话接口（TRTC 遗留）

> **新客户端请用 LiveKit：** [livekit-call-client.md](./livekit-call-client.md) · webhook `POST /webhook/livekit`。  
> 本文仅描述腾讯 TRTC `CallbackAfterEndCall` 与共用的最近通话 API。

> 版本：v1.1  
> 依赖：TUICallKit + 通过 REST API 配置回调（无需控制台手点）

---

## 1. 回调配置

### 方式 A：服务启动自动注册（推荐）

设置环境变量后，服务启动时会调用 [`v4/call_config/set_callback`](https://trtc.io/zh/document/68938?product=call&menulabel=uikit&platform=flutter) 自动写入回调 URL：

| 环境变量 | 说明 |
| --- | --- |
| `TRTC_CALLBACK_URL` | 公网回调地址，如 `https://api.example.com/webhook/trtc/call-status` |
| `TRTC_CALLBACK_BOOTSTRAP` | 默认 `true`；设为 `false` 可关闭自动注册 |
| `TRTC_CALLBACK_TOKEN` | 可选，回调 Token 校验 |
| `TRTC_ALLOWED_SDK_APP_IDS` | 可选 SDKAppID 白名单 |

`application.yml` 对应项：

```yaml
chat99:
  trtc:
    callback:
      bootstrap-on-startup: true
      callback-url: ${TRTC_CALLBACK_URL:}
```

未设置 `TRTC_CALLBACK_URL` 时跳过注册（本地开发不受影响）。

### 方式 B：手动 REST API

```json
{
  "Url": "https://你的域名/webhook/trtc/call-status",
  "CallbackCommandList": [
    "Call.CallbackAfterEndCall"
  ]
}
```

请求域名（新加坡）：`https://adminapisgp.im.qcloud.com/v4/call_config/set_callback`

**腾讯文档**：[设置回调配置](https://trtc.io/zh/document/68938?product=call&menulabel=uikit&platform=flutter) · [通话结束后回调](https://www.tencentcloud.com/document/product/647/68940)

---

## 2. Webhook：`POST /webhook/trtc/call-status`

**无需 JWT**。Query 参数（腾讯自动附带）：

| 参数 | 说明 |
| --- | --- |
| SdkAppid | 必须与本应用 SDKAppID 一致 |
| CallbackCommand | 固定 `Call.CallbackAfterEndCall` |
| contenttype | `json` |
| ClientIP | 客户端 IP |
| OptPlatform | iOS / Android / Web 等 |

**Body 示例**（通话结束后，一次回调含完整通话记录）：

```json
{
  "CallbackCommand": "Call.CallbackAfterEndCall",
  "CallRecord": {
    "CallId": "055662e1-bc8a-469c-a334-1126c8c17d58",
    "Caller_Account": "brbp11nv6s",
    "MediaType": "Video",
    "CallType": "SingleCall",
    "StartTime": 1741231146,
    "EndTime": 1741231155,
    "AcceptTime": 1741231149,
    "CallResult": "NormalEnd",
    "CalleeList_Account": ["c88wbzp8nc"],
    "RoomId": "roomid-1434",
    "RoomIdType": 2
  },
  "EventTime": 1741231155000
}
```

**应答**（必须，否则腾讯重试）：

```json
{ "ActionStatus": "OK", "ErrorInfo": "", "ErrorCode": 0 }
```

**校验**：
- `SdkAppid` 白名单
- 可选 `X-Callback-Token` 或 query `token`
- 幂等：`callId|Call.CallbackAfterEndCall|EventTime`（毫秒）

**首期归并**：仅处理 `CallType=SingleCall`；`MultiCall` 只落原始日志。一次回调会同时写入主叫/被叫两条 `call_record_user`。

**CallResult → result 映射**：

| 腾讯 | result |
| --- | --- |
| NormalEnd | answered |
| NotAnswer | missed |
| Reject | rejected |
| Cancel | canceled |
| CallBusy | busy |
| Interrupt / Offline | failed |

> 服务端同时支持：
> - 官方 **`Call.CallbackAfterEndCall`** 回调（`POST /webhook/trtc/call-status`）
> - IM 消息里的 **`av_call` 信令**（`C2C.CallbackAfterSendMsg`，TUICallKit 实际常用路径，**已自动落库**）

---

## 3. 最近通话列表

### GET /calls/recent

需 JWT。

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| filter | all | `all` / `missed` |
| page | 0 | |
| pageSize | 20 | 最大 50 |

**响应**：

```json
{
  "items": [{
    "callId": "055662e1-bc8a-469c-a334-1126c8c17d58",
    "peerUserId": "c88wbzp8nc",
    "peerName": "Alice",
    "peerAvatar": "https://...",
    "mediaType": "video",
    "direction": "outgoing",
    "result": "answered",
    "durationSec": 6,
    "occurredAt": 1741231155000,
    "callerUserId": "brbp11nv6s",
    "operatorUserId": null
  }],
  "page": 0,
  "pageSize": 20,
  "total": 1
}
```

| 字段 | 说明 |
| --- | --- |
| callerUserId | 权威主叫（原始 userId），供聊天气泡判断谁发起 |
| operatorUserId | 结束操作者（原始 userId），供气泡区分「已拒绝 / 对方已拒绝」等；无关键操作者时为 `null` |

**operatorUserId 映射**（由 `result` + 会话主叫 / 被叫推导）：

| result | operatorUserId | 气泡文案（对主叫 / 对被叫） |
| --- | --- | --- |
| canceled | 主叫 | 已取消 / 对方已取消 |
| rejected | 被叫 | 对方已拒绝 / 已拒绝 |
| missed | 被叫 | 对方无应答 / 未接听 |
| busy | 被叫 | 对方忙线中 / 忙线未接 |
| answered | `null` | 通话时长 hh:mm:ss |
| failed（Offline） | `null` | 未接听（服务端结束） |

> `direction` 按**接收者视角**返回（主叫得 `outgoing`，被叫得 `incoming`）。客户端用 `operatorUserId == 自己` 判断是「已拒绝」还是「对方已拒绝」。

**前端展示规则**：
- 红字昵称：`result=missed`
- 「接听,5秒」：`result=answered` + `durationSec`
- 「拨出」：`direction=outgoing`
- 「未接」：`result=missed`

### GET /calls/{callId}

需 JWT。按 `callId` 拉取单条权威通话结果，供聊天气泡在缺 TCP 推送时按需补偿（历史消息 / 弱网）。`direction` / `operatorUserId` 均按**请求者视角**返回。

**响应**（统一包装 `{ code, message, data }`）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "callId": "055662e1-bc8a-469c-a334-1126c8c17d58",
    "peerUserId": "c88wbzp8nc",
    "peerName": "Alice",
    "peerAvatar": "https://...",
    "mediaType": "audio",
    "direction": "outgoing",
    "result": "rejected",
    "durationSec": 0,
    "occurredAt": 1741231296000,
    "callerUserId": "brbp11nv6s",
    "operatorUserId": "c88wbzp8nc"
  }
}
```

记录不存在或已软删 → `404 CALL_RECORD_NOT_FOUND`。

### DELETE /calls/recent/{callId}

软删当前用户该条记录 → `{ "ok": true }`

### DELETE /calls/recent?filter=all|missed

- `all`：软删全部
- `missed`：仅软删未接

→ `{ "ok": true, "deleted": 12 }`

---

## 4. 错误码

| HTTP | code |
| --- | --- |
| 401 | UNAUTHORIZED |
| 403 | FORBIDDEN |
| 404 | CALL_RECORD_NOT_FOUND |
| 400 | INVALID_FILTER |

---

## 5. 数据表

- `call_callback_log` — 原始回调 + 幂等 + processed
- `call_session` — 通话会话（按 call_id）
- `call_record_user` — 用户视角最近通话（软删）

---

## 6. TCP 实时推送（`call_recent_changed`）

通话记录写入后，主叫与被叫若已连接 TCP 长连接（`chat99.realtime.*`），会收到 `call_recent_changed` 事件，payload 字段与 `GET /calls/recent` 的 `items[]` 单条一致（含 `callerUserId` / `operatorUserId`）。`direction` 按**接收者视角**分别推送（主叫收 `outgoing`，被叫收 `incoming`），不会全员同值。客户端收到后应增量更新列表，无需仅依赖轮询。

```json
{
  "type": "event",
  "event": "call_recent_changed",
  "action": "added",
  "callId": "055662e1-...",
  "callerUserId": "brbp11nv6s",
  "operatorUserId": "c88wbzp8nc",
  "peerUserId": "c88wbzp8nc",
  "mediaType": "audio",
  "direction": "outgoing",
  "result": "rejected",
  "durationSec": 0,
  "occurredAt": 1741231296000
}
```

> `callerUserId` / `operatorUserId` 用**原始 userId**；无关键操作者（`answered` / `failed`）时省略 `operatorUserId`。同一 `callId` 可能被 TCP 与回调重复推送，客户端按 `callId` 去重、后到覆盖先到。

详见 [friend-self-hosted-client.md](./friend-self-hosted-client.md) §10「最近通话」。

---

## 7. 增量落库与超时补全

| 机制 | 说明 |
| --- | --- |
| IM 回调同步落库 | `C2C.CallbackAfterSendMsg` 收到 `av_call` 终态信令立即写库 |
| Kafka 归档消费 | 消息写入 `chat_message_*` 后同步尝试落库 |
| 定时增量扫描 | 每 30s 从归档表补并尚未落库的信令 |
| 会话超时补全 | 已接听但 20s 内无挂断信令 → 按 `ANSWERED` 补记录，`durationSec=0`；仅邀请 60s 无接听 → `MISSED` |
| 邀请/接听即时落库 | 拨号邀请、接听信令到达时即写入最近通话并推送 `call_recent_changed`，挂断后再更新终态 |

列表按 `occurredAt` 降序；`occurredAt` 为通话结束时间（毫秒时间戳）。
