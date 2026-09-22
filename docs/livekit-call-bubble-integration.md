# LiveKit 音视频通话气泡对接文档

## 1. 目标

所有音视频气泡以 `callId` 为唯一键，以服务端 `CallSession` 为最终状态来源。IM 信令、实时 TCP 事件、HTTP 接口和 LiveKit SDK 回调都不能直接覆盖 UI；客户端必须经过同一个状态合并器处理。

服务端已提供：

- `POST /calls/livekit/invite`
- `POST /calls/livekit/accept`
- `POST /calls/livekit/reject`
- `POST /calls/livekit/cancel`
- `POST /calls/livekit/hangup`
- `GET /calls/livekit/token?callId={callId}`
- `GET /calls/livekit/status/{callId}`：权威状态快照
- 实时 TCP `call_recent_changed`：通话记录终态刷新
- IM 自定义信令 `businessID = lk_call`

所有需要登录的接口使用现有 JWT 鉴权。普通成功 HTTP 响应会被服务端统一包装为 `{ "code": 0, "message": "ok", "data": ... }`；实时 TCP 和 IM 信令不使用这个 HTTP 包装。

## 2. 状态模型

```text
RINGING -> ANSWERED -> ENDED
RINGING -> REJECTED
RINGING -> CANCELED
RINGING -> MISSED
```

终态为 `REJECTED`、`CANCELED`、`MISSED`、`ENDED`。终态一旦写入，客户端不得再接受旧的 `invite` 或 `accept` 事件。

建议客户端状态类型：

```ts
type CallStatus =
  | 'RINGING'
  | 'ANSWERED'
  | 'REJECTED'
  | 'CANCELED'
  | 'MISSED'
  | 'ENDED'

type CallBubble = {
  callId: string
  roomName?: string
  mediaType: 'audio' | 'video'
  callerUserId: string
  calleeUserId: string
  status: CallStatus
  startedAt?: string
  acceptedAt?: string
  endedAt?: string
  durationSec: number
}
```

## 3. 发起、接听和结束

### 发起

```http
POST /calls/livekit/invite
Authorization: Bearer <jwt>
Content-Type: application/json
```

```json
{ "calleeUserId": "10002", "mediaType": "video" }
```

返回 `data` 中包含 `callId`、`roomName`、LiveKit `url`、`token`、`callerUserId`、`calleeUserId` 和 `timeoutSec`。收到成功响应后立即创建或更新 `callId` 对应的 `RINGING` 气泡。

### 接听

```http
POST /calls/livekit/accept
Authorization: Bearer <jwt>
Content-Type: application/json

{ "callId": "<callId>" }
```

只有被叫可以接听。成功后状态为 `ANSWERED`，然后使用返回的 token 加入 LiveKit 房间。不要使用 `participant_joined` 代替业务接听状态。

### 拒接、取消、挂断

```http
POST /calls/livekit/reject
POST /calls/livekit/cancel
POST /calls/livekit/hangup
Authorization: Bearer <jwt>
Content-Type: application/json

{ "callId": "<callId>" }
```

- `reject`：被叫在振铃阶段拒接，最终状态 `REJECTED`
- `cancel`：主叫在接通前取消，最终状态 `CANCELED`
- `hangup`：任意一方结束；接通后最终状态为 `ENDED`，未接通时按实际角色返回取消或拒接结果

`hangup` 返回 HTTP 409 且错误码为 `CALL_ENDED` 时，不应显示失败。说明另一设备或 webhook 已经先完成收尾，客户端应关闭通话窗口并调用状态查询接口校正气泡。

## 4. 权威状态查询

```http
GET /calls/livekit/status/{callId}
Authorization: Bearer <jwt>
Cache-Control: no-cache
```

