# 业务数据同步——前端对接文档

> 版本：v2（Phase 4）  
> 适用：99chat Flutter / iOS / Android 客户端  
> 范围：好友通讯录、群展示、群成员、群通知四类业务权威数据

本文描述登录用户使用统一 snapshot（快照）和 changes（增量变更）接口同步业务数据。腾讯 IM SDK 负责消息、历史消息、会话和未读数；本文不覆盖这些内容。

设备通讯录、照片、视频备份使用 `/me/sync/*`，详见 [cloud-sync-api.md](cloud-sync-api.md) 和 [cloud-sync-client.md](cloud-sync-client.md)，不要与本文的 `/sync/{domain}/*` 混用。

## 1. 接入前提

所有接口需要登录 JWT：

```http
Authorization: Bearer <token>
```

请求使用 `application/json`；接口路径前加服务端 Base URL。错误响应通常为：

```json
{"code":"SNAPSHOT_REQUIRED","message":"SNAPSHOT_REQUIRED"}
```

| domain | 数据内容 | 额外参数 |
|---|---|---|
| `contacts` | 好友通讯录 | 无 |
| `groups` | 群展示信息 | 无 |
| `groupMembers` | 指定群成员 | `groupId` 必填 |
| `groupNotices` | 当前用户的群系统通知收件箱 | 无 |

## 2. 公共字段与语义

| 字段 | 说明 |
|---|---|
| `snapshotRevision` | 本次响应对应的快照版本。每个域独立递增，不同域不可比较。 |
| `toRevision` | `changes` 本次响应覆盖并推进到的 revision。 |
| `opaqueCursor` | 服务端游标。客户端不得解码、修改、拼接或跨域复用，只能原样持久化和回传。 |
| `itemVersion` | 单条实体版本。同一实体只接受更大的版本。 |
| `eventId` | 增量事件唯一标识。重复收到相同 ID 时幂等忽略。 |
| `deleted` | tombstone 标记。为 `true` 时删除本地实体。 |
| `hasMore` | 是否还有下一页；为 `true` 时必须继续使用响应中的新 cursor。 |
| `total` | 仅 snapshot 可能返回，表示当前活跃实体总数；其他域可能为 `null`。 |
| `serverTime` | 服务端 Unix 时间戳，单位毫秒。 |
| `seq` / `groupSeq` | 旧协议兼容字段。新客户端以 `opaqueCursor` 和 revision 为准。 |

`groupMembers` 按 `groupId` 隔离，每个群必须独立保存游标。

## 3. 统一接口

### 3.1 获取快照

```http
GET /sync/{domain}/snapshot?limit=100&cursor=<opaqueCursor>&snapshotRevision=42&groupId=<groupId>
```

| 参数 | 说明 |
|---|---|
| `limit` | 每页条数；省略或小于等于 0 使用默认值 100。各域上限见域说明。 |
| `cursor` | 翻页时原样使用上一页响应的 `opaqueCursor`；首次可省略。 |
| `snapshotRevision` | 继续同一个快照时使用的版本，通常与 cursor 一起使用。 |
| `groupId` | 仅 `groupMembers` 使用且必填。 |

```json
{
  "domain":"contacts","snapshotRevision":42,"toRevision":null,
  "opaqueCursor":"eyJkIjoiY29udGFjdHMiLi4u","hasMore":false,
  "total":128,"serverTime":1730000000000,
  "items":[{"id":"u_10001","itemVersion":3,"deleted":false}],
  "events":null
}
```

快照数据在 `items`；`toRevision` 和 `events` 对 snapshot 无意义，可能为 `null`。

### 3.2 获取增量变更

```http
GET /sync/{domain}/changes?cursor=<opaqueCursor>&limit=100&groupId=<groupId>
```

首次没有游标时可省略 `cursor`。正常同步必须使用上一次成功响应的 cursor。

