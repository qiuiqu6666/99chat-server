# Community(社群)历史记录 - 前端查询接口对接

> 版本：v1.0  
> 适用端：Flutter App / Web 客户端  
> Base URL：`http://<host>:8081`（生产环境以实际 HTTPS 域名为准）

## 1. 适用范围

本文档覆盖**"社群/群组"相关的查询接口**（只读 + 游标增量），由 99chat-server 自建业务库支撑。

- ✅ **适用**：群元数据、群成员、群变更、入群申请、系统通知、消息归档。
- ❌ **不适用**：实时消息（走腾讯 IM SDK）、管理后台查询（`/api/v1/...`）。
- ❌ **不适用**：写操作（建群、改资料、踢人、审批等），详见各专项文档。

> **关于"社群"**  
> 99chat-server 没有独立的 "社群" 实体；腾讯 IM 的 `GroupType=Community` 在业务库使用与普通群相同的 `group_*` 表。  
> 所有 Community 群与普通群共用以下接口，无需额外区分。  
> 腾讯 IM 端的 Community 群迁移工具详见 `scripts/im-migrate-cn/README.md`。

## 2. 鉴权

所有 `/me/...`、`/group/...`、`/im/...` 接口都需要登录 JWT：

```http
Authorization: Bearer <App JWT>
```

返回错误码：

| 状态码 | 含义 |
|---|---|
| 401 | JWT 失效，按现有登录刷新流程处理 |
| 403 | 当前用户不是该会话/群的参与者 |
| 410 (`CURSOR_EXPIRED`) | 游标过旧，需要回退到快照接口重新全量 |
| 429 | 触发限流，短暂退避后重试；消息归档接口约束为单用户 10 req/s |

## 3. 接口总览

| 类别 | 路径前缀 | Controller |
|---|---|---|
| 我的群列表 | `/me/groups` | `MeGroupsController` |
| 群变更增量 | `/me/groups/changes` | `MeGroupsChangesController` |
| 群成员变更增量 | `/me/groups/{groupId}/members/changes` | `GroupMemberController` |
| 群成员快照 | `/group/{groupId}/members` | `GroupMemberController` |
| 成员邀请人 | `/group/{groupId}/members/{userId}/inviter` | `GroupMemberController` |
| 群资料 | `/group/{groupId}` | `GroupProfileController` |
| 群隐私设置 | `/group/{groupId}/privacy` | `GroupPrivacyController` |
| 群加群配置 | `/group/{groupId}/join-options` | `GroupJoinController` |
| 群入群申请 | `/group/{groupId}/join-applications` | `GroupJoinController` |
| 待审批邀请人 | `/group/{groupId}/pending-invitees` | `GroupJoinController` |
| 群加群查找 | `/group/join-lookup` | `GroupJoinController` |
| 我的入群申请 | `/me/join-applications` | `MeGroupNoticeController` |
| 群系统通知 | `/me/group-notices` | `MeGroupNoticeController` |
| 群通知增量 | `/me/group-notices/changes` | `MeGroupNoticeController` |
| 群变更事件 | `/group/{groupId}/change-events`、`/me/group-change-events` | `GroupChangeEventController` |
| 我的群配额 | `/me/group-create-limits` | `MeGroupCreateLimitController` |
| 消息归档（C2C） | `/me/messages/c2c` | `MeMessageHistoryController` |
| 消息归档（群） | `/me/messages/group` | `MeMessageHistoryController` |
| 解析 MsgId | `/me/messages/resolve-msg-ids` | `MeMessageHistoryController` |
| IM 登录快照 | `/im/snapshot` | `ImSnapshotController` |

## 4. 群组基础信息

### 4.1 我的群列表（本地投影）

