# 自建好友关系链 — 改造方案与待办

> 版本：v0.8  
> 日期：2026-06-15  
> 状态：**P1 后端已实施**（2026-06-15 编译部署；BeforeSendMsg 默认 **log-only 灰度**，未强制拦截）

### 实施进度摘要

| 阶段 | 状态 | 说明 |
|------|------|------|
| **P1 好友核心** | ✅ 后端已完成 | DB / API / BeforeSendMsg / 大部分联动已落地 |
| **P1 联调测试** | ⏳ 进行中 | 已有单元测试；端到端集成测试待补 |
| **P1 实时推送** | ✅ TCP + 离线 Push | 端口 8082；TCP 不可达时补系统 Push |
| **P2 会话 UI** | ❌ 未开始 | 置顶 / 归档 |
| **P3 清理迁移** | ⏳ 部分 | 系统号注册已改自建表；IM 同步 / backfill / 客户端文档仍待 |

**灰度配置**（`application.yml` → `chat99.user-friend`）：

| 配置项 | 当前默认 | 含义 |
|--------|----------|------|
| `before-send-log-only` | `true` | BeforeSendMsg 只打日志，不拦截 |
| `before-send-enforce` | `false` | 设为 `true` 后非双向好友拒发 |
| `request-cooldown-seconds` | `600` | 同对用户申请频控 10 分钟 |

---

## 1. 项目目标

**腾讯云 IM 只负责消息通道**；好友关系、备注、会话 UI 状态（置顶/免打扰/归档）全部由 **99chat-server 自建** 维护。

- 客户端**不再**调用 IM SDK 的加好友 / 删好友 / 改备注 / 拉好友列表
- IM **不维护** SNS 好友链；控制台开启「陌生人 C2C」仅表示 **IM 传输层**不因「非 IM 好友」而拦截
- 谁能发消息由服务端 **`C2C.CallbackBeforeSendMsg`** 决定，查询 **自建** `user_friend` 表（与 IM 好友链无关）

---

## 2. 已确认的业务规则

| 规则 | 结论 |
|------|------|
| 好友关系权威来源 | MySQL **`user_friend`（自建）**，不以 IM SNS 为准 |
| IM 好友链 | 客户端不再调用 IM 加/删/改好友；IM 侧 **无好友数据** |
| IM 发消息模式 | IM 控制台开「陌生人 C2C」→ **IM 不管**好友能不能发，只负责传消息 |
| 发消息拦截 | **`C2C.CallbackBeforeSendMsg` 查自建 `user_friend`**（**仅单聊**；群聊不校验好友） |
| **发消息条件** | **双向**有效好友（自建表）：`A→B` 且 `B→A` 均为 `status=1` |
| **删除好友** | **单向**：只删 `操作者→对方`，不动 `对方→操作者` |
| 单向删除后发消息 | 缺任意一边关系 → **双方都不能发** |
| 同意好友 | **双向**写入 `(A→B)` + `(B→A)` |
| 星标好友 | 保留 `user_starred_friend` + 现有 API |
| 消息免打扰 | 保留 `user_conversation_notify.muted` + 现有 API |
| 置顶 / 归档 | 需扩展表字段 + 新 API |
| 搜索加好友 | 保留 `POST /users/search` + 隐私预检 |
| 用户间拉黑 | **本期不做**（见 §10.5） |
| 好友申请频控 | 复用 `add-friend/check` 隐私规则；同一对用户 **10 分钟**内不可重复发起申请 |
| Admin 好友 | 列表改读 `user_friend`；提供运营 **强制加好友 / 强制删好友** |
| IM 历史数据 | **保留**现有 `user_friend` IM 同步数据作初始种子数据 |
| **双向同时 pending** | **自动同意**：检测到 `A→B` 与 `B→A` 均为 pending 时，立即双向写 `user_friend` 并关闭两条申请 |
| **系统号** | 用户**注册时**自动与系统号（99Messenger / 99Chat）**双向绑定**自建 `user_friend`；不存在「用户与系统号需走申请」的情况 |
| **A 删 B 后发消息** | 缺任意一边有效关系 → **A、B 均不可互发**（双向门禁） |
| **A 删 B 后 B 的列表** | **仍显示 A**，并标注 **「对方已删除你」**；B 须 **手动删除 A** 才能从列表移除 |
| **A 删 B 后再申请** | **允许**；走普通 pending / 验证开关下的 auto_accepted，**不再直接恢复** |

### 2.1 IM 层 vs 自建层（易混点）

好友关系与发消息权限是 **两层分离** 的设计，不要与 IM SNS 好友链混淆：

| 层级 | 用什么 | 作用 |
|------|--------|------|
| **腾讯云 IM 控制台** | IM 平台配置 | 开启「陌生人 C2C」= IM **传输层**不因「非 IM 好友」而挡消息，允许发起 C2C 请求 |
| **99chat-server BeforeSendMsg** | **自建 `user_friend`** | 查 `A→B` 与 `B→A` 是否均为 `status=1`；不满足则 `ErrorCode=1` 拒绝 |

