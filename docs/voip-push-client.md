# iOS VoIP Push — 速查

> 版本：v4.0（2026-07-14）  
> **完整音视频前端对接文档**：  
> **[av-call-client.md](./av-call-client.md)**

---

## 快速索引

| 内容 | 位置 |
|------|------|
| 全平台对接总览 | [av-call-client.md](./av-call-client.md) |
| Token API | [av-call-client.md §3](./av-call-client.md#31-上报-push--voip-token) |
| PushKit / Bundle | [av-call-client.md §4](./av-call-client.md#4-ios-pushkit-配置硬性约束) |
| IM `av_call` | [av-call-client.md §5](./av-call-client.md#5-im-av_call-信令全平台必接) |
| 最近通话 / TCP | [av-call-client.md §8–9](./av-call-client.md#8-最近通话-api) |
| 排查 | [av-call-client.md §11–12](./av-call-client.md#11-联调验收清单) |

---

## 核心要点

1. **iOS 离线来电**：PushKit VoIP → 上报 `voipToken` → 不弹通知栏，直接唤醒 TUICallKit  
2. **Android 来电**：无 VoIP Push，仅 IM `av_call`（需长连接保活）  
3. **双通道去重**：PushKit 与 IM 按同一 `inviteId` 去重  
4. **Bundle ID**：`vip.99chat.pro`；VoIP topic：`vip.99chat.pro.voip`  
5. **服务端不因在线跳过 VoIP**；邀请信令才会推，终态落库≠已推送来电  

---

## VoIP Payload 速查

```json
{
  "aps": { "content-available": 1 },
  "data": {
    "type": "av_call",
    "inviteId": "...",
    "callerId": "rqwm8onw3j",
    "callerName": "对方备注名或真实昵称",
    "callerAvatarUrl": "https://...",
    "calleeId": "...",
    "mediaType": "audio",
    "roomId": "..."
  }
}
```

`callerName`：被叫视角（好友备注 → 昵称 → `callerId`）。

---

## Apple 审核

1. VoIP Push **仅用于真实来电**  
2. 收到后须尽快展示 CallKit / TUICallKit  
3. 聊天通知走普通 APNs（`type: im_chat`），**禁止**走 VoIP  