```json
{
  "domain":"contacts","snapshotRevision":42,"toRevision":45,
  "opaqueCursor":"eyJkIjoiY29udGFjdHMiLi4u","hasMore":false,
  "total":null,"serverTime":1730000000000,"items":null,
  "events":[
    {"id":"u_10001","eventId":"evt_ab12","operation":"upsert","itemVersion":4,"deleted":false},
    {"id":"u_10002","eventId":"evt_cd34","operation":"delete","itemVersion":2,"deleted":true}
  ]
}
```

增量数据在 `events`。`operation` 只有 `upsert`（新增/更新）和 `delete`（删除）。

## 4. 客户端同步流程

### 4.1 首次同步或全量恢复

1. 每个域分别执行 snapshot；`groupMembers` 为每个群单独执行。
2. 首次请求不带 cursor 和 snapshotRevision。
3. 合并 `items`。
4. `hasMore=true` 时原样回传 `opaqueCursor` 继续请求，不自行生成游标。
5. `hasMore=false` 后持久化最后一页 cursor，作为下一次 changes 起点。

### 4.2 日常增量

```text
读取该域 cursor -> GET /sync/{domain}/changes
-> 按 eventId 去重、按 itemVersion 合并
-> hasMore=true：继续使用响应 cursor
-> hasMore=false：提交最后 cursor
```

只有整页处理成功后才提交新 cursor。处理中断时保留旧 cursor 并重试。

### 4.3 冷启动、断线和 410

- 可先展示本地缓存，再后台执行 changes；TCP 在线不代表已经同步。
- TCP 与 changes 同时存在时，按 `eventId`、`itemVersion` 或域内版本去重，TCP 不能替代补偿同步。
- 收到 HTTP 410，删除该域 cursor 和缓存，重新执行 snapshot；群成员只重建对应 `groupId`。
- 一个域恢复失败不影响其他域；四个域独立保存状态。

## 5. 本地合并规则

建议保存以下状态：

```text
contacts -> opaqueCursor
groups -> opaqueCursor
groupMembers:<groupId> -> opaqueCursor
groupNotices -> opaqueCursor
```

1. 好友使用 `id`，群展示优先使用 `groupId`，成员使用 `groupId + userId`，通知使用 `noticeId`。
2. 相同 ID 只接受更大的 `itemVersion`；相同版本不重复刷新 UI。
3. 记录近期 `eventId`，重复事件幂等忽略。
4. `deleted=true` 或 `operation=delete` 时删除本地可见实体，不把 tombstone 展示给用户。
5. `hasMore=true` 时不能提前把当前 cursor 当作本轮最终水位。
6. 尽量在同一事务/可恢复批次中提交数据和 cursor，避免游标前移而数据未落库。

## 6. contacts：好友通讯录

### 6.1 接口

```http
GET /sync/contacts/snapshot?limit=100
GET /sync/contacts/snapshot?limit=100&cursor=<opaqueCursor>&snapshotRevision=<revision>
GET /sync/contacts/changes?cursor=<opaqueCursor>&limit=100
```

所有请求都必须携带：

```http
Authorization: Bearer <JWT>
```

`/sync/contacts/snapshot` 的快照唯一权威数据源是当前 `user_friend` 关系表，不是 `friend_contact_change` 事件表。事件表只用于 `/sync/contacts/changes` 增量接口。

### 6.2 快照查询语义

服务端对快照 items 和 total 使用同一套当前关系过滤条件：

```sql
WHERE user_id = :currentUserId
  AND status = 1
  AND deleted = 0
  AND friend_user_id <> :currentUserId
  AND 对方用户存在且 status = 1
```

这等价于当前旧接口 `/me/friends` 的有效好友判定，并排除：

- 自己；
- `status != 1` 的关系；
- `deleted = 1` 的逻辑删除关系；
- 对方用户不存在或已失效的关系；
- 仅存在于 `friend_contact_change`、但当前关系表中已经不存在的历史关系。

因此以下关系不会被错误删除：

- 对方单向删除当前用户，但当前用户仍保留的关系；
- 当前仍可发消息的单向关系；
- 官方账号，例如 `99Messenger`；
- 有备注但昵称或头像为空的有效关系。

### 6.3 快照响应

