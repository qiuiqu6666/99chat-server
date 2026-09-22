# LiveKit 音视频通话 — iOS / Android 对接文档

> 版本：v1.1（2026-07-19）  
> 适用：99chat iOS / Android（**新版本，完全替换 TUICallKit/TRTC**）  
> 媒体：自建 LiveKit  
> 部署：[livekit-self-host.md](./livekit-self-host.md)  
> 旧文档：[av-call-client.md](./av-call-client.md)（仅维护旧版 App）

### 现网地址

| 用途 | 地址 |
|------|------|
| 业务 API（主服务） | **`http://47.239.60.107:8081`**（99chat-server） |
| 业务 API（HTTPS 反代，可选） | `https://api99chat.99chat.vip` → 同机 `8081` |
| LiveKit 媒体信令 | **`wss://trtc.99chat.vip`**（invite/accept 响应里的 `url` 字段，勿写死也可） |
| 鉴权 | 业务接口：`Authorization: Bearer <App JWT>`；媒体：`token` 进房 |

---

## 1. 一眼看清

| 能力 | iOS | Android |
|------|-----|---------|
| 发起 / 接听 | LiveKit SDK + 本文件 API | 同左 |
| 在线来电 | IM 自定义消息 `lk_call` | 同左 |
| 离线来电 | **PushKit VoIP**（`type=lk_call`） | 无服务端 VoIP，靠 IM 保活 |
| 最近通话 | `GET /calls/recent` + TCP `call_recent_changed`（协议不变） | 同左 |
| 去重键 | `callId`（等同旧 `inviteId`） | 同左 |

```
主叫 POST /calls/livekit/invite
        │
        ├─► 主叫 connect(LiveKit) + publish
        │
        └─► 服务端：IM lk_call(invite) + iOS VoIP
                │
                ▼
被叫 accept → POST /calls/livekit/accept → connect + publish
挂断 → POST /calls/livekit/hangup → room.disconnect
```

---

## 2. 登录后必做

```text
1. 腾讯 IM 登录（聊天仍需要 UserSig；不再用于 TRTC）
2. 监听 C2C 自定义消息 businessID=lk_call
3. 初始化 LiveKit（无需预登录房间）
4. iOS：PushKit + POST /me/push-token（带 voipToken）
5. 同步 callNotificationEnabled
6. 订阅 TCP call_recent_changed
7. 移除 TUICallKit / TRTC 初始化与 av_call 来电入口
```

Push / VoIP Token 上报与旧版相同，见 [av-call-client.md §3.1](./av-call-client.md#31-上报-push--voip-token)。

---

## 3. HTTP API（均需 `Authorization: Bearer <JWT>`）

Base（主服务）：**`http://47.239.60.107:8081`**

示例完整 URL：`http://47.239.60.107:8081/calls/livekit/invite`

> 成功响应可能被全局包装为 `{ "code": 0, "message": "ok", "data": { ... } }`，客户端取 `data`。

### 3.1 发起通话

```http
POST http://47.239.60.107:8081/calls/livekit/invite
Authorization: Bearer <JWT>
Content-Type: application/json

{ "calleeUserId": "<peer>", "mediaType": "audio" }
```

`mediaType`：`audio` | `video`。

**成功 200 `data`：**

```json
{
  "callId": "a1b2c3...",
  "roomName": "call_a1b2c3...",
  "url": "wss://trtc.99chat.vip",
  "token": "<livekit-jwt>",
  "mediaType": "audio",
  "expiresAt": 1720000000000,
  "callerUserId": "...",
  "calleeUserId": "...",
  "timeoutSec": 60
}
```

主叫收到后：

1. `Room.connect(url, token)`
2. 按 `mediaType` publish 麦克风 / 摄像头
3. 振铃 UI；`timeoutSec` 内未接听可调 `cancel`

**常见错误码**

| code | 含义 |
|------|------|
| `LIVEKIT_NOT_CONFIGURED` | 服务端未开 LiveKit |
| `NOT_FRIENDS` | 非好友 |
| `CALLEE_BUSY` | 对方振铃中 |
| `CANNOT_CALL_SELF` | 呼叫自己 |

### 3.2 接听

```http
POST http://47.239.60.107:8081/calls/livekit/accept
Authorization: Bearer <JWT>
Content-Type: application/json

{ "callId": "..." }
```

返回同 invite 的凭证结构（`token` 为被叫身份）。然后 `connect` + publish。

### 3.3 拒接 / 取消 / 挂断

```http
POST http://47.239.60.107:8081/calls/livekit/reject   { "callId": "..." }
POST http://47.239.60.107:8081/calls/livekit/cancel   { "callId": "..." }
POST http://47.239.60.107:8081/calls/livekit/hangup   { "callId": "..." }
```

均需 `Authorization: Bearer <JWT>`。挂断顺序：**先** `hangup` API，**再** `room.disconnect()`。

### 3.4 重连补 Token

```http
GET http://47.239.60.107:8081/calls/livekit/token?callId=...
Authorization: Bearer <JWT>
```

仅未结束且调用者为参与者时可拿新 token。

---

## 4. IM 信令 `lk_call`

服务端通过 IM Admin 代发 `TIMCustomElem`，`Data` 为 JSON：

```json
{
  "businessID": "lk_call",
  "action": "invite",
  "callId": "a1b2c3...",
  "roomName": "call_a1b2c3...",
  "mediaType": "audio",
  "callerId": "rqwm8onw3j",
  "calleeId": "acnj6oxey9",
  "timeoutSec": 60
}
```

| action | 谁发 | 客户端行为 |
|--------|------|------------|
| `invite` | 主叫 → 被叫 | 展示来电 UI（与 VoIP 按 `callId` 去重） |
| `accept` | 被叫 → 主叫 | 主叫停止振铃，确保已进房 |
| `answered_elsewhere` | 服务端代发：主叫 → 被叫 | **被叫其它 App 端停铃**；已接听本端可忽略 |
| `reject` | 被叫 → 主叫；另有被叫多端收口 | 结束 UI |
| `cancel` | 主叫 → 被叫 | 结束来电 UI |
| `hangup` | 任一方 → 对方 | 断开 LiveKit，结束 UI |

### 多端停铃（App 内 + 系统来电）

被叫可能同时有：

| 通道 | 来源 | 一端接通后如何停 |
|------|------|------------------|
| App 内 | IM `lk_call` / `invite` | 收 `answered_elsewhere`（或 `reject`/`hangup`/`cancel`）→ 关来电 UI |
| 系统 CallKit | PushKit VoIP | 再收一条 VoIP，`action`≠`invite` 且常带 `call_end=1` → `CXCallController` 结束该 `callId` |

服务端在 `accept` / `reject` / 响铃中 `hangup` 时：IM 通知被叫多端 + **向全部 voip_enabled 设备**推终态（不限「最新一台」）。`cancel` 已有 IM，另补 VoIP 终态。

客户端须：

1. VoIP / IM 解析 `action`；`invite` 才展示来电，终态只停铃、**禁止**再弹 CallKit。  
2. 按 `callId` / `inviteId` 匹配本地正在响的通话并结束。  
3. 本端已在通话中收到 `answered_elsewhere` 可忽略。

**不要**再依赖腾讯 `av_call` / TUICallKit 信令。可忽略旧 `av_call` 以免误进房。

聊天离线 Push 不会对 `lk_call` 再发一条普通消息推送（服务端已跳过）。

---

## 5. iOS VoIP Payload

```json
{
  "aps": { "content-available": 1 },
  "data": {
    "type": "lk_call",
    "inviteId": "<callId>",
    "callId": "<callId>",
    "callerId": "...",
    "callerName": "...",
    "callerAvatarUrl": "https://...",
    "calleeId": "...",
    "mediaType": "audio",
    "roomId": "call_...",
    "roomName": "call_..."
  }
}
```

处理：

1. `type` 必须为 `lk_call`（旧包可能仍收到 `av_call`，新版本忽略）  
2. 按 `callId` / `inviteId` 去重  
3. 立刻 CallKit 来电 UI，**禁止**当普通通知  
4. 用户接听 → `POST /calls/livekit/accept` → LiveKit connect  

**多端 VoIP（服务端策略，2026-08-13 / 2026-08-15）：**

- 来电：同一 `userId` 多台 `voip_enabled` 时，**只向 `last_seen_at` 最新一台**发 `action=invite`，降低双机抢房。  
- 停铃：`accept`/`reject`/`cancel`/响铃中挂断时，向**全部** voip 设备推终态（`action=answered_elsewhere|reject|cancel|hangup`，`call_end=1`）。  
- 其它端仍可能通过 IM `lk_call` 收到在线信令；须按 `callId` 去重，且**禁止**第二台再 `accept`/进房。  
- APNs 返回 `BadDeviceToken` / `Unregistered` / `DeviceTokenNotForTopic` 时，服务端会 `clearVoipToken`。

Bundle / Topic 仍为 `vip.99chat.pro` / `vip.99chat.pro.voip`（见旧文档 §4）。

---

## 6. LiveKit SDK 要点

### 6.1 依赖（示例）

- iOS：`LiveKit` Swift Package / CocoaPods  
- Android：`io.livekit:livekit-android`

### 6.2 进房

```text
Room.connect(url = creds.url, token = creds.token)
LocalParticipant.setMicrophoneEnabled(true)
if mediaType == video: setCameraEnabled(true)
订阅 RemoteParticipant 音视频 track → 渲染
```

### 6.3 生命周期

| 事件 | 建议 |
|------|------|
| `ParticipantDisconnected` / 对方离开 | 调 `hangup`（若尚未终态）并退出 UI |
| App 杀进程 | 下次启动不自动进房；以服务端 session 为准 |
| Token 过期 | `GET /calls/livekit/token` 后重连 |

### 6.4 权限

- 麦克风（语音/视频必选）  
- 摄像头（仅视频）  
- iOS：Background Modes → Audio、Voice over IP  

---

## 7. 最近通话（不变）

- `GET /calls/recent`  
- `GET /calls/{callId}`  
- TCP `call_recent_changed`  

`callId` 即 LiveKit 会话 ID；`room_id` 字段为 `roomName`（如 `call_xxx`）。  
列表 UI **无需**区分 TRTC / LiveKit（服务端 `call_type=livekit` 可忽略）。

---

## 8. 版本切断策略

| App 版本 | 行为 |
|----------|------|
| 新版本（本文件） | 仅 LiveKit；不初始化 TUICallKit |
| 旧 TUICallKit | **服务端已关闭**（`TRTC_CALLBACK_ENABLED=false`）：不再处理 TRTC 回调 / `av_call` VoIP / 落库；代码保留便于回滚 |

现网开关：

```bash
LIVEKIT_ENABLED=true
TRTC_CALLBACK_ENABLED=false
TRTC_CALLBACK_BOOTSTRAP=false
```

---

## 9. 联调检查清单

- [ ] invite 后主叫能进房听到自己静音检测  
- [ ] 被叫在线：IM `invite` 弹到来电  
- [ ] 被叫 iOS 杀进程：VoIP → CallKit → accept 进房  
- [ ] reject / cancel / hangup 双方 UI 一致  
- [ ] 最近通话与 TCP 推送更新  
- [ ] 4G ↔ Wi‑Fi 互通；TURN 回落  

---

## 10. 错误与日志

服务端关键字：

- `livekit invite callId=`  
- `livekit call finalized`  
- `voip push sent`（`inviteId` = `callId`；现含 `deviceId=`，且 `devices=1`）  
- `voip push prefer latest device`（多 VoIP 设备时选最新一台）  
- `voip push invalid token cleared`（BadDeviceToken 等已清理）  
- `livekit webhook`  

客户端建议打点：`callId`、`action`、`room.connect` 耗时、ICE 失败原因。
