# 会话置顶多端同步 — 后端契约

> 版本：v1.0  
> 日期：2026-08-03  
> 状态：**契约冻结；后端已按本契约实现**  
> 客户端：99chat Flutter — **彻底不调** IM `pinConversation`；忽略 SDK `isPinned`

关联：归档 [`conversation-archive` / `/me/archived-conversations`]、免打扰 `/me/conversation-notify`、分组 folders。

## 架构

```
PUT REST → DB（稀疏 user_conversation_pin）→ 响应全量 items
         → TCP conversation_pin_changed（全量 items）推本人其他在线设备
登录 GET /me/pinned-conversations 一次全量覆盖本地
```

- 不做 IM 历史迁移；不做 since 增量；每用户上限 **100**
- 标识：`chatType`(`c2c`|`group`) + `peerId`（与归档/免打扰一致）
- 取消置顶 = **删行**

## REST

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/me/pinned-conversations` | 全量 `{ items, serverTime, updatedAt }` |
| PUT | `/me/pinned-conversations` | `{ chatType, peerId, pinned }` → 含全量 `items` |
| PUT | `/me/pinned-conversations/batch` | `{ items: [...] }` 原子，最多 100 条 |

错误：`400 INVALID_INPUT` · `400 PIN_LIMIT_EXCEEDED` · `401 UNAUTHORIZED`

## TCP

`event=conversation_pin_changed`，**必带全量 `items`** + `updatedAt`；批量可 `batch=true`。  
推送走 `sendToUser`（与归档一致；操作端可能收到 echo，客户端忽略即可）。

## DDL

见 `scripts/migrate-conversation-pin.sql`。