```json
{
  "domain": "contacts",
  "snapshotRevision": 4,
  "toRevision": null,
  "opaqueCursor": "<下一页游标，终页为空字符串>",
  "hasMore": false,
  "total": 7,
  "serverTime": 1788361593907,
  "items": [
    {
      "id": "friendUserId",
      "eventId": "snapshot:currentUserId:friendUserId:12",
      "operation": "upsert",
      "itemVersion": 12,
      "deleted": false,
      "updatedAt": 1788361593907,
      "peerNickname": "昵称",
      "peerAvatarUrl": "头像",
      "remark": "好友备注",
      "inMyFriendList": true,
      "isFriend": true,
      "peerDeletedMe": false,
      "canMessage": true,
      "lastActiveAt": 1788160000000,
      "lastActiveVisibility": "everyone",
      "tcpAction": "updated"
    }
  ],
  "events": null
}
```

字段约定：

| 字段 | 前端处理 |
|---|---|
| `id` | 对方用户 ID。不能使用关系表主键、事件 ID、当前用户 ID、会话 ID 或 `c2c_` 前缀。 |
| `eventId` | 快照项的可追踪 ID；增量事件使用数据库事件 ID。可用于日志和幂等辅助。 |
| `operation` | 快照固定为 `upsert`；增量为 `upsert` 或 `delete`。 |
| `itemVersion` | 单条关系版本。只接受更大的版本覆盖本地数据。 |
| `deleted` | 快照有效项为 `false`；增量删除事件为 `true`。 |
| `updatedAt` | Unix 毫秒时间戳。 |
| `remark` | 没有备注时可能是空字符串或 `null`，不要因此删除好友。 |
| `peerAvatarUrl` | 可能为空；头像为空不代表关系无效。 |
| `inMyFriendList` | 当前用户是否保留该关系。 |
| `isFriend` | 双向好友关系是否成立。 |
| `peerDeletedMe` | 对方是否已删除当前用户。 |
| `canMessage` | 当前关系是否允许继续发消息。 |
| `lastActiveAt` | 可能为 `null`，按隐私设置展示。 |
| `lastActiveVisibility` | `everyone`、`friends_only`、`hidden` 或 `null`。 |

### 6.4 稳定分页和游标

服务端按以下顺序分页：

```sql
ORDER BY friend_user_id ASC
LIMIT :limitPlusOne
```

游标由服务端生成，内部保存至少：

```json
{
  "snapshotRevision": 4,
  "lastFriendUserId": "最后一条好友 ID",
  "total": 7,
  "emittedCount": 3
}
```

客户端不得解码、修改或拼接 `opaqueCursor`。正确流程：

1. 第一页不传 `cursor` 和 `snapshotRevision`。
2. 收到 `hasMore=true` 时，原样回传 `opaqueCursor`，并同时回传响应中的 `snapshotRevision`。
3. 每页成功写入本地后，才能保存新游标。
4. 收到 `hasMore=false` 时，本轮快照完成；终页 `opaqueCursor` 为空字符串。
5. `items.length` 永远不超过 `limit`。
6. 若 `total=7` 且 `limit=200`，正确结果必须返回 7 条 items，而不是 1 条。

不要使用旧的普通 OFFSET 或关系表自增 `id` 作为新快照游标。新客户端也不要把 `total` 当作事件数量。

### 6.5 快照与增量的衔接

快照完成后，保存该域的最终游标：

```text
contacts -> opaqueCursor
```

日常同步：

```text
读取 contacts cursor
  -> GET /sync/contacts/changes?cursor=<cursor>&limit=100
  -> 按 eventId 去重
  -> 按 id + itemVersion 合并
  -> deleted=true 或 operation=delete：删除本地可见好友
  -> hasMore=true：继续使用新 cursor
  -> hasMore=false：提交最终 cursor
```

好友关系写入必须先落 `user_friend`，再写 `friend_contact_change`，事务提交后才推送 TCP。TCP 只作为实时通知，不能替代 snapshot 或 changes 补偿同步。

### 6.6 410 和完整性错误