```http
GET /me/groups?limit=100&offset=0&refresh=false
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `limit` | 否 | 100 | 上限 200，必须分页，勿一次拉全量 |
| `offset` | 否 | 0 | 偏移量，配合 `limit` 分页 |
| `refresh` | 否 | false | 仅用于建群失败恢复等场景，会异步入队同步并跳过短缓存 |

返回：`MyGroupsResponse`（含当前用户作为成员的群，含 Community 群）。

`GroupProfileView` 只读字段 **`gameid`**：IM 群自定义字段 `/gameid` 的业务库投影。空字符串表示未绑定游戏 ID。App 不提供写接口。

### 4.2 群资料详情

```http
GET /group/{groupId}
Authorization: Bearer <token>
```

路径：

| 参数 | 必填 | 说明 |
|---|---|---|
| `groupId` | 是 | 腾讯群 ID，例如 `@TGS#2J4SZEAEL` |

返回：`GroupProfileView`（含只读 `gameid`，空串 = 未绑定）。

### 4.3 群隐私设置

```http
GET /group/{groupId}/privacy
Authorization: Bearer <token>
```

返回：

```json
{
  "privacyProtectionEnabled": true
}
```

## 5. 群成员

### 5.1 群成员本地快照

```http
GET /group/{groupId}/members?limit=50&offset=0
Authorization: Bearer <token>
```

群主+管理员：

```http
GET /group/{groupId}/members?role=admins&limit=50&offset=0
Authorization: Bearer <token>
```

普通成员：

```http
GET /group/{groupId}/members?role=members&limit=50&offset=0
Authorization: Bearer <token>
```

> 注：`refresh` 参数已忽略（保留仅为兼容旧客户端，不再触发 IM 拉取）。

参数：

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `limit` | 否 | 50 | 单页大小，服务端夹到 1–100 |
| `offset` | 否 | 0 | 偏移量；按当前筛选集合分页 |
| `role` | 否 | 不传 | 仅小写 `admins`=群主+管理员（`role >= 300`），`members`=普通成员（`role < 300`）。不传、空串、以及其它值（`owner`/`admin`/`member`/`all`/`ADMINS` 等）等同不传，返回全员 |
| `refresh` | 否 | false | 已忽略 |

`total` 为当前筛选集合的未删除人数；不传 `role`、空串或其它未识别值时仍为全群未删除人数。排序：群主 → 管理员 → 普通成员，同类按入群时间、用户 ID。

返回：`GroupMembersResponse`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `groupId` | string | 群 ID |
| `items` | array | 本页成员，元素见下表 `GroupMemberView` |
| `total` | int | 当前筛选集合人数 |
| `limit` | int | 实际单页大小 |
| `offset` | int | 实际偏移 |

`items[]`（`GroupMemberView`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `userId` | string | 业务用户 ID |
| `imUserId` | string \| null | IM 账号 |
| `nickname` | string | 昵称；无则回落为 `userId` |
| `avatarUrl` | string \| null | 头像 |
| `friendRemark` | string \| null | 调用者对该成员的好友备注 |
| `nameCard` | string \| null | 群名片 |
| `role` | int | `400` 群主 / `300` 管理员 / `200` 普通成员 |
| `roleName` | string | `owner` / `admin` / `member`。`admin` 不含群主 |
| `joinedAt` | number \| null | 入群时间毫秒 |
| `isSelf` | boolean | 是否当前登录用户 |
| `invitedByUserId` | string \| null | 邀请人业务 userId |
| `invitedByNickname` | string \| null | 邀请人昵称 |
| `joinChannel` | string \| null | `invite` \| `group_id` \| null |

响应示例（`role=admins`）：

```json
{
  "groupId": "@TGS#_mc2SX4NMM62CZ",
  "items": [
    {
      "userId": "q14gkm5swv",
      "imUserId": "q14gkm5swv",
      "nickname": "群主昵称",
      "avatarUrl": "https://example.com/a.jpg",
      "friendRemark": null,
      "nameCard": null,
      "role": 400,
      "roleName": "owner",
      "joinedAt": 1754094554000,
      "isSelf": false,
      "invitedByUserId": null,
      "invitedByNickname": null,
      "joinChannel": null
    },
    {
      "userId": "p34z8sgtnr",
      "imUserId": "p34z8sgtnr",
      "nickname": "管理员昵称",
      "avatarUrl": "https://example.com/b.jpg",
      "friendRemark": "备注",
      "nameCard": null,
      "role": 300,
      "roleName": "admin",
      "joinedAt": 1754094554000,
      "isSelf": true,
      "invitedByUserId": null,
      "invitedByNickname": null,
      "joinChannel": null
    }
  ],
  "total": 2,
  "limit": 50,
  "offset": 0
}
```

