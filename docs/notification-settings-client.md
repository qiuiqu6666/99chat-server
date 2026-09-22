# 通知设置 — 客户端对接文档

> 版本：v1.0（2026-06-19）  
> 适用：99chat Flutter 客户端 + 99chat-server  
> Base URL：`http://47.239.60.107:8081`（以部署为准）  
> 关联：[push-client.md](./push-client.md) · [voip-push-client.md](./voip-push-client.md)

---

## 1. 概述

服务端持久化用户**通知偏好**，并在下发系统 Push 时生效：

| 设置页 UI（未打开时） | 后端字段 | 作用范围 |
|----------------------|----------|----------|
| 系统消息通知 | `systemMessageNotificationEnabled` | 聊天 / 群聊 / 公告 / 钱包等 **系统通知栏 Push** |
| 语音和视频通话通知 | `callNotificationEnabled` | **iOS VoIP 来电 Push**（Android 来电仍走 IM 信令，见 [push-client.md](./push-client.md)） |
| 通知显示内容 | `notificationDisplayContent` | 系统通知栏 **title / body 文案** 脱敏策略 |

> **「打开时」** 的消息横幅、提示音、通话弹窗快捷接听等属于 **App 内本地 UI**，本期 **不由后端存储**；客户端自行用 SharedPreferences / 本地配置即可。

---

## 2. 鉴权与响应格式

| 项 | 说明 |
|----|------|
| 鉴权 | `Authorization: Bearer <JWT>` |
| Content-Type（PUT） | `application/json` |
| 成功响应 | `{ "code": 0, "message": "ok", "data": { ... } }` |
| 失败响应 | `{ "code": "<错误码>", "message": "<同 code>" }` |

---

## 3. API

### 3.1 读取当前设置

```http
GET /me/notification-settings
Authorization: Bearer <JWT>
```

**200 响应 `data`：**

```json
{
  "systemMessageNotificationEnabled": true,
  "callNotificationEnabled": true,
  "notificationDisplayContent": "show_all"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `systemMessageNotificationEnabled` | boolean | 是否接收系统消息类 Push（默认 `true`） |
| `callNotificationEnabled` | boolean | 是否接收音视频来电 Push（默认 `true`） |
| `notificationDisplayContent` | string | 通知栏展示策略，见 §4 |

---

### 3.2 更新设置

```http
PUT /me/notification-settings
Authorization: Bearer <JWT>
Content-Type: application/json
```

**Body（部分更新，只传要改的字段）：**

```json
{
  "systemMessageNotificationEnabled": false,
  "callNotificationEnabled": true,
  "notificationDisplayContent": "generic"
}
```

**200 响应 `data`：** 与 GET 相同结构，返回**更新后**的完整快照。

| HTTP | code | 说明 |
|------|------|------|
| 401 | UNAUTHORIZED | JWT 缺失或过期 |
| 404 | USER_NOT_FOUND | 用户不存在或已禁用 |
| 400 | INVALID_INPUT | JSON 非法或 `notificationDisplayContent` 枚举值无效 |

---

## 4. `notificationDisplayContent` 枚举

与设置页「通知显示内容」三项一一对应：

| 值 | 设置页文案 | 系统通知栏展示（服务端下发） |
|----|-----------|------------------------------|
| `show_all` | 显示朋友名称、群聊名及消息内容 | **默认**。title = 好友昵称 / 群名；body = 消息摘要 |
| `generic` | 仅显示「你收到了一条消息」 | title = `99chat`；body = `你收到了一条消息` |
| `hidden` | 隐藏朋友名称、群聊名及消息内容 | title = `99chat`；body = 空字符串 |

### 4.1 重要说明

1. **脱敏仅影响通知栏可见文字**（APNs / 极光 alert）。Push payload 里的 `data`（如 `type`、`msgKey`、`groupId`、`fromAccount`）**仍完整保留**，客户端点击通知后可正常路由跳转。
2. **`notificationDisplayContent` 不影响 VoIP 来电 Push** 的主叫展示；来电是否下发由 `callNotificationEnabled` 控制。
3. 老用户未设置过时，三项默认值：`true` / `true` / `show_all`。

---

## 5. 与服务端 Push 的对应关系

```
用户收到离线消息
    ↓
服务端准备 Push（title/body + data）
    ↓
systemMessageNotificationEnabled == false  →  不下发，结束
    ↓
按 notificationDisplayContent 替换 title/body（show_all 则原样）
    ↓