| HTTP | code | 前端动作 |
|---|---|---|
| 410 | `INVALID_CURSOR` | 删除 contacts 游标，重新 snapshot。 |
| 410 | `SNAPSHOT_EXPIRED` | 本次快照版本已不可继续，清除 contacts 快照和游标，重新 snapshot。 |
| 410 | `SNAPSHOT_REQUIRED` | 增量游标过期，清除 contacts 游标和本地好友快照，重新 snapshot。 |
| 500 | `CONTACT_SNAPSHOT_INCOMPLETE` | 不提交本页游标，不覆盖完整好友列表，记录账号和 revision，稍后重试或重新 snapshot。 |

`CONTACT_SNAPSHOT_INCOMPLETE` 表示服务端发现终页输出数量与同一查询条件得到的 `total` 不一致。客户端不能把此响应当作成功终页，也不能把 `total` 截断为已收到的数量。

### 6.7 兼容旧接口

旧接口仍可用但不建议新代码使用：

```http
GET /me/friends
GET /me/friends/snapshot
GET /me/friends/changes/v2
GET /me/friends/changes?since_seq=...
```

新客户端统一使用：

```http
GET /sync/contacts/snapshot
GET /sync/contacts/changes
```

不要混用旧接口的数字 `cursor`、`syncSeq` 与新接口的 `opaqueCursor`。

## 7. groups：群展示

```http
GET /sync/groups/snapshot?limit=100
GET /sync/groups/changes?cursor=<opaqueCursor>&limit=100
```

默认 limit 100，上限 200。常用字段：

| 字段 | 说明 |
|---|---|
| `id` | 变更事件 ID；本地合并优先使用 `groupId` |
| `groupId` | 群 ID |
| `groupName` / `avatarUrl` / `avatarVersion` | 群名称、头像、头像版本 |
| `notice` | 群公告 |
| `action` | 群变更动作 |
| `operatorUserId` / `timelineRank` | 操作人、时间线排序 |
| `groupSeq` | 旧群展示序号，兼容保留 |

部分变更事件可能只携带 `groupId` 和变更元数据，详情为空时按 `groupId` 请求已有群详情进行纠偏。

## 8. groupMembers：群成员

必须携带 `groupId`，并且当前用户需为群成员：

```http
GET /sync/groupMembers/snapshot?groupId=m2225Q3N5CC&limit=100
GET /sync/groupMembers/changes?groupId=m2225Q3N5CC&cursor=<opaqueCursor>&limit=100
```

默认 limit 100，上限 200。常用字段：

| 字段 | 说明 |
|---|---|
| `id` / `userId` | 成员用户 ID |
| `groupId` | 群 ID |
| `nickName` / `avatarUrl` | 成员昵称、头像 |
| `role` | 群角色值 |
| `type` | `MEMBER_UPSERTED` 或 `MEMBER_REMOVED` 等事件类型 |
| `memberCount` | 当前响应或事件写入时成员人数 |
| `updatedAt` | 更新时间 |

```json
{"id":"u_10002","eventId":"evt_member_1","operation":"delete","itemVersion":8,"deleted":true,"groupId":"m2225Q3N5CC","userId":"u_10002","memberCount":127}
```

收到 `GROUP_ID_REQUIRED` 时补齐参数；收到无权限错误时停止该群同步并刷新群关系。

## 9. groupNotices：群通知收件箱

```http
GET /sync/groupNotices/snapshot?limit=100
GET /sync/groupNotices/changes?cursor=<opaqueCursor>&limit=100
```

默认 limit 100，上限 200。常用字段：

| 字段 | 说明 |
|---|---|
| `noticeId` | 通知稳定 ID |
| `groupId` / `groupName` / `groupAvatarUrl` | 来源群信息 |
| `noticeType` | `grant_administrator`、`revoke_administrator`、`transfer_owner` |
| `operatorUserId` / `operatorNickName` | 操作人 |
| `targetUserId` / `targetNickName` | 被操作人 |
| `lastReadAtMs` | 当前用户已读水位，单位毫秒 |
| `type` | `NOTICE_UPSERTED`、`NOTICE_DELETED`、`READ_WATERMARK` 等兼容事件类型 |

