# 通讯录黑名单 — 前端对接文档

> 版本：v1.0  
> 日期：2026-09-18  
> 适用：99chat（Flutter / iOS / Android）  
> 鉴权：`Authorization: Bearer <JWT>`  
> JSON：**camelCase**  
> 详细字段亦可对照 [friend-self-hosted-client.md](./friend-self-hosted-client.md)

---

## 1. 必须先读

1. **拉黑 / 解除 / 列表只走 REST**，不要只调腾讯 IM SDK 的 `addToBlackList` / `deleteFromBlackList` / `getBlackList`。只调 SDK 时，服务端收不到事件，双方 pending 好友申请不会作废，和 REST 对不上。
2. **不要用 IM 好友列表判断是否还是好友。** IM `black_list_add` 会拆掉 IM SNS 好友；自建 `user_friend` **不删**。是否好友以 `GET /me/friends`、`GET /me/friends/{peerUserId}/relation` 为准。
3. 拉黑后 `GET /me/friends` **仍可能列出对方**。资料页 / 会话页不要用「还在好友列表」当作「没拉黑」。
4. 单聊能否发出去，由 IM 黑名单拦截；服务端 C2C 回调不额外查黑名单。
5. 群邀请、建群拉人、管理端强制加好友 **不拦** 黑名单。强制加后本地可能已是好友，但 IM 黑名单还在时 C2C 仍可能发不出。

---

## 2. 产品行为（按微信口径）

| 场景 | 行为 |
|------|------|
| 非好友 | 可以拉黑 |
| 已是好友 | 可以拉黑；**通讯录好友关系保留** |
| A 拉黑 B 后 | A、B **双方**都不能再发好友申请，也不能同意已有申请 |
| 拉黑时已有 pending | 双向 pending 立刻作废（变成 `rejected`，走现有拒绝推送） |
| 解除拉黑 | 只从 IM 黑名单移除。若本地仍是好友，单聊按现有好友规则恢复 |
| 拉黑自己 | 不允许 |

---

## 3. 通用响应

成功 2xx 会被包装成：

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

下文「成功响应」写的是 **`data` 内字段**。客户端请先取 `data`。

失败（非 2xx）**不包装**，直接：

```json
{
  "code": "USER_BLOCKED",
  "message": "因拉黑无法添加好友"
}
```

| HTTP | code | message | 客户端建议 |
|------|------|---------|------------|
| 400 | `INVALID_INPUT` | `INVALID_INPUT` | 拉黑自己、`userId` 为空 |
| 401 | — | 请先登录 | 重新登录 |
| 403 | `USER_BLOCKED` | 因拉黑无法添加好友 | 加好友 / 同意申请：提示因拉黑无法添加 |
| 404 | `USER_NOT_FOUND` | `USER_NOT_FOUND` | 对方不存在 |
| 502 | `IM_REST_ERROR` | IM 原文 | Toast：操作失败，请重试 |
| 503 | `IM_UNAVAILABLE` | 即时通讯服务暂不可用 | 加好友预检/申请/同意失败，请重试，**不要当成功** |

预检接口例外：拉黑时 **HTTP 仍 200**，`data.allowed=false`，`data.reason=USER_BLOCKED`。

---

## 4. 接口

Base path 与现有业务 API 相同。均需登录。

### 4.1 拉黑 — `POST /me/blocks`

资料页 / 设置页「加入黑名单」。幂等：已在名单里也返回成功。

```http
POST /me/blocks
Authorization: Bearer <token>
Content-Type: application/json

{
  "userId": "b1"
}
```

**`data`**

