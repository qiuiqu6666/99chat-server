# 群通知页 — 完全自建后端接口文档

**版本**：v1.0  
**适用群类型**：Public / Meeting / Community  
**原则**：审批、系统通知读写全部走 99chat-server；IM SDK 不再作为数据源或审批通道  
**关联文档**：[group-member-invite.md](./group-member-invite.md) · [group-governance-client.md](./group-governance-client.md)

---

## 1. 范围说明

本文档描述会话列表「群通知」入口及其详情页（AllGroupApplicationListPage）在完全自建后端模式下的接口契约。

| 概念 | 说明 |
|------|------|
| **群通知** | 入群审批 + 管理员/群主变更通知（本文档范围） |
| **群公告** | 群资料里的 notice 字段，走 `PUT /group/{id}` + TCP `group_notice_changed`（不在本文档范围） |

### 1.1 能力对照

| 能力 | 完全自建后数据来源 | 当前状态 |
|------|-------------------|----------|
| 管理员看待审批 | `GET /group/{id}/join-applications` | ✅ 已实现 |
| 同意 / 拒绝 | `POST .../approve` / `reject` | ✅ 已实现 |
| 申请人看结果 / 被邀请人看状态 | `GET /me/join-applications` | ✅ 已实现 |
| 系统通知（设/撤管理员、转让群主） | `GET /me/group-notices` + `GET /me/group-notices/changes` + TCP `group_system_notice` | ✅ 已实现 |
| 软删除系统通知 | `DELETE /me/group-notices/{noticeId}` | ✅ 已实现 |
| 实时刷新 | TCP `group_changed` | ✅ 已实现 |
| 群名 / 头像展示 | `GET /me/groups` | ✅ 已有 |

### 1.2 客户端调用约束（削峰）

| 做 | 不要做 |
|----|--------|
| 群通知页用 `GET /me/join-applications` 一次拉齐 | 对 `/me/groups` 返回的每个群再打 `GET /group/{id}/join-applications` |
| `GET /me/groups` 默认 `limit=100`（上限 200），需要时再翻页 | 无脑 `limit=500` 或每次带 `refresh=true` |
| `refresh=true` 仅用于建群失败恢复（见 group-create-client） | 进会话列表就 refresh |
| 单群「入群申请」管理页才打 `GET /group/{id}/join-applications` | 进会话/群列表就全量扇出 |

服务端热路径：`GET /group/{id}/join-applications` 默认**不再同步**拉 IM `portrait_get`，昵称/头像优先本地用户表；可选短缓存（约 8s）。单群管理页仍可用该接口。

#### 客户端迁移验收清单

- [ ] 群通知 / 待审列表 / 红点只调 `GET /me/join-applications`（分页）
- [ ] 已删除「`/me/groups` 后对每个群 `join-applications`」逻辑
- [ ] 单群管理页仍可用 `GET /group/{id}/join-applications`（低频）

---

## 2. 新增接口

### 2.1 我的审批通知列表

`GET /me/join-applications`

| Query | 默认 | 说明 |
|-------|------|------|
| `limit` | 50 | 1–100 |
| `offset` | 0 | 分页 |
| `includeHandled` | true | 是否含已处理 |
| `status` | 空 | 可选：`pending` / `approved` / `rejected` |

返回范围（满足任一即返回）：

| 角色 | 条件 |
|------|------|
| 申请人 | `fromUserId = 当前用户` 且 `applicationType = join` |
| 被邀请人 | `toUserId = 当前用户` 且 `applicationType = invite` |
| 邀请人 | `fromUserId = 当前用户` 且 `applicationType = invite`（创建时自动软隐藏，见下） |
| 群主/管理员 | 该群 `role >= 300`，返回群内所有待审批/已处理记录 |

### 软删除（仅自己不可见）

`DELETE /me/join-applications/{applicationId}` 或批量 `DELETE /me/join-applications` 会在 `group_join_application_dismiss` 写入**当前用户**的隐藏标记；`GET /me/join-applications` 与 `GET /group/{id}/join-applications` 均会排除已隐藏记录。**审批单本身不删**，其他管理员/用户仍可见。

普通成员发起邀请后，服务端**自动**为邀请人写入隐藏标记，邀请人侧群通知不会出现该条；群主/管理员仍正常收到待审批。

响应示例：

```json
{
  "items": [
    {
      "applicationId": 123,
      "groupId": "@TGS#2ABCDEF",
      "groupName": "产品讨论群",
      "groupAvatarUrl": "https://cdn.example.com/thumb.jpg",
      "applicationType": "join",
      "fromUserId": "10001",
      "toUserId": null,
      "message": "申请加入",
      "status": "approved",
      "createdAt": 1718452800000,
      "handledAt": 1718456400000,
      "fromUserNickName": "张三",
      "toUserNickName": null,
      "fromUserFaceUrl": "https://cdn.example.com/avatar.jpg",
      "handledByUserId": "admin001",
      "handledByNickName": "群管理员"
    }
  ],
  "total": 1,
  "limit": 50,
  "offset": 0
}
```

### 2.2 我的群系统通知列表

`GET /me/group-notices`

| Query | 默认 | 说明 |
|-------|------|------|
| `limit` | 50 | 1–200 |
| `offset` | 0 | 分页 |
| `since` | 0 | 毫秒，增量拉取 |
| `unreadOnly` | false | 为 true 时仅返回 `lastReadAtMs` 之后的通知 |

服务端对列表响应做短缓存（默认约 8s，按 userId 升版本失效）；`PUT .../read`、软删除、以及新系统通知写入后会立即失效。契约字段不变。登录时请避免对该接口无节制轮询。

响应除 `items` 外还包含：

| 字段 | 说明 |
|------|------|
| `unreadCount` | 未读数量（`createdAt > lastReadAtMs`） |
| `lastReadAtMs` | 服务端记录的已读水位；未标记过则为 `null` |

```json
{
  "items": [ ... ],
  "total": 1,
  "limit": 50,
  "offset": 0,
  "unreadCount": 1,
  "lastReadAtMs": null
}
```

`type` 枚举：

| type | 含义 |
|------|------|
| `grant_administrator` | 设为管理员 |
| `revoke_administrator` | 取消管理员 |
| `transfer_owner` | 转让群主 |

**软删除**：`DELETE /me/group-notices/{noticeId}` 或批量 `DELETE /me/group-notices`（body: `{ "noticeIds": ["..."] }`）仅对当前用户隐藏，写入 `group_system_notice_dismiss`；通知记录本身不删，其他相关用户仍可见。`GET /me/group-notices` 与未读计数均排除已隐藏项；同时写入 inbox 流 `NOTICE_DELETED`（见 [group-notice-incremental-sync-client.md](./group-notice-incremental-sync-client.md)）。

### 2.2.1 系统通知 Inbox 游标增量

`GET /me/group-notices/changes?since_seq=&limit=` — 详见 [group-notice-incremental-sync-client.md](./group-notice-incremental-sync-client.md)。  
与 `GET /me/groups/changes`（群 Entity）分离；`CURSOR_EXPIRED` 时回退本接口快照。

### 2.3 标记已读（可选）

`PUT /me/group-notices/read`

```json
{ "readAt": 1718592000000 }
```

### 2.4 软删除我的群通知（入群审批）

| 方法 | 路径 | 说明 |
|------|------|------|
| DELETE | `/me/join-applications/{applicationId}` | 隐藏单条（仅当前用户） |
| DELETE | `/me/join-applications` | 批量或全部隐藏 |

批量 body：`{ "applicationIds": [123] }`；无 body 时隐藏当前用户可见的全部，可用 `status` 过滤。

响应 `{ "deleted": N }` 为本次新隐藏条数。审批单不物理删除，**其他管理员仍可见**。

### 2.5 软删除我的群系统通知

| 方法 | 路径 | 说明 |
|------|------|------|
| DELETE | `/me/group-notices/{noticeId}` | 隐藏单条（仅当前用户） |
| DELETE | `/me/group-notices` | 批量或全部隐藏 |

批量 body：`{ "noticeIds": ["grant_administrator|@TGS#...|..."] }`；无 body 时隐藏当前用户可见的全部系统通知。

响应 `{ "deleted": N }`。404 `NOTICE_NOT_FOUND` 表示通知不存在或当前用户无权隐藏。

---

## 3. 已有接口增强

### 3.1 管理员审批列表

`GET /group/{groupId}/join-applications?includeHandled=true`

- 默认 `includeHandled=false`：仅 pending（兼容旧行为）
- `includeHandled=true`：含历史已处理记录
- 响应字段对齐 `JoinApplicationItem`：`applicationId`、`applicationType`（`join`/`invite`）、`fromUserFaceUrl`、`toUserNickName`、`groupName`、`groupAvatarUrl`、`handledByUserId`、`handledByNickName` 等
- 已处理记录可用 `handledByNickName` 展示“已同意 · 张三”或“张三已拒绝”；兼容字段 `handlerUserId` 仍保留

### 3.2 TCP `group_changed` 增强

| action | 说明 |
|--------|------|
| `join_application_pending` | detail 含 `applicationId`、`status`、`createdAt`、`fromUserNickName` |
| `join_application_handled` | detail 含 `status`、`handledAt`、`handlerUserId`、`handledByUserId`、`handledByNickName`、`groupName`、`groupAvatarUrl` |
| `group_system_notice` | 设/撤管理员、转让群主时推送；`detail` 含 `noticeId`、`type`/`noticeType`、operator/target、群名头、**`seq`**（与 inbox Sync 同源）；软删时 `type=NOTICE_DELETED` |

`group_system_notice` 落库 + 推 TCP 时机：

| 来源 | REST 操作 | IM 回调 | type |
|------|-----------|---------|------|
| REST | `PUT /group/{id}/members/{userId}/role`（升为管理员） | `Group.CallbackOnMemberStateChange` | `grant_administrator` |
| REST | `PUT /group/{id}/members/{userId}/role`（降为成员） | 同上 | `revoke_administrator` |
| REST | `POST /group/{id}/transfer-owner` | `Group.CallbackAfterChangeGroupOwner` | `transfer_owner` |

REST 与 IM 回调双写时，60 秒内同群同人同类型通知自动去重，避免重复记录。

离线 Push：`group_system_notice`（UPSERT）在 TCP 不可达时会按 `type` 推送文案；`NOTICE_DELETED` 通常无离线正文。

---

## 4. 关联文档

- 邀请 / 申请 / 审批基础流程：[group-member-invite.md](./group-member-invite.md)
- 群治理（设管理员、转让群主）：[group-governance-client.md](./group-governance-client.md)