```
客户端发 C2C
    → IM 传输层（陌生人模式：不因「非 IM 好友」拦截）
    → BeforeSendMsg 回调 99chat-server
    → 查 MySQL user_friend（自建双向好友）
    → 通过才真正发出 / 否则拦截（NOT_FRIEND）
```

**一句话**：关系链是 **自建** 的；IM 只当消息通道；`BeforeSendMsg` 是自建规则在 IM 上的 enforcement 点。

---

## 3. 核心行为矩阵

| 场景 | A→B | B→A | A 发 B | B 发 A |
|------|-----|-----|--------|--------|
| 互为好友 | ✅ | ✅ | ✅ | ✅ |
| **A 删 B 后（双向软删）** | ❌ | ❌ | ❌ | ❌ |
| **A 删 B 后再申请** | ❌→pending/同意后✅ | ❌→同意后✅ | 同意前❌ | 同意前❌ |
| 仅 pending 未同意 | ❌ | ❌ | ❌ | ❌ |
| **A、B 双向同时 pending** | — | — | ❌ → **自动同意** → ✅ | 同左 |
| 同意申请后 | ✅ | ✅ | ✅ | ✅ |
| 用户 ↔ 系统号（注册后） | ✅ | ✅ | ✅ | ✅ |

> **A 删 B**：双方边均 tombstone；双方列表均不再保留对方。下次须重新申请（对方开验证则 pending 等同意；关验证则仍可能 auto_accepted）。

### 3.1 好友申请状态机（已确认部分）

```
发起 A→B 申请 (pending)
    ├─ 若已存在 B→A pending → 【自动同意】双向写 user_friend，两条 request 置 accepted
    ├─ 被 B 同意 → 双向写 user_friend
    ├─ 被 B 拒绝 → request rejected，写 history
    ├─ 对方关闭验证 → auto_accepted，双向写 user_friend
    └─ pending 期间 → 不可发 C2C

A 删 B（用户 DELETE）
    └─ 双向软删 A→B 与 B→A；双方列表均移除；不再保留「对方已删除你」产品路径

用户注册
    └─ 自动与系统号双向写 user_friend（99Messenger、99Chat 等），无需 friend_request
```

---

## 4. 目标架构

```
┌─────────────┐     好友/申请/备注/置顶/归档/星标      ┌──────────────┐
│  Flutter    │ ──────────────────────────────────────►│ 99chat-server│
│  客户端     │                                        │  (权威数据源) │
└──────┬──────┘                                        └──────┬───────┘
       │                                                      │
       │  仅发消息 / 收消息 / UserSig                          │ BeforeSendMsg
       ▼                                                      │ 查自建 user_friend
┌─────────────┐                                               │ （非 IM 好友链）
│  腾讯云 IM   │ ◄── 陌生人 C2C，不传 SNS 好友 ───── POST /webhook/im/message
└─────────────┘
```

### BeforeSendMsg 校验逻辑（草案）

> 以下全部查 **自建 `user_friend`**，不读 IM SNS 好友数据。  
> **仅 `C2C.CallbackBeforeSendMsg`** 注册并校验；**群聊不注册** Group BeforeSendMsg 好友校验。

```
发送方 = From，接收方 = To

1. 发送方 / 接收方 users.status != 1（封禁）→ 拒绝
2. 必须同时满足（含用户 ↔ 系统号，注册时已双向绑定，走同一套校验）：
   - user_friend(From → To, status=1) ✅
   - user_friend(To → From, status=1) ✅
   任一不存在 → 拒绝（ErrorCode=1, ErrorInfo=NOT_FRIEND）
```

> 暂不做用户间拉黑校验（见 §10.5）。

> 系统号**不需要** BeforeSendMsg 单独白名单：注册流程已在自建表写入双向 `user_friend`，与普通好友相同规则放行。

拒绝响应示例：

```json
{
  "ActionStatus": "OK",
  "ErrorCode": 1,
  "ErrorInfo": "NOT_FRIEND"
}
```

---

## 5. 现状盘点

### 5.1 已有，可复用

| 模块 | 表 / API | 说明 |
|------|----------|------|
| 好友关系（权威） | `user_friend` + `UserFriendService` | ✅ **已改为权威表**；保留 `im_add_time` 兼容种子数据 |
| 待处理申请 | `friend_request` + `/friend-requests/*` | ✅ **已实现** |
| 好友列表 / 备注 / 删 | `/me/friends` | ✅ **已实现**（含 `peerDeletedMe`、`canMessage`） |
| 申请历史 | `friend_application_history` + `/friend-application/*` | 保留；`accept` 旧接口 **仍并存**（见 P1-API-9） |
| 星标 | `user_starred_friend` + `/me/starred-friends` | 可直接用 |
| 免打扰 | `user_conversation_notify` + `/me/conversation-notify` | 可扩展 pin/archive |
| 搜索 | `POST /users/search` | 加好友入口 |
| 隐私预检 | `POST /users/add-friend/check` | 保留；`FriendRequestService` 已复用 |
| IM 回调 | `POST /webhook/im/message` | ✅ 已支持 **`C2C.CallbackBeforeSendMsg`** + 原 `AfterSendMsg` |
| Push 展示名 | `PushDisplayNameResolver` | ✅ **已改读** `user_friend.remark` |
| 系统号绑定 | `SystemNotifyService` / `PlatformWalletNoticeService` | ✅ 注册时 **`bindMutualFriends`** 写自建表 |
| Admin 好友 | `AdminUserDetailService` + `AdminUserFriendOpsController` | ✅ 列表读 `user_friend`；强制加删 API 已上线 |
| 双向好友缓存 | `UserFriendMutualCache`（Redis） | ✅ BeforeSendMsg / 发消息校验用 |
| 申请频控 | `FriendRequestRateLimiter`（Redis） | ✅ 10 分钟 TTL |

### 5.2 仍缺失 / 待完成

| 模块 | 状态 |
|------|------|
| 置顶对话 | ❌ DB 字段 + API（P2） |
| 归档对话 | ❌ DB 字段 + API（P2） |
| 旧 `/friend-application/accept` 重构 | ⏳ 仍独立，与新 Service 双轨（P1-API-9） |
| IM 好友同步 Job | ⏳ `UserFriendSyncService` **仍启用**（P1-LINK-3 / P3） |
| `NotifyFriendBackfillService` | ⏳ 仍调 IM `addFriendBoth`（P3-1） |
| Admin 同步接口 | ⏳ `POST /admin/user-friends/sync` 仍存在（P3-2） |
| 客户端对接文档 | ❌ `docs/friend-self-hosted-client.md`（P3-3） |
| 集成测试 P1-TEST-1~6 | ⏳ 待补 |

---

## 6. 数据表设计

### 6.1 `user_friend`（关系主表，权威来源）

在现有表上调整：

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | VARCHAR(64) | 关系所属用户 |
| `friend_user_id` | VARCHAR(64) | 好友用户 ID |
| `remark` | VARCHAR(100) | **新增** — 我对好友的备注（仅 `user_id` 侧维护，不同步） |
| `friend_nickname` | VARCHAR(100) | 缓存对方 **平台** 昵称；用户改昵称时联动更新（见 §6.1.1） |
| `friend_avatar_url` | VARCHAR(500) | 缓存对方 **平台** 头像；用户改头像时联动更新（见 §6.1.1） |
| `added_at` | TIMESTAMP | 成为好友时间（替代 `im_add_time`） |
| `status` | TINYINT | `1`=有效，`0`=已删除 |
| `created_at` / `updated_at` | TIMESTAMP | |

约束：`UNIQUE(user_id, friend_user_id)`

同意好友时写入 **两条**：`(A→B)` 和 `(B→A)`，备注各自独立。

删除好友时 **双向软删**：`me→peer` 与 `peer→me` 均 `status=0` + tombstone（`deleted=1`）。

**列表展示字段**（`GET /me/friends` 建议返回）：

| 字段 | 含义 |
|------|------|
| `peerDeletedMe` | **兼容字段**；用户删除已改为双向后正常路径不再出现。历史单边数据：`me→peer` 有效且 `peer→me` 无效 |
| `canMessage` | 双向均有效时为 `true`（与 BeforeSendMsg 规则一致） |

**删后再申请**：走普通 `POST /friend-requests`（pending / auto_accepted），**不再** `directRestore` / `outcome=restored`。

#### 6.1.1 好友列表中的头像 / 昵称缓存同步

`friend_nickname`、`friend_avatar_url` 是 **对方在平台 `users` 表中的展示信息快照**，用于好友列表、申请列表等读库场景，**不读 IM 资料**。

| 触发时机 | 同步动作 |
|----------|----------|
| 用户修改头像（`POST /me/avatar` → `UserAvatarService`） | 更新 IM 资料头像后，批量更新 `user_friend SET friend_avatar_url = :newUrl WHERE friend_user_id = :userId AND status = 1` |
| 用户修改昵称（`PATCH /me/nickname` → `NicknameService`） | 批量更新 `user_friend SET friend_nickname = :newNickname WHERE friend_user_id = :userId AND status = 1` |
| 同意好友 / 写入新关系 | 写入时从 `users` 表取当前 `nickname`、`avatar_url` 填入 |
| Admin 重置用户头像 | 同 `POST /me/avatar` 链路，一并刷新 `user_friend` |

说明：