APNs / 极光 → 系统通知栏
```

| Push 类型 | `data.type` 示例 | 受 `systemMessageNotificationEnabled` | 受 `notificationDisplayContent` |
|-----------|------------------|--------------------------------------|----------------------------------|
| 单聊 / 群聊 | `im_chat` | ✅ | ✅ |
| 公告 | `announcement` | ✅ | ✅ |
| 钱包通知 | `wallet_*` 等 | ✅ | ✅ |
| 好友列表变更等 | 各业务 type | ✅ | ✅ |
| iOS 音视频来电 | VoIP payload | — | —（看 `callNotificationEnabled`） |

详细 Push 字段与路由见 [push-client.md §9、§10](./push-client.md)。

---

## 6. 客户端集成建议

### 6.1 进入「通知」设置页

```dart
Future<NotificationSettings> loadSettings() async {
  final res = await dio.get('/me/notification-settings');
  final d = res.data['data'] as Map<String, dynamic>;
  return NotificationSettings(
    systemMessageEnabled: d['systemMessageNotificationEnabled'] as bool,
    callEnabled: d['callNotificationEnabled'] as bool,
    displayContent: NotificationDisplayContent.parse(
      d['notificationDisplayContent'] as String,
    ),
  );
}
```

### 6.2 切换开关 / 选择展示内容

**推荐：用户操作后立即 PUT**，不必等离开页面。

```dart
Future<void> setSystemMessageEnabled(bool enabled) async {
  await dio.put('/me/notification-settings', data: {
    'systemMessageNotificationEnabled': enabled,
  });
}

Future<void> setCallNotificationEnabled(bool enabled) async {
  await dio.put('/me/notification-settings', data: {
    'callNotificationEnabled': enabled,
  });
}

Future<void> setDisplayContent(NotificationDisplayContent mode) async {
  await dio.put('/me/notification-settings', data: {
    'notificationDisplayContent': mode.apiValue, // show_all | generic | hidden
  });
}
```

### 6.3 UI 映射示例

```dart
enum NotificationDisplayContent {
  showAll('show_all'),
  generic('generic'),
  hidden('hidden');

  const NotificationDisplayContent(this.apiValue);
  final String apiValue;

  static NotificationDisplayContent parse(String raw) =>
      NotificationDisplayContent.values.firstWhere(
        (e) => e.apiValue == raw,
        orElse: () => NotificationDisplayContent.showAll,
      );
}
```

设置页底部弹窗三选一 → 选中项调用 `setDisplayContent(...)`。

### 6.4 登录后

可选：登录成功拉一次 GET，缓存到内存，供调试页展示；**不必**在本地再维护一套默认值，以服务端为准。

### 6.5 与 OS 通知权限

| 层级 | 说明 |
|------|------|
| OS 通知权限 | 系统级；用户拒绝则无论后端开关如何都不弹通知 |
| `systemMessageNotificationEnabled` | 业务级；用户 App 内关闭后，服务端不再下发 Push |
| 会话免打扰 | 单聊/群维度，见 `POST /me/conversation-notify`（[push-client.md](./push-client.md)） |

三者独立：**全局关 ≠ 单会话免打扰**。

---

## 7. 「打开时」设置（纯客户端）

以下项 **无需调后端**，本地存储即可：

| 设置页 UI | 建议 |
|-----------|------|
| 消息横幅 | App 前台收到 IM 消息时是否展示 in-app banner |
| 消息提示音 | App 前台是否播放提示音 |
| 语音和视频通话用弹窗快捷接听 | 来电 in-app 浮层 |

若未来需要多端同步，再扩展后端字段。

---

## 8. 联调检查清单

- [ ] 进入通知页调用 `GET /me/notification-settings` 渲染初始状态
- [ ] 关闭「系统消息通知」后，另一账号发消息，**不应**收到系统 Push（聊天 IM 仍在线可达）
- [ ] 关闭「语音和视频通话通知」后，iOS 被叫 **不应**收到 VoIP Push
- [ ] `notificationDisplayContent = generic` 时，通知栏仅见 `99chat` + `你收到了一条消息`，点击仍能进对应会话
- [ ] `notificationDisplayContent = hidden` 时，通知栏仅见 App 名、无消息预览
- [ ] 切换展示内容后 **无需重启 App**，下一条 Push 即生效
- [ ] 401 时跳转登录

---

## 9. 关联文档

| 文档 | 内容 |
|------|------|
| [push-client.md](./push-client.md) | Push Token、离线消息、VoIP、payload 路由 |
| [voip-push-client.md](./voip-push-client.md) | iOS VoIP 速查 |
| [push-stale-notification-fix.md](./push-stale-notification-fix.md) | 进会话后残留通知处理 |
