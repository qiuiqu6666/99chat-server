# LiveKit 音视频通话 — 前端待办清单

> 版本：v1.0（2026-08-16）  
> 适用：**iOS / Android Flutter 客户端**（非 admin-console，非 Web H5）  
> 关联文档：[livekit-call-client.md](./livekit-call-client.md)  
> 背景：现网反馈通话不稳定、单向无声（接听方能听不能说 / 我方听不到对方）

---

## 1. 问题摘要

| 用户现象 | 最可能原因 |
|----------|------------|
| 对方能听到我，我听不到对方 | 本端 subscribe/播放失败，或远端未 publish |
| **接听方能听拨打方，但接听方说话拨打方听不到** | **被叫 connect 成功但麦克风未 publish**（高概率） |
| 时好时坏、偶发无声 | 多设备重复 accept → LiveKit `DUPLICATE_IDENTITY` |
| 长通话后异常 | 客户端未处理 token 重连；历史上有服务端 20s 误杀（已修） |

**编译产物侧证据（`web.99chat.chat/web/main.dart.js`）：**

| 路径 | 主叫 `qy()` | 被叫 `ZA()` |
|------|-------------|-------------|
| 权限预检 `b2t()` | ✅ 有 | ❌ **无** |
| `POST /calls/livekit/invite` `aCG()` | ✅ | — |
| `POST /calls/livekit/accept` `axA()` | — | ✅ |
| connect + publish `Ie()` | ✅ | ✅（但缺前置权限） |
| `answered_elsewhere` 处理 | — | ❌ **全文无匹配** |
| `lk_call` → `av_call` 改写 | — | ⚠️ 仍存在遗留逻辑 |

---

## 2. 服务端已配合变更（部署后生效）

前端需同步适配：

| 变更 | 影响 |
|------|------|
| `POST /accept` **只允许成功一次** | 重复调用返回 `409 CALL_ALREADY_ANSWERED` |
| 重连/补 token 必须用 `GET /calls/livekit/token?callId=` | 网络重试勿再 POST accept |
| iOS 来电 VoIP：**在线/离线均推送**（不因在线心跳跳过） | 与 IM `lk_call` 并行，客户端须按 callId 去重 |
| LiveKit 会话不再 20s 被 Job 误杀 | 长通话 session 更稳定 |

---

## 3. 待办总览

| 优先级 | ID | 任务 | 负责人 | 状态 |
|--------|-----|------|--------|------|
| P0 | F-01 | 被叫接听前麦克风/摄像头权限预检 | | ⬜ |
| P0 | F-02 | publish 失败必须 hangup，禁止「假连接」 | | ⬜ |
| P0 | F-03 | 实现 `answered_elsewhere` 多端停铃 | | ⬜ |
| P0 | F-04 | accept 重试改走 `/token`，处理 409 | | ⬜ |
| P1 | F-05 | 分离 `lk_call` 与 `av_call` 状态机 | | ⬜ |
| P1 | F-06 | 主叫收到 `accept` 后确保订阅远端 audio | | ⬜ |
| P1 | F-07 | VoIP + IM 双通道按 callId 去重 | | ⬜ |
| P1 | F-08 | 监听 `DuplicateIdentity` / 被踢出 | | ⬜ |
| P2 | F-09 | 通话 telemetry 上报 | | ⬜ |
| P2 | F-10 | iOS CallKit 与 LiveKit 生命周期对齐 | | ⬜ |

---

## 4. P0 详细说明（必须先做）

### F-01 被叫接听前权限预检

**目标：** 对齐主叫 `qy()`，被叫 `ZA()` / CallKit 接听回调增加相同 gate。

**建议顺序：**

```text
1. b2t(context, isVideo)                 // 麦克风；视频通话 + 摄像头
2. iOS: AVAudioSession 激活（audio/video 模式）
3. POST /calls/livekit/accept
4. Room.connect(url, token)
5. setMicrophoneEnabled(true)
6. video: setCameraEnabled(true)
7. 配置音频路由（扬声器/听筒）
```

