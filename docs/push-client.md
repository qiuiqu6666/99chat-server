# 离线推送 & 音视频来电 — 客户端完整对接文档

> 版本：v3.0  
> 适用：99chat Flutter 客户端 + 99chat-server  
> Base URL：`http://47.239.60.107:8081`（以部署为准）  
> **不依赖**腾讯云 IM 离线推送；系统通知栏由自建 Push 负责。

---

## 目录

1. [架构总览](#1-架构总览)
2. [平台策略](#2-平台策略)
3. [客户端必做 / 必禁](#3-客户端必做--必禁)
4. [鉴权与响应格式](#4-鉴权与响应格式)
5. [Push Token API](#5-push-token-api)
6. [心跳 API（Android 保活推荐）](#6-心跳-apiandroid-保活推荐)
7. [iOS 集成（APNs + PushKit）](#7-ios-集成apns--pushkit)
8. [Android 集成](#8-android-集成)
9. [离线消息推送（系统通知栏）](#9-离线消息推送系统通知栏)
10. [音视频来电推送](#10-音视频来电推送)
11. [Flutter 集成参考](#11-flutter-集成参考)
12. [与 IM 在线消息协同](#12-与-im-在线消息协同)
13. [集成检查清单](#13-集成检查清单)
14. [排查](#14-排查)
15. [关联文档](#15-关联文档)

---

## 1. 架构总览

### 1.1 双通道模型

```
┌─────────────────────────────────────────────────────────────┐
│                        99chat 客户端                         │
├──────────────────────────┬──────────────────────────────────┤
│  通道 A：腾讯 IM SDK      │  通道 B：自建系统 Push            │
│  （长连接 / 漫游）         │  （通知栏 / VoIP 唤醒）           │
├──────────────────────────┼──────────────────────────────────┤
│  App 内消息、在线收消息    │  用户离线 / 后台时弹系统通知       │
│  钱包/公告 IM 自定义消息   │  iOS 来电 VoIP Push 唤醒 App      │
│  av_call 来电信令         │  Android 来电走 IM 信令（无 VoIP）│
└──────────────────────────┴──────────────────────────────────┘
```

| 能力 | 通道 A（IM） | 通道 B（系统 Push） |
|------|-------------|-------------------|
| 聊天消息（App 内） | ✅ 始终 | — |
| 聊天消息（通知栏） | — | ✅ 离线时 |
| 钱包 / 公告 / 欢迎语（App 内） | ✅ IM 自定义消息 | ✅ 离线时补通知栏 |
| 音视频来电（在线） | ✅ `av_call` 信令 | — |
| 音视频来电（iOS 离线唤醒） | — | ✅ PushKit VoIP |
| 音视频来电（Android 离线） | ✅ IM 信令（需保活） | ❌ 无 VoIP Push |

### 1.2 标准流程

```
登录成功（deviceId 与下文上报一致）
  → iOS：获取 APNs token + PushKit VoIP token
  → Android（方案 A）：获取极光 registrationId
  → Android（方案 B）：不集成极光，前台服务保活 IM
  → POST /me/push-token（方案 B 的 Android 跳过此步）
  → TCP `ping`（已连实时通道时周期保活；可选 `deviceId`）
  → 未连 TCP 时回退 `POST /me/heartbeat`
  → 用户离线时服务端下发系统通知
  → 用户点击通知 → 按 data.type 路由跳转
```

---

## 2. 平台策略

| 能力 | iOS | Android 方案 A（极光） | Android 方案 B（保活） |
|------|-----|----------------------|----------------------|
| 聊天离线通知栏 | APNs | 极光 | IM 监听 → 本地通知 |
| 钱包 / 公告通知栏 | APNs | 极光 | IM 监听 → 本地通知 |
| 来电唤醒 | PushKit VoIP | IM `av_call` + TUICallKit | 同左 |
| 上报 `/me/push-token` | ✅ 必填 | ✅ 必填 | ❌ 不上报 |
| 进程被杀后仍能收到 | ✅ | ✅（厂商通道） | ❌ |

**推荐**：
- iOS：APNs + PushKit（完整能力）
- Android：量产建议方案 A；若明确不用极光，采用方案 B 并接受杀进程后无通知

---

## 3. 客户端必做 / 必禁

### 必做

1. 登录、`/me/push-token` 使用 **同一 `deviceId`**（与 `POST /auth/login` 一致）
2. **关闭**腾讯 IM SDK 的 offline push 注册
3. 实现 `data.type` 统一路由（§9、§10）
4. 聊天 Push 按 `msgKey` 与 IM 在线消息去重（§12）
5. iOS 音视频：上报 `voipToken` + PushKit delegate 处理来电
6. Android 音视频：TUICallKit 监听 IM `av_call` 信令

### 必禁

1. **不要**向腾讯 IM SDK 注册 offline push
2. **不要**在 IM 控制台配置 APNs 离线推送
3. **不要**用 iOS VoIP Push 通道发聊天 / 营销通知（Apple 会拒审）
4. Android 方案 B：**不要**调用 `/me/push-token`

---

## 4. 鉴权与响应格式

除 `/webhook/**`、`/admin/**` 外，App 接口成功响应统一为：

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

请求头：

```http
Authorization: Bearer <jwt>
Content-Type: application/json
```

下文示例均为 **`data` 内业务体**（已省略外层信封）。

---

## 5. Push Token API

### 5.1 注册 / 更新 Push Token

```http
POST /me/push-token
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceId` | string | 是 | 与登录相同 |
| `platform` | string | 是 | **`IOS`** 或 **`ANDROID`**（大写枚举） |
| `token` | string | 是 | iOS：APNs device token（十六进制）；Android：极光 `registrationId` |
| `voipToken` | string | 否 | 仅 iOS；PushKit VoIP token |

**iOS 示例**

```json
{
  "deviceId": "7c1fa03b-8426-43f9-8112-ce65ed314e81",
  "platform": "IOS",
  "token": "a1b2c3d4e5f6789012345678abcdef01",
  "voipToken": "d4e5f6789012345678abcdef01234567"
}
```

**Android 示例（方案 A）**

```json
{
  "deviceId": "4a6815bd-06d7-45d8-867c-c6829c775e2b",
  "platform": "ANDROID",
  "token": "13065ffa4f5a2a80053abc"
}
```

**成功响应**

```json
{
  "ok": true,
  "platform": "IOS",
  "provider": "APNS",
  "hasVoipToken": true
}
```

| 字段 | 说明 |
|------|------|
| `provider` | iOS → `APNS`；Android → `JPUSH` |
| `hasVoipToken` | 是否已保存 VoIP token |

**错误码（HTTP 400，`message` 字段）**

| code | 说明 |
|------|------|
| `INVALID_DEVICE` | `deviceId` 为空 |
| `INVALID_PLATFORM` | `platform` 不是 `IOS` / `ANDROID` |
| `INVALID_TOKEN` | `token` 为空 |

**调用时机**

| 时机 | 动作 |
|------|------|
| 登录成功后 | 立即上报 |
| APNs / 极光 token 刷新 | 再次 `POST /me/push-token` |
| PushKit VoIP token 变化 | 带 `voipToken` 重报，或 §5.2 |
| 推送权限重新授权后 | 重新上报 |
| 退出登录 | §5.3 注销 |

> Token 被 APNs / 极光判定无效时，服务端自动 `enabled=false`；下次启动或 token 刷新须重新上报。

---

### 5.2 单独更新 VoIP Token（iOS）

须先通过 §5.1 注册过该 `deviceId` 的普通 token。

```http
POST /me/voip-push-token
```

```json
{
  "deviceId": "7c1fa03b-8426-43f9-8112-ce65ed314e81",
  "token": "pushkit_voip_token_hex"
}
```

**成功**：`{ "ok": true, "deviceId": "..." }`

**错误码**：`DEVICE_NOT_REGISTERED` / `VOIP_IOS_ONLY` / `INVALID_TOKEN`

---

### 5.3 注销 Token

```http
DELETE /me/push-token
```

```json
{ "deviceId": "uuid-on-device" }
```

**成功**：`{ "ok": true }`（标记 `enabled=false`，不物理删除）

---

## 6. 心跳（TCP 优先）

**主路径**：实时 TCP 已鉴权后发 `{"type":"ping","deviceId":"..."}`（`deviceId` 可选），收 `pong`（含 `ts`）。建议 30s 一次；替代周期 HTTP 心跳。

**回退**（未连 TCP / 降级）：

```http
POST /me/heartbeat
```

无请求体，或 `{ "deviceId": "..." }`。成功：`{ "ok": true }`

| 说明 | 值 |
|------|-----|
| 作用 | 服务端记录用户活跃（默认 30s 节流） |
| 方案 A | 可选 |
| 方案 B（保活） | **建议** App 前台 / 后台服务存活时：有 TCP 用 `ping`，否则周期 HTTP |
| 与 Push 关系 | 在线时部分业务 Push 可能跳过；Android 方案 B 不依赖此机制收通知 |

---

## 6.1 会话免打扰（必接）

IM SDK 切换免打扰后，**必须同步到服务端**，否则离线 Push 仍会发送。

```http
PUT /me/conversation-notify
Content-Type: application/json

{
  "chatType": "c2c",
  "peerId": "对方userId",
  "muted": true
}
```

群聊将 `chatType` 设为 `group`，`peerId` 为 `groupId`。

**批量同步（登录后推荐）**

```http
PUT /me/conversation-notify/batch
{ "items": [
  { "chatType": "group", "peerId": "@TGS#xxx", "muted": true },
  { "chatType": "c2c", "peerId": "abc12", "muted": false }
]}
```

**查询已免打扰列表**

```http
GET /me/conversation-notify
```

服务端在单聊 / 群聊 Push 前会检查该表；`muted=true` 时不发系统 Push。

---

### 6.2 当前会话 focus（减少残留 Push，推荐）

用户正在查看某会话时上报，服务端对该会话**跳过**聊天离线 Push（Redis TTL 90s，须在会话内周期续期）。

**进入会话**

```http
PUT /me/push-focus
Content-Type: application/json

{ "chatType": "c2c", "peerId": "对方userId" }
```

群聊：

```json
{ "chatType": "group", "groupId": "@TGS#xxx" }
```

**离开会话**

```http
DELETE /me/push-focus
```

**调用时机**

| 时机 | 动作 |
|------|------|
| 进入聊天页 | `PUT /me/push-focus` |
| 会话内每 60s | `PUT` 续期 |
| 离开聊天页 | `DELETE /me/push-focus` |

与 `POST /me/heartbeat`、前台清 `im_chat` 通知配合使用，见 [push-stale-notification-fix.md](./push-stale-notification-fix.md)。

---

## 7. iOS 集成（APNs + PushKit）

### 7.1 Xcode Capabilities

| Capability | 用途 |
|------------|------|
| Push Notifications | 聊天 / 钱包 / 公告通知栏 |
| Background Modes → **Voice over IP** | 来电 VoIP 唤醒（必填） |

**Bundle ID**：`chat.99chat.app`（须与后端 APNs 证书一致）

### 7.2 普通 APNs Token

1. 申请系统通知权限
2. `didRegisterForRemoteNotificationsWithDeviceToken` 取 **十六进制** device token（去除 `<>` 和空格）
3. 登录成功后 `POST /me/push-token`，`platform=IOS`

### 7.3 PushKit VoIP Token

1. 注册 `PKPushRegistry`（类型 `.voIP`）
2. `didUpdate pushCredentials` 取 VoIP token（十六进制）
3. 与普通 token 一并上报，或 `POST /me/voip-push-token`
4. Token 变化时**必须**重新上报

### 7.4 环境匹配

| 包类型 | 后端 `PUSH_APNS_PRODUCTION` |
|--------|----------------------------|
| TestFlight / App Store | `true` |
| Xcode 直装 Debug | `false` |

### 7.5 读取 Push Payload

APNs 自定义字段与 `title` / `body` 同级，键名即 `data` 中的字段（如 `type`、`msgKey`、`avatarUrl`）。点击通知时从 `userInfo` 读取。

### 7.6 聊天通知头像（Notification Service Extension）

当 payload 含 `avatarUrl` 时，服务端会设 `aps.mutable-content = 1`。须新增 **Notification Service Extension** Target：

1. Extension 的 `didReceive` 读取 `userInfo["avatarUrl"]`
2. 下载图片到本地临时文件
3. 创建 `UNNotificationAttachment` 挂到 `content.attachments`
4. 调用 `contentHandler(content)`

无 Extension 时通知仍正常展示文字，只是没有头像缩略图。

---

## 8. Android 集成

### 8.1 方案 A：极光 Push（推荐量产）

1. 集成 [极光 Flutter SDK](https://docs.jiguang.cn/jpush/client/flutter/)
2. 初始化后取完整 `registrationId`（勿截断）
3. 登录成功后 `POST /me/push-token`，`platform=ANDROID`
4. 国产机须在极光控制台配置华为 / 小米 / OPPO / vivo 等厂商通道；服务端会附带 `options.third_party_channel`（小米需配置 `push.jpush_xiaomi_channel_id`）
5. 会话归组读 `extras.threadId`（服务端下发），勿依赖已废弃的 `android.group`
6. 在极光回调中读取 `extras` 字段（与 §9 中 `data` 键一致）

### 8.2 方案 B：保活 + IM 本地通知（不用极光）

```
登录 → IM SDK login
     → 启动 Foreground Service（前台服务 + 常驻通知）
     → 周期 TCP `ping`（未连 TCP 时 `POST /me/heartbeat`）
     → 监听 IM 新消息 / 自定义消息
     → App 不在前台时，flutter_local_notifications 弹本地通知
     → 不调 POST /me/push-token
```

| 步骤 | 说明 |
|------|------|
| 前台服务 | Android 8+ 后台长连接必备 |
| 电池优化 | 引导用户关闭对本 App 的限制 |
| 自启动 | 国产 ROM 需引导开启 |
| 本地通知 | 聊天、钱包、公告均从 IM 监听器触发 |
| 局限 | 进程被杀 / 深度休眠后**无法**收到通知，重连后靠 IM 漫游补消息 |

**服务端**：将 `push.jpush_enabled` 设为 `false` 即可，无需改客户端接口。

---

## 9. 离线消息推送（系统通知栏）

服务端通过 APNs 自定义字段 / 极光 `extras` 下发。所有业务类型通过 **`type`** 字段区分。

### 9.1 统一路由

```dart
void onNotificationTap(Map<String, String> data) {
  switch (data['type']) {
    case 'im_chat':
      openChat(data);
      break;
    case 'platform_wallet_notice':
      openWalletNotice(data);
      break;
    case 'announcement':
      openAnnouncement(data);
      break;
    case 'register_welcome':
      openMessengerChat(); // c2c_99Messenger
      break;
    case 'av_call':
      handleIncomingCall(data); // §10，仅 iOS PushKit 路径
      break;
    default:
      openHome();
  }
}
```

---

### 9.2 聊天离线 Push — `type: im_chat`

**触发**：对方发 IM 消息后，服务端经腾讯 IM 回调解析并 Push（用户离线时）。

**Payload 字段**

| 字段 | 说明 |
|------|------|
| `type` | 固定 `im_chat` |
| `chatType` | `c2c` / `group` |
| `fromAccount` | 发送者 IM userId |
| `groupId` | 群聊时有值 |
| `msgKey` | IM 消息唯一键（去重用） |
| `avatarUrl` | 头像 URL（单聊=发送者头像，群聊=群头像；可能为空） |

**头像规则**

| chatType | `avatarUrl` 含义 |
|----------|------------------|
| `c2c` | 发送者头像（优先 IM `Tag_Profile_IM_Image`，否则库内 `avatar_url`） |
| `group` | 群头像（腾讯 IM 群 `FaceUrl`） |

**客户端展示头像**

- **iOS**：须实现 Notification Service Extension；服务端在含 `avatarUrl` 时已设 `aps.mutable-content = 1`，Extension 下载 `avatarUrl` 并挂 `UNNotificationAttachment`。
- **Android（极光）**：`extras.avatarUrl` 同时用于 `large_icon`；也可在回调里自行下载设大图标。

**通知栏展示（服务端已格式化）**

| 场景 | title | body |
|------|-------|------|
| 单聊 | 发送者昵称 | 消息摘要 |
| 群聊 | 群名称 | 普通消息：`发送者: 摘要`；**纯群 Tips** 消息：仅摘要（无发送者前缀） |

**摘要规则**

| 消息类型 | 摘要 |
|----------|------|
| 文本 | 原文 |
| 图片 | `[图片]` |
| 语音 | `[语音]` |
| 视频 | `[视频]` |
| 文件 | `[文件]` 或 `[文件] 文件名` |
| 表情 | `[表情]` |
| 位置 | `[位置]` |
| 合并转发 | `[合并转发]` 或 `[合并转发] 标题` |
| 群 Tips（非静默） | 完整群提示，如「张三邀请李四加入群组」 |
| 转账 `wallet_transfer` | `[转账] 转账给你` / `[转账] 转账给 {昵称}` |
| 红包 `wallet_red_packet` | `[红包]` 或 `[红包] {祝福语}` |
| 名片 `contact_card` | `[个人名片] {昵称}` |
| 群创建 `group_create` | `{操作者}创建了群聊「{群名}」` |
| 其他自定义 | Desc 或 `text`/`title` 或 `[type]` |

**不会发聊天 Push 的消息**（服务端自动跳过）：

- 系统号：`99Messenger` / `99Chat`（已有独立业务 Push）
- 信令：`av_call`、`rtc_call`（VoIP / IM 信令单独处理）
- 业务卡片：`platform_wallet_notice`、`announcement`、`register_welcome`、`wallet_order`
- 静默类：`user_typing_status`、`red_packet_claim_notice`、`friend_became_friends`
- 静默群 Tips：改群名/头像/公告/简介、全员禁言、设/取消管理员

**点击跳转**

| chatType | 目标会话 |
|----------|----------|
| `c2c` | `c2c_{fromAccount}` |
| `group` | `group_{groupId}` |

**APNs 示例（结构示意）**

```json
{
  "aps": {
    "alert": { "title": "张三", "body": "你好" },
    "sound": "default",
    "mutable-content": 1
  },
  "type": "im_chat",
  "chatType": "c2c",
  "fromAccount": "abc12def34",
  "avatarUrl": "https://cdn.example.com/user-avatar/abc12/preview.jpg",
  "msgKey": "1224486638_2071424560_1780039003"
}
```

> `mutable-content` 仅在有 `avatarUrl` 时下发，供 iOS Extension 下载头像。

**极光 extras 示例**

```json
{
  "type": "im_chat",
  "chatType": "group",
  "fromAccount": "abc12def34",
  "groupId": "@TGS#xxx",
  "avatarUrl": "https://cdn.example.com/group-avatar/@TGS%23xxx/preview.jpg",
  "msgKey": "1224486638_2071424560_1780039003"
}
```

> 极光 Android 通知同时带 `large_icon` = `avatarUrl`（有值时）。

> 无头像时不下发 `avatarUrl` 与 `mutable-content`，客户端用默认图。

---

### 9.3 公告 Push — `type: announcement`

同时会收到 `99Messenger` 会话内的 IM 自定义消息。

| 字段 | 说明 |
|------|------|
| `type` | `announcement` |
| `announcementId` | 公告 ID |
| `scope` | `GLOBAL` / `PERSONAL` |

跳转公告详情或列表页。

---

### 9.4 钱包 Push — `type: platform_wallet_notice`

同时会收到 `99Chat` 会话内的 IM 自定义消息（`businessID: platform_wallet_notice`）。

| 字段 | 说明 |
|------|------|
| `type` | `platform_wallet_notice` |
| `noticeType` | `deposit` / `withdraw` / `flashExchange` 等 |
| `orderId` | 关联订单号 |

IM 消息完整字段见 [platform-wallet-notice-client.md](./platform-wallet-notice-client.md)。

---

### 9.5 注册欢迎 — `type: register_welcome`

| 字段 | 说明 |
|------|------|
| `type` | `register_welcome` |

无其它必填字段。跳转 `c2c_99Messenger` 欢迎会话。同时会收到 IM 文本欢迎消息。

---

## 10. 音视频来电推送

音视频来电有 **两条独立路径**，客户端须同时实现。

```
                    主叫发起 TUICallKit 通话
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
     被叫 IM 在线（含 Android 保活）    被叫 iOS 离线
              │                               │
              ▼                               ▼
     IM 自定义消息 av_call 信令         PushKit VoIP Push
              │                               │
              └───────────────┬───────────────┘
                              ▼
                    TUICallKit 展示来电 UI
```

| 平台 | 在线 | 离线 |
|------|------|------|
| iOS | IM `av_call` 信令 | PushKit VoIP Push（须上报 `voipToken`） |
| Android | IM `av_call` 信令 | 仅 IM 信令（需长连接保活；**无 VoIP Push**） |

---

### 10.1 iOS VoIP Push Payload

**不走通知栏**，直接唤醒 App。由 PushKit delegate 接收。

```json
{
  "aps": { "content-available": 1 },
  "type": "av_call",
  "inviteId": "d6d41bff0508da001befd65deb4032bf",
  "callerId": "s9q2qry8lg",
  "callerName": "哒哒的西瓜🍉1",
  "callerAvatarUrl": "https://cdn.example.com/user-avatar/s9q2qry8lg/preview.jpg",
  "calleeId": "cxsxi5d22l",
  "mediaType": "audio",
  "roomId": "854118195"
}
```

| 字段 | 说明 |
|------|------|
| `type` | 固定 `av_call` |
| `inviteId` | 邀请 ID，**去重键**（同一 inviteId 只处理一次） |
| `callerId` | 主叫 userId |
| `callerName` | 主叫展示名（CallKit `localizedCallerName`）：**被叫给主叫的好友备注名优先**，否则平台真实昵称 |
| `callerAvatarUrl` | 主叫头像 URL（供 TUICallKit 自定义 UI；CallKit 系统层不直接显示网络图） |
| `calleeId` | 被叫 userId（当前登录用户） |
| `mediaType` | `audio`（语音）/ `video`（视频） |
| `roomId` | TRTC 房间号（可选） |

**处理要求**

1. 在 PushKit delegate 收到，**禁止**弹普通通知栏
2. 尽快交给 TUICallKit / TRTC 展示 CallKit 来电 UI
3. 用 `inviteId` 去重，避免重复弹来电
4. VoIP Push **仅用于真实来电**，勿用于聊天 / 营销

**前置条件**：已 `POST /me/push-token` 且带 `voipToken`（或 `POST /me/voip-push-token`）。

---

### 10.2 IM `av_call` 信令（全平台必接）

无论是否使用系统 Push，**所有平台**都须监听 IM 自定义消息中的 TUICallKit 来电信令。Android 来电**只走此路径**。

消息类型：`TIMCustomElem`，`MsgContent.Data` 为 **JSON 字符串**。

#### 外层 envelope

```json
{
  "actionType": 1,
  "businessID": 1,
  "data": "<内层 av_call JSON 字符串>",
  "inviteID": "d6d41bff0508da001befd65deb4032bf",
  "inviteeList": ["被叫userId"],
  "inviter": "主叫userId",
  "onlineUserOnly": false,
  "timeout": 30
}
```

#### 内层 `data`（将外层 `data` 字段解析为 JSON 后）

```json
{
  "businessID": "av_call",
  "call_end": 0,
  "call_type": 1,
  "data": {
    "cmd": "audioCall",
    "inviter": "主叫userId",
    "room_id": 854118195,
    "userIDs": ["被叫userId"]
  },
  "room_id": 854118195,
  "version": 4
}
```

#### 关键字段

| 字段 | 说明 |
|------|------|
| `actionType` | `1` = 来电邀请；`2` = 拒接；`3` = 取消 |
| `call_end` | `0` = 进行中；`1` 取消 / `2` 拒接 / `3` 未接 / `4` 已接 / `5` 挂断 |
| `call_type` | `1` = 语音 → `mediaType: audio`；`2` = 视频 → `video` |
| `inviteID` | 对应 VoIP Push 的 `inviteId` |
| `inviter` | 主叫 |
| `inviteeList` / `userIDs` | 被叫 |
| `data.cmd` | `audioCall` / `videoCall` / `accept` / `reject` / `cancel` 等 |

#### 何时展示来电 UI

```dart
bool isIncomingInvite(Map<String, dynamic> envelope, Map<String, dynamic> av) {
  final actionType = envelope['actionType'] as int? ?? 0;
  final callEnd = av['call_end'] as int? ?? 0;
  return actionType == 1 && callEnd == 0;
}
```

满足上述条件 → 交给 TUICallKit 展示来电界面。

#### 字段映射（IM 信令 → 统一模型）

| 统一字段 | IM 来源 |
|----------|---------|
| `inviteId` | `inviteID` |
| `callerId` | `inviter` |
| `calleeId` | `inviteeList[0]` 或 `data.userIDs[0]` |
| `mediaType` | `call_type == 2` → `video`，否则 `audio` |
| `roomId` | `room_id`（字符串化） |

IM 信令与 iOS VoIP Push payload 字段一致，**复用同一套解析与 UI 逻辑**。

---

### 10.3 音视频统一处理伪代码

```dart
/// 入口 1：iOS PushKit（仅 iOS）
void onVoipPush(Map<String, dynamic> payload) {
  if (payload['type'] != 'av_call') return;
  handleIncomingCall(IncomingCall.fromPush(payload));
}

/// 入口 2：IM 自定义消息（iOS + Android）
void onImCustomMessage(String dataJson) {
  final envelope = jsonDecode(dataJson);
  final av = jsonDecode(envelope['data'] as String);
  if (av['businessID'] != 'av_call') return;
  if (!isIncomingInvite(envelope, av)) return;
  handleIncomingCall(IncomingCall.fromIm(envelope, av));
}

/// 统一去重 + 展示
final _handledInvites = <String>{};

void handleIncomingCall(IncomingCall call) {
  if (_handledInvites.contains(call.inviteId)) return;
  _handledInvites.add(call.inviteId);
  TUICallKit.instance.showIncomingCall(
    callerId: call.callerId,
    mediaType: call.mediaType,
    roomId: call.roomId,
  );
}
```

---

### 10.4 音视频排查

| 现象 | 检查项 |
|------|--------|
| iOS 离线来电不唤醒 | 是否上报 `voipToken`；Background VoIP 是否开启；Bundle ID 是否 `chat.99chat.app` |
| iOS 在线有信令、离线无唤醒 | VoIP token 是否过期未重报；Debug 包是否匹配 `PUSH_APNS_PRODUCTION=false` |
| Android 来电无界面 | IM 是否连着；`av_call` 监听是否实现；保活是否被系统杀死 |
| 重复来电 | 正常；按 `inviteId` 去重；服务端也按 inviteId 去重 VoIP Push |
| `DeviceTokenNotForTopic` | iOS Bundle ID 与后端证书不一致 |

---

## 11. Flutter 集成参考

### 11.1 启动时序

```dart
Future<void> onLoginSuccess(String jwt, String deviceId) async {
  // 1. IM 登录
  await TencentImSDK.login(userId, userSig);

  // 2. 注册 IM 监听器（聊天 + av_call + 业务自定义消息）
  setupImListeners();

  // 3. 平台 Push
  if (Platform.isIOS) {
    final apnsToken = await getApnsToken();
    final voipToken = await getPushKitVoipToken();
    await api.post('/me/push-token', {
      'deviceId': deviceId,
      'platform': 'IOS',
      'token': apnsToken,
      'voipToken': voipToken,
    });
  } else if (useJPush) {
    final regId = await JPush.setup();
    await api.post('/me/push-token', {
      'deviceId': deviceId,
      'platform': 'ANDROID',
      'token': regId,
    });
  } else {
    // 方案 B：保活
    await startForegroundService();
    startHeartbeatTimer();
  }
}
```

### 11.2 IM 监听器（方案 B 必做，方案 A 也建议）

```dart
void setupImListeners() {
  // 新消息 → 后台时弹本地通知
  TencentImSDK.onRecvNewMessage = (msg) {
    if (AppLifecycleState.resumed == currentState) return;
    if (isAvCallMessage(msg)) {
      onImCustomMessage(msg.customElem.data);
      return;
    }
    if (isBusinessMessage(msg)) {
      showLocalNotificationFromIm(msg);
      return;
    }
    showChatLocalNotification(msg);
  };

  // 自定义消息（钱包 / 公告 / av_call）
  TencentImSDK.onRecvCustomMessage = (msg) {
    final data = msg.customElem.data;
    if (data.contains('av_call')) {
      onImCustomMessage(data);
    } else if (data.contains('platform_wallet_notice')) {
      if (!isForeground) showWalletLocalNotification(data);
    }
  };
}
```

### 11.3 退出登录

```dart
Future<void> onLogout(String deviceId) async {
  if (Platform.isIOS || useJPush) {
    await api.delete('/me/push-token', {'deviceId': deviceId});
  }
  await TencentImSDK.logout();
  stopForegroundService();
}
```

---

## 12. 与 IM 在线消息协同

> **进会话后仍弹残留通知**：见专项整改 [push-stale-notification-fix.md](./push-stale-notification-fix.md)（P0 客户端清通知 + P1 服务端 collapse-id 等）。

### 12.1 去重规则

| 场景 | 做法 |
|------|------|
| 聊天 Push + IM 在线 | IM 收到 `msgKey` 相同消息时，**取消**对应系统通知 |
| 音视频 | `inviteId` 相同只处理一次（PushKit 与 IM 信令可能双通道到达） |
| App 前台 | **全部** `im_chat` 由 IM UI 展示，suppress 系统通知栏；启动/resumed 时**清除全部** `im_chat` 通知（见 [push-stale-notification-fix.md](./push-stale-notification-fix.md)） |

### 12.2 双通道到达顺序（音视频）

```
典型 iOS 离线场景：
  1. PushKit VoIP Push 先到 → 唤醒 App → 展示来电
  2. IM 信令随后到达     → inviteId 去重 → 忽略

典型 Android 场景：
  仅 IM 信令 → 保活连着则直接展示来电
```

---

## 13. 集成检查清单

| # | 项 | iOS | Android A | Android B |
|---|----|-----|-----------|-----------|
| 1 | 关闭 IM offline push | ✓ | ✓ | ✓ |
| 2 | `POST /me/push-token` | ✓ | ✓ | — |
| 3 | token 刷新重报 | ✓ | ✓ | — |
| 4 | `DELETE /me/push-token` 退出 | ✓ | ✓ | — |
| 5 | 实现 `type` 路由 | ✓ | ✓ | ✓ |
| 6 | 聊天 `msgKey` 去重 | ✓ | ✓ | ✓ |
| 7 | PushKit + `voipToken` | ✓ | — | — |
| 8 | IM `av_call` 监听 | ✓ | ✓ | ✓ |
| 9 | TUICallKit 来电 UI | ✓ | ✓ | ✓ |
| 10 | `inviteId` 去重 | ✓ | ✓ | ✓ |
| 11 | 前台服务保活 | — | — | ✓ |
| 12 | IM → 本地通知 | — | — | ✓ |
| 13 | TCP `ping` / `POST /me/heartbeat` 回退 | 可选 | 可选 | ✓ |

---

## 14. 排查

| 现象 | 客户端检查 |
|------|------------|
| 完全收不到 Push | 是否登录后 `/me/push-token`；通知权限；token 是否被禁用需重报 |
| 仅 iOS 无 Push | APNs token 格式；Debug/Release 证书环境 |
| 仅 Android 无 Push（方案 A） | `registrationId` 是否完整；厂商通道；极光 AppKey 是否一致 |
| Android 无通知（方案 B） | 前台服务是否存活；IM 是否断线；本地通知权限 |
| 有钱包 IM 无通知栏 | 方案 B 须实现 IM → 本地通知；方案 A 检查 token |
| 有聊天 IM 无通知栏 | 同上；或发送方为系统号被服务端跳过 |
| 在线重复弹通知 | `msgKey` 去重；前台 suppress |
| iOS 离线来电不唤醒 | `voipToken`；PushKit delegate；VoIP Background Mode |
| Android 来电无界面 | IM 保活；`av_call` 解析；`actionType==1 && call_end==0` |
| 点击通知无跳转 | `data.type` 路由是否覆盖全部类型 |
| `INVALID_PLATFORM` 报 400 | `platform` 必须为 `IOS` 或 `ANDROID` 大写枚举 |

---

## 15. 关联文档

| 文档 | 内容 |
|------|------|
| [push-backend-checklist.md](./push-backend-checklist.md) | 后端实现清单与联调 |
| [voip-push-client.md](./voip-push-client.md) | iOS VoIP 速查（指向本文 §10） |
| [platform-wallet-notice-client.md](./platform-wallet-notice-client.md) | 钱包 IM 消息字段 |
| [im-chat-push-callback.md](./im-chat-push-callback.md) | 服务端 IM 回调与运维配置 |
| [push-stale-notification-fix.md](./push-stale-notification-fix.md) | 进会话后残留 Push 整改 + 商业闭环（§11） |
| [system-notify-and-announcements.md](./system-notify-and-announcements.md) | 公告与 99Messenger |
| [registration-and-login.md](./registration-and-login.md) | `deviceId` 与登录流程 |
| [notification-settings-client.md](./notification-settings-client.md) | 通知设置（系统消息 / 来电 / 展示内容） |
| [call-recent-and-webhook.md](./call-recent-and-webhook.md) | TUICallKit 通话回调 |