- 更新范围：凡 **`friend_user_id = 该用户`** 的有效好友记录（即「所有把该用户当好友的人」的列表里，该用户的展示头像/昵称一并刷新）。
- **`remark` 不受影响**（备注是观看者本地维护，与对方改头像无关）。
- 实现建议：`UserFriendRepository.updateAvatarByFriendUserId(...)` / `updateNicknameByFriendUserId(...)`，在头像/昵称变更事务内调用；IM `profile/portrait_set` 与 DB 更新同一事务或先 DB 后 IM，失败可打日志补偿。

### 6.2 `friend_request`（新建，待处理申请）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | |
| `from_user_id` | VARCHAR(64) | 申请人 |
| `to_user_id` | VARCHAR(64) | 被申请人 |
| `add_wording` | VARCHAR(200) | 验证语 |
| `add_source` | VARCHAR(20) | qr_code / search / phone / nearby / card / group |
| `status` | VARCHAR(20) | pending / accepted / rejected |
| `created_at` | TIMESTAMP | |
| `handled_at` | TIMESTAMP | |

约束：同一 `(from_user_id, to_user_id)` 同时只能有一条 `pending`。

**双向同时 pending → 自动同意**（已确认）：在 `POST /friend-requests` 写入前/后检测反向 pending；若存在 `B→A pending` 且当前为 `A→B`，则同事务内双向写 `user_friend`、两条 request 置 `accepted`、写 `friend_application_history`，**无需对方手动点同意**。

### 6.3 系统号注册绑定（已确认）

用户注册成功时（`AuthController` / `SystemNotifyService` / `PlatformWalletNoticeService`）：

- 对每个系统号（99Messenger、99Chat 等）**双向写入**自建 `user_friend`（`status=1`）
- **不创建** `friend_request`
- **不再**调用 IM `sns/friend_add`（P3 移除 `addFriendBoth`）

BeforeSendMsg 对用户 ↔ 系统号消息与普通好友相同：查双向 `user_friend` 即可。

### 6.4 `friend_application_history`（保留）

继续作为终态历史（accepted / rejected），供「新的朋友」列表展示。  
`user_id` / `peer_user_id` 字段长度需扩至 64，与 `user_friend` 一致。

### 6.5 `user_conversation_notify`（扩展）

在现有 `muted` 基础上增加：

| 字段 | 说明 |
|------|------|
| `pinned` | 是否置顶 |
| `pinned_at` | 置顶时间（排序用） |
| `archived` | 是否归档 |
| `archived_at` | 归档时间 |

---

## 7. API 草案

### 7.1 好友关系（✅ 已实现）

```
GET    /me/friends                          # 含 remark、addedAt、peerDeletedMe、canMessage
PUT    /me/friends/{friendUserId}/remark    # 修改备注 { "remark": "..." }（可空串清空）
DELETE /me/friends/{friendUserId}           # 双向软删；双方通讯录均移除
```

### 7.2 好友申请（✅ 已实现）

```
POST   /friend-requests                     # 返回 outcome: pending | auto_accepted（restored 已废弃）
GET    /friend-requests/incoming            # 收到的 pending（limit 分页）
GET    /friend-requests/outgoing            # 发出的 pending（limit 分页）
POST   /friend-requests/{id}/accept         # 同意 → 双向写 user_friend + 写 history
POST   /friend-requests/{id}/reject         # 拒绝 → 写 history
```

### 7.3 会话设置（扩展）

```
PUT    /me/conversation-settings            # { chatType, peerId, muted?, pinned?, archived? }
PUT    /me/conversation-settings/batch
GET    /me/conversation-settings            # 返回非默认项
```

### 7.4 已有保留

```
POST   /users/search
POST   /users/add-friend/check
GET    /users/{userId}/privacy
GET/PUT /me/privacy
GET/PUT/DELETE /me/starred-friends[/{friendUserId}]
PUT    /me/conversation-notify              # 免打扰（可合并到 conversation-settings）
```

### 7.5 废弃 / 重构

| 现有 | 处理 | 状态 |
|------|------|------|
| `POST /friend-application/accept` | 重构为调用新 Service，或标记 deprecated | ⏳ **仍并存**，客户端应切 `/friend-requests` |
| `GET /friend-application/history` | 保留或合并到新 API | ✅ 保留 |
| `POST /admin/user-friends/sync` | P3 废弃（IM 同步） | ⏳ 接口仍存在 |
| Admin 好友列表读 IM | 改为读 `user_friend` | ✅ 已完成 |

### 7.6 Admin 运营好友（已确认 ✅ 已实现）

```
GET    /api/v1/users/friends?user_uid=...     # ✅ 改读 user_friend（`AdminUserDetailService`）
GET    /api/v1/relations/friends?user_uid=... # ✅ 同上（关系页）
POST   /api/v1/users/{userUid}/friends/{peerUid}   # ✅ 强制双向加好友（审计 action: user.friend.force_add）
DELETE /api/v1/users/{userUid}/friends/{peerUid}   # ✅ 强制双向删好友（审计 action: user.friend.force_delete）
```