```json
{
  "ok": true,
  "userId": "b1"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `userId` | String | 是 | 对方 userId，不能是自己 |

成功后客户端应：

1. 本地记「我已拉黑该 userId」（黑名单页、资料页按钮切成「移出黑名单」）。
2. 刷新好友申请 incoming / outgoing / sent：双方 pending 已被拒绝。
3. **不要**从 `GET /me/friends` 里删掉对方（服务端没删）。
4. 单聊会话可按产品隐藏输入框或提示「已拉黑」；发消息结果以 IM 为准。

**错误**：400 `INVALID_INPUT` · 404 `USER_NOT_FOUND` · 502 `IM_REST_ERROR` · 401

---

### 4.2 解除拉黑 — `DELETE /me/blocks/{userId}`

```http
DELETE /me/blocks/b1
Authorization: Bearer <token>
```

路径里的 userId 按 URL 编码。

**`data`**

```json
{
  "ok": true,
  "userId": "b1"
}
```

成功后：

1. 从本地黑名单去掉该人。
2. 若 `GET /me/friends/{userId}/relation` 仍是 `isFriend=true`，单聊按好友恢复。
3. 不会自动恢复 IM SNS 好友；加好友关系仍以自建通讯录为准。

**错误**：400 `INVALID_INPUT`（含解除自己）· 502 `IM_REST_ERROR` · 401

---

### 4.3 黑名单列表 — `GET /me/blocks`

设置页「通讯录黑名单」。

```http
GET /me/blocks?startIndex=0&limit=50
Authorization: Bearer <token>
```

| Query | 类型 | 默认 | 说明 |
|-------|------|------|------|
| `startIndex` | Int | `0` | 首页传 `0`；下一页传上一页返回的 `startIndex` |
| `limit` | Int | `50` | 服务端夹到 1–100 |

**`data`**

```json
{
  "items": [
    {
      "userId": "b1",
      "nickname": "Bob",
      "avatarUrl": "https://cdn.example.com/b.png",
      "blockedAt": 1700000000000
    }
  ],
  "startIndex": 0,
  "hasMore": false
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `items[].userId` | String | 被拉黑人 |
| `items[].nickname` | String? | 用户表昵称；账号已删可能为 `null`，UI 用 userId 兜底 |
| `items[].avatarUrl` | String? | 用户表头像，可能为 `null` |
| `items[].blockedAt` | Long? | 拉黑时间，**毫秒** UTC；没有则 `null` |
| `startIndex` | Int | **下一页游标**。没有下一页时 IM 返回 `0`，照填 |
| `hasMore` | Boolean | `startIndex > 0` 才还有下一页 |

分页：

```
cursor = 0
do {
  resp = GET /me/blocks?startIndex={cursor}&limit=50
  追加 resp.data.items
  if (!resp.data.hasMore) break
  cursor = resp.data.startIndex
} while (true)
```

**错误**：502 `IM_REST_ERROR` · 401

没有「是否拉黑某人」的单独查询接口。资料页判断「我是否拉黑对方」：拉黑成功后本地缓存，或拉 `GET /me/blocks` 看 `items[].userId`。对方是否拉黑我：不要猜；加好友时看预检 / 申请错误码。

---

## 5. 加好友相关（必须一起改）

### 5.1 预检 — `POST /users/add-friend/check`

**必须带登录 token**（现网该接口本就需登录）。用当前用户检查与目标是否拉黑。

```http
POST /users/add-friend/check
Authorization: Bearer <token>
Content-Type: application/json

{
  "targetUserId": "b1",
  "channel": "card"
}
```

`channel` 仅：`card` / `qr` / `group`（小写）。搜索 UID / 手机号加好友不走这个 channel，直接 `POST /friend-requests`。

**允许 `data`**

```json
{
  "allowed": true,
  "reason": null,
  "friendAddRequiresVerify": true
}
```

**拉黑或渠道关闭时仍是 HTTP 200**

```json
{
  "allowed": false,
  "reason": "USER_BLOCKED",
  "friendAddRequiresVerify": true
}
```

| `reason` | 含义 | UI |
|----------|------|-----|
| `USER_BLOCKED` | 我拉黑了对方，或对方拉黑了我 | 「因拉黑无法添加好友」 |
| `ADD_FRIEND_VIA_CARD_DISABLED` | 对方关了名片加好友 | 沿用现有文案 |
| `ADD_FRIEND_VIA_QR_DISABLED` | 对方关了扫码加好友 | 沿用现有文案 |
| `ADD_FRIEND_VIA_GROUP_DISABLED` | 对方关了群聊加好友 | 沿用现有文案 |

`allowed=false` 时 **不要** 再调 `POST /friend-requests`。

检查失败（IM 超时等）是 **HTTP 503** `IM_UNAVAILABLE`，不是 200。此时不要当「可以加」。

---

### 5.2 发申请 — `POST /friend-requests`

请求体不变：`targetUserId` / `addWording` / `addSource`。

新增错误：

| HTTP | code | 何时 |
|------|------|------|
| 403 | `USER_BLOCKED` | 任一侧已拉黑 |
| 503 | `IM_UNAVAILABLE` | 黑名单检查失败，申请未发出 |

`auto_accepted` 同样会先查黑名单，拉黑时不会自动通过。

---

### 5.3 同意申请 — `POST /friend-requests/{id}/accept`

拉黑发生在申请发出之后、点同意之前：同意会 **403 `USER_BLOCKED`**，好友关系不会建立。

拉黑当时 pending 通常已被作废（`rejected`）。若列表还残留，点同意会 403 或 409 `REQUEST_ALREADY_HANDLED`，刷新列表即可。

---

## 6. 实时推送

拉黑作废 pending 时，走现有拒绝通道：

| type | 谁会收到 | 客户端动作 |
|------|----------|------------|
| `friend_request_rejected` | 原申请人 | 刷新 outgoing / sent，申请变为已拒绝 |

**没有**单独的 `user_blocked` / `user_unblocked` TCP 事件。本机拉黑成功以 REST 返回为准；多端同步请重新 `GET /me/blocks`。

---

## 7. 建议接入步骤

| 顺序 | 页面 | 改动 |
|------|------|------|
| 1 | 用户资料 / 聊天设置 | 「加入黑名单」→ `POST /me/blocks`；已拉黑显示「移出黑名单」→ `DELETE /me/blocks/{userId}` |
| 2 | 设置 → 黑名单 | `GET /me/blocks` 分页列表；左滑/按钮解除 |
| 3 | 加好友（名片/扫码/群） | `add-friend/check` 增加 `USER_BLOCKED`；`allowed=false` 拦截 |
| 4 | 加好友（搜索 UID/手机号） | `POST /friend-requests` 增加 403 `USER_BLOCKED`、503 `IM_UNAVAILABLE` |
| 5 | 新的朋友 / 同意 | `accept` 增加 403 `USER_BLOCKED`；拉黑后刷新申请列表 |
| 6 | 好友列表 / IM | 不要因拉黑删本地好友；不要用 IM `getFriendList` 当通讯录权威 |
| 7 | 单聊 | 我拉黑对方：可禁用输入（产品决定）。对方拉黑我：发消息失败按 IM 错误提示，不要当成「已不是好友」去删通讯录 |

---

## 8. IM SDK 对照（仅对照，不要当主入口）

| IM SDK | 必须改走的 REST |
|--------|-----------------|
| `addToBlackList` | `POST /me/blocks` |
| `deleteFromBlackList` | `DELETE /me/blocks/{userId}` |
| `getBlackList` | `GET /me/blocks` |

---

## 9. 自测清单

- [ ] 非好友可拉黑，列表能看到
- [ ] 好友拉黑后，`GET /me/friends` 里还在
- [ ] A 拉黑 B 后，A 申请加 B → 403 `USER_BLOCKED`
- [ ] A 拉黑 B 后，B 申请加 A → 403 `USER_BLOCKED`
- [ ] 预检 `channel=card` 返回 `allowed=false, reason=USER_BLOCKED`（HTTP 200）
- [ ] 拉黑前已有 pending，拉黑后申请变成 rejected，申请人收到 `friend_request_rejected`
- [ ] 拉黑自己 → 400
- [ ] 解除后，若仍是好友，单聊可按好友规则发（IM 黑名单已删）
- [ ] 只调 IM SDK 拉黑、不调 REST：pending **不会**作废（这是错误用法，用来确认必须走 REST）
