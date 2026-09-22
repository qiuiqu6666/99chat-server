# 自建好友关系 — 客户端 API

> 版本：v1.0  
> 日期：2026-06-15  
> 鉴权：`Authorization: Bearer <token>`（除特别说明）  
> JSON 字段：**camelCase**  
> 方案背景：[self-hosted-friend-chain-plan.md](./self-hosted-friend-chain-plan.md)

---

## 1. 错误响应格式

业务错误统一：

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | String | 错误码 |
| `message` | String | 可读说明 |

部分 429 额外带 `retryAfter`（秒）。

---

## 2. 通用错误码

| HTTP | code | 说明 |
|------|------|------|
| 400 | `INVALID_INPUT` | 参数非法 / 校验失败 |
| 401 | — | 未登录或 token 无效 |
| 403 | `ADD_FRIEND_VIA_CARD_DISABLED` | 对方关闭名片加好友 |
| 403 | `ADD_FRIEND_VIA_QR_DISABLED` | 对方关闭扫码加好友 |
| 403 | `ADD_FRIEND_VIA_GROUP_DISABLED` | 对方关闭群聊加好友 |
| 403 | `ADD_FRIEND_VIA_UID_DISABLED` | 对方关闭 UID 搜索加好友 |
| 403 | `ADD_FRIEND_VIA_PHONE_DISABLED` | 对方关闭手机号搜索加好友 |
| 404 | `USER_NOT_FOUND` | 用户不存在或不可用 |
| 404 | `FRIEND_NOT_FOUND` | 好友关系不存在 |
| 404 | `REQUEST_NOT_FOUND` | 好友申请不存在或非接收方 |
| 404 | `RECORD_NOT_FOUND` | 申请历史记录不存在 |
| 409 | `ALREADY_FRIENDS` | 已是双向好友 |
| 409 | `REQUEST_ALREADY_HANDLED` | 申请已处理 |
| 429 | `FRIEND_REQUEST_COOLDOWN` | 同对用户 10 分钟内重复申请 |
| 429 | `SEARCH_BLOCKED` | 搜索失败次数过多被临时封禁 |
| 403 | `USER_BLOCKED` | 任一侧已拉黑，无法添加或同意好友 |
| 503 | `IM_UNAVAILABLE` | IM 已配置但黑名单检查失败；申请/同意/预检均不放行 |

---

## 3. 枚举

### addSource（申请来源，`POST /friend-requests`）

| 值 | 含义 |
|----|------|
| `qr_code` | 扫码 |
| `search` | 搜索 UID |
| `phone` | 搜索手机号 |
| `nearby` | 附近的人 |
| `card` | 名片 |
| `group` | 群聊 |

### channel（隐私预检，`POST /users/add-friend/check`）

| 值 | 含义 |
|----|------|
| `card` | 名片 |
| `qr` | 扫码 |
| `group` | 群聊 |

### outcome（`POST /friend-requests` 响应）

| 值 | 含义 |
|----|------|
| `pending` | 待对方处理 |
| `auto_accepted` | 双向 pending，自动同意 |
| `restored` | **已废弃**：不再返回；删后再加走普通 pending / auto_accepted |

### status（申请历史）

| 值 | 含义 |
|----|------|
| `accepted` | 已同意 |
| `rejected` | 已拒绝 |

---

## 4. 好友关系

### GET `/me/friends`

好友列表（**有界分页**；不再一次返回全部好友）。

**Query 参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `limit` | Int | 否 | 每页条数，默认 `100`，范围 `1..200` |
| `cursor` | String | 否 | 上一页返回的 `nextCursor`；首页省略 |

排序：按好友边内部 `id` 升序（稳定游标）。`lastActiveAt` / presence 仅覆盖**本页**好友；视口外请优先用 TCP `presence_last_seen`（单批上限 200）补拉；未连 TCP 时回退 `POST /presence/last-seen`。

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `items` | Array | 本页好友条目 |
| `nextCursor` | String? | 下一页游标；无下一页为 `null` |
| `hasMore` | Boolean | 是否还有下一页 |
| `total` | Long | 当前有效好友总数 |
| `items[].friendUserId` | String | 好友 userId |
| `items[].friendNickname` | String | 好友昵称缓存 |
| `items[].friendAvatarUrl` | String | 好友头像缓存 |
| `items[].remark` | String | 我的备注；无备注时为 `""` |
| `items[].addedAt` | Instant? | 成为好友时间 |
| `items[].peerDeletedMe` | Boolean | 兼容字段：对方已删我（用户删除已改为双向后，正常路径不再出现） |
| `items[].canMessage` | Boolean | 是否可发消息（双向有效） |
| `items[].inMyFriendList` | Boolean | 我是否仍保留对方为好友（列表内恒为 `true`） |
| `items[].isFriend` | Boolean | 是否双向好友，同 `canMessage` |
| `items[].lastActiveAt` | Long? | 对方最后活跃时间戳（毫秒，UTC）；始终返回原始值，无活跃记录时为 `null` |
| `items[].lastActiveVisibility` | String? | 对方在线时间可见性：`everyone` / `friends_only` / `hidden`；是否展示由客户端决定 |