### 5.1.1 单成员邀请人

任意群成员可读。只查一人的邀请人，不拉成员列表。

```http
GET /group/{groupId}/members/{userId}/inviter
Authorization: Bearer <token>
```

```json
{
  "groupId": "@TGS#_mc2SX4NMM62CZ",
  "userId": "user_b",
  "invitedByUserId": "user_a",
  "invitedByNickname": "邀请人昵称",
  "joinChannel": "invite"
}
```

`invitedByUserId` / `invitedByNickname` / `joinChannel` 语义同 §5.1。目标不在群内 → 404 `NOT_GROUP_MEMBER`；调用者不是群成员 → 403 `NOT_GROUP_MEMBER`。

### 5.2 群成员变动游标增量

```http
GET /me/groups/{groupId}/members/changes?since_seq=0&limit=100
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `since_seq` | 否 | 0 | 客户端上次已收到的最大 seq |
| `limit` | 否 | 100 | 1 ≤ limit ≤ 200 |

返回：`MembersChangesResponse`。

- **`since_seq` 过旧返回 410 `CURSOR_EXPIRED`**，客户端应回退到 `GET /group/{groupId}/members` 本地快照（不再回源 IM）。

### 5.3 我的禁言状态

```http
GET /group/{groupId}/members/me/mute-status
Authorization: Bearer <token>
```

返回：

```json
{
  "userId": "user_a",
  "muteUntil": 1718453600000,
  "isAllMuted": false
}
```

`muteUntil` 为 `null` 表示未禁言。

### 5.4 群内被禁言成员

```http
GET /group/{groupId}/members/muted
Authorization: Bearer <token>
```

返回：

```json
{
  "members": [
    {
      "userId": "user_b",
      "muteUntil": 1718453600000,
      "nameCard": "阿B",
      "role": "Member"
    }
  ],
  "isAllMuted": false
}
```

## 6. 群变更（群维度增量同步）

### 6.1 我所有群的展示字段增量

```http
GET /me/groups/changes?since_seq=0&limit=100
Authorization: Bearer <token>
```

- 返回**群 Entity 展示字段**（群名/头像/公告）的增量变更。
- `since_seq` 过旧返回 410 `CURSOR_EXPIRED`，客户端应回退 `GET /me/groups` 快照。

### 6.2 单个群的变更事件流

```http
GET /group/{groupId}/change-events?since=0&limit=50&actions=ADD_MEMBER,REMOVE_MEMBER
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `since` | 否 | 0 | 上次已收到的最大 seq |
| `limit` | 否 | 50 | 最大 200 |
| `actions` | 否 | - | 逗号分隔的事件类型过滤，可选值：`ADD_MEMBER`、`REMOVE_MEMBER`、`UPDATE_MEMBER_ROLE`、`UPDATE_GROUP_PROFILE`、`TRANSFER_OWNER` 等 |

返回：

```json
{
  "groupId": "@TGS#2J4SZEAEL",
  "items": [
    {
      "changeEventId": "evt_001",
      "action": "ADD_MEMBER",
      "operatorUserId": "user_a",
      "memberUserIds": ["user_b"],
      "occurredAt": 1718450000000,
      "timelineRank": 1
    }
  ],
  "nextSince": 1718450000000,
  "hasMore": true
}
```

### 6.3 我所有群的变更事件聚合

```http
GET /me/group-change-events?since=0&limit=100&actions=ADD_MEMBER,REMOVE_MEMBER
Authorization: Bearer <token>
```