- 强制加好友：双向写 `user_friend`，不创建 `friend_request`
- 强制删好友：默认 **双向删除**（`UserFriendService.forceDeleteMutual`）；操作记 Admin 审计日志

### 7.7 好友申请约束（已确认）

- **隐私**：`POST /friend-requests` 须复用 `POST /users/add-friend/check` 同等规则（`addSource` 映射到 card/qr/group 等 channel；search/phone 走 `allowViaUid` / `allowViaPhone`）
- **频控**：同一 `(from_user_id, to_user_id)` **10 分钟内**不可重复发起（Redis TTL 600s；直接恢复/自动同意场景除外）

---

## 8. 完整待办清单

### P0 — 前置决策（实施前必须拍板）

- [ ] **P0-1** IM 控制台配置由谁操作（陌生人 C2C、BeforeSendMsg 回调 URL）
- [x] **P0-2** ~~系统号 BeforeSendMsg 白名单~~ → **已确认**：注册时双向绑定 `user_friend`，无需白名单；✅ **注册链路已改自建表**
- [x] **P0-3** 现有 `user_friend` IM 同步数据：**保留**作种子；`UserFriendSyncService` 暂未关闭
- [x] **P0-4** `friend_application_history` 与 `friend_request`：**两表并存**（方案 A）；✅ **已按此实现**
- [x] **P0-5** 单向删除后「对方列表仍有我」的 UI 展示策略 → ✅ 已确认；✅ API 返回 `peerDeletedMe`
- [x] **P0-6** ~~用户间拉黑~~ → **本期不做**
- [x] **P0-7** ~~群聊 BeforeSendMsg~~ → **已确认：仅 C2C 校验好友**（§10.4）；✅ handler 仅处理 `C2C.CallbackBeforeSendMsg`
- [ ] **P0-8** 发布顺序（服务端 / 客户端 / IM 控制台）；服务端 P1 已部署，**BeforeSendMsg 仍 log-only**

### P1 — 好友关系核心

#### 数据库

- [x] **P1-DB-1** `user_friend` 增 `remark` — ✅ `UserFriend.remark`
- [x] **P1-DB-2** `im_add_time` → `added_at`；弱化 `synced_at` — ✅ 新增 `added_at`，**保留** `im_add_time` 兼容种子
- [x] **P1-DB-3** 新建 `friend_request` 表 — ✅ `FriendRequest` 实体 + JPA
- [x] **P1-DB-4** 约束：同一 `(from, to)` 仅一条 pending — ✅ Service 层校验 + 查询
- [x] **P1-DB-5** `friend_application_history` 字段长度扩至 64 — ✅

#### 后端 API

- [x] **P1-API-1** `POST /friend-requests`（隐私复用 + **10 分钟频控** + 自动同意 + 直接恢复） — ✅ `FriendRequestController` / `FriendRequestService`
- [x] **P1-API-2** `GET /friend-requests/incoming` — ✅
- [x] **P1-API-3** `GET /friend-requests/outgoing` — ✅
- [x] **P1-API-4** `POST /friend-requests/{id}/accept` — ✅
- [x] **P1-API-5** `POST /friend-requests/{id}/reject` — ✅
- [x] **P1-API-6** `GET /me/friends`（含 `peerDeletedMe`、`canMessage`） — ✅ `MeFriendsController`
- [x] **P1-API-7** `PUT /me/friends/{friendUserId}/remark` — ✅（`remark` 可传空字符串清空）
- [x] **P1-API-8** `DELETE /me/friends/{friendUserId}`（**双向软删**） — ✅
- [ ] **P1-API-9** 重构 `/friend-application/accept`，避免双轨 — ⏳ 旧接口仍可用，客户端应切 `/friend-requests`

#### IM 回调

- [x] **P1-IM-1** 实现 `C2C.CallbackBeforeSendMsg` handler — ✅ `ImC2cBeforeSendMsgCallbackService`
- [x] **P1-IM-2** 校验发送方 / 接收方 `users.status=1` — ✅
- [x] **P1-IM-3** 校验双向 `user_friend` 均 active — ✅ `UserFriendService.isMutualActive` + Redis 缓存
- [x] **P1-IM-4** 拒绝响应格式 — ✅ `ErrorCode=1`, `ErrorInfo=NOT_FRIEND`
- [x] **P1-IM-5** IM 控制台 **仅**注册 `C2C.CallbackBeforeSendMsg` — ✅ 代码侧仅处理 C2C；**控制台配置待 P0-1**

#### 联动改造