**验收：**

- [ ] 用户拒绝麦克风 → 不调用 accept，或 accept 前拦截并提示
- [ ] LiveKit Dashboard：被叫进房后 **有 audio publication**
- [ ] 不再出现「UI 显示通话中但对方听不到我」

**涉及代码（在 Flutter 源码中搜索）：**

- 被叫：`ZA()`、`axA()`、`Ie()`
- 主叫参考：`qy()`、`b2t()`、`aCG()`

---

### F-02 publish 失败必须 hangup

**目标：** connect 成功但 publish 失败时，不能停留在通话 UI。

**实现要点：**

```dart
// 伪代码
final ok = await room.localParticipant?.setMicrophoneEnabled(true);
if (ok != true) {
  await api.hangup(callId);
  await room.disconnect();
  showError('无法开启麦克风');
  return;
}
```

**验收：**

- [ ] 模拟 publish 失败 → 自动挂断，双方 UI 结束
- [ ] 日志含 `publishOk=false`

---

### F-03 实现 `answered_elsewhere`

**目标：** 一端接听后，被叫其它 App 端 / CallKit 必须停铃，**禁止第二次 accept/进房**。

**IM `lk_call` action 处理表（须完整）：**

| action | 行为 |
|--------|------|
| `invite` | 展示来电（与 VoIP 按 callId 去重） |
| `accept` | 主叫：停振铃，确认已进房/已订阅 |
| `answered_elsewhere` | **被叫其它端：停铃 + 禁止 accept/connect** |
| `reject` / `cancel` / `hangup` | 结束 UI，disconnect |

**VoIP Push 终态（`action` ≠ `invite`，常带 `call_end=1`）：**

- 结束 CallKit 对应 `callId` 的来电，禁止再 accept

**验收：**

- [ ] 被叫双端在线（手机 + 平板）：A 接听后 B 收 `answered_elsewhere`，B 不进房
- [ ] LiveKit room 始终 ≤ 2 participants
- [ ] 无 `DUPLICATE_IDENTITY` 相关错误

**涉及代码：**

- IM 信令：`blG()` / lk_call handler（勿走 `av_call` 分支）
- 当前缺失：`answered_elsewhere` 字符串在产物中 **0 处**

---

### F-04 accept 重试与 409 处理

**背景：** 服务端 `POST /accept` 仅允许首次成功，重复返回 `CALL_ALREADY_ANSWERED`。

**客户端策略：**

| 场景 | 做法 |
|------|------|
| accept 网络超时，不确定是否成功 | `GET /calls/livekit/token?callId=` 后 connect |
| 收到 409 `CALL_ALREADY_ANSWERED` | 视为已接听，走 `/token` 重连，**不要**再 POST accept |
| 正常接听 | accept 一次 → connect → publish |

**验收：**

- [ ] 弱网重复点接听不会导致双 connect
- [ ] 409 有明确 fallback 到 `/token`

---

## 5. P1 详细说明

### F-05 分离 `lk_call` 与 `av_call`

**问题：** 消息解析层将 `businessID=lk_call` 改写为 `av_call`，仍走 TUICallKit 时代状态机，与 LiveKit 并行。

**要求：**

- LiveKit 通话 **独立 handler**，不经过 TRTC `blG()` / `bN0()` 主路径
- 旧 `av_call` 消息：**忽略**（服务端 TRTC 已关）
- `accept` IM 消息不应只改 UI 状态而不触发 LiveKit 订阅检查

**验收：**

- [ ] 全链路日志可区分 `lk_call` / `av_call`
- [ ] 新包不再初始化 TUICallKit

---

### F-06 主叫订阅远端 audio

**问题：** 被叫 publish 晚于主叫 connect 时，主叫可能未订阅到新 track。

**要求：**

- 监听 `RoomEvent.ParticipantConnected` / `TrackPublished`
- 远端 audio publication 出现时自动 attach 播放
- 收到 IM `accept` 后检查 `remoteParticipants` 是否有 audio track

