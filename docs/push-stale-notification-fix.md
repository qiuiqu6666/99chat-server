# 聊天离线 Push 残留通知 — 整改方案

> 版本：v1.3  
> 更新：2026-06-18  
> **服务端**：P1 collapse-id、P3 push-focus 已实现（2026-06-18）
> 适用：99chat Flutter 客户端 + 99chat-server  
> 关联：[push-client.md](./push-client.md)、[im-chat-push-callback.md](./im-chat-push-callback.md)

---

## 1. 问题描述

### 1.1 现象

用户离线期间收到多条聊天 Push（例如 1–9 条）。用户看到第 3 条通知后点击进入 App 并打开对应会话，但第 4–9 条 Push 仍会继续出现在系统通知栏。

### 1.2 根因

| 原因 | 说明 |
|------|------|
| Push 不可撤回 | 消息一旦发往 APNs / 极光，服务端无法取消已在队列或已送达的通知 |
| 每条消息独立 Push | 腾讯 IM `AfterSendMsg` 回调逐条触发，服务端逐条下发 |
| 单聊 collapse-id 按消息 | 当前 `apns-collapse-id = msgKey`，每条通知 ID 不同，iOS 不会合并/覆盖 |
| 服务端不知「正在看哪个会话」 | 默认 `chat_push_skip_when_online=false`，用户已进 App 仍会继续发 Push |

### 1.3 目标

- 用户**已打开 App（启动 / 回前台）**后，**所有会话**的聊天离线 Push **立即清除且不再展示**（多会话并行离线时同样适用）
- 用户**已进入某会话**后，该会话残留/后续 Push **不再打扰**（按会话清理作双保险）
- 用户**未进 App** 时，同一会话通知栏**不堆叠多条**（只保留最新一条或聚合摘要）
- **不清除**非聊天类通知（钱包、好友申请、公告等 `type != im_chat`）
- 改动尽量小，可分阶段上线

### 1.4 多会话场景（为何必须「启动即清全部 im_chat」）

用户离线时可能同时收到：

- 会话 A：9 条 Push  
- 会话 B：3 条 Push  
- 会话 C：1 条 Push  

若只在「进入会话 A」时清理，B/C 的 Push 仍会在 App 已打开后继续弹出。  
因此：**App 一旦进入前台，就应清除全部 `type=im_chat` 的通知**；IM SDK 会通过漫游补全所有会话消息，无需依赖通知栏。

---

## 2. 方案总览

采用**三层防护**，按优先级实施：

```
┌─────────────────────────────────────────────────────────────┐
│ P0 客户端：App 启动/前台 → 清除全部 im_chat 通知             │  ← 必做，覆盖多会话并行离线
│           + 前台 suppress 聊天 Push + IM 按 msgKey 取消      │
├─────────────────────────────────────────────────────────────┤
│ P1 服务端：单聊 collapse-id 改为按会话（c2c_xxx）            │  ← 小改动，减少通知堆积
├─────────────────────────────────────────────────────────────┤
│ P2 配置：开启在线跳过 + App 前台立即心跳                     │  ← 减少后续新发 Push
├─────────────────────────────────────────────────────────────┤
│ P3 可选：PUT /me/push-focus 上报当前会话                     │  ← 最精准，需新接口
└─────────────────────────────────────────────────────────────┘
```

| 阶段 | 负责方 | 预期效果 |
|------|--------|----------|
| P0 | 客户端 | App 已打开时，任意会话的残留/后续聊天 Push 均不打扰 |
| P1 | 服务端 | 未进 App 时同会话只显示最新一条（iOS 覆盖；Android 归组） |
| P2 | 客户端 + 运维 | 用户已打开 App 后，新消息不再发 Push |
| P3 | 前后端 | 用户正在看某会话时，服务端直接跳过该会话 Push |

---

## 3. P0 — 客户端改造（必做）