- [x] **P1-LINK-1** `PushDisplayNameResolver` 改读 `user_friend.remark` — ✅
- [x] **P1-LINK-2** Admin 好友列表改读 `user_friend` + **强制加删好友 API** — ✅
- [ ] **P1-LINK-3** 关闭 `UserFriendSyncService` IM 同步 — ⏳ 仍保留（默认 scheduled 关闭，接口可用）
- [x] **P1-LINK-4** 用户改头像（`UserAvatarService`）时同步 `user_friend.friend_avatar_url` — ✅
- [x] **P1-LINK-5** 用户改昵称（`NicknameService`）时同步 `user_friend.friend_nickname` — ✅
- [x] **P1-LINK-6** 注册链路：系统号改为写自建 `user_friend` 双向关系 — ✅ `bindMutualFriends` 替代 `addFriendBoth`

#### 测试

- [ ] **P1-TEST-1** 申请 → 同意 → 双向好友 → 可发消息 — ⏳ 集成测试待补
- [ ] **P1-TEST-2** 非好友 / 单向关系 → BeforeSendMsg 拒绝 — ⏳ 需开 `before-send-enforce=true` 后联调
- [ ] **P1-TEST-3** A 单向删 B → 双方均不可发 — ⏳
- [ ] **P1-TEST-4** 重复申请、自己加自己等边界 — ⏳
- [ ] **P1-TEST-5** 双向同时 pending → 自动同意 → 可发消息 — ⏳
- [ ] **P1-TEST-6** A 删 B → B 见「对方已删除你」→ A 再申请 → B 未删 A 时直接恢复 — ⏳
- [x] **P1-TEST-U** 单元测试 — ✅ `UserFriendServiceTest`；Push 展示名测试已更新

- [x] **P1-MIG-1** 保留现有 IM 同步 `user_friend` 作初始数据 — ✅ 已保留；未做批量补反向边脚本

### P2 — 会话 UI 状态

- [ ] **P2-DB-1** `user_conversation_notify` 增 pinned / archived 字段
- [ ] **P2-API-1** conversation-settings API（muted + pinned + archived）
- [ ] **P2-API-2** 批量同步接口
- [ ] **P2-API-3** GET 返回所有非默认项
- [ ] **P2-DOC-1** 更新 `docs/push-client.md`

### ~~P2 — 用户拉黑~~（本期不做）

> 用户确认暂不需要；BeforeSendMsg 不做拉黑校验。若后续要做再单独立项。

### P3 — 清理与迁移

- [ ] **P3-1** 系统号停止 `addFriendBoth` / friend-backfill Job — ⏳ 注册链路已停 IM 加好友；`NotifyFriendBackfillService` **仍调** `addFriendBoth`
- [ ] **P3-2** 废弃 IM 好友 Admin 同步接口 — ⏳ `POST /admin/user-friends/sync` 仍存在
- [ ] **P3-3** 新建客户端对接文档 `docs/friend-self-hosted-client.md` — ✅ v1.0 已创建
- [ ] **P3-4** 重写 / 废弃 `docs/friend-application-history-client.md`
- [ ] **P3-5** 更新 `docs/backend-add-friend-via-card-integration.md`
- [ ] **P3-6** 输出 Flutter 改造清单

---

## 9. 执行方案

### 9.1 推荐实施顺序

```
P0 拍板决策
  ↓
P1 数据库变更
  ↓
P1 好友 API + Service
  ↓
P1 BeforeSendMsg 回调
  ↓
P1 Push / Admin 联动
  ↓
P1 联调测试
  ↓
P2 置顶/归档
  ↓
P3 废弃 IM 同步 + 文档 + 客户端跟进
```

### 9.2 阶段预估

| 阶段 | 范围 | 预估 |
|------|------|------|
| P1 | 好友核心 + BeforeSendMsg | ~1 周 |
| P2 | 会话设置 | ~3–5 天 |
| P3 | 清理 + 文档 | ~2–3 天 |

### 9.3 客户端改造对照

| 原 IM SDK 能力 | 改为 |
|----------------|------|
| `getFriendList` | `GET /me/friends` |
| `addFriend` | `POST /friend-requests` |
| IM 同意 / 拒绝 | `POST /friend-requests/{id}/accept` / `reject` |
| `setFriendRemark` | `PUT /me/friends/{id}/remark` |
| `deleteFriend` | `DELETE /me/friends/{id}` |
| IM 会话 pin / mute / archive | `/me/conversation-settings` |
| 星标 | 现有 `/me/starred-friends` 不变 |
| 发 C2C 消息 | 仍走 IM SDK（BeforeSendMsg 服务端拦截） |

### 9.4 推荐发布顺序

1. 服务端上线 P1（含 BeforeSendMsg）
2. 客户端发版（停止 IM 好友 SDK，改调 REST）
3. IM 控制台开启 BeforeSendMsg 回调
4. P3 关闭 IM 好友同步与系统号 `addFriendBoth`

> ⚠️ 步骤 1–3 之间存在窗口期：旧客户端仍走 IM 好友，新规则仅对新客户端 + BeforeSendMsg 生效。需评估是否可接受。

---

## 10. 待拍板的疑惑点

### 10.1 删除好友后的产品体验（已更新：双向删）