**兼容说明**：未传 `limit` 时按默认 `100` 截断并返回 `hasMore`/`nextCursor`，**不再全量 dump**。海量好友客户端须按页拉完或按需分页。

**错误码**：400 `INVALID_INPUT`（`limit` 越界或 `cursor` 非法）· 401

---

### GET `/me/friends/{peerUserId}/relation`

查询我与某用户的好友关系（加好友前、资料页展示用）。

> 好友列表请用 `GET /me/friends`（已含 `isFriend` / `peerDeletedMe` / `canMessage`），**勿**对列表里每个人再扇出本接口。服务端对 relation 做了双向边合并查询 + 约 15s 短缓存。

**路径参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| `peerUserId` | String | 对方 userId |

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `peerUserId` | String | 对方 userId |
| `isFriend` | Boolean | 是否双向好友（可互发消息） |
| `inMyFriendList` | Boolean | 我是否仍保留对方为好友 |
| `peerDeletedMe` | Boolean | 兼容字段：我留着对方，但对方已删我（历史单边数据）；用户 `DELETE` 已改为双向后正常为 false |
| `canMessage` | Boolean | 是否可发消息，同 `isFriend` |

**错误码**：400 `INVALID_INPUT`（含查自己）· 404 `USER_NOT_FOUND` · 401

---

### PUT `/me/friends/{friendUserId}/remark`

修改备注。

**路径参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| `friendUserId` | String | 好友 userId |

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `remark` | String | 否 | 备注；空串/null 清空 |

**响应字段**

| 字段 | 类型 |
|------|------|
| `friendUserId` | String |
| `remark` | String? |

**错误码**：400 `INVALID_INPUT` · 404 `FRIEND_NOT_FOUND` · 401

成功后服务端向本人其他在线端推送 `friend_list_changed`（`action=remark_updated`），payload 含最新 `remark`。

---

### DELETE `/me/friends/{friendUserId}`

**双向删除**好友：软删「我→对方」与「对方→我」两条边；双方通讯录均移除该联系人。下次须重新 `POST /friend-requests`。

**路径参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| `friendUserId` | String | 好友 userId |

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `friendUserId` | String | |
| `deleted` | Boolean | 我侧本次是否删除成功 |

成功后服务端向**双方**推送 `friend_list_changed`（`action=removed`）。

**错误码**：400 `INVALID_INPUT` · 404 `FRIEND_NOT_FOUND` · 401

---

### 通讯录黑名单

前端对接请用独立文档：[blocklist-client.md](./blocklist-client.md)。

存储在腾讯 IM SNS 黑名单，**不建**本地 `user_block` 表。客户端必须走下面 REST，不要只调 IM SDK：否则 pending 作废和对账会对不上。

口径：

- 拉黑 / 解除 **都不改** 自建 `user_friend`。`GET /me/friends` 仍可能列出被拉黑的人。
- `black_list_add` 会拆掉 IM SNS 好友；客户端不要用 IM 好友列表判断是否还是好友。
- 非好友也可以拉黑。对方不存在 `404 USER_NOT_FOUND`；拉黑自己 `400 INVALID_INPUT`。
- 任一侧把对方加进黑名单后，**双方**都不能 `POST /friend-requests`，也不能同意 / 自动通过（`403 USER_BLOCKED`）。IM 已配置但检查失败时 `503 IM_UNAVAILABLE`，不放行。
- 拉黑时作废 AB 双向 pending（按现有拒绝逻辑：`rejected`、写历史、清频控、发 realtime）。
- 拉群 / 建群拉人 / 管理端强制加好友（`bindMutualFriends`）**不查**黑名单。强制加后本地可成好友，但若 IM 黑名单还在，C2C 仍可能被 IM 拒收。
- C2C `BeforeSendMsg` 不改；单聊拒收交给 IM 黑名单。解除拉黑后，若自建仍是好友，单聊按现有好友规则恢复。

#### POST `/me/blocks`

拉黑。先写 IM 黑名单，再作废双向 pending。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `userId` | String | 是 | 对方 userId |

**响应示例**

```json
{ "ok": true, "userId": "b1" }
```

**错误码**：400 `INVALID_INPUT` · 404 `USER_NOT_FOUND` · 502 `IM_REST_ERROR` · 401

---

#### DELETE `/me/blocks/{userId}`

解除拉黑（只删 IM 黑名单）。

**响应示例**

```json
{ "ok": true, "userId": "b1" }
```

**错误码**：400 `INVALID_INPUT` · 502 `IM_REST_ERROR` · 401

---

#### GET `/me/blocks`

分页拉取我的黑名单。

**Query 参数**

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `startIndex` | Int | 0 | IM 分页游标 |
| `limit` | Int | 50 | 每页条数，夹到 1–100 |

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `items` | Array | 本页黑名单 |
| `items[].userId` | String | 被拉黑人 userId |
| `items[].nickname` | String? | 用户表昵称；用户已删可为 `null` |
| `items[].avatarUrl` | String? | 用户表头像 |
| `items[].blockedAt` | Long? | 拉黑时间（毫秒，由 IM `AddBlackTimeStamp` 秒转换） |
| `startIndex` | Int | 下一页游标；无下一页时按 IM 返回（完成则为 0） |
| `hasMore` | Boolean | `startIndex > 0` 为还有下一页 |

**响应示例**

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

**错误码**：502 `IM_REST_ERROR` · 401

---

## 5. 好友申请

### POST `/friend-requests`

发起申请 / 自动同意 / 直接恢复。

当目标用户 `friendAddRequiresVerify=false` 时，申请会直接同意，响应 `outcome=auto_accepted`（不进入 pending 列表）。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `targetUserId` | String | 是 | 目标 userId |
| `addWording` | String | 否 | 验证语 |
| `addSource` | String | 是 | 见 [§3 addSource](#addsource申请来源post-friend-requests) |

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `outcome` | String | `pending` / `auto_accepted`（`restored` 已废弃，不再返回） |
| `requestId` | Long? | pending/auto_accepted 时有值 |

**错误码**：400 `INVALID_INPUT` · 403 隐私错误码 · 403 `USER_BLOCKED` · 404 `USER_NOT_FOUND` · 409 `ALREADY_FRIENDS` · 429 `FRIEND_REQUEST_COOLDOWN` · 503 `IM_UNAVAILABLE` · 401

---

### GET `/friend-requests/incoming`

收到的 pending 申请。

**Query**

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `limit` | int | 100 | 1–200 |

**响应字段**

| 字段 | 类型 |
|------|------|
| `items` | Array |
| `items[].id` | Long |
| `items[].fromUserId` | String |
| `items[].toUserId` | String |
| `items[].peerUserId` | String | 列表展示用对方 userId（incoming=申请人，outgoing=被申请人） |
| `items[].peerNickname` | String | 对方昵称（实时读 `users` 表） |
| `items[].peerAvatarUrl` | String? | 对方头像（实时读 `users` 表，换头像后刷新即最新） |
| `items[].addWording` | String? |
| `items[].addSource` | String |
| `items[].status` | String | `pending` / `accepted` / `rejected` |
| `items[].createdAt` | Instant |
| `items[].handledAt` | Instant? | 已处理时有值 |

**错误码**：401

---

### DELETE `/friend-requests/incoming/{id}`

删除一条「对方添加我」的记录（须为接收方）。

**路径参数**

| 参数 | 类型 |
|------|------|
| `id` | Long |

**响应字段**

| 字段 | 类型 |
|------|------|
| `id` | Long |
| `deleted` | Boolean |

**错误码**：404 `REQUEST_NOT_FOUND` · 401

---

### DELETE `/friend-requests/incoming/batch`

批量删除「对方添加我」的记录。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ids` | Long[] | 是 | 1–200 条 |

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `deleted` | int | 实际删除条数（仅删本人为接收方的记录） |

**错误码**：400 `INVALID_INPUT` · 401

---

### GET `/friend-requests/outgoing`

发出的 **pending** 申请。参数与响应同 [incoming](#get-friend-requestsincoming)（`status` 恒为 `pending`）。

**错误码**：401

---

### GET `/friend-requests/sent`

我发起的加好友记录（含待处理、已通过、已拒绝）。

**Query**

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `limit` | int | 100 | 1–200 |

**响应字段**：同 [incoming](#get-friend-requestsincoming)；`peerUserId` 为被申请人，`status` 可为 `pending` / `accepted` / `rejected`。

**错误码**：401

---

### DELETE `/friend-requests/sent/{id}`

删除一条「我发出的」加好友记录（须为发起方）。

**路径参数**

| 参数 | 类型 |
|------|------|
| `id` | Long |

**响应字段**

| 字段 | 类型 |
|------|------|
| `id` | Long |
| `deleted` | Boolean |

**错误码**：404 `REQUEST_NOT_FOUND` · 401

---

### DELETE `/friend-requests/sent/batch`

批量删除「我发出的」加好友记录。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ids` | Long[] | 是 | 1–200 条 |

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `deleted` | int | 实际删除条数（仅删本人为发起方的记录） |

**错误码**：400 `INVALID_INPUT` · 401

---

### POST `/friend-requests/{id}/accept`

同意申请。

**路径参数**

| 参数 | 类型 |
|------|------|
| `id` | Long |

**响应字段**

| 字段 | 类型 |
|------|------|
| `ok` | Boolean |

**错误码**：403 `USER_BLOCKED` · 404 `REQUEST_NOT_FOUND` · 409 `REQUEST_ALREADY_HANDLED` · 503 `IM_UNAVAILABLE` · 401

---

### POST `/friend-requests/{id}/reject`

拒绝申请。

**路径参数**

| 参数 | 类型 |
|------|------|
| `id` | Long |

**响应字段**

| 字段 | 类型 |
|------|------|
| `ok` | Boolean |

**错误码**：404 `REQUEST_NOT_FOUND` · 409 `REQUEST_ALREADY_HANDLED` · 401

---

## 6. 搜索与隐私

### POST `/users/search`

搜索用户（加好友入口）。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `keyword` | String | 是 | UID 或手机号；可带 `@` 前缀 |
| `phoneCountry` | String | 否 | 纯数字手机号时的国家码，默认 `CN` |

**响应字段**

| 字段 | 类型 |
|------|------|
| `userId` | String |
| `nickname` | String |
| `avatarUrl` | String? |
| `phoneMasked` | String? |
| `lastActiveAt` | Long? |

**错误码**：400 `INVALID_INPUT` · 404 `USER_NOT_FOUND` · 429 `SEARCH_BLOCKED` · 401

---

### POST `/users/add-friend/check`

加好友隐私预检（需登录）。任一侧拉黑时不抛错，返回 `allowed=false, reason=USER_BLOCKED`（与渠道禁用同一返回形）。IM 已配置但检查失败时 `503 IM_UNAVAILABLE`。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `targetUserId` | String | 是 | |
| `channel` | String | 是 | `card` / `qr` / `group` |

**响应字段**

| 字段 | 类型 |
|------|------|
| `allowed` | Boolean |
| `reason` | String? |
| `friendAddRequiresVerify` | Boolean | 目标用户是否需验证；`false` 时 POST `/friend-requests` 会直接 `auto_accepted` |

**错误码**：400 `INVALID_INPUT` · 404 `USER_NOT_FOUND` · 503 `IM_UNAVAILABLE` · 401

`reason` 还可能为 `USER_BLOCKED`（HTTP 仍 200）。

---

### GET `/me/friend-add-verify`

读取「添加我时需验证」开关。

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `friendAddRequiresVerify` | Boolean | `true`=需验证；`false`=直接成为好友 |

**错误码**：404 `USER_NOT_FOUND` · 401

---

### PUT `/me/friend-add-verify`

修改「添加我时需验证」开关。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `friendAddRequiresVerify` | Boolean | 是 | `true`=需验证；`false`=直接通过 |

**响应字段**：同 GET

**错误码**：400 `INVALID_INPUT` · 404 `USER_NOT_FOUND` · 401

---

### GET `/users/{userId}/friend-add-verify`

查看对方「添加我时需验证」设置（加好友前 UI 提示用）。

**路径参数**

| 参数 | 类型 |
|------|------|
| `userId` | String |

**响应字段**：同 [GET `/me/friend-add-verify`](#get-mefriend-add-verify)

**错误码**：404 `USER_NOT_FOUND`

---

### GET `/me/online-privacy-protection`

读取「最后上线时间」可见性设置。

**响应字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `lastActiveVisibility` | String | 见下表 |

| 值 | UI 文案 | 说明 |
|----|---------|------|
| `everyone`（默认） | 所有人可查看 | 任意用户可见你的最后上线时间 |
| `friends_only` | 仅好友可查看 | 仅**双向好友**可见 |
| `hidden` | 不显示在线时间 | 他人均不可见 |

**错误码**：404 `USER_NOT_FOUND` · 401

---

### PUT `/me/online-privacy-protection`

修改「最后上线时间」可见性。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `lastActiveVisibility` | String | 是 | `everyone` / `friends_only` / `hidden` |

**请求示例**

```json
{ "lastActiveVisibility": "friends_only" }
```

**响应示例**

```json
{ "lastActiveVisibility": "friends_only" }
```

**错误码**：400 `INVALID_INPUT` · 404 `USER_NOT_FOUND` · 401

---

### GET `/users/{userId}/online-privacy-protection`

查看对方「最后上线时间」可见性（资料页 / 会话列表 UI 用）。

**路径参数**

| 参数 | 类型 |
|------|------|
| `userId` | String |

**响应字段**：同 [GET `/me/online-privacy-protection`](#get-meonline-privacy-protection)

**错误码**：404 `USER_NOT_FOUND`

---

### GET `/users/{userId}/privacy`

查看对方加好友隐私（无需登录）。

**路径参数**

| 参数 | 类型 |
|------|------|
| `userId` | String |

**响应字段**

| 字段 | 类型 |
|------|------|
| `allowViaQrCode` | Boolean |
| `allowViaCard` | Boolean |
| `allowViaGroup` | Boolean |
| `allowViaPhone` | Boolean |
| `allowViaUid` | Boolean |

**错误码**：404 `USER_NOT_FOUND`

---

### GET `/me/privacy`

读取我的加好友隐私。

**响应字段**：同 [GET `/users/{userId}/privacy`](#get-usersuseridprivacy)

**错误码**：404 `USER_NOT_FOUND` · 401

---

### PUT `/me/privacy`

修改我的加好友隐私。

**请求体**

| 字段 | 类型 | 必填 |
|------|------|------|
| `allowViaQrCode` | Boolean | 是 |
| `allowViaCard` | Boolean | 是 |
| `allowViaGroup` | Boolean | 是 |
| `allowViaPhone` | Boolean | 是 |
| `allowViaUid` | Boolean | 是 |

**响应字段**：同 GET

**错误码**：400 `INVALID_INPUT` · 404 `USER_NOT_FOUND` · 401

---

## 7. 申请历史

> 新客户端同意/拒绝请用 `/friend-requests`；`/friend-application/accept` 为旧接口，勿双轨调用。

### GET `/friend-application/history`

已同意/已拒绝历史（cursor 分页）。

**Query**

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `cursor` | Instant | — | 上次 `nextCursor` |
| `limit` | int | 100 | 1–200 |

**响应字段**

| 字段 | 类型 |
|------|------|
| `content` | Array |
| `content[].id` | Long |
| `content[].peerUserId` | String |
| `content[].peerNickname` | String |
| `content[].peerFaceUrl` | String? |
| `content[].addWording` | String? |
| `content[].addSource` | String |
| `content[].addTime` | Instant |
| `content[].status` | String |
| `content[].handledAt` | Instant? |
| `nextCursor` | Instant? |
| `hasMore` | Boolean |

**错误码**：401

---

### DELETE `/friend-application/history/{id}`

删除一条历史。

**路径参数**

| 参数 | 类型 |
|------|------|
| `id` | Long |

**响应字段**

| 字段 | 类型 |
|------|------|
| `ok` | Boolean |
| `id` | Long |

**错误码**：404 `RECORD_NOT_FOUND` · 401

---

### POST `/friend-application/accept`（旧，deprecated）

**请求体**

| 字段 | 类型 | 必填 |
|------|------|------|
| `peerUserId` | String | 是 |
| `addTime` | Instant | 是 |
| `addWording` | String | 否 |
| `addSource` | String | 是 |

**响应**：204 无 body

**错误码**：400 `INVALID_INPUT`

---

## 8. 星标好友

### GET `/me/starred-friends`

**响应字段**

| 字段 | 类型 |
|------|------|
| `items` | Array |
| `items[].friendUserId` | String |
| `items[].starredAt` | Instant |

**错误码**：401

---

### PUT `/me/starred-friends/{friendUserId}`

设星标（幂等）。

**路径参数**

| 参数 | 类型 |
|------|------|
| `friendUserId` | String |

**响应字段**

| 字段 | 类型 |
|------|------|
| `friendUserId` | String |
| `starred` | Boolean |
| `starredAt` | Instant? |

**错误码**：400 `INVALID_INPUT` · 400 `CANNOT_STAR_SELF` · 404 `USER_NOT_FOUND` · 401

---

### DELETE `/me/starred-friends/{friendUserId}`

取消星标（幂等）。路径参数、响应字段同 PUT。

**错误码**：400 `INVALID_INPUT` · 400 `CANNOT_STAR_SELF` · 401

---

## 9. 会话免打扰

### GET `/me/conversation-notify`

**响应**：`Array<NotifySettingView>`

| 字段 | 类型 |
|------|------|
| `chatType` | String |
| `peerId` | String |
| `muted` | Boolean |

**错误码**：401

---

### PUT `/me/conversation-notify`

**请求体**

| 字段 | 类型 | 必填 |
|------|------|------|
| `chatType` | String | 是 |
| `peerId` | String | 是 |
| `muted` | Boolean | 是 |

**响应字段**

| 字段 | 类型 |
|------|------|
| `ok` | Boolean |

**错误码**：400 `INVALID_INPUT` · 401

---

### PUT `/me/conversation-notify/batch`

**请求体**

| 字段 | 类型 | 必填 |
|------|------|------|
| `items` | Array | 是 |
| `items[].chatType` | String | 是 |
| `items[].peerId` | String | 是 |
| `items[].muted` | Boolean | 是 |

**响应字段**

| 字段 | 类型 |
|------|------|
| `ok` | Boolean |
| `count` | int |

**错误码**：400 `INVALID_INPUT` · 401

---

## 10. TCP 实时推送（好友申请）

> 协议：TCP 明文 JSON 行（每条消息以 `\n` 结尾）  
> 默认端口：**8082**（与 HTTP 8081 分离）  
> 配置：`chat99.realtime.*`

### 连接

| 项 | 值 |
|----|-----|
| 协议 | TCP |
| 地址 | 与 API 同 host |
| 端口 | `8082`（`REALTIME_TCP_PORT`） |
| 编码 | UTF-8 |
| 帧格式 | 一行一条 JSON，以 `\n` 结束 |

### 客户端 → 服务端

#### 鉴权（连接后 **10 秒内**必须发送，否则断开）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | 是 | 固定 `auth` |
| `token` | String | 是 | JWT（与 REST 相同） |
| `deviceId` | String | 否 | 设备 ID；缺省取 token 内 `did` |

#### 心跳

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | 是 | 固定 `ping` |
| `deviceId` | String | 否 | 设备 ID；传入则更新本连接设备并走 `deviceHeartbeat` |

建议间隔 **30s**；**90s** 无读活动服务端断开。

TCP 已连接时应用 `ping` 替代周期 `POST /me/heartbeat`（HTTP 仅作未连 TCP / 降级回退）。`auth` 成功与 `ping` 均会触发活跃时间更新（共用 30s 节流）；好友可见时通过 `presence_changed` 事件推送。

#### 批量 last-seen（冷启动 / 视口补拉）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | 是 | 固定 `presence_last_seen` |
| `requestId` | String | 是 | 客户端关联请求；响应原样带回 |
| `userIds` | String[] | 是 | 去重后单批 ≤ `max-batch-size`（默认 200） |

单连接并发未完成查询上限 **3**；超出回 `presence_last_seen_fail` / `TOO_MANY_INFLIGHT`。未连 TCP 时回退 `POST /presence/last-seen`。

### 服务端 → 客户端

| type | 说明 |
|------|------|
| `auth_ok` | 鉴权成功 |
| `auth_fail` | 鉴权失败，随后断开 |
| `pong` | 心跳响应（含 `ts` 毫秒） |
| `presence_last_seen_ok` | last-seen 成功 |
| `presence_last_seen_fail` | last-seen 失败 |
| `event` | 业务事件（见下表） |
| `error` | 协议错误 |

**`pong` 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 固定 `pong` |
| `ts` | Long | 服务端毫秒时间戳 |

**`presence_last_seen_ok` 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 固定 `presence_last_seen_ok` |
| `requestId` | String | 请求原样带回 |
| `lastSeen` | Object | userId → 最后活跃毫秒（UTC）；与 HTTP 同义 |
| `lastActiveVisibility` | Object | userId → 可见性枚举；与 HTTP 同义 |
| `ts` | Long | 服务端毫秒时间戳 |

**`presence_last_seen_fail` 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 固定 `presence_last_seen_fail` |
| `requestId` | String? | 能解析时带回 |
| `code` | String | `INVALID_INPUT` / `BATCH_TOO_LARGE` / `TOO_MANY_INFLIGHT` / `INTERNAL` |
| `ts` | Long | 服务端毫秒时间戳 |

**`auth_fail` / `error` 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | String | `UNAUTHORIZED` / `INVALID_INPUT` / `UNKNOWN_TYPE` |

**`event` 公共字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 固定 `event` |
| `event` | String | 事件名 |
| `fromUserId` | String | 申请人 |
| `toUserId` | String | 被申请人 |
| `requestId` | Long? | 申请 ID |
| `addWording` | String? | 验证语 |
| `addSource` | String? | 来源 |
| `createdAt` | String? | ISO8601 |
| `ts` | Long | 服务端毫秒时间戳 |

**事件名**

| event | 推送给 | 触发 |
|-------|--------|------|
| `friend_request_received` | `toUserId` | 收到新申请 |
| `friend_request_accepted` | `fromUserId` | 对方同意 |
| `friend_request_rejected` | `fromUserId` | 对方拒绝 |
| `friend_request_auto_accepted` | 双方 | 双向 pending 自动同意 / 对方关闭验证 |
| `friend_restored` | — | **已废弃**，服务端不再推送 |
| `friend_list_changed` | 列表变动方 | 好友关系新增/双向删除等 |
| `presence_changed` | 可查看对方上线时间的好友 | 好友活跃时间更新（心跳 / 登录 / TCP auth·ping） |

**`presence_changed` 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `peerUserId` | String | 状态变化的好友 |
| `lastActiveAt` | Long? | 最后活跃毫秒时间戳（UTC）；无活跃记录时为 `null` |
| `lastActiveVisibility` | String? | 在线时间可见性：`everyone` / `friends_only` / `hidden` |
| `online` | Boolean | 当前窗口内视为在线 |

**`presence_changed` 推送对象**

| 条件 | 推送给 |
|------|--------|
| 对方在你通讯录中（有效好友边） | 你 |

服务端始终推送原始 `lastActiveAt` 与 `lastActiveVisibility`；是否展示由客户端根据可见性与好友关系决定。

**`friend_list_changed` 字段**（在 `event` 公共字段之外）

| 字段 | 类型 | 说明 |
|------|------|------|
| `action` | String | `added` / `removed` / `updated` / `profile_updated` / `remark_updated` |
| `peerUserId` | String | 变动涉及的好友 |
| `peerNickname` | String? | 好友昵称 |
| `peerAvatarUrl` | String? | 好友头像 |
| `remark` | String | 我的备注 |
| `addedAt` | String? | 成为好友时间 ISO8601 |
| `inMyFriendList` | Boolean | 我是否仍保留对方 |
| `isFriend` | Boolean | 是否双向好友 |
| `peerDeletedMe` | Boolean | 兼容字段；用户双向删除后正常为 `false` |
| `canMessage` | Boolean | 是否可发消息 |
| `lastActiveAt` | Long? | 对方最后活跃毫秒时间戳（UTC）；始终返回原始值 |
| `lastActiveVisibility` | String? | 对方在线时间可见性 |

**`friend_list_changed` 推送对象**

| action | 推送给 | 触发 |
|--------|--------|------|
| `added` | 双方 | 同意申请 / 自动通过 |
| `removed` | **双方** | 双向 `DELETE /me/friends/{id}` / Admin 强制删 |
| `updated` | 列表方 | 资料/关系字段更新（用户删除已不再用此 action 表达「对方删我」） |
| `profile_updated` | 所有仍保留该好友的用户 | 好友修改昵称 / 头像（`PATCH /me/nickname`、`POST /me/avatar`） |
| `remark_updated` | 备注修改方本人 | `PUT /me/friends/{friendUserId}/remark`（多端同步备注） |

### 客户端处理建议

| 收到 event | 动作 |
|------------|------|
| `friend_request_received` | 刷新 incoming 列表 / 红点 |
| `friend_request_accepted` | 刷新好友列表 + outgoing |
| `friend_request_rejected` | 刷新 outgoing / 历史 |
| `friend_request_auto_accepted` | 刷新好友列表 |
| `friend_restored` | **已废弃**，可忽略 |
| `friend_list_changed` `action=added` | 增量插入或 `GET /me/friends` 全量刷新 |
| `friend_list_changed` `action=removed` | 从本地通讯录移除 `peerUserId` |
| `friend_list_changed` `action=updated` | 更新条目关系/展示字段（兼容） |
| `friend_list_changed` `action=profile_updated` | 更新条目的 `peerNickname` / `peerAvatarUrl` 等展示字段 |
| `friend_list_changed` `action=remark_updated` | 更新条目的 `remark` |
| `friend_list_changed`（任意 action） | payload 含同源 **`seq`**；与 [friend-contact-incremental-sync-client.md](./friend-contact-incremental-sync-client.md) Difference 去重 |

断线补偿：`GET /me/friends/changes?since_seq=`（见好友通讯录 Versioned Sync 文档）。

断线后指数退避重连；重连后重新 `auth`，并再拉 Difference。

### 离线补推（系统 Push）

TCP 不可达时，**好友申请**（`friend_request_*`）仍可补发系统 Push；**好友列表变动**（`friend_list_changed`）默认**不**发 Push，仅在线 TCP。

| 项 | 说明 |
|----|------|
| 触发条件 | 目标用户 **无** TCP 在线连接 |
| 好友申请 Push | `chat99.realtime.offline-push-enabled`（默认 `true`） |
| 好友列表 Push | `chat99.realtime.friend-list-offline-push-enabled`（默认 **`false`**） |
| Push `data.type` | `friend_request`（`friend_list` 默认关闭） |

**Push data 字段**

| 字段 | 说明 |
|------|------|
| `type` | `friend_request` |
| `event` | `friend_request_received` / `accepted` / `rejected` / `auto_accepted`（`friend_restored` 已废弃） |
| `fromUserId` | 申请人 |
| `toUserId` | 被申请人 |
| `requestId` | 申请 ID（可选） |
| `addSource` | 来源（可选） |

**通知栏文案**

| event | title | body 示例 |
|-------|-------|-----------|
| `friend_request_received` | 新的好友申请 | `{昵称} 请求添加你为好友` |
| `friend_request_accepted` | 好友申请已通过 | `{昵称} 已同意你的好友申请` |
| `friend_request_rejected` | 好友申请未通过 | `{昵称} 拒绝了你的好友申请` |
| `friend_request_auto_accepted` | 已成为好友 | `你与 {昵称} 已成为好友` |
| `friend_restored` | — | **已废弃**，服务端不再推送 |

`friend_list_changed` 默认不发 Push；在线靠 TCP，离线用户上线后拉 `GET /me/friends` 补数据。

客户端点击通知：按 `data.event` 跳转「新的朋友」或刷新好友列表。

### 群聊变动（`group_changed`）

> 需在 IM 控制台开启对应 Group 回调（同一 URL `/webhook/im/message`）。

**事件名**：`group_changed`

**公共字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| `groupId` | String | 群 ID |
| `action` | String | 见下表 |
| `operatorUserId` | String? | 操作者 |
| `memberUserIds` | String[]? | 本次涉及的成员 |
| `detail` | Object? | 动作相关详情 |

**action 与触发**

| action | 说明 | IM 回调 / 来源 |
|--------|------|----------------|
| `group_notice_changed` | 群公告 | `Group.CallbackAfterGroupInfoChanged` · Notification |
| `group_name_changed` | 群昵称 | 同上 · Name |
| `group_avatar_changed` | 群头像 | 同上 · FaceUrl |
| `group_mute_all_changed` | 全员禁言 | 同上 · ShutUpAllMember |
| `group_join_option_changed` | 加群方式 | `PUT /group/{id}/join-options` 或 IM 回调 · ApplyJoinOption |
| `join_application_pending` | 新待审批 | `POST /group/{id}/members` / `POST /group/{id}/join` |
| `join_application_handled` | 审批结果 | `POST .../join-applications/{id}/approve` / `reject` |
| `group_privacy_changed` | 群隐私保护 | `PUT /group/{id}/privacy` |
| `member_added` | 添加群成员 | `AfterNewMemberJoin` / `AfterMemberInvited` |
| `member_removed` | 移除群成员 | `AfterMemberKicked` |
| `member_left` | 成员退群 | `AfterMemberExit` |
| `member_role_changed` | 设置/取消管理员 | `OnMemberStateChange` · Role |
| `member_muted` | 成员禁言 | `OnMemberStateChange` · MutedUntil |
| `member_profile_changed` | 群名片等 | `AfterMemberFieldChanged` |
| `owner_changed` | 转让群主 | `AfterChangeGroupOwner` |
| `group_dismissed` | 解散群聊 | `AfterGroupDestroyed` |

**客户端处理建议**：收到后按 `detail` 增量 patch 本地群资料/成员列表（字段名与 [group-profile-client.md](./group-profile-client.md) REST 一致）。`member_added` / `member_removed` / `member_left` 的 `detail` 含 `memberCount`、`memberUserIds`、`updatedAt`。

**离线**：`group_changed` 默认**不**发系统 Push（`chat99.realtime.group-offline-push-enabled=false`）。在线靠 TCP；离线用户上线后拉 `GET /me/groups` 等 REST 补数据。入群审批亦不发系统号 C2C（见 [group-member-invite.md](./group-member-invite.md) §6）。

### 最近通话（`call_recent_changed`）

拨号邀请、接听、挂断落库后，主叫与被叫各收到一条 TCP 事件，用于**实时刷新**最近通话列表（无需等轮询 `GET /calls/recent`）。

**事件名**：`call_recent_changed`

| 字段 | 类型 | 说明 |
|------|------|------|
| `action` | String | 固定 `added`（新增或更新一条记录） |
| `callId` | String | 通话 ID |
| `peerUserId` | String | 对方用户 ID |
| `peerName` | String? | 对方昵称 |
| `peerAvatar` | String? | 对方头像 |
| `mediaType` | String | `audio` / `video` |
| `direction` | String | `outgoing` / `incoming` |
| `result` | String | `answered` / `missed` / `rejected` / `canceled` / `busy` / `failed` |
| `durationSec` | int | 通话时长（秒） |
| `occurredAt` | long | 发生时间（毫秒） |

**客户端处理建议**：收到后将 `callId` 对应条目插入或更新到本地最近通话列表头部；字段与 `GET /calls/recent` 的 `items[]` 一致。

---

## 11. IM 发消息（非 REST）

| 能力 | 方式 |
|------|------|
| 发 C2C | IM SDK |
| 非双向好友拦截 | 服务端 `C2C.CallbackBeforeSendMsg`，IM 返回 `ErrorInfo=NOT_FRIEND` |
| 群聊 | 不做好友校验 |

### 服务端 IM SNS 兜底（双写）

自建权威库加/删好友、改备注（含清空）成功后，服务端会同步腾讯 IM SNS：

| 自建操作 | IM REST |
|----------|---------|
| 双向加好友 | `sns/friend_add`（`Add_Type_Both`） |
| 双向删好友 | `sns/friend_delete`（`Delete_Type_Both`） |
| 改/清空备注 | `sns/friend_update`（`Tag_SNS_IM_Remark`；清空写空串） |

- IM 与本地同一请求同步完成；IM 失败则 REST 失败（`IM_REST_ERROR`）并回滚本地。
- 客户端仍可用 IM SDK 自行写 SNS；与后端双写依赖 IM 幂等。
- **系统通知号 / 钱包通知号**注册绑定好友仅写自建库，**不同步** IM SNS。

### 服务端群资料 / 成员 IM

| 自建操作 | IM REST | 时机 |
|----------|---------|------|
| 入群通过 / 邀请加人 | `add_group_member` | 与本地同一请求；失败 REST 失败并回滚 |
| 踢人 / 退群 | `delete_group_member` | 同上 |
| 改群名 / 公告 | `modify_group_base_info` | `afterCommit`，失败入 `ImRestQueue` |
| 改群头像 | `modify_group_base_info`（FaceUrl） | 同上 |
| 加群选项 | `modify_group_base_info`（Apply/InviteJoinOption） | 同上 |
| 解散 | `destroy_group` | 同上 |

---

## 12. IM SDK → REST 对照

| IM SDK | REST |
|--------|------|
| `getFriendList` | `GET /me/friends` |
| `addFriend` | `POST /friend-requests` |
| 同意 | `POST /friend-requests/{id}/accept` |
| 拒绝 | `POST /friend-requests/{id}/reject` |
| `setFriendRemark` | `PUT /me/friends/{friendUserId}/remark` |
| `deleteFriend` | `DELETE /me/friends/{friendUserId}` |
| `addToBlackList` | `POST /me/blocks` |
| `deleteFromBlackList` | `DELETE /me/blocks/{userId}` |
| `getBlackList` | `GET /me/blocks` |
