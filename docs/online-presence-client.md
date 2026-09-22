# 在线时间展示 — 客户端对接指南

> 版本：v1.0（2026-06-19）  
> 适用：Flutter 客户端  
> 关联：[user-privacy-and-search.md](./user-privacy-and-search.md) · [friend-self-hosted-client.md](./friend-self-hosted-client.md)

---

## 1. 变更说明（必读）

**旧行为**：对方设置 `lastActiveVisibility=hidden`（或 `friends_only` 且非双向好友）时，API 返回 `lastActiveAt: null`。

**新行为**：API **始终返回原始** `lastActiveAt`（毫秒 UTC）+ `lastActiveVisibility`；**是否展示由客户端决定**。

若仍用 `lastActiveAt != null` 判断是否显示，会把「隐藏在线时间」的用户时间也展示出来，**违反隐私设置**。

---

## 2. 涉及接口与字段

| 接口 / 事件 | 新增/变更字段 |
|-------------|---------------|
| `GET /me/friends` | `items[].lastActiveVisibility` |
| `POST /users/search` | `lastActiveVisibility` |
| `POST /users/contacts/match` | `lastActiveVisibility` |
| `POST /presence/last-seen` | 响应含 `lastActiveVisibility` map；**TCP 优先**，本接口为未连 TCP 回退 |
| TCP `presence_last_seen` | 与 HTTP 同批字段（`lastSeen` + `lastActiveVisibility`），需 `requestId` |
| TCP `presence_changed` | `lastActiveVisibility` |
| TCP `friend_list_changed` | `lastActiveVisibility` |

### `lastActiveVisibility` 枚举

| 值 | 含义 | 展示规则 |
|----|------|----------|
| `everyone` | 所有人可见 | 有 `lastActiveAt` 即可展示 |
| `friends_only` | 仅好友可见 | 仅当 **双向好友**（`isFriend` / `canMessage`）时展示 |
| `hidden` | 不展示 | **永不**展示在线时间（即使 `lastActiveAt` 有值） |
| `null` | 未知/无记录 | 不展示 |

---

## 3. 展示逻辑（复制即用）

```dart
enum LastActiveVisibility { everyone, friendsOnly, hidden }

LastActiveVisibility? parseVisibility(String? raw) {
  switch (raw) {
    case 'everyone':
      return LastActiveVisibility.everyone;
    case 'friends_only':
      return LastActiveVisibility.friendsOnly;
    case 'hidden':
      return LastActiveVisibility.hidden;
    default:
      return null;
  }
}

bool shouldShowLastActive({
  required LastActiveVisibility? visibility,
  required bool isMutualFriend,
  required int? lastActiveAtMs,
}) {
  if (lastActiveAtMs == null) return false;
  switch (visibility) {
    case LastActiveVisibility.everyone:
      return true;
    case LastActiveVisibility.friendsOnly:
      return isMutualFriend;
    case LastActiveVisibility.hidden:
    case null:
      return false;
  }
}

String? formatLastActiveLabel({
  required int? lastActiveAtMs,
  required LastActiveVisibility? visibility,
  required bool isMutualFriend,
}) {
  if (!shouldShowLastActive(
        visibility: visibility,
        isMutualFriend: isMutualFriend,
        lastActiveAtMs: lastActiveAtMs,
      )) {
    return null;
  }
  return formatRelativeTime(DateTime.fromMillisecondsSinceEpoch(lastActiveAtMs!));
}
```

---

## 4. 各场景接入

### 4.1 通讯录 / 好友列表 `GET /me/friends`

```json
{
  "items": [{
    "friendUserId": "zlfb3col3r",
    "friendNickname": "铁观音",
    "isFriend": true,
    "lastActiveAt": 1750312588000,
    "lastActiveVisibility": "hidden"
  }]
}
```

```dart
final item = friendItem;
final label = formatLastActiveLabel(
  lastActiveAtMs: item.lastActiveAt,
  visibility: parseVisibility(item.lastActiveVisibility),
  isMutualFriend: item.isFriend,
);
// hidden → label == null，UI 不显示「最近活跃」
```