A 删 B 后，**双方**边均 tombstone，规则如下：

| 项 | 决定 |
|----|------|
| B 的列表是否显示 A | **不显示**（双向删） |
| A 能否再向 B 发起申请 | **允许**；走普通 pending / auto_accepted，**不再直接恢复** |
| `peerDeletedMe` | 兼容字段；用户删除路径正常不再产生 |

### 10.2 `friend_request` 与 `friend_application_history`

| 方案 | 说明 | 状态 |
|------|------|------|
| **A（推荐）** | 两表并存：`friend_request` 管 pending 流转；`history` 管终态展示 | ✅ **已按此实现** |
| **B** | 合并为一表，status 含 pending / accepted / rejected | 未采用 |

### 10.3 系统号（已确认 ✅）

- 用户 ↔ 系统号**不存在**好友申请流程
- **注册时**自动与系统号（99Messenger、99Chat 等）**双向绑定**自建 `user_friend`
- BeforeSendMsg **无单独白名单**，与普通好友相同：双向 `user_friend` 有效即可发消息
- P1 改造注册链路写自建表；✅ **注册已改 `bindMutualFriends`**；P3 停止 `NotifyFriendBackfillService` 等 IM 加好友

### 10.4 群聊 BeforeSendMsg（已确认 ✅：仅 C2C 校验好友）

IM 有两种发消息前回调；本方案 **只对单聊做好友校验**：

| 回调类型 | 本方案 |
|----------|--------|
| `C2C.CallbackBeforeSendMsg` | ✅ **注册**；查自建 `user_friend` 双向好友，非好友拒发 |
| `Group.CallbackBeforeSendMsg` | ❌ **不注册** / 不做好友校验（群成员通常非好友，校验会导致群消息全拒） |

群聊权限仍靠 IM 群成员身份、禁言、账号封禁等现有机制，与 `user_friend` 无关。

IM 控制台：**仅给 C2C 配置 BeforeSendMsg 回调 URL**。

### 10.5 用户间拉黑（已确认：本期不做 ✅）

当前不做用户间拉黑功能；不建 `user_block` 表；BeforeSendMsg **不**做拉黑校验。  
现有账号封禁仍走 `users.status`；设备封禁走 `admin_banned_device`。

### 10.6 已有 IM 同步数据（已确认 ✅）

`user_friend` 中已有 IM 同步数据（约 190 条）。

| 决定 | 说明 |
|------|------|
| **保留作初始数据** | 不清空；作为迁移期种子数据 |
| 后续 | P1 起新关系走自建 API；P3 停 IM 同步 Job |
| 注意 | IM 同步为**单向边**，保留数据不等于双向好友；**不能单独靠补反向边放行 BeforeSendMsg**，仅作列表展示种子；缺反向边仍不可发消息，直至走申请/恢复/运营强制加好友 |

### 10.7 重复申请 / 删除后再加

- **A 删 B 后再申请 B**：✅ 允许；走普通 pending / auto_accepted（**不再直接恢复**）
- 双方都曾删除（两条均 tombstone）：同上
- **双向同时 pending**：✅ 自动同意（§3.1、§6.2）

### 10.8 申请频控（已确认 ✅）

| 项 | 决定 |
|----|------|
| 隐私规则 | **复用** `POST /users/add-friend/check` 同等逻辑（按 `addSource` 映射 channel） |
| 频控 | 同一 `(from, to)` **10 分钟内**不可重复发起 `friend-requests`（Redis，TTL 600s） |
| 例外 | 双向 pending 自动同意 **不受** 10 分钟限制（未新建 pending） |

### 10.9 Admin 后台（已确认 ✅）

| 项 | 决定 |
|----|------|
| 好友列表数据源 | 改读自建 **`user_friend`**（不再读 IM `sns/friend_get`） |
| 运营能力 | **需要** 强制加好友 / 强制删好友（见 §7.6） |

---

## 11. P0 决策表

| # | 问题 | 建议默认 | 决定 |
|---|------|----------|------|
| 1 | 单向删除后 B 列表展示 | 仍显示 +「对方已删除你」 | ✅ 已确认 |
| 2 | 请求表设计 | 两表并存（request + history） | ✅ 已确认 + **已实现** |
| 3 | 系统号绑定 | 注册时双向写自建 `user_friend` | ✅ 已确认 |
| 4 | 用户 → 系统号发消息 | 同普通好友，靠注册双向绑定 | ✅ 已确认 |
| 5 | 群聊 BeforeSendMsg | 仅 C2C 校验好友 | ✅ 已确认 |
| 6 | 用户拉黑 | 本期不做 | ✅ 已确认 |
| 7 | 历史 IM 数据 | 保留作初始种子 | ✅ 已确认 |
| 8 | 删后再申请 | 允许；B 未删 A 时直接恢复 | ✅ 已确认 |
| 9 | B 清列表 | 须手动删除好友 | ✅ 已确认 |
| 10 | Admin 读本地表 | 改读 `user_friend` | ✅ 已确认 + **已实现** |
| 11 | Admin 强制加删好友 | 需要 | ✅ 已确认 + **已实现** |
| 12 | 申请隐私 | 复用 add-friend/check | ✅ 已确认 |
| 13 | 申请频控 | 同对用户 10 分钟 | ✅ 已确认 |
| 14 | 发布策略 | 服务端先上，客户端跟进 | |
| 15 | 双向同时 pending | 自动同意 | ✅ 已确认 |
| 16 | A 删 B 后发消息 | 双方均不可发 | ✅ 已确认 |

