# IM Snapshot 前端对接（新设备冷启动）

> 目标：新设备登录先展示最多 **40 单聊 + 40 群聊**（**置顶优先**纳入），用后端 Snapshot 预热消息，再用腾讯 SDK **按会话 ID** 补未读/草稿/头像等；**不要**一上来全量 `getConversationList`。

相关 SDK：[getConversationListByConversaionIds](https://comm.qq.com/im/doc/flutter/zh/SDKAPI/Api/V2TIMConversationManager/getConversationListByConversaionIds.html)

置顶真源：自建 [`/me/pinned-conversations`](./conversation-pin-backend-todo.md)；**忽略** SDK `isPinned`。

---

## 1. 推荐调用

```http
GET /im/snapshot?limitC2c=40&limitGroup=40&limitMsg=40
Authorization: Bearer <App JWT>
```

| 参数 | 推荐值 | 范围 | 说明 |
|------|--------|------|------|
| `limitC2c` | `40` | 1～50 | 单聊会话数；与 `limitGroup` **任一出现**即分路模式 |
| `limitGroup` | `40` | 1～50 | 群聊会话数 |
| `limitMsg` | `40` | 30～50 | 每会话预热消息条数 |

**兼容旧版：** 只传 `limitConv`、不传 `limitC2c`/`limitGroup` → 混合 Top N（默认 20）。新设备请走分路参数。

依赖后端：`chat99.message-archive.enabled=true`。接口不存在 / 非 200 / 业务失败 → 降级走腾讯全量或分页会话，**不卡登录**。

限流：每用户约 **2 次/秒**，超限 `RATE_LIMITED`。

---

## 2. 响应结构（`data`）

```json
{
  "conversations": [
    {
      "conversationId": "c2c_peerUserId",
      "type": "c2c",
      "chatType": "c2c",
      "peerId": "peerUserId",
      "lastSeq": null,
      "lastMessage": {
        "msgId": "144115268026882536-1784319889-1876410779",
        "msgKey": "3358721060_1876410779_1784319889",
        "seq": null,
        "sender": "peerUserId",
        "time": 1710000000,
        "type": "TIMTextElem",
        "text": "Hello",
        "msgBody": [],
        "status": 1
      },
      "pinned": true,
      "pinnedAt": 1710000100123
    },
    {
      "conversationId": "group_@TGS#xxxx",
      "type": "group",
      "chatType": "group",
      "peerId": "@TGS#xxxx",
      "lastSeq": 19382,
      "lastMessage": { "...": "..." },
      "pinned": false,
      "pinnedAt": null
    }
  ],
  "preload": [
    {
      "conversationId": "c2c_peerUserId",
      "messages": [ /* 旧 → 新 */ ]
    }
  ],
  "degraded": false
}
```

### 字段说明

| 字段 | 说明 |
|------|------|
| `conversationId` | **腾讯会话 ID**，可直接进 SDK：`c2c_{userId}` / `group_{groupId}` |
| `chatType` / `type` | `c2c` \| `group` |
| `peerId` | 裸对方 userId 或裸群 ID（含 `@TGS#`） |
| `lastSeq` | 群多为 `msg_seq`；C2C 常为 `null`；置顶无归档消息时可为 `null` |
| `lastMessage` | 末条；置顶但归档无消息时可为 `null`（仍返回该会话行） |
| `lastMessage.msgId` | 腾讯真正 `MsgId`（可空） |
| `lastMessage.msgKey` | 归档键（与 `/me/messages` 一致） |
| `lastMessage.time` | **秒** |
| `lastMessage.status` | `0` = 已撤回 |
| `pinned` | 是否自建置顶 |
| `pinnedAt` | 置顶时间**毫秒**；未置顶为 `null` |
| `preload[].messages` | 该会话预热消息，**旧 → 新**；置顶无消息时为 `[]` |
| **unread** | **不下发**；勿用「没有 unread」去清本地未读 |
| `degraded` | `true` 表示部分失败/超时，列表可能不完整 |

### 顺序与配额

分路模式（推荐）：

1. **C2C 段**：该侧置顶（`pinnedAt` 降序）优先占满 `limitC2c`；剩余名额用近 7 天活跃会话按末条时间降序补齐（去重）
2. **Group 段**：同上，受 `limitGroup` 约束
3. 数组整体仍是 **先全部 C2C，再全部 Group**

一侧置顶数 > limit 时只带前 limit 条；超出部分仍以 `GET /me/pinned-conversations` 全量为准。

---

## 3. 新设备冷启动流程（推荐）

```text
1. App JWT 登录成功 + 腾讯 IM login 成功（可并行，但调 SDK 会话前需 IM 已登录）
2. GET /me/pinned-conversations（全量覆盖本地置顶）
3. GET /im/snapshot?limitC2c=40&limitGroup=40&limitMsg=40
4. 用返回的 conversationId 列表调用：
   getConversationListByConversaionIds(conversationIDList: ids)
5. 合并后写入本地会话表并刷新 UI（置顶已在 Snapshot 靠前；先出首屏）
6. preload.messages 写入本地消息缓存（按 conversationId / peerId）
7. 后台再按需：分页/增量同步其余腾讯会话；点进会话再拉历史
```

### 合并规则（按 `conversationId` upsert）

| 数据 | 真源 |
|------|------|
| 列表里「先有谁 / 置顶靠前」 | Snapshot `conversations`（含 `pinned`）；超 limit 的 pin 靠 `/me/pinned-conversations` |
| 置顶集合全量 | **自建** `GET /me/pinned-conversations`（忽略 SDK `isPinned`） |
| 未读 / 草稿 / 免打扰 / 头像昵称 | 腾讯 `V2TimConversation`（SDK 返回为准） |
| 末条预览 / 时间 | 取更新鲜的一侧；冲突时优先 SDK `lastMessage`，Snapshot 作兜底 |
| 预热消息正文 | Snapshot `preload`；与 SDK 消息用 `msgKey`/`msgId` 去重 |
| 未读 | **只信 SDK**；Snapshot 无 unread 字段时 **不要清零** |

### Flutter 示意

```dart
// 0) 自建置顶全量
final pins = await api.getPinnedConversations();

// 1) 后端 Snapshot（已含 limit 内置顶优先）
final snap = await api.getImSnapshot(limitC2c: 40, limitGroup: 40, limitMsg: 40);
final ids = snap.conversations.map((c) => c.conversationId).toList();

// 2) 腾讯按 ID 补会话（ID 格式已是 c2c_ / group_）
final imRes = await TencentImSDKPlugin.v2TIMManager
    .getConversationManager()
    .getConversationListByConversaionIds(conversationIDList: ids);

if (imRes.code == 0) {
  // 按 conversationID 与 snap.conversations 合并后刷新列表 UI
  // 本地 pinned 以 pins + snap.pinned 为准，忽略 SDK isPinned
}

// 3) preload → 本地消息库（旧→新）
for (final bucket in snap.preload) {
  await messageCache.putAll(bucket.conversationId, bucket.messages);
}
```

SDK 文档字段：`unreadCount`、`draftText`、`faceUrl`、`showName`、`lastMessage`、`recvOpt` 等见官网（**不要用** `isPinned` 作为业务真源）。

---

## 4. 本地落库建议

主键：`conversationId` 或 `(chatType, peerId)`。

| 本地列 | 来源 |
|--------|------|
| `conversation_id` | Snapshot / SDK |
| `chat_type` / `peer_id` | Snapshot |
| `title` / `avatar` | SDK `showName` / `faceUrl`（可再用 `/me/friends`、`/me/groups` 校正） |
| `last_msg_*` | max(Snapshot.lastMessage, SDK.lastMessage) |
| `unread` | **仅 SDK** |
| `draft_*` | **仅 SDK**（或本地编辑） |
| `pinned` / `pinned_at` | **自建**：`/me/pinned-conversations` + Snapshot `pinned`/`pinnedAt` |
| `muted` | 自建免打扰 REST；无则 SDK |

---

## 5. 错误与降级

| 情况 | 客户端行为 |
|------|------------|
| Snapshot 404 / 超时 / `degraded=true` 且列表空 | 不阻塞登录；回退腾讯 `getConversationList` 分页；置顶仍可用自建 REST |
| Snapshot 有数据，SDK 按 ID 部分缺失 | 先用 Snapshot 行展示；缺未读显示 0 或「…」，待会话同步后再补 |
| `RATE_LIMITED` | 短退避重试；首屏可先空再补 |
| 消息 `msgId` 为空 | 仍可用 `msgKey`；需要腾讯 id 时走 `POST /me/messages/resolve-msg-ids`（C2C） |

---

## 6. 验收清单

- [ ] 新设备登录只调分路 Snapshot，**未**先全量拉会话
- [ ] 有自建置顶时，Snapshot 同侧会话中置顶靠前，且带 `pinned=true`
- [ ] 置顶不在近 7 天活跃 Top 内时仍可能出现在 Snapshot（占 limit 名额）
- [ ] UI 先出现最多 40 C2C + 40 Group（有数据时）
- [ ] `conversationId` 原样传入 `getConversationListByConversaionIds`
- [ ] 未读来自 SDK；Snapshot 无 unread 时未把本地未读清成 0
- [ ] 本地置顶**不**信 SDK `isPinned`
- [ ] `preload` 消息升序写入缓存，进会话可先看到暖窗
- [ ] Snapshot 失败时登录仍成功，并有腾讯降级路径

---

## 7. 服务端说明（前端可不深究）

读投影表 `user_c2c_conversation_recent` + `group_conversation_recent`，并与 `user_conversation_pin` 合并（置顶优先占 limit）；近 **7 天**活跃用于补齐非置顶；超时默认 **3000ms**。

```yaml
chat99.message-archive.snapshot:
  default-limit-c2c: 40
  default-limit-group: 40
  default-limit-msg: 40
  timeout-ms: 3000
  projection-mode: hybrid
```