> 参考 [push-client.md §12.1](./push-client.md#121-去重规则)，本节给出可落地的实现步骤。

### 3.1 约定：threadId 与 msgKey

与服务端 Push payload 保持一致：

| 场景 | `threadId`（通知分组键） | `msgKey`（单条去重键） |
|------|--------------------------|------------------------|
| 单聊 | `c2c_{fromAccount}` | IM `MsgKey` |
| 群聊 | `group_{groupId}` | IM `MsgId` / `MsgSeq` |

`fromAccount` = 对方 userId（单聊时 payload 里的 `fromAccount` 即发送者）。

### 3.2 通知 ID 规划

**推荐**：用 `msgKey` 的稳定 hash 作为 `notificationId`，便于按条取消。

```dart
int notificationIdFor(String msgKey) {
  return msgKey.hashCode & 0x7fffffff; // 非负 int
}
```

### 3.3 时机一（核心）：App 启动 / 回前台 → 清除**全部** `im_chat` 通知

> **原则**：用户已打开 App，聊天内容以 IM 漫游为准，通知栏里的聊天 Push 全部作废。  
> **注意**：只清 `type == im_chat`，**不要** `cancelAll()`，否则会误删钱包/好友申请等通知。

```dart
/// 清除通知栏中所有聊天类离线 Push（跨会话）
Future<void> clearAllImChatNotifications() async {
  // 1) 本地登记表：取消本 App 展示过的 im_chat 通知
  for (final id in NotificationRegistry.allImChatIds()) {
    await flutterLocalNotificationsPlugin.cancel(id);
  }
  NotificationRegistry.clearAllImChat();

  // 2) iOS：移除已送达的 im_chat 通知（含 APNs 系统展示的）
  final center = UNUserNotificationCenter.current();
  final delivered = await center.getDeliveredNotifications();
  final toRemove = <String>[];
  for (final n in delivered) {
    final data = n.request.content.userInfo;
    if (data['type'] == 'im_chat') {
      toRemove.add(n.request.identifier);
    }
  }
  if (toRemove.isNotEmpty) {
    await center.removeDeliveredNotificationsWithIdentifiers(toRemove);
  }

  // 3) Android：若用 NotificationChannel + group，按 group 前缀清理
  //    group = c2c_* / group_* 的 im_chat 条目；勿清其他 channel
}
```

**调用点**（缺一不可）：

| 时机 | 动作 |
|------|------|
| 冷启动完成、IM SDK `login` 成功后 | `clearAllImChatNotifications()` |
| `AppLifecycleState.resumed`（从后台回前台） | `clearAllImChatNotifications()` + TCP `ping`（无 TCP 则 `POST /me/heartbeat`） |
| 用户点击**任意**聊天通知进入 App | 先 `clearAllImChatNotifications()`，再跳转目标会话 |

### 3.4 时机二：进入聊天页 → 清除该会话通知（双保险）

在 §3.3 已清全部 `im_chat` 的前提下，进入某会话可再按 `threadId` 清一次，防止边界漏网：

```dart
Future<void> clearChatNotifications({
  required String chatType,
  required String peerOrGroupId,
}) async {
  final threadId = chatType == 'c2c'
      ? 'c2c_$peerOrGroupId'
      : 'group_$peerOrGroupId';

  for (final id in NotificationRegistry.idsForThread(threadId)) {
    await flutterLocalNotificationsPlugin.cancel(id);
  }
  NotificationRegistry.clearThread(threadId);

  // iOS：按 threadId 再扫一遍 delivered
  // ...
}
```

**调用点**：`ChatPage.initState` / `onResume`。

### 3.5 时机三：IM SDK 收到新消息 → 按 msgKey 取消对应通知

```dart
void onTencentImNewMessage(V2TimMessage msg) {
  final msgKey = msg.msgID; // 或与服务端一致的 MsgKey 字段
  final id = notificationIdFor(msgKey);
  await flutterLocalNotificationsPlugin.cancel(id);
  NotificationRegistry.remove(id);

  // App 在前台：聊天消息由 IM UI 展示，不弹通知栏（任意会话均 suppress）
  if (isAppForeground) {
    return;
  }
  // ...
}
```

**漫游拉取 / 历史同步**时同样处理：凡 IM 已拥有的 `msgKey`，对应系统通知都应 cancel。

### 3.6 时机四：App 生命周期统一入口

```dart
void onAppLifecycle(AppLifecycleState state) {
  if (state == AppLifecycleState.resumed) {
    // 1. 清除全部聊天离线通知（多会话场景关键）
    clearAllImChatNotifications();

    // 2. 心跳（配合 P2 服务端在线跳过，减少后续新发 Push）
    api.post('/me/heartbeat', {'deviceId': deviceId});

    // 3. 若已在聊天页，按会话再清一次（双保险）
    if (currentChat != null) {
      clearChatNotifications(
        chatType: currentChat!.type,
        peerOrGroupId: currentChat!.id,
      );
    }
  }
}
```

### 3.7 收到系统 Push 时的展示逻辑

```dart
void onPushReceived(Map<String, String> data) {
  if (data['type'] != 'im_chat') return;

  // App 在前台：所有聊天 Push 均不展示（不限于当前会话）
  if (isAppForeground) {
    // 可选：直接 cancel，避免角标累加
    final msgKey = data['msgKey'];
    if (msgKey != null) {
      flutterLocalNotificationsPlugin.cancel(notificationIdFor(msgKey));
    }
    return;
  }

  // 展示时登记 notificationId，便于后续 cancel
  final msgKey = data['msgKey']!;
  final threadId = data['chatType'] == 'c2c'
      ? 'c2c_${data['fromAccount']}'
      : 'group_${data['groupId']}';
  final id = notificationIdFor(msgKey);
  NotificationRegistry.register(threadId, id);

  showNotification(id: id, title: ..., body: ..., payload: data);
}
```

### 3.8 清理范围对照

| `data.type` | App 启动/前台是否清除 | 说明 |
|-------------|----------------------|------|
| `im_chat` | ✅ 清除 | 聊天离线 Push，IM 漫游可补 |
| `platform_wallet_notice` | ❌ 保留 | 用户可能仍需在通知栏查看 |
| `friend_request` | ❌ 保留 | 需用户主动处理 |
| `announcement` | ❌ 保留 | 运营类通知 |
| VoIP 来电 | ❌ 不走此逻辑 | PushKit 单独处理 |

### 3.9 客户端检查清单

| # | 项 | 完成 |
|---|----|------|
| 1 | **App 启动 / resumed 调用 `clearAllImChatNotifications`** | ☐ |
| 2 | 进入聊天页调用 `clearChatNotifications`（双保险） | ☐ |
| 3 | IM `onRecvNewMessage` 按 `msgKey` cancel | ☐ |
| 4 | IM 漫游同步后批量 cancel 已有消息 | ☐ |
| 5 | App `resumed` 时发 heartbeat | ☐ |
| 6 | **前台 suppress 全部 `im_chat` Push**（不限当前会话） | ☐ |
| 7 | 清理时**不**误删非 `im_chat` 通知 | ☐ |
| 8 | `notificationId` 与 `msgKey` 映射可追踪 | ☐ |

---

## 4. P1 — 服务端改造（单聊 collapse-id）✅ 已实现

### 4.1 改动说明

**文件**：`src/main/java/com/chat99/server/im/ImChatPushCallbackService.java`

**方法**：`buildChatMessage`

**现状**（每条消息独立 collapse-id）：

```java
return message.withApnsGrouping(msgKey, threadId);
// collapseId = msgKey  →  9 条消息 = 9 个独立通知
```

**已改为**（按会话 collapse）：

```java
return message.withApnsGrouping(threadId, threadId);
// collapseId = c2c_xxx / group_xxx  →  同会话新 Push 覆盖旧 Push
```

`msgKey` 仍保留在 `data` 中，客户端点击跳转与去重不受影响。

### 4.2 影响范围

| 平台 | 行为变化 |
|------|----------|
| iOS APNs | 同一会话仅保留最新一条通知（`apns-collapse-id` 相同则覆盖） |
| Android 极光 | `android.group` 已是 `threadId`，通知归组；配合 P0 客户端清理更稳 |
| 群聊 | 聚合服务 `GroupPushAggregationService` 已用 `group_{groupId}` 作 collapse，**无需改** |

### 4.3 部署步骤

```bash
# 1. 修改 ImChatPushCallbackService.buildChatMessage 一行
# 2. 编译部署
mvn -q -DskipTests package
# 3. 重启服务（按你们现有脚本）
scripts/start-jar-with-env.sh
```

### 4.4 验证

1. 单聊连发 5 条，设备**不打开 App**
2. iOS：通知栏应只见**最新一条**（或快速被覆盖，不会堆 5 条独立横幅）
3. 打开 App 进会话：P0 客户端应清掉剩余通知

---

## 5. P2 — 配置与心跳（减少后续新发）

### 5.1 开启「聊天 Push 在线跳过」

**数据库** `app_setting`（或管理 API `PATCH /api/v1/push/config`）：

| Key | 建议值 | 说明 |
|-----|--------|------|
| `im.callback.chat_push_skip_when_online` | `true` | 30s 内心跳有效则不发聊天 Push |

管理 API 示例：

```http
PATCH /api/v1/push/config
Content-Type: application/json
Authorization: Bearer <admin-jwt>

{ "key": "chatPushSkipWhenOnline", "value": "true" }
```

### 5.2 客户端：App 打开时立即心跳

```http
POST /me/heartbeat
Authorization: Bearer <jwt>
Content-Type: application/json

{ "deviceId": "<与登录一致的 deviceId>" }
```

| 时机 | 动作 |
|------|------|
| App 冷启动完成 | TCP `ping` / 回退 `POST /me/heartbeat` |
| `AppLifecycleState.resumed` | TCP `ping` / 回退 `POST /me/heartbeat` |
| Android 前台服务保活 | 周期心跳（已有文档 §6） |

服务端判断：`PresenceService.isLikelyOnline(userId)`，Redis 键 `presence:hb:{userId}`，TTL = `heartbeat-throttle-seconds`（默认 30s）。

### 5.3 注意

- P2 **拦不住**已进入 APNs 队列的通知，只能减少「用户已在线仍继续发」的情况
- 必须与 P0 配合；单独开 P2 无法解决「4–9 仍弹出」

---

## 6. P3 — 当前会话上报（push-focus）✅ 已实现

> 若 P0 + P1 + P2 仍不满足（例如用户极速切会话、心跳窗口边界），由本接口兜底。

### 6.1 API

#### 进入会话

```http
PUT /me/push-focus
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "chatType": "c2c",
  "peerId": "abc12def34"
}
```

群聊：

```json
{
  "chatType": "group",
  "groupId": "@TGS#xxx"
}
```

（群聊也可只传 `peerId` 作为 `groupId` 别名，与 `conversation-notify` 一致。）

#### 离开会话 / 切到其他页

```http
DELETE /me/push-focus
Authorization: Bearer <jwt>
```

**响应**：`{ "ok": true }`

### 6.2 服务端逻辑（已实现）

```
Redis: push:focus:{userId} = "c2c:abc12" | "group:@TGS#xxx"
TTL: 90s（与 device online TTL 对齐）

发 Push 前（ImChatPushCallbackService / GroupPushAggregationService）：
  if focus 匹配当前会话 → skip push
```

### 6.3 客户端调用点

| 事件 | 请求 |
|------|------|
| `ChatPage.initState` | `PUT /me/push-focus` |
| `ChatPage.dispose` | `DELETE /me/push-focus` |
| 会话内每 60s | `PUT /me/push-focus`（续期） |

### 6.4 实现文件

| 文件 | 职责 |
|------|------|
| `push/PushFocusController.java` | `PUT/DELETE /me/push-focus` |
| `push/PushFocusService.java` | Redis `push:focus:{userId}`，TTL 90s |
| `im/ImChatPushCallbackService.java` | 单聊发 Push 前检查 focus |
| `im/GroupPushAggregationService.java` | 群聊 enqueue / flush 前检查 focus |

---

## 7. 推荐上线顺序

```
第 1 周：P0 客户端（可独立上线，立刻改善体验）
第 1 周：P1 服务端 collapse-id（1 行改动，与 P0 同发或紧随其后）
第 2 周：P2 开 chat_push_skip_when_online + 客户端 resumed 心跳
按需   ：P3 push-focus 新接口
```

---

## 8. 联调测试用例

### 8.1 核心场景（你描述的问题）

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 用户 A 连发 9 条给离线用户 B | B 收到 Push |
| 2 | B 看到第 3 条，点击进入该会话 | 会话内看到漫游消息 |
| 3 | 等待 4–9 条 Push 到达 | **通知栏不再出现 4–9**（P0）；或只闪一下即被清除 |
| 4 | B 退出会话回列表 | 新消息仍可正常 Push |

### 8.2 多会话并行离线（必测）

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | A/B/C 三人同时给离线用户 D 发消息 | D 通知栏有多条来自不同会话的 Push |
| 2 | D 点击**任意一条**通知进入 App（或冷启动打开 App） | `clearAllImChatNotifications` 执行 |
| 3 | 等待其余会话 Push 陆续到达 | **全部 im_chat 不再展示**；钱包/好友申请类通知仍保留 |
| 4 | D 在会话列表浏览 | 各会话消息由 IM 漫游补全，无需通知栏 |

### 8.3 单聊堆积（P1）

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | A 连发 5 条，B 不打开 App | iOS 通知栏最多 1 条（最新内容） |
| 2 | B 点击通知进入 | 会话内 5 条都在，通知栏清空 |

### 8.4 在线跳过（P2）

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | B 打开 App 并保持前台 | heartbeat 生效 |
| 2 | A 再发消息 | B **无**系统 Push，仅 IM 内展示 |

### 8.5 群聊

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 群内连发多条，B 离线 | 聚合窗口内合并为「张三: N条新消息」 |
| 2 | B 进该群会话 | 该群 thread 通知全部清除 |

---

## 9. 排查

| 现象 | 检查 |
|------|------|
| 打开 App 后其他会话仍弹 Push | 是否实现 `clearAllImChatNotifications`（仅清 `im_chat`） |
| 进会话后仍弹 4–9 | 前台是否 suppress **全部** `im_chat`；`onPushReceived` 是否在前台直接 return |
| 钱包/好友通知被误删 | 是否错误使用 `cancelAll()`；清理逻辑是否只匹配 `type==im_chat` |
| IM 有消息但通知还在 | `onRecvNewMessage` 是否按 `msgKey` cancel |
| iOS 仍堆多条 | P1 collapse-id 是否已部署；查 payload `apns-collapse-id` |
| 在线仍收 Push | `chat_push_skip_when_online` 是否为 true；是否调 heartbeat |
| 正在看会话仍收 Push | 是否调 `PUT /me/push-focus`；会话内是否每 60s 续期 |
| 群聊正常单聊不行 | 单聊 collapse-id 是否已部署（`threadId` 非 `msgKey`） |

日志关键字：`im chat push c2c`、`push skipped: user online`、`push skipped: push focus`、`push sent userId`。

---

## 11. 商业闭环补全（未读 / 角标 / 埋点 / 归因）

> §3–§6 解决的是**体验闭环**（用户不烦）。  
> 本章补**商业闭环**：触达可度量、转化可追踪、未读可一致、成本可优化。

### 11.1 完整商业闭环模型

```
离线                    在线                         消费                    度量
 │                       │                            │                       │
 ▼                       ▼                            ▼                       ▼
Push 触达 ──点击──▶ 进 App / 进会话 ──漫游──▶ 读消息 / 回复 ──埋点──▶ 漏斗 / ROI
     │                    │                            │
     │                    ├─ 清全部 im_chat 通知         ├─ 会话已读
     │                    ├─ 同步未读 / 角标            ├─ message_read
     │                    └─ heartbeat / push-focus     └─ push_click 归因
     │
     └─ push_sent（服务端日志 / 可选客户端回执）
```

| 闭环层 | 负责方 | 本文对应章节 |
|--------|--------|--------------|
| 体验闭环 | 客户端为主 | §3 P0 |
| 成本闭环 | 服务端 + 客户端 | §5 P2、§6 P3 |
| 未读闭环 | 客户端（IM SDK） | §11.2 |
| 数据闭环 | 客户端埋点 + 可选服务端 | §11.4–§11.5 |

**判定标准**：用户从通知进 App 后，通知栏干净、列表未读正确、能回复、运营能算 Push 转化率——才算完整商业闭环。

### 11.2 未读数 / 角标闭环（产品必做）

#### 11.2.1 问题

只执行 `clearAllImChatNotifications()` 而不同步未读，会出现：

- 通知栏清了，但 App 图标角标仍为 9  
- 会话列表红点与真实未读不一致  
- 用户以为「还有消息」，信任感下降，闭环断裂  

#### 11.2.2 原则

| 数据源 | 角色 |
|--------|------|
| **腾讯 IM SDK 未读** | 唯一真相（会话列表红点、总未读、角标） |
| **系统通知栏** | 仅离线拉活；App 前台后**不得**再作为未读依据 |

#### 11.2.3 客户端时序（与 §3.3 绑定）

```dart
Future<void> onAppEnteredForeground() async {
  // 1. 清全部 im_chat 系统通知（§3.3）
  await clearAllImChatNotifications();

  // 2. 心跳（§5.2）
  await api.post('/me/heartbeat', {'deviceId': deviceId});

  // 3. 拉 IM 未读并刷新 UI（关键：在清通知之后或并行，以 IM 为准）
  final totalUnread = await imSdk.getTotalUnreadCount();
  await imSdk.getConversationList(); // 各会话 unreadCount
  await syncAppBadge(totalUnread);   // iOS badge / Android 角标

  // 4. 埋点（§11.4）
  analytics.track('notification_cleared_on_foreground', {
    'total_unread': totalUnread,
  });
}
```

#### 11.2.4 角标（Badge）规则

| 平台 | 建议 |
|------|------|
| iOS | `UIApplication.shared.applicationIconBadgeNumber` = IM 总未读；清通知**后**必须用 IM 未读重设，禁止置 0 除非 IM 未读确实为 0 |
| Android | 各厂商角标能力不一；优先用 IM 未读驱动 Launcher 角标；极光 `notification` 自带 `badge_add_num` 时，前台后应停止累加 |
| Push payload | 服务端当前**未**下发 `badge` 字段；角标以客户端 IM 未读为准，避免 Push 与 IM 双源打架 |

#### 11.2.5 进入会话已读

```dart
Future<void> onEnterChat(String chatType, String peerOrGroupId) async {
  await clearChatNotifications(chatType: chatType, peerOrGroupId: peerOrGroupId);

  // 标记该会话已读（IM SDK）
  await imSdk.markConversationAsRead(chatType, peerOrGroupId);

  // 刷新总未读与角标
  await syncAppBadge(await imSdk.getTotalUnreadCount());

  analytics.track('chat_open', {
    'chat_type': chatType,
    'peer_or_group_id': peerOrGroupId,
    'source': openSource, // notification | list | search | ...
  });
}
```

#### 11.2.6 检查清单

| # | 项 | 完成 |
|---|----|------|
| 1 | 清 `im_chat` 通知后，角标来自 IM 未读而非通知条数 | ☐ |
| 2 | 会话列表 `unreadCount` 与 IM SDK 一致 | ☐ |
| 3 | 进会话 `markConversationAsRead` 后角标递减 | ☐ |
| 4 | 未读为 0 时角标为 0 | ☐ |

### 11.3 服务端成本闭环（与 P2 / P3 对齐）

体验闭环靠客户端清通知；**商业成本闭环**靠服务端少发无效 Push。

| 手段 | 配置 / 实现 | 商业价值 |
|------|-------------|----------|
| 在线跳过 | `im.callback.chat_push_skip_when_online=true` + 客户端 heartbeat | 用户已在 App 时不浪费 APNs/极光配额 |
| 会话 focus | `PUT /me/push-focus`（§6，待实现） | 用户正在看某会话时，该会话零 Push |
| 会话 collapse | P1 `collapse-id=threadId` | 离线同会话只展示最新一条，降低打扰与通道成本 |
| 群聚合 | 已有 `GroupPushAggregationService` | 大群扇出成本控制 |

**推荐生产配置**：

```
im.callback.chat_push_skip_when_online = true
push.skip_when_online = true          # 业务类 Push 同样跳过
```

### 11.4 埋点事件定义（数据闭环）

以下事件建议客户端上报（自有统计 / Firebase / 友盟等），字段名可映射。

#### 11.4.1 事件表

| 事件名 | 触发时机 | 核心属性 | 商业用途 |
|--------|----------|----------|----------|
| `push_received` | 系统收到 Push（含后台） | `type`, `msgKey`, `chatType`, `fromAccount`, `groupId` | 送达侧观测 |
| `push_displayed` | 通知栏实际展示 | 同上 + `threadId` | 展示率 |
| `push_click` | 用户点击通知 | 同上 + `threadId` | **点击率 CTR** |
| `push_suppressed` | App 前台拦截未展示 | `reason`: `foreground` \| `current_chat` | 策略有效性 |
| `notification_cleared_on_foreground` | `clearAllImChatNotifications` 执行 | `cleared_count`, `total_unread` | 清理是否执行 |
| `chat_open` | 进入聊天页 | `source`, `chatType`, `peerOrGroupId` | **转化** |
| `chat_open_from_push` | `source=notification` 且带 `msgKey` | `msgKey`, 同上 | **Push 归因转化** |
| `message_read` | 会话内消息曝光 / 标记已读 | `msgKey`, `chatType` | **消费深度** |
| `message_reply` | 用户发送回复 | `chatType`, `reply_to_msgKey` | **最终 ROI** |

#### 11.4.2 漏斗示例

```
push_sent（服务端日志）
    → push_received
    → push_displayed
    → push_click
    → chat_open_from_push
    → message_read
    → message_reply
```

运营可看：

- **CTR** = `push_click` / `push_displayed`  
- **打开转化率** = `chat_open_from_push` / `push_click`  
- **有效沟通率** = `message_reply` / `push_sent`  

#### 11.4.3 服务端可补充的日志（无需改客户端）

当前已有日志，可直接用于粗算：

| 日志关键字 | 含义 |
|------------|------|
| `im chat push c2c` | 聊天 Push 已尝试下发 |
| `im chat push group enqueued` | 群 Push 入聚合队列 |
| `push sent userId=` | 实际发出 |
| `push skipped: user online` | 在线跳过（P2 生效指标） |
| `push skipped: no token` | 未上报 token |

后续可选：Admin 报表聚合上述日志，或 Kafka 出 `push_sent` 事件流。

### 11.5 点击归因闭环（msgKey）

Push payload 已带 `msgKey`，客户端须打通**点击 → 会话 → 消息**链路。

#### 11.5.1 点击通知跳转

```dart
void onNotificationTap(Map<String, String> data) {
  if (data['type'] != 'im_chat') {
    routeByType(data);
    return;
  }

  final chatType = data['chatType']!;
  final peerOrGroupId = chatType == 'c2c'
      ? data['fromAccount']!
      : data['groupId']!;
  final msgKey = data['msgKey'];

  analytics.track('push_click', {
    'type': 'im_chat',
    'msgKey': msgKey,
    'chatType': chatType,
  });

  // 先进 App 清全部 im_chat（§3.3），再跳转会话
  clearAllImChatNotifications().then((_) {
    navigator.openChat(
      chatType: chatType,
      peerOrGroupId: peerOrGroupId,
      scrollToMsgKey: msgKey,  // 可选：定位到对应消息
      source: 'notification',
    );
  });
}
```

#### 11.5.2 归因校验

| 步骤 | 校验 |
|------|------|
| 点击后 | `chat_open_from_push` 的 `msgKey` 与 Push 一致 |
| 进入会话 | 漫游/本地能查到该 `msgKey` 消息 |
| 读完后 | `message_read` 带同一 `msgKey` |

查不到消息时上报 `push_attribution_miss`，用于排查漫游延迟或 `msgKey` 字段不一致。

#### 11.5.3 msgKey 对齐说明

| 来源 | 字段 |
|------|------|
| 服务端 Push `data.msgKey` | IM 回调 `MsgKey`（单聊）/ `MsgId`（群聊） |
| 客户端 IM SDK | 须确认与上表同一字段（常见为 `msgID` / `msgKey`） |

**不一致会导致**：去重失败、归因断裂。联调时打印双方 `msgKey` 对比。

### 11.6 商业闭环总检查清单

| # | 类别 | 项 | 完成 |
|---|------|----|------|
| 1 | 体验 | App 启动/resumed 清全部 `im_chat` | ☐ |
| 2 | 体验 | 前台 suppress 全部聊天 Push | ☐ |
| 3 | 未读 | 角标 = IM 总未读 | ☐ |
| 4 | 未读 | 进会话已读 + 角标更新 | ☐ |
| 5 | 成本 | `chat_push_skip_when_online=true` | ☐ |
| 6 | 成本 | resumed 发 heartbeat | ☐ |
| 6b | 成本 | 进会话 `PUT /me/push-focus`，离开 `DELETE` | ☐ |
| 7 | 归因 | 点击通知带 `msgKey` 进会话 | ☐ |
| 8 | 数据 | `push_click` / `chat_open_from_push` 埋点 | ☐ |
| 9 | 数据 | `message_read` / `message_reply` 埋点 | ☐ |
| 10 | 服务端 | P1 collapse-id 按会话 | ☐ |

### 11.7 分期建议

| 期 | 内容 | 闭环程度 |
|----|------|----------|
| **一期**（当前文档 §3–§5） | 清通知 + suppress + heartbeat + collapse | 体验闭环 |
| **二期** | 未读/角标同步 + 点击归因 `msgKey` | 产品闭环 |
| **三期** | 埋点漏斗 + 服务端在线跳过观测 | 数据闭环 |
| **四期**（可选） | `push-focus` API + Admin Push 报表 | 成本 + 运营闭环 |

---

## 12. 关联文档

| 文档 | 内容 |
|------|------|
| [push-client.md](./push-client.md) | 客户端 Push 全量对接 |
| [im-chat-push-callback.md](./im-chat-push-callback.md) | IM 回调与服务端配置 |
| [push-backend-checklist.md](./push-backend-checklist.md) | 后端实现清单 |