---

## 12. 相关文件索引

| 类型 | 路径 | 状态 |
|------|------|------|
| 好友实体 | `src/main/java/com/chat99/server/user/UserFriend.java` | ✅ |
| 好友 Service | `src/main/java/com/chat99/server/user/UserFriendService.java` | ✅ |
| 好友 Repository | `src/main/java/com/chat99/server/user/UserFriendRepository.java` | ✅ |
| 好友 App API | `src/main/java/com/chat99/server/user/MeFriendsController.java` | ✅ |
| 申请实体 | `src/main/java/com/chat99/server/user/FriendRequest.java` | ✅ |
| 申请 Service | `src/main/java/com/chat99/server/user/FriendRequestService.java` | ✅ |
| 申请 API | `src/main/java/com/chat99/server/user/FriendRequestController.java` | ✅ |
| 申请频控 | `src/main/java/com/chat99/server/user/FriendRequestRateLimiter.java` | ✅ |
| 双向好友缓存 | `src/main/java/com/chat99/server/user/UserFriendMutualCache.java` | ✅ |
| 配置 | `src/main/java/com/chat99/server/user/UserFriendProperties.java` | ✅ |
| BeforeSendMsg | `src/main/java/com/chat99/server/im/ImC2cBeforeSendMsgCallbackService.java` | ✅ |
| Admin 强制加删 | `src/main/java/com/chat99/server/adminapi/AdminUserFriendOpsController.java` | ✅ |
| Admin 好友列表 | `src/main/java/com/chat99/server/adminapi/AdminUserDetailService.java` | ✅ |
| IM 同步服务（待废弃） | `src/main/java/com/chat99/server/user/UserFriendSyncService.java` | ⏳ |
| 申请历史（旧 accept） | `src/main/java/com/chat99/server/user/FriendApplicationService.java` | ⏳ 并存 |
| 星标好友 | `src/main/java/com/chat99/server/user/StarredFriendService.java` | 已有 |
| 会话免打扰 | `src/main/java/com/chat99/server/push/ConversationNotifyController.java` | 已有 |
| IM 回调入口 | `src/main/java/com/chat99/server/im/ImMessageWebhookController.java` | ✅ |
| Push 展示名 | `src/main/java/com/chat99/server/push/PushDisplayNameResolver.java` | ✅ |
| 系统号绑定 | `src/main/java/com/chat99/server/notify/SystemNotifyService.java` | ✅ |
| 支付助手绑定 | `src/main/java/com/chat99/server/notify/PlatformWalletNoticeService.java` | ✅ |
| 单元测试 | `src/test/java/com/chat99/server/user/UserFriendServiceTest.java` | ✅ |
| 客户端对接文档 | `docs/friend-self-hosted-client.md` | ✅ v1.0 |
| 现有客户端文档 | `docs/starred-friends-client.md` | 已有 |
| 现有客户端文档 | `docs/backend-add-friend-via-card-integration.md` | 待更新（P3） |
| Push 文档 | `docs/push-client.md` | 待更新（P2） |

---

## 13. 变更记录

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-06-15 | v0.8 | **P1 后端实施完成**：更新 §5 现状、§8 待办勾选、§12 文件索引；标注灰度配置与未完成项（P1-API-9、P1-LINK-3、P3、集成测试） |
| 2026-06-15 | v0.7 | 确认：仅 C2C BeforeSendMsg 校验双向好友，群聊不校验（§10.4） |
| 2026-06-15 | v0.6 | 确认：不做拉黑；保留 IM 种子数据；申请隐私+10min 频控；Admin 读 user_friend+强制加删 |
| 2026-06-15 | v0.5 | 确认单向删除 UI、B 手动清列表、删后再申请直接恢复（§10.1） |
| 2026-06-15 | v0.4 | 确认：双向 pending 自动同意；系统号注册双向绑定；A 删 B 后双方不可发 |
| 2026-06-15 | v0.3 | §6.1.1：用户改头像/昵称时联动刷新 `user_friend` 缓存字段 |
| 2026-06-15 | v0.2 | 澄清 IM 陌生人 C2C 与 BeforeSendMsg 查自建 `user_friend` 的分工（§2.1） |
| 2026-06-15 | v0.1 | 初版：汇总自建好友链改造方案、待办、疑惑点 |