处理方式：`NOTICE_UPSERTED` 按 noticeId upsert 并刷新红点；`NOTICE_DELETED` 删除对应通知；`READ_WATERMARK` 只更新已读水位，不创建通知实体。

## 10. 旧接口与路径隔离

统一接口上线后，旧接口 deprecated，计划保留 6 个月；新客户端只使用 `/sync/{domain}/*`：

| domain | 推荐接口 | 旧接口 |
|---|---|---|
| contacts | `/sync/contacts/*` | `/me/friends/snapshot`、`/me/friends/changes/v2`、`/me/friends/changes?since_seq=` |
| groups | `/sync/groups/*` | `/me/groups/snapshot`、`/me/groups/changes/v2`、`/me/groups/changes` |
| groupMembers | `/sync/groupMembers/*` | `/me/groups/{groupId}/members/snapshot`、成员 changes v2、`changes?since_seq=` |
| groupNotices | `/sync/groupNotices/*` | `/me/group-notices/snapshot`、`/me/group-notices/changes/v2`、`/me/group-notices/changes` |

`/me/sync/*` 是设备通讯录、照片、视频上传同步；`/sync/{domain}/*` 是业务数据同步，不能交叉使用游标或响应结构。

## 11. 错误处理

| HTTP | code | 客户端动作 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 刷新登录态或重新登录，不循环重试 |
| 400 | `GROUP_ID_REQUIRED` | 为 groupMembers 补充非空 groupId |
| 400 | 参数校验错误 | 修正参数后重试 |
| 404 | `SYNC_DOMAIN_NOT_FOUND` | 检查 domain，仅允许四个规定值 |
| 410 | `SNAPSHOT_REQUIRED` | 删除该域 cursor 和缓存，重新 snapshot |
| 410 | `INVALID_CURSOR` / `CURSOR_INVALID` | 游标损坏、非法或跨域使用，重新 snapshot |
| 410 | `CURSOR_EXPIRED` | 旧 since_seq 游标过期；旧接口全量恢复，新接口按实际错误回退 snapshot |

不要无限重试 400、401、404、410。网络超时、5xx 等暂时性错误可指数退避；重试前保持旧 cursor 不变。

## 12. 前端联调清单

- [ ] 每个请求都带 JWT，确认路径为 `/sync` 而非 `/me/sync`。
- [ ] 四个 domain 都能 snapshot；groupMembers 带正确 groupId。
- [ ] snapshot 分页原样使用 opaqueCursor，结束后持久化。
- [ ] changes 读取持久化 cursor，hasMore 时继续分页。
- [ ] 重复请求或重复事件不会重复插入、刷新。
- [ ] 低 itemVersion 不会覆盖高版本。
- [ ] deleted/operation=delete 能移除本地实体。
- [ ] 模拟 410 后只重建对应域，快照完成前不提交新 cursor。
- [ ] 群成员按群隔离缓存和游标；无权限时停止该群同步。
- [ ] 群通知的 upsert、dismiss 删除和已读水位分别处理。
- [ ] 设备通讯录、照片、视频按 [cloud-sync-client.md](cloud-sync-client.md) 联调。

## 13. 客户端伪代码

```text
sync(domain, groupId?):
  scope = domain + (":" + groupId if groupId else "")
  cursor = loadCursor(scope)
  if cursor is empty:
    repeat:
      response = snapshot(domain, cursor, groupId)
      mergeItems(response.items)
      cursor = response.opaqueCursor
    until response.hasMore == false
  else:
    repeat:
      response = changes(domain, cursor, groupId)
      mergeEvents(response.events)
      cursor = response.opaqueCursor
    until response.hasMore == false
  saveCursor(scope, cursor)

onHttp410(domain, groupId?):
  clearLocalDataAndCursor(domain, groupId)
  sync(domain, groupId)
```

`mergeItems` 和 `mergeEvents` 必须遵守第 5 节的版本、幂等和删除规则；网络层还应处理鉴权刷新、超时和指数退避。
