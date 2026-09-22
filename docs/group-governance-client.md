# 群治理与建群 — 客户端对接文档

> 版本：v1.0  
> 原则：建群、退群、踢人、解散、转让等**全部走 REST**；IM 仅负责消息通道；变更通过 TCP `group_changed` 同步。

关联：[group-profile-client.md](./group-profile-client.md) · [group-member-invite.md](./group-member-invite.md) · [group-entity-incremental-sync-client.md](./group-entity-incremental-sync-client.md)

---

## 1. 总览

| 能力 | 方法 | 路径 |
|------|------|------|
| 建群 | POST | `/group` |
| 主动退群 | POST | `/group/{groupId}/leave` |
| 解散群 | POST | `/group/{groupId}/dismiss` |
| 踢人 | DELETE | `/group/{groupId}/members/{userId}` |
| 批量踢人 | DELETE | `/group/{groupId}/members` |
| 设/取消管理员 | PUT | `/group/{groupId}/members/{userId}/role` |
| 转让群主 | POST | `/group/{groupId}/transfer-owner` |
| 成员禁言 | PUT | `/group/{groupId}/members/{userId}/mute` |
| 全员禁言 | PUT | `/group/{groupId}/mute-all` |

鉴权：`Authorization: Bearer <JWT>`

---

## 2. `POST /group` 建群

**Body**

```json
{
  "groupType": "Public",
  "groupName": "产品讨论群",
  "avatarUrl": "https://cdn.../thumb.jpg",
  "memberUserIds": ["friend01"],
  "joinOptions": {
    "applyJoinOption": "need_permission",
    "inviteJoinOption": "need_permission"
  },
  "introduction": "可选群简介"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `groupType` | ✅ | `Public` / `Meeting` / `Community` / `Work` |
| `groupName` | ✅ | 群名称 |
| `memberUserIds` | 否 | 初始成员（不含创建者）；须双向好友，最多 100 |
| `avatarUrl` | 否 | 可先 `POST /group/avatar/upload`；省略则用默认群头像 |
| `joinOptions` | 否 | 默认双 `need_permission` |

**响应 `201`**：与 `GET /group/{groupId}` 相同（`GroupProfileView`）

**错误码**：`INVALID_INPUT` · `GROUP_TYPE_NOT_SUPPORTED` · `CREATE_LIMIT_EXCEEDED` · `NOT_FRIEND` · `BATCH_TOO_LARGE` · `IM_NOT_CONFIGURED`

> **防重复建群、失败恢复、客户端迁移清单** → 见 [group-create-client.md](./group-create-client.md)

---

## 3. `POST /group/{groupId}/leave` 退群

**权限**：成员（含管理员）；**群主不可退**（须转让或解散）

**响应 `200`**

```json
{ "groupId": "@TGS#...", "status": "left", "memberCount": 127 }
```

**TCP**：`member_left`（推送给剩余成员 + 退群者本人）

**错误码**：`OWNER_CANNOT_LEAVE` · `NOT_GROUP_MEMBER` · `GROUP_NOT_FOUND`

---

## 4. `POST /group/{groupId}/dismiss` 解散

**权限**：仅群主

**响应 `200`**

```json
{ "groupId": "@TGS#...", "status": "dismissed" }
```

**TCP**：`group_dismissed`（推送给解散前全体成员）

**错误码**：`NOT_GROUP_OWNER` · `GROUP_NOT_FOUND`

---

## 5. `DELETE /group/{groupId}/members/{userId}` 踢人

**权限**：群主 / 管理员（管理员不能踢群主或其他管理员）

**响应 `200`**

```json
{
  "groupId": "@TGS#...",
  "userId": "userB",
  "status": "removed",
  "memberCount": 126
}
```

**TCP**：`member_removed`（剩余成员 + 被踢者本人）

**错误码**：`NOT_GROUP_ADMIN` · `CANNOT_KICK_OWNER` · `CANNOT_KICK_ADMIN` · `NOT_GROUP_MEMBER`

---

## 5.1 `DELETE /group/{groupId}/members` 批量踢人

**权限**：同单人踢人（群主 / 管理员）

**Body**

```json
{
  "userIds": ["userB", "userC", "userD"]
}
```

`userIds` 单次最多 **100** 人，超出返回 `400 BATCH_TOO_LARGE`。

**逻辑**：逐个校验权限；可移除的成员**一次**调 IM `delete_group_member`；不可移除的返回 `failed` 及 `code`，不影响其他成员。

**响应 `200`**

```json
{
  "groupId": "@TGS#...",
  "results": [
    { "userId": "userB", "status": "removed" },
    { "userId": "userC", "status": "removed" },
    { "userId": "userD", "status": "failed", "code": "CANNOT_KICK_ADMIN" }
  ],
  "memberCount": 124
}
```

| status | 说明 |
|--------|------|
| `removed` | 已踢出 |
| `failed` | 失败，见 `code` |

**TCP**：成功移除的成员合并为一次 `member_removed`（`detail` 含全部被踢者）

**错误码**（整请求）：`NOT_GROUP_ADMIN` · `INVALID_INPUT` · `BATCH_TOO_LARGE` · `GROUP_NOT_FOUND`

单人踢人 `DELETE .../members/{userId}` 仍可用，行为与批量接口一致（失败时直接抛对应 HTTP 错误）。

---

## 6. `PUT /group/{groupId}/members/{userId}/role`

**权限**：仅群主

**Body**：`{ "role": 300 }`（`300` 管理员 · `200` 普通成员）

**响应**：`GroupMemberView`（同成员列表 item；含 `joinedAt` / `invitedByUserId` / `invitedByNickname` / `joinChannel`，全员可见，见 [group-member-invite.md §7.1](./group-member-invite.md)）

**TCP**：`member_role_changed`

**错误码**：`NOT_GROUP_OWNER` · `CANNOT_CHANGE_OWNER_ROLE`

---

## 6.1 `PUT /group/{groupId}/members/roles`（批量设/取消管理员）

**权限**：仅群主

一次最多 **30** 人（去重后计数）。

**响应策略（加速）**：同步只校验「群存在 + 调用者是群主 + 入参合法」，立刻返回；  
腾讯云改角色、本地投影、系统通知在**后台异步**执行。最终成功以 TCP `member_role_changed` / 群系统通知为准。

**Body**

```json
{
  "role": 300,
  "userIds": ["userA", "userB"]
}
```

- `role`：`300` 管理员 · `200` 普通成员  
- `userIds`：非空，最多 30 个

**响应 `200`（受理成功，非最终执行结果）**

```json
{
  "groupId": "@TGS#...",
  "role": 300,
  "results": [
    { "userId": "userA", "status": "accepted", "code": null, "member": null },
    { "userId": "userB", "status": "accepted", "code": null, "member": null }
  ]
}
```

| status | 含义 |
|--------|------|
| `accepted` | 已受理，后台处理中 |

整请求错误（不会入队）：`NOT_GROUP_OWNER` · `INVALID_INPUT` · `BATCH_TOO_LARGE` · `GROUP_NOT_FOUND`

异步单人失败（如非成员、改群主、IM 错误）只打日志，**不体现在本 HTTP 响应**；成功者仍发 TCP `member_role_changed`。

单人 `PUT .../members/{userId}/role` 仍为**同步**等到改完再返回。

---

## 7. `POST /group/{groupId}/transfer-owner` 转让群主

**Body**：`{ "newOwnerUserId": "userB" }`

**响应 `200`**

```json
{
  "groupId": "@TGS#...",
  "ownerUserId": "userB",
  "oldOwnerUserId": "userA"
}
```

**TCP**：`owner_changed`

---

## 8. 禁言（P2）

### `PUT /group/{groupId}/members/{userId}/mute`

`{ "muteSeconds": 600 }`，`0` 表示解除。TCP：`member_muted`

个人禁言仍走 IM `MuteTime`，被禁成员发任意消息（含 `group_tip`）都会被 IM 拦截。

### `PUT /group/{groupId}/mute-all`

`{ "shutUpAllMember": true }`。TCP：`group_mute_all_changed`

**软全员禁言（现网）**：

| 项 | 说明 |
|----|------|
| 业务态 | 写本地投影 `shut_up_all`；`GET .../mute-status` 的 `isAllMuted` 以此为准 |
| IM 原生 | **不**再打开 `ShutUpAllMember`（始终 Off），避免 IM 层拦死普通成员 tip |
| 发言门禁 | `Group.CallbackBeforeSendMsg`：普通成员非 tip → `GROUP_ALL_MUTED`；纯 `group_tip` **放行**；群主/管理员任意消息放行 |
| 客户端 UI | 输入框禁用请读 mute-status / 投影，**不要**只信 SDK `ShutUpAllMember` |

存量群若 IM 仍为 On：再次调用 mute-all（开或关）会强制 Off；或运维对 `shut_up_all=1` 的群调一次 IM Off。

---

## 9. `member_added` detail 补全

新入群成员收到的 TCP `member_added` 的 `detail` 含完整群条目字段（`groupName`、`groupType`、`displayAlias`、`avatarUrl`、`notice`、`myRole`、`myNameCard`、`joinedAt` 等），可不再调 `GET /group/{id}`。

群内其他成员收到的 `member_added` 仅含 `memberCount`、`memberUserIds`、`updatedAt`。

断线补偿：`GET /me/groups/{groupId}/members/changes`（见 [group-member-incremental-sync-client.md](./group-member-incremental-sync-client.md)）。

详见 [group-profile-client.md](./group-profile-client.md) §6。

---

## 10. 客户端迁移

| 原 IM SDK | 改 REST |
|-----------|---------|
| `createGroup` | `POST /group` |
| `quitGroup` | `POST .../leave` |
| `dismissGroup` | `POST .../dismiss` |
| `kickGroupMember` | `DELETE .../members/{userId}` |
| 批量踢人 | `DELETE .../members`（body `userIds`） |
| `setGroupMemberRole` | `PUT .../members/{userId}/role` |
| 批量设/取消管理员 | `PUT .../members/roles`（body `role` + `userIds`，最多 30） |
| 转让群主 | `POST .../transfer-owner` |