**验收：**

- [ ] 被叫晚 2s publish，主叫仍能听到

---

### F-07 VoIP + IM 去重

**要求：**

- 同一 `callId` 只展示 **一个** 来电 UI
- 在线：IM `lk_call` + iOS VoIP 可能同时到达，须按 callId 去重
- 离线 iOS：VoIP 展示 CallKit；IM 到达时按 callId 合并

**验收：**

- [ ] 不因 VoIP + IM 各弹一次接听

---

### F-08 DuplicateIdentity / 被踢

**要求：**

- 监听 LiveKit `RoomEvent.ParticipantDisconnected`（reason=duplicate identity）
- 被踢时：提示 + hangup + 退出 UI

**验收：**

- [ ] 异常进房时有用户可见提示，非静默单向无声

---

## 6. P2 详细说明

### F-09 Telemetry（建议字段）

每次通话上报（成功/失败均上报）：

```json
{
  "callId": "...",
  "role": "caller|callee",
  "mediaType": "audio|video",
  "micPermission": "granted|denied",
  "cameraPermission": "granted|denied|n/a",
  "connectMs": 120,
  "publishMs": 80,
  "publishOk": true,
  "remoteAudioTrackCount": 1,
  "iceState": "...",
  "error": null,
  "duplicateIdentity": false
}
```

### F-10 iOS CallKit 对齐

- CallKit `CXAnswerCallAction` 回调内先走 F-01 权限，再 accept
- CallKit 结束与 LiveKit `room.disconnect()` 顺序一致
- 后台音频 session 类别与 LiveKit 一致

---

## 7. 联调验收清单（发版前必过）

### 7.1 单向音频

- [ ] 主叫 → 被叫接听：双方互听正常
- [ ] 被叫拒麦克风：通话失败，非单向无声
- [ ] 视频通话：音视频双向正常

### 7.2 多端

- [ ] 被叫双设备在线，仅一台能接听
- [ ] 另一台收 `answered_elsewhere` 后 UI 关闭

### 7.3 生命周期

- [ ] 主叫 cancel → 被叫来电消失
- [ ] 被叫 reject → 主叫结束
- [ ] 通话中 hangup → 双方退出
- [ ] 通话 > 2 分钟：双方仍正常，无 token 失效断连

### 7.4 LiveKit Dashboard（运维协助）

- [ ] 每通电话 room 内 **2 个 participant**
- [ ] 每人 **1 个 audio publication**（视频 +1 video）
- [ ] 无第三方/debug 客户端占坑

---

## 8. 推荐实施顺序

```text
Week 1  F-01 → F-02 → F-03 → F-04   （解决单向无声 + 多端抢房）
Week 2  F-05 → F-06 → F-07 → F-08   （稳定性 + 遗留栈清理）
Week 3  F-09 → F-10                 （可观测 + iOS 体验）
```

---

## 9. 不在本次范围

| 项 | 说明 |
|----|------|
| Web H5 通话 | 产物明确 `Unimplemented`，需单独立项 |
| admin-console | 无通话 UI，不涉及 |
| 服务端 LiveKit 部署 | 见 [livekit-self-host.md](./livekit-self-host.md) |
| 群通话 / 1v1 升级会议 | 未实现 |

---

## 10. 参考 API 速查

| 操作 | 方法 | 路径 |
|------|------|------|
| 发起 | POST | `/calls/livekit/invite` |
| 接听（仅一次） | POST | `/calls/livekit/accept` |
| 重连 token | GET | `/calls/livekit/token?callId=` |
| 拒接 | POST | `/calls/livekit/reject` |
| 取消 | POST | `/calls/livekit/cancel` |
| 挂断 | POST | `/calls/livekit/hangup` |

IM 信令：`businessID=lk_call`，字段见 [livekit-call-client.md §4](./livekit-call-client.md#4-im-信令-lk_call)。

---

## 11. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-08-16 | 初版：基于现网单向无声排查与客户端产物分析 |