### 4.2 批量补拉（TCP 优先）

**主路径：TCP `presence_last_seen`**（实时通道已鉴权）

```json
{
  "type": "presence_last_seen",
  "requestId": "viewport-1",
  "userIds": ["userA", "userB"]
}
```

成功：

```json
{
  "type": "presence_last_seen_ok",
  "requestId": "viewport-1",
  "lastSeen": { "userA": 1700000000000, "userB": 1710000000000 },
  "lastActiveVisibility": { "userA": "everyone", "userB": "hidden" },
  "ts": 1700000100000
}
```

失败：`presence_last_seen_fail`，`code` 为 `INVALID_INPUT` / `BATCH_TOO_LARGE` / `TOO_MANY_INFLIGHT` / `INTERNAL`。

与推送分工：`presence_changed` 负责近实时增量；本查询用于冷启动、翻页视口补全。TCP 已连接时**不要**再周期打 HTTP last-seen。

**回退：`POST /presence/last-seen`**（未连 TCP / 降级）

**请求**

```json
{ "userIds": ["userA", "userB"] }
```

**响应**

```json
{
  "lastSeen": {
    "userA": 1700000000000,
    "userB": 1710000000000
  },
  "lastActiveVisibility": {
    "userA": "everyone",
    "userB": "hidden"
  }
}
```

更新本地缓存时 **两个 map 都要写**；展示仍走 `shouldShowLastActive`。

### 4.3 TCP `presence_changed`

```json
{
  "event": "presence_changed",
  "peerUserId": "userB",
  "lastActiveAt": 1700000000000,
  "lastActiveVisibility": "friends_only",
  "online": true
}
```

**变更**：不再用 `lastActiveAt == null` 表示「清除展示」；hidden 用户活跃时也会推送真实时间戳。

```dart
void onPresenceChanged(PresenceChangedEvent e) {
  // 始终更新缓存
  cache.update(
    userId: e.peerUserId,
    lastActiveAt: e.lastActiveAt,
    visibility: parseVisibility(e.lastActiveVisibility),
    online: e.online,
  );
  // UI 刷新时再 shouldShowLastActive
}
```

### 4.4 用户搜索 `POST /users/search`

响应含 `lastActiveAt` + `lastActiveVisibility`，逻辑同好友列表。

---

## 5. 不再需要的行为

| ❌ 旧逻辑 | ✅ 新逻辑 |
|----------|----------|
| `if (lastActiveAt != null) show()` | 先 `shouldShowLastActive(...)` |
| `lastActiveAt == null` 时清除 UI | 根据 `visibility` 决定；时间仍可缓存 |
| 为每个好友调 `GET /users/{id}/online-privacy-protection` | 列表接口已带 `lastActiveVisibility` |

设置页查看/修改 **自己的** 隐私仍用：

- `GET /me/online-privacy-protection`
- `PUT /me/online-privacy-protection`

---

## 6. 自测清单

| # | 场景 | 期望 |
|---|------|------|
| 1 | 好友 `hidden` + 有 `lastActiveAt` | **不展示**在线时间 |
| 2 | 好友 `everyone` + 有 `lastActiveAt` | 展示 |
| 3 | 好友 `friends_only`，双向好友 | 展示 |
| 4 | 好友 `friends_only`，非双向 | 不展示 |
| 5 | 收到 `presence_changed` hidden | 缓存更新时间，UI 仍隐藏 |
| 6 | TCP `presence_last_seen`（或 HTTP `/presence/last-seen` 回退） | 解析 `lastActiveVisibility` map |

---

## 7. 数据模型建议

```dart
class PeerPresence {
  final int? lastActiveAtMs;
  final LastActiveVisibility? visibility;
  final bool online; // 来自 presence_changed 或客户端推断

  bool visibleToViewer({required bool isMutualFriend}) =>
      shouldShowLastActive(
        visibility: visibility,
        isMutualFriend: isMutualFriend,
        lastActiveAtMs: lastActiveAtMs,
      );
}
```

本地 DB 建议存储 `lastActiveAtMs` + `lastActiveVisibility` 两列；展示层统一走 `visibleToViewer`。