返回示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "callId": "abc123",
    "roomName": "call_abc123",
    "callType": "livekit",
    "mediaType": "video",
    "callerUserId": "10001",
    "calleeUserId": "10002",
    "status": "ENDED",
    "result": "ANSWERED",
    "startedAt": "2026-08-26T08:00:00Z",
    "acceptedAt": "2026-08-26T08:00:06Z",
    "endedAt": "2026-08-26T08:04:12Z",
    "durationSec": 246
  }
}
```

以下场景必须查询一次：

- 实时 TCP 重连后
- App 从后台恢复前台
- 页面刷新恢复聊天列表
- `hangup`、`accept` 返回 409
- 收到未知 `callId` 的事件
- LiveKit `Disconnected`

查询结果按 `callId` 覆盖本地快照，但仍需遵守终态保护：本地已经是终态时，较旧的查询结果不能回滚状态。

## 5. IM 信令

C2C 自定义消息的 `MsgContent.Data` 是 JSON 字符串，核心字段如下：

```json
{
  "businessID": "lk_call",
  "action": "invite",
  "callId": "abc123",
  "roomName": "call_abc123",
  "mediaType": "audio",
  "callerId": "10001",
  "calleeId": "10002",
  "timeoutSec": 60,
  "phase": "RINGING",
  "status": "RINGING"
}
```

`action` 与状态对应关系：

| action | phase | status |
| --- | --- | --- |
| `invite` | `RINGING` | `RINGING` |
| `accept` | `ANSWERED` | `ANSWERED` |
| `reject` | `ENDED` | `REJECTED` |
| `cancel` | `ENDED` | `CANCELED` |
| `hangup` | `ENDED` | `ENDED` |
| `answered_elsewhere` | `ENDED` | `ENDED` |

终态信令可能重复到达，必须幂等处理。已接通后的挂断会通知双方相关设备；客户端不要因为收到重复 `hangup` 重复插入气泡。

## 6. 实时 TCP 事件

连接认证沿用现有协议：

```json
{ "type": "auth", "token": "<jwt>", "deviceId": "device-a" }
```

通话记录终态事件示例：

```json
{
  "type": "event",
  "event": "call_recent_changed",
  "action": "added",
  "callId": "abc123",
  "mediaType": "video",
  "direction": "outgoing",
  "result": "answered",
  "phase": "ENDED",
  "status": "ENDED",
  "durationSec": 246,
  "occurredAt": 1787731452000,
  "ts": 1787731453000
}
```

该事件用于刷新气泡和最近通话列表，但它不是唯一可靠来源。收到后可直接更新终态，也可以按 `callId` 调用状态接口获取完整快照。

## 7. 客户端状态合并

推荐按状态等级拒绝旧事件：

```ts
const rank = {
  RINGING: 10,
  ANSWERED: 20,
  REJECTED: 30,
  CANCELED: 30,
  MISSED: 30,
  ENDED: 40,
} as const

function mergeCall(current: CallBubble | undefined, incoming: CallBubble) {
  if (!current) return incoming
  if (isTerminal(current.status)) return current
  if (rank[incoming.status] < rank[current.status]) return current
  return { ...current, ...incoming }
}

function isTerminal(status: CallStatus) {
  return status === 'REJECTED'
    || status === 'CANCELED'
    || status === 'MISSED'
    || status === 'ENDED'
}
```

聊天气泡、通话窗口、最近通话列表必须共用同一个 `CallStore`。禁止三个页面分别维护 `activeCall` 和气泡状态。

## 8. 显示规则

- `RINGING`：等待接听
- `ANSWERED`：通话中
- `REJECTED`：已拒绝
- `CANCELED`：已取消
- `MISSED`：未接听
- `ENDED`：通话结束；`durationSec > 0` 时显示通话时长

LiveKit 房间是否存在、是否收到 `participant_left`、本地窗口是否关闭，都不能单独决定气泡最终文案。

## 9. 验收场景

1. A 拨打 B，双方气泡均显示 `RINGING`。
2. B 接听，双方气泡均显示 `ANSWERED`。
3. A 挂断，A/B 的所有在线设备最终均显示 `ENDED`。
4. B 拒接，双方最终均显示 `REJECTED`，重复信令不新增气泡。
5. A 取消后，延迟到达的 `accept` 不得把气泡恢复成 `ANSWERED`。
6. 先收到 `room_finished`，再收到 `accept`，最终仍为终态。
7. 断网重连、刷新页面、后台恢复后，通过状态接口恢复一致状态。
8. 接通后 `hangup` 与 LiveKit `room_finished` 并发，只产生一条终态记录和一条最终气泡状态。