- 返回当前用户作为成员的所有群的变更事件（已按时间排序）。
- 默认 `limit=100`，上限 200。

## 7. 入群申请

### 7.1 某个群的入群申请（管理员视角）

```http
GET /group/{groupId}/join-applications?includeHandled=false
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `includeHandled` | 否 | false | 是否包含已审批的记录 |

返回 `JoinApplicationListResponse`，单条结构：

```json
{
  "applicationId": 10001,
  "groupId": "@TGS#2J4SZEAEL",
  "applicationType": "join",
  "fromUserId": "user_a",
  "toUserId": null,
  "message": "求拉",
  "status": "pending",
  "createdAt": 1718450000000,
  "handledAt": null,
  "fromUserNickName": "阿A",
  "toUserNickName": null,
  "fromUserFaceUrl": "https://...",
  "groupName": "示例群",
  "groupAvatarUrl": "https://...",
  "handlerUserId": null,
  "handledByUserId": null,
  "handledByNickName": null,
  "viewerRole": "admin",
  "canHandle": true
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `applicationType` | `join`（申请加群）或 `invite`（邀请入群） |
| `status` | `pending` / `approved` / `rejected` |
| `viewerRole` | 当前用户视角：`inviter` / `invitee` / `applicant` / `admin` |
| `canHandle` | 是否展示同意/拒绝按钮；邀请人看自己发起的 pending 时为 `false` |

### 7.2 某个群的待审批邀请人

```http
GET /group/{groupId}/pending-invitees
Authorization: Bearer <token>
```

返回当前群中**有未处理邀请**的所有被邀请人 userId 列表。

### 7.3 我的入群申请（用户视角）

```http
GET /me/join-applications?limit=50&offset=0&includeHandled=true&status=pending
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `limit` | 否 | 50 | 1 ≤ limit ≤ 100 |
| `offset` | 否 | 0 | 偏移量 |
| `includeHandled` | 否 | true | 是否包含已审批的 |
| `status` | 否 | - | `pending` / `approved`（accepted、added） / `rejected`（declined） |

> 注意：本接口用于"用户侧通知/审批"，不要对本接口结果扇出 `GET /group/{id}/join-applications`。

### 7.4 加群关键字查找

```http
GET /group/join-lookup?keyword=%40TGS%232J4SZEAEL&joinSource=BY_ID
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `keyword` | 是 | 群 ID 或关键字 |
| `joinSource` | 是 | 入群来源，例如 `BY_ID`、`BY_QR_CODE`、`BY_ALIAS` |

返回 `JoinLookupView`。

### 7.5 群加群配置

```http
GET /group/{groupId}/join-options
Authorization: Bearer <token>
```

返回：

```json
{
  "applyJoinOption": "Free",
  "inviteJoinOption": "AdminApproval",
  "allowJoinByQrCode": true,
  "allowJoinByAlias": true
}
```

## 8. 群系统通知

### 8.1 我的群系统通知列表

```http
GET /me/group-notices?limit=50&offset=0&since=0&unreadOnly=false
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `limit` | 否 | 50 | 1 ≤ limit ≤ 200 |
| `offset` | 否 | 0 | 偏移量 |
| `since` | 否 | 0 | 时间游标（毫秒） |
| `unreadOnly` | 否 | false | 仅展示未读 |

返回管理员操作通知（踢人、禁言、转让群主等）的列表。

### 8.2 群通知游标增量

```http
GET /me/group-notices/changes?since_seq=0&limit=100
Authorization: Bearer <token>
```

- `since_seq` 过旧返回 410 `CURSOR_EXPIRED`，客户端应回退 `GET /me/group-notices` 快照。

## 9. 我的群配额（含 Community 社群）

```http
GET /me/group-create-limits
Authorization: Bearer <token>
```

返回：

```json
{
  "enabled": true,
  "joinGroups": {
    "max": 50,
    "used": 12,
    "remaining": 38,
    "limited": false
  },
  "communityJoinGroups": {
    "max": 30,
    "used": 5,
    "remaining": 25,
    "limited": false
  },
  "communityGroups": {
    "groupType": "Community",
    "max": 3,
    "used": 1,
    "remaining": 2,
    "limited": false
  }
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `joinGroups` | 我作为成员加入普通群的配额 |
| `communityJoinGroups` | 我加入 Community 社群的配额 |
| `communityGroups` | 我**创建** Community 社群的配额 |

## 10. 消息归档

详细参数、响应字段、分页模式见 [`message-archive-client.md`](./message-archive-client.md)。本节只列路径与适用场景。

### 10.1 单聊历史

```http
GET /me/messages/c2c?peerUserId=user_b&limit=30
GET /me/messages/c2c?peerUserId=user_b&cursor=1718450000000&limit=30
GET /me/messages/c2c?peerUserId=user_b&fromTimeMs=1718450000000&toTimeMs=1718453600000&limit=40
```

### 10.2 群聊历史

```http
GET /me/messages/group?groupId=%40TGS%232J4SZEAEL&limit=30
GET /me/messages/group?groupId=%40TGS%232J4SZEAEL&cursor=1718450000000&limit=30
GET /me/messages/group?groupId=%40TGS%232J4SZEAEL&fromSeq=100&toSeq=150&limit=40
```

响应通用结构：

```json
{
  "items": [
    {
      "msgKey": "3358721060_1876410779_1784319889",
      "msgId": "144115268026882536-1784319889-1876410779",
      "fromAccount": "user_b",
      "peerAccount": "user_a",
      "groupId": null,
      "msgSeq": null,
      "msgTimeMs": 1718450000000,
      "elemType": "TIMTextElem",
      "previewText": "你好",
      "msgBody": [
        { "MsgType": "TIMTextElem", "MsgContent": { "Text": "你好" } }
      ],
      "status": 1
    }
  ],
  "nextCursor": 1718450000000,
  "hasMore": true
}
```

- `status=1` 正常；`status=0` 已撤回（保留位置，不要删除）。
- `msgKey` 是归档唯一键；群聊通常为 `{groupId}:{msgSeq}`。

### 10.3 解析单聊 MsgId

```http
POST /me/messages/resolve-msg-ids
Content-Type: application/json
{
  "chatType": "c2c",
  "peerId": "user_b",
  "msgKeys": ["3358721060_1876410779_1784319889"]
}
```

- `msgKeys` 最多 100 个；仅支持 `chatType=c2c`；当前用户必须是会话参与者。

### 10.4 只查 Community 群的历史消息

**没有"按群类型过滤"的消息查询接口**。Community 群的消息和普通群存在同一份 `chat_message_archive_*` 表里，仅 `group_id` 不同。

推荐做法：

1. 用 `GET /me/groups` 拉取我的群列表，**前端按响应里的 `groupType === "Community"` 过滤**，得到 Community 群 ID 集合。
2. 对每个 Community 群 ID 调用 `GET /me/messages/group` 拉历史。

> Community 群 ID 约定：`@TGS#_mc…`（见 `scripts/im-migrate-cn/README.md`）。

例如：

```text
GET /me/groups
  → 过滤 groupType == "Community"
  → 得到 [@TGS#_mc_001, @TGS#_mc_002, …]

GET /me/messages/group?groupId=@TGS#_mc_001&limit=30
GET /me/messages/group?groupId=@TGS#_mc_002&limit=30
  → 并发拉取（单用户 10 req/s 限流，建议加队列）
```

## 11. IM 登录快照（新设备冷启动）

```http
GET /im/snapshot?limitConv=20&limitC2c=20&limitGroup=20&limitMsg=30
Authorization: Bearer <token>
```

参数（均可选）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `limitConv` | 服务端默认 | 拉取的会话摘要数量上限 |
| `limitC2c` | 服务端默认 | 单聊会话预加载消息条数 |
| `limitGroup` | 服务端默认 | 群聊会话预加载消息条数 |
| `limitMsg` | 服务端默认 | 每个会话预加载消息数 |

返回：

```json
{
  "conversations": [
    {
      "conversationId": "c2c_user_a_user_b",
      "type": "C2C",
      "chatType": "C2C",
      "peerId": "user_b",
      "lastSeq": null,
      "lastMessage": {
        "msgId": "144115268026882536-1784319889-1876410779",
        "msgKey": "3358721060_1876410779_1784319889",
        "seq": null,
        "fromUserId": "user_b",
        "sender": "user_b",
        "time": 1718450000000,
        "type": "TIMTextElem",
        "text": "你好",
        "msgBody": [],
        "status": 1
      },
      "pinned": false,
      "pinnedAt": null
    }
  ],
  "preload": [
    {
      "conversationId": "c2c_user_a_user_b",
      "messages": []
    }
  ],
  "degraded": false
}
```

- 接口**故意不含 unread 字段**，未读数仍走腾讯 IM SDK。
- `degraded=true` 表示 IM 后端降级，客户端应继续走腾讯 IM SDK。

## 12. 推荐消息同步流程

```text
登录 / 进入 App
  ↓
GET /im/snapshot          会话摘要 + 最近消息
  ↓
GET /me/groups            我的群列表（一次性拉本地投影，分页）
  ↓
GET /me/groups/changes    群展示字段增量（since_seq 续拉）
  ↓
腾讯 IM SDK                实时消息 + 未读
  ↓
进入单聊/群聊会话
  ↓
GET /me/messages/c2c | /me/messages/group
                          拉历史（按 msgKey 去重）
  ↓
status=0                  展示「消息已撤回」，不删除记录
```

## 13. 错误与降级

| 场景 | 处理 |
|---|---|
| 401 | JWT 失效，按现有登录刷新流程处理 |
| 403 | 当前用户不是该会话/群的参与者，不展示详情 |
| 410 (`CURSOR_EXPIRED`) | 游标过旧，回退对应快照接口（见各小节） |
| 429 | 消息归档接口：10 req/s 限流，短暂退避后重试 |
| 5xx / 超时 / 接口不存在 | 不阻塞登录/进入会话；继续走腾讯 IM SDK 或本地缓存 |

## 14. 联调验收清单

- [ ] 所有请求都附带 JWT 到 `Authorization` 头。
- [ ] 首次进入群列表使用 `GET /me/groups`，续拉用 `GET /me/groups/changes`。
- [ ] 群成员续拉使用 `GET /me/groups/{groupId}/members/changes`，过旧回退快照。
- [ ] 群通知续拉使用 `GET /me/group-notices/changes`，过旧回退快照。
- [ ] 群变更事件使用 `GET /me/group-change-events`，按需传 `actions` 过滤。
- [ ] 进入会话前先 `GET /im/snapshot`，再按需 `GET /me/messages/c2c|group` 拉历史。
- [ ] 历史消息按 `msgKey` 去重，本地不依赖 `msgId` 作为唯一主键。
- [ ] `status=0` 展示「消息已撤回」，不删除本地记录。
- [ ] 配额接口 `/me/group-create-limits` 在建群前调用一次，普通群/社群分别判断。
- [ ] 我的入群申请用 `/me/join-applications`，不要扇出 `/group/{id}/join-applications`。

## 相关文档

- [IM 消息归档对接（详情）](./message-archive-client.md)
- [IM 消息归档 Kafka 与服务端说明](./message-archive-kafka.md)
- [群成员变更增量](./group-member-change-client.md)
- [群治理（踢人/禁言/转让群主）](./group-governance-client.md)
- [群成员邀请与审批](./group-member-invite.md)
- [Community 群邀请/审批（已废弃，仅参考）](./community-group-invite.md)
- [建群配额与限制](./group-create-limit.md)
- [IM Snapshot 详情](./im-snapshot-client.md)
- [文档索引](./README.md)