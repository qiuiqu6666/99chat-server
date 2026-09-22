# 音视频通话 — 前端对接文档（TRTC 遗留）

> 版本：v4.0（2026-07-14）  
> **状态：遗留文档。** 新客户端请对接 **[livekit-call-client.md](./livekit-call-client.md)**（自建 LiveKit，完全替换 TUICallKit/TRTC）。  
> 部署见 [livekit-self-host.md](./livekit-self-host.md)。  
> 适用：仍运行旧版 TUICallKit 的 App + 并行期服务端  
> 通话 SDK：腾讯云 TUICallKit / TRTC（已弃用）  
> 关联：[push-client.md](./push-client.md) · [call-recent-and-webhook.md](./call-recent-and-webhook.md) · [notification-settings-client.md](./notification-settings-client.md)

---

## 1. 一眼看清

| 能力 | iOS | Android |
|------|-----|---------|
| 发起 / 接听通话 | TUICallKit | TUICallKit |
| 在线来电 UI | IM `av_call` 信令 | IM `av_call` 信令 |
| 离线 / 被杀进程来电 | **PushKit VoIP Push** | **无服务端 VoIP**，靠 IM 长连接保活 |
| 最近通话列表 | `GET /calls/recent` + TCP `call_recent_changed` | 同左 |
| 来电开关 | `callNotificationEnabled`（仅影响 iOS VoIP） | 不影响 IM 信令 |

服务端行为（当前线上逻辑）：

1. 主叫拨号 → IM 发出 `av_call` 邀请信令（`actionType=1`）
2. 服务端收到 IM AfterSend / TRTC 回调 → **立刻发 iOS VoIP Push**（**不因被叫在线而跳过**）
3. 通话终态落库 → 最近通话列表 + TCP 推送 `call_recent_changed`
4. 「有通话记录」≠「一定发过 VoIP」——只有**邀请信令**触发来电 Push

---

## 2. 架构与双通道

```
主叫 TUICallKit.dial
        │
        ▼
 IM 自定义消息 av_call（必达被叫，若 IM 在线）
        │
        ├──► 被叫 App 在线：直接展示来电 UI
        │
        └──► 99chat-server
                ├── 解析邀请信令
                └── iOS：APNs VoIP → PushKit 唤醒被叫 → 展示来电 UI
```

| 入口 | 谁用 | 去重键 |
|------|------|--------|
| IM `TIMCustomElem` / `av_call` | **全平台必接** | `inviteID` |
| PushKit payload `type=av_call` | **仅 iOS** | `inviteId`（同值） |

两条路径可能同时到达，**同一 `inviteId` 只处理一次**。

---

## 3. 登录后必做清单

```text
1. Tencent IM 登录 + 监听自定义消息（含 av_call）
2. 初始化 TUICallKit
3. iOS：请求通知权限 + 注册 PushKit，拿到 voipToken
4. POST /me/push-token（iOS 务必带 voipToken）
5. 读取 / 同步通知设置 callNotificationEnabled
6. 订阅 TCP 长连接事件 call_recent_changed（刷新最近通话）
```

### 3.1 上报 Push / VoIP Token

```http
POST /me/push-token
Authorization: Bearer <JWT>
Content-Type: application/json
```

**iOS（必带 voipToken）**

```json
{
  "deviceId": "<与登录一致的设备 UUID>",
  "platform": "IOS",
  "token": "<APNs alert token 十六进制>",
  "voipToken": "<PushKit VoIP token 十六进制>"
}
```

**Android**

```json
{
  "deviceId": "<设备 UUID>",
  "platform": "ANDROID",
  "token": "<极光 registrationId>"
}
```

成功：

```json
{
  "ok": true,
  "platform": "IOS",
  "provider": "APNS",
  "hasVoipToken": true
}
```

> 若 `hasVoipToken=false`，iOS 离线来电一定失败。

### 3.2 VoIP Token 变更时单独更新

须先完成 §3.1（同 `deviceId` 已有普通 token）。

```http
POST /me/voip-push-token
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "deviceId": "<设备 UUID>",
  "token": "<新的 PushKit voip token>"
}
```

成功：`{ "ok": true, "deviceId": "..." }`  
错误：`DEVICE_NOT_REGISTERED`（先注册 push-token）、`VOIP_IOS_ONLY`。

### 3.3 上报时机

| 时机 | 动作 |
|------|------|
| 登录成功 | `POST /me/push-token`（iOS 带 voip） |
| APNs / PushKit token 刷新 | 再次上报 |
| 从后台回前台发现 token 变化 | 再次上报 |
| 退出登录 | `DELETE /me/push-token` |

---

## 4. iOS PushKit 配置（硬性约束）

| 项 | 值 |
|----|-----|
| App Bundle ID | **`vip.99chat.pro`** |
| VoIP Topic | **`vip.99chat.pro.voip`** |
| 证书类型 | Apple VoIP Services（与 Bundle 匹配） |
| 环境 | 与后端 `PUSH_APNS_PRODUCTION` 一致（线上多为 production） |

Xcode：

1. Capabilities → **Push Notifications**
2. Background Modes → **Voice over IP**
3. 使用 `PKPushRegistry`，`desiredPushTypes = [.voIP]`
4. 收到 VoIP Push **禁止**弹本地通知栏；须尽快拉起 CallKit / TUICallKit 来电 UI（Apple 审核要求）

### 4.1 VoIP Payload（服务端下发）

```json
{
  "aps": { "content-available": 1 },
  "data": {
    "type": "av_call",
    "inviteId": "dc9d988a86faa9f6e9e3e49fda2576b1",
    "callerId": "rqwm8onw3j",
    "callerName": "秋🍂",
    "callerAvatarUrl": "https://...",
    "calleeId": "acnj6oxey9",
    "mediaType": "audio",
    "roomId": "854118195"
  }
}
```

| 字段 | 说明 |
|------|------|
| `type` | 固定 `av_call` |
| `inviteId` | 去重键，与 IM `inviteID` 相同 |
| `callerId` / `calleeId` | 主叫 / 被叫 userId |
| `callerName` | **被叫视角**：好友备注 → 昵称 → 回退 `callerId` |
| `callerAvatarUrl` | 主叫头像（可选） |
| `mediaType` | `audio` / `video` |
| `roomId` | TRTC 房间号（可选，字符串） |

处理要求：

1. `type != av_call` 忽略  
2. 按 `inviteId` 去重  
3. 立刻交给 TUICallKit / CallKit  
4. **禁止**用 VoIP 通道发聊天 / 营销通知  

---

## 5. IM `av_call` 信令（全平台必接）

消息类型：`TIMCustomElem`，`MsgContent.Data` 为 **JSON 字符串**（可能双层字符串嵌套）。

### 5.1 外层 envelope

```json
{
  "actionType": 1,
  "businessID": 1,
  "data": "<内层 JSON 字符串>",
  "inviteID": "dc9d988a86faa9f6e9e3e49fda2576b1",
  "inviteeList": ["acnj6oxey9"],
  "inviter": "rqwm8onw3j",
  "timeout": 30
}
```

### 5.2 内层（解析外层 `data` 后）

```json
{
  "businessID": "av_call",
  "call_end": 0,
  "call_type": 1,
  "data": {
    "cmd": "audioCall",
    "inviter": "rqwm8onw3j",
    "room_id": 854118195,
    "userIDs": ["acnj6oxey9"]
  },
  "room_id": 854118195,
  "version": 4
}
```

### 5.3 关键字段

| 字段 | 含义 |
|------|------|
| `actionType` | `1` 邀请 · `2` 接听 · `3`/`4`/`5` 拒接/挂断等终态（以 SDK 为准） |
| `call_end` | `0` 进行中；`>0` 常为终态时长或结束标记 |
| `call_type` | `1` → `audio`，`2` → `video` |
| `inviteID` | = VoIP `inviteId` |
| `inviter` / `inviteeList` | 主叫 / 被叫 |

### 5.4 何时展示来电 UI

```dart
bool isIncomingInvite(Map envelope, Map av) {
  final actionType = envelope['actionType'] as int? ?? 0;
  final callEnd = av['call_end'] as int? ?? 0;
  return actionType == 1 && callEnd == 0;
}
```

满足条件 → 与 VoIP 共用同一套 `handleIncomingCall`。

---

## 6. 客户端统一处理伪代码

```dart
final _handledInvites = <String>{};

void onVoipPush(Map<String, dynamic> payload) {
  final data = payload['data'] as Map<String, dynamic>? ?? payload;
  if (data['type'] != 'av_call') return;
  handleIncomingCall(IncomingCall.fromPush(data));
}

void onImCustomMessage(String dataJson) {
  final envelope = jsonDecode(dataJson) as Map<String, dynamic>;
  final raw = envelope['data'];
  final av = raw is String ? jsonDecode(raw) as Map<String, dynamic> : raw as Map<String, dynamic>;
  if (av['businessID'] != 'av_call') return;
  if (!isIncomingInvite(envelope, av)) return;
  handleIncomingCall(IncomingCall.fromIm(envelope, av));
}

void handleIncomingCall(IncomingCall call) {
  if (!_handledInvites.add(call.inviteId)) return; // 双通道去重
  TUICallKit.instance.showIncomingCall(
    callerId: call.callerId,
    callerName: call.callerName,
    mediaType: call.mediaType,
    roomId: call.roomId,
  );
}
```

---

## 7. 通知设置

```http
GET /me/notification-settings
PUT /me/notification-settings
Authorization: Bearer <JWT>
```

| 字段 | 作用 |
|------|------|
| `callNotificationEnabled` | `false` 时服务端**不发 iOS VoIP**；不影响本机在线收到 IM 信令 |
| `systemMessageNotificationEnabled` | 聊天等普通 Push，与通话无关 |

默认均为 `true`。用户关闭「语音和视频通话通知」后，iOS 离线不会响。

---

## 8. 最近通话 API

均需 JWT。`direction` / 文案视角按**当前登录用户**。

### 8.1 列表

```http
GET /calls/recent?filter=all|missed&page=0&pageSize=20
```

```json
{
  "items": [{
    "callId": "...",
    "peerUserId": "...",
    "peerName": "...",
    "peerAvatar": "https://...",
    "mediaType": "audio",
    "direction": "incoming",
    "result": "answered",
    "durationSec": 0,
    "occurredAt": 1784001872682,
    "callerUserId": "rqwm8onw3j",
    "operatorUserId": null
  }],
  "page": 0,
  "pageSize": 20,
  "total": 1
}
```

| `result` | 含义 | 气泡提示（示意） |
|----------|------|------------------|
| `answered` | 已接通 | 显示时长 |
| `missed` | 未接 | 未接听 |
| `rejected` | 拒接 | 已拒绝 / 对方已拒绝（看 `operatorUserId`） |
| `canceled` | 主叫取消 | 已取消 / 对方已取消 |
| `busy` | 忙线 | 忙线 |
| `failed` | 失败 | 未接听等 |

`operatorUserId == 自己` → 「已拒绝」类文案；否则「对方已拒绝」。

### 8.2 单条（补偿）

```http
GET /calls/{callId}
```

包装：`{ "code": 0, "message": "ok", "data": { ... } }`  
不存在：`404 CALL_RECORD_NOT_FOUND`

### 8.3 删除

```http
DELETE /calls/recent/{callId}          → { "ok": true }
DELETE /calls/recent?filter=all|missed → { "ok": true, "deleted": N }
```

---

## 9. TCP 实时：`call_recent_changed`

通话邀请 / 接听 / 终态写入后，已连接实时 TCP 的主叫与被叫会收到：

```json
{
  "type": "event",
  "event": "call_recent_changed",
  "action": "added",
  "callId": "...",
  "callerUserId": "rqwm8onw3j",
  "operatorUserId": null,
  "peerUserId": "...",
  "mediaType": "audio",
  "direction": "incoming",
  "result": "answered",
  "durationSec": 0,
  "occurredAt": 1784001872682
}
```

客户端：按 `callId` upsert 最近通话列表；可与 HTTP 列表混用。

---

## 10. Android 特别说明

| 项 | 说明 |
|----|------|
| 来电通道 | **只有 IM `av_call`**，服务端不发 Android 来电 Push |
| 保活 | 需保证 IM 长连接在后台不被系统杀死（厂商保活 / 前台服务等，按产品策略） |
| 极光 | 仅用于聊天等普通通知，**不负责响铃来电** |

---

## 11. 联调验收清单

| # | 场景 | 期望 |
|---|------|------|
| 1 | 双方在线互拨 | IM 信令弹出 TUICallKit；可选同时收到 VoIP（iOS 按 inviteId 去重） |
| 2 | 被叫 iOS 杀进程 / 锁屏 | 响铃 + 来电 UI；服务端日志有 `voip push sent` |
| 3 | 被叫关闭通话通知 | 无 VoIP；在线仍可能靠 IM 看到 |
| 4 | 挂断后 | `GET /calls/recent` 有记录；TCP 收到 `call_recent_changed` |
| 5 | 注册 token | iOS `hasVoipToken=true` |

服务端日志关键字（给后端查）：

| 日志 | 含义 |
|------|------|
| `voip push sent ... devices=N` | VoIP 至少一台成功 |
| `apns voip rejected ... DeviceTokenNotForTopic` | Bundle / topic 不匹配或错用 alert token |
| `apns voip rejected ... BadDeviceToken` | VoIP token 无效，需客户端重报 |
| `voip push skipped: no voip token` | 未上报 voipToken |
| `voip push failed all devices` | 有 token 但全部被 APNs 拒绝 |
| `av_call finalized` | 通话记录已终态落库（**不代表发过 VoIP**） |

---

## 12. 常见问题

| 现象 | 原因与处理 |
|------|------------|
| 有最近通话记录，但没响铃 | 记录由终态归档写入；VoIP 只在**邀请**时发。查是否有 `voip push sent` |
| `DeviceTokenNotForTopic` | Bundle 不是 `vip.99chat.pro`，或把普通 APNs token 当成 voipToken |
| `BadDeviceToken` | 重装 / 重签后旧 token；重新 `POST /me/push-token` + voipToken |
| 聊天能推、通话不能 | 正常：聊天走极光/普通 APNs，通话走 VoIP 专用 token |
| Android 杀进程无来电 | 产品限制；需保活 IM，服务端目前无 Android 来电 Push |
| 重复弹来电 | 双通道；按 `inviteId` 去重 |

---

## 13. 相关文档

| 文档 | 内容 |
|------|------|
| [push-client.md](./push-client.md) | 含离线消息 Push + 原 §10 音视频细则 |
| [voip-push-client.md](./voip-push-client.md) | iOS VoIP 一页速查 |
| [call-recent-and-webhook.md](./call-recent-and-webhook.md) | 服务端 TRTC 回调与落库细节 |
| [notification-settings-client.md](./notification-settings-client.md) | 通知开关 API |
