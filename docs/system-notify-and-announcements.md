# 系统通知与公告 — 产品设计 & 对接文档

> 版本：v1.2（P0/P0b/P1 已实现；P2 全站 IM 分批推送待实现）  
> 适用：99chat-server + Flutter 客户端 + 运营后台  
> 用户规模参考：**约 5 万注册用户**  
> 核心形态：**普通 IM 账号充当「假公众号」** — 注册即双向好友，消息走 C2C 推送  
> 关联文档：[official-account.md](./official-account.md)（腾讯云**原生公众号**，**本方案不采用**）

---

## 1. 背景与目标

### 1.1 业务诉求

平台需要向用户推送消息，**不是**微信式「订阅号」运营形态，主要包括：

| 类型 | 示例 | 触达对象 |
|------|------|----------|
| **事务通知** | 注册欢迎、充值到账、提现成功/失败、转账结果 | 单个用户 |
| **个人公告** | 风控提醒、专属活动、定向通知 | 指定用户 |
| **全站公告** | 系统维护、版本更新、平台活动 | 全体用户 |

要求：**在线用户尽量实时收到**；离线用户打开 App 后仍能看到（尤其全站公告）。

### 1.2 方案结论（5 万用户）

| 决策 | 选择 | 原因 |
|------|------|------|
| 公众号形态 | **普通 IM 用户「假公众号」** | 不依赖 `@TOA#_`、订阅、广播 API；UI 上可当订阅号展示 |
| 与用户关系 | **注册后自动双向好友** | REST `sns/friend_add`，`ForceAddFlags=1`，无需用户点「关注」 |
| 推送方式 | **C2C 单聊**（`openim/sendmsg`） | 好友会话内收消息，体验接近公众号会话 |
| 是否使用腾讯云**公众号产品** | **否** | 与假公众号重复；频控严（广播约 2 条/小时） |
| 全站公告主路径 | **数据库 + 客户端拉取** | 保证必达 |
| 全站公告增强 | **可选分批 C2C** | 对好友列表中的用户逐条 `sendmsg` |
| 是否自建 WebSocket / 独立 FCM | **否** | 5 万规模由 IM 长连接 + 离线推送覆盖 |

### 1.3 「假公众号」是什么

在腾讯 IM 里创建一个**普通账号**（如 `sys_99chat`），在 App 里：

- 会话列表显示为 **「99chat 通知」**（置顶、禁言输入框仅展示等由客户端做）
- 技术上它是 **C2C 好友**，不是 `subscribeOfficialAccount`
- 所有注册用户与该号 **默认已是好友**，服务端可直接发消息

与真公众号对比：

| 项 | 腾讯云原生公众号 | 假公众号（本方案） |
|----|------------------|-------------------|
| IM 账号类型 | `@TOA#_` 特殊 ID | 普通 `userId` |
| 用户侧关系 | 订阅 `add_subscriber` | **好友** `friend_add` |
| 收消息 | 公众号广播 / 单聊 API | **普通 C2C** |
| 发现页「关注」 | 需要 | **不需要**（注册即好友） |
| 客户端 SDK | `subscribeOfficialAccount` | `好友列表` + `c2c_sys_99chat` |

### 1.4 与现有「公众号」文档的关系

| 文档/模块 | 定位 |
|-----------|------|
| **本文档** | 假公众号 + 系统通知、全站/个人公告（**推荐按此实现**） |
| [official-account.md](./official-account.md) | 腾讯云原生公众号；**新业务不依赖** |

过渡期：注册仍可能走 `OfficialAccountWelcomeService`（`add_subscriber` + 欢迎语）；目标态改为 **`friend_add` + C2C**，去掉订阅。

---

## 2. 架构总览

### 2.1 发件人：系统 IM 账号（假公众号）

| 项 | 建议值 | 说明 |
|----|--------|------|
| IM `userId` | **`99Messenger`**（可配置 `chat99.system-notify.sender-user-id`） | 启动时 `account_import`（`bootstrap-on-startup`） |
| 展示名 | `99Messenger` | `Nick` / 头像可配置 |
| 加好友验证 | `AllowType_Type_AllowAny` | 避免用户侧需确认；由服务端 REST 设置 |
| 会话 ID（Flutter） | `c2c_99Messenger` | 标准 C2C，**非**公众号会话类型 |

**系统号初始化（一次性，运维/启动脚本）**

1. `im_open_login_svc/account_import` — 导入 `sys_99chat`  
2. `profile/portrait_set` — 昵称、头像、`Tag_Profile_IM_AllowType` = `AllowType_Type_AllowAny`  
3. （可选）写入本地表 `official_accounts` 仅作 App「通知号」展示配置，**不**调 `create_official_account`

配置项（目标态）：

```yaml
chat99:
  system-notify:
    sender-user-id: ${SYSTEM_NOTIFY_SENDER_ID:99Messenger}
    sender-display-name: ${SYSTEM_NOTIFY_SENDER_NAME:99Messenger}
    sender-face-url: ${SYSTEM_NOTIFY_SENDER_FACE_URL:}   # 通知号头像
    auto-friend-on-register: true                          # 注册后自动双向好友
    friend-add-source: AddSource_Type_Server             # IM 要求的前缀+关键字
    register-welcome-enabled: true
    register-welcome-message: "欢迎使用99 Messenger。"
    global-push-enabled: true
    global-push-batch-size: 300
    global-push-delay-ms: 150
    global-push-active-days: 30
```

### 2.2 注册链路（默认加好友 + 欢迎语）

```
POST /auth/register 成功
  ├─ account_import(新用户 userId)
  ├─ sns/friend_add（From=sys_99chat → To=新用户，双向、强制）
  │     或 From=新用户 → To=sys_99chat 再补一条；推荐系统号发起双向一次即可
  ├─ sendC2cText / sendCustomC2c（sys_99chat → 新用户，欢迎语）
  └─ 返回 JWT（IM 失败仅打日志，不挡 200）
```

**腾讯 IM REST：`sns/friend_add`（由服务端管理员账号调用）**

```json
{
  "From_Account": "sys_99chat",
  "AddFriendItem": [
    {
      "To_Account": "<新用户 platformId>",
      "AddSource": "AddSource_Type_Server",
      "AddWording": "欢迎使用99 Messenger"
    }
  ],
  "AddType": "Add_Type_Both",
  "ForceAddFlags": 1
}
```

| 字段 | 说明 |
|------|------|
| `AddType` | `Add_Type_Both`：双向好友（用户好友列表里也有系统号） |
| `ForceAddFlags` | `1`：强制加好友，不走过客验证 |
| `AddSource` | 必须以 `AddSource_Type_` 为前缀 |

实现归属：`ImAdminClient.addFriend(...)` + `SystemNotifyService.onUserRegistered(userId)`。

**注意**

- 仅 C2C 发消息**不要求**已是好友，但产品要求「默认好友」以便会话列表展示、未读数、历史漫游一致。  
- 老用户迁移：可跑一次性 Job，对 `users` 表批量 `friend_add`（分批 ≤1000/次）。

### 2.3 推送数据流

```
┌─────────────────────────────────────────────────────────────────┐
│                        99chat-server                             │
├─────────────────────────────────────────────────────────────────┤
│  SystemNotifyService          AnnouncementService                │
│    · ensureFriend(userId)       · publishGlobal() / Personal()    │
│    · registerWelcome()                                           │
│    · walletDeposit() …                                           │
│         │                              │                         │
│         └──────────┬───────────────────┘                         │
│                    ▼                                             │
│              ImAdminClient                                       │
│    sns/friend_add（注册/补绑）                                    │
│    openim/sendmsg（C2C 文本 / 自定义）                            │
│    From: sys_99chat  →  To: userId                               │
└────────────────────────────┬────────────────────────────────────┘
                             ▼
                    腾讯 IM（好友 C2C + 离线推送）
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Flutter：好友列表含 sys_99chat；C2C 监听；全站公告 HTTP 拉取     │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 三类消息送达策略

| 类型 | 存储 | IM 推送 | 客户端拉取 |
|------|------|---------|------------|
| 事务通知 | 可选 `system_message_log` | **必做** | 不需要 |
| 个人公告 | `announcement` 表 | **必做**（目标用户） | `GET /me/announcements` 备份 |
| 全站公告 | `announcement` 表 | **可选**（分批） | **必做**（启动/回前台） |

---

## 3. 数据模型（目标态）

### 3.1 表：`announcement`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(36) PK | UUID |
| `type` | ENUM | `GLOBAL` \| `PERSONAL` |
| `title` | VARCHAR(200) | 标题 |
| `body` | TEXT | 正文（纯文本或 Markdown，由客户端约定） |
| `link_url` | VARCHAR(500) NULL | 可选跳转链接 |
| `payload_json` | JSON NULL | 扩展字段（图片 URL、按钮文案等） |
| `priority` | INT | 排序权重，越大越靠前，默认 0 |
| `target_user_id` | VARCHAR(32) NULL | `PERSONAL` 时必填 |
| `status` | ENUM | `DRAFT` \| `PUBLISHED` \| `REVOKED` |
| `publish_at` | TIMESTAMP NULL | 发布时间 |
| `expire_at` | TIMESTAMP NULL | 过期时间（NULL=长期有效） |
| `im_push_status` | ENUM NULL | 全站分批推送：`PENDING` \| `RUNNING` \| `DONE` \| `SKIPPED` |
| `created_by` | VARCHAR(64) NULL | 运营标识 |
| `created_at` / `updated_at` | TIMESTAMP | — |

索引建议：`(type, status, publish_at)`、`(target_user_id, status)`。

### 3.2 表：`announcement_read`

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | VARCHAR(32) | 用户平台 ID |
| `announcement_id` | VARCHAR(36) | 公告 ID |
| `read_at` | TIMESTAMP | 已读时间 |

主键：`(user_id, announcement_id)`。用于全站公告未读红点、弹窗只展示一次。

### 3.3 表：`system_message_log`（可选）

记录事务类 IM 发送结果，便于客服排查。

| 字段 | 说明 |
|------|------|
| `id`, `user_id`, `kind`, `payload_json`, `im_msg_key`, `success`, `error`, `sent_at` | — |

`kind` 示例：`REGISTER_WELCOME`、`WALLET_DEPOSIT`、`WALLET_WITHDRAW`。

---

## 4. IM 消息格式（客户端必读）

统一使用 **`TIMCustomElem`**（`sendCustomC2c`），便于扩展；纯文本欢迎可用 `TIMTextElem`（`sendC2cText`）。

### 4.1 自定义消息外壳

`MsgContent.Data` 为 **JSON 字符串**（UTF-8），建议结构：

```json
{
  "type": "<见下表>",
  "version": 1,
  "sentAt": "2026-05-29T10:00:00Z",
  "payload": { }
}
```

### 4.2 `type` 枚举

| type | 场景 | payload 示例字段 |
|------|------|------------------|
| `register_welcome` | 注册欢迎 | `{ "text": "欢迎使用…" }` |
| `wallet_deposit` | 充值到账 | `amountUsdt`, `txId`, `title` |
| `wallet_withdraw` | 提现结果 | `status`, `amountUsdt`, `txId`, `title` |
| `wallet_transfer` | 转账/收款（若需要） | `direction`, `amount`, `counterparty` |
| `announcement` | 个人/全站公告 | `announcementId`, `scope`, `title`, `body`, `linkUrl` |

### 4.3 全站公告 IM 推送（可选）

与 `announcement` 表一致，`scope: "GLOBAL"`，`announcementId` 用于客户端跳转详情或标记已读。

### 4.4 会话展示建议（Flutter）

1. 固定会话：**系统通知**（对端 `sys_99chat`）。  
2. `type` 映射为卡片 UI（钱包类可 Deep link 到钱包页）。  
3. 全站公告：除 IM 外，**启动时** `GET /me/announcements` 弹 Modal / 顶部横幅。  
4. 未知 `type`：展示 `title` + 原始 JSON（兼容升级）。

---

## 5. HTTP API（目标态）

### 5.1 用户端（JWT）

#### `GET /me/announcements`

查询当前用户可见的**已发布公告**（全站 + 发给自己的个人公告）。

| Query | 说明 |
|-------|------|
| `unreadOnly` | `true` 时仅返回未读（依赖 `announcement_read`） |
| `limit` | 默认 20，最大 50 |

**200 示例**

```json
{
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "type": "GLOBAL",
      "title": "系统维护通知",
      "body": "今晚 2:00–4:00 进行升级…",
      "linkUrl": null,
      "priority": 10,
      "publishAt": "2026-05-29T08:00:00Z",
      "expireAt": "2026-06-05T08:00:00Z",
      "read": false
    }
  ]
}
```

#### `POST /me/announcements/{id}/read`

标记公告已读。`204` 无 body。

#### `GET /me/announcements/{id}`

单条详情（可选，列表已够用时可不实现）。

---

### 5.2 管理端（Admin IP 白名单）

路径前缀建议：`/admin/announcements`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/announcements` | 创建草稿 |
| GET | `/admin/announcements` | 列表（含草稿） |
| GET | `/admin/announcements/{id}` | 详情 |
| PATCH | `/admin/announcements/{id}` | 修改草稿 |
| POST | `/admin/announcements/{id}/publish` | 发布；`PERSONAL` 立即 IM；`GLOBAL` 写库 + 可选触发分批 Job |
| POST | `/admin/announcements/{id}/revoke` | 撤回 |
| POST | `/admin/announcements/{id}/push-im` | 对已发布全站公告手动/重试 IM 分批推送 |

**创建草稿 — PERSONAL**

```json
{
  "type": "PERSONAL",
  "targetUserId": "abc12def34",
  "title": "账户安全提醒",
  "body": "检测到异常登录…",
  "linkUrl": null,
  "priority": 0,
  "expireAt": null
}
```

**创建草稿 — GLOBAL**

```json
{
  "type": "GLOBAL",
  "title": "新版本上线",
  "body": "请更新至 2.0…",
  "linkUrl": "https://example.com/release-notes",
  "priority": 5,
  "expireAt": "2026-06-30T00:00:00Z"
}
```

---

### 5.3 事务通知（无单独用户 API）

由服务端业务代码调用 `SystemNotifyService`，不暴露给客户端触发。

| 触发点 | 方法（规划名） | 说明 |
|--------|----------------|------|
| `POST /auth/register` 成功 | `onUserRegistered(userId)` | `friend_add`（双向强制）+ 欢迎 C2C |
| 充值入账 `DepositScanService` | `sendWalletDeposit(userId, …)` | 先发 C2C；若未好友则 `ensureFriend` 后重试 |
| 提现状态终态 `WithdrawService` | `sendWalletWithdraw(userId, …)` | 同上 |

`ensureFriend(userId)`：查本地标记或调 IM `sns/friend_check`，未好友则补 `friend_add`（用于老用户或加好友失败补偿）。

---

## 6. 全站公告：5 万用户推送估算

假设 **全员 IM 推送**（`global-push-active-days: 0`）：

| 参数 | 值 |
|------|-----|
| 用户数 | 50,000 |
| 每批条数 | 300 |
| 批间间隔 | 150 ms |
| 每批耗时（REST） | ~1–2 s（视 IM 延迟） |

粗算：约 **170 批**，总时长 **约 15–25 分钟**（含间隔）。一天 1～2 条全站公告可接受。

若 `global-push-active-days: 30`，假设活跃 40%（2 万人），时长约 **6–10 分钟**。

**推荐运营策略**

1. **重要必达**（维护、合规）：发布 + 依赖 **拉取 + 启动弹窗**；IM 推送作增强。  
2. **营销活动**：可开启 IM 分批 + 拉取双通道。  
3. **不急**：仅拉取，不发 IM，零 REST 压力。

---

## 7. 客户端集成指南

### 7.1 前置条件

1. 用户已登录业务后端，持有 JWT。  
2. `GET /im/user-sig` → IM `login(userId, userSig)`。  
3. 注册成功后，服务端已 **自动加 `sys_99chat` 为好友**；客户端 **无需** 再调 `addFriend` / `subscribeOfficialAccount`。  
4. 监听 C2C 新消息（`conversationID == c2c_sys_99chat`）。

### 7.2 UI 建议（假公众号展示）

| 能力 | 建议 |
|------|------|
| 会话列表 | 将 `sys_99chat` **置顶**；标题用服务端昵称或本地文案「系统通知」 |
| 聊天页 | **禁止用户主动发消息**（输入框隐藏或置灰），仅展示通知流 |
| 好友列表 | 可隐藏系统号，或放在「服务号」分组 |
| 未读红点 | 与普通 C2C 相同，走 IM 未读计数 |
| 历史消息 | 走 IM 漫游；全站公告重要内容仍建议 `GET /me/announcements` 弹窗 |

### 7.3 推荐流程

```
App 启动 / 回前台
  ├─ IM login 后拉取好友列表（应已含 sys_99chat）
  ├─ GET /me/announcements?unreadOnly=true → 全站弹窗
  └─ 监听 c2c_sys_99chat 新消息

用户注册成功
  └─ 客户端不调加好友；等服务端 friend_add + 首条欢迎 C2C（数秒内）

钱包页
  └─ wallet_* 自定义消息 → 卡片 / 跳转钱包
```

### 7.4 勿再使用的 SDK / 接口

| 场景 | 应使用 | 不应使用 |
|------|--------|----------|
| 建立关系 | 服务端注册时 `friend_add` | `subscribeOfficialAccount`、`POST .../official-accounts/.../subscribe` |
| 收通知 | C2C `c2c_sys_99chat` | 公众号广播监听专用 API |
| 全站公告 | `GET /me/announcements` | `send_official_account_msg` |

---

## 8. 配置项

### 8.1 已实现配置（P0）

```yaml
chat99:
  system-notify:
    sender-user-id: 99Messenger
    sender-display-name: 99Messenger
    bootstrap-on-startup: true
    auto-friend-on-register: true
    register-welcome-enabled: true
    register-welcome-message: "欢迎使用99 Messenger。"
    wallet-notify-enabled: true
  official-account:
    register-welcome-enabled: false   # 注册欢迎改由 system-notify 处理
```

注册成功：`SystemNotifyService.onUserRegistered` → `sns/friend_add`（双向）+ 欢迎 `sendC2cText`。  
充值到账 / 提现成功或失败：`sendCustomC2c`（`wallet_deposit` / `wallet_withdraw`）。

### 8.2 目标态（待实现）

见 §2.1 `chat99.system-notify.*`。环境变量命名建议：

| 变量 | 说明 |
|------|------|
| `SYSTEM_NOTIFY_SENDER_ID` | 系统号 userId |
| `SYSTEM_NOTIFY_REGISTER_WELCOME` | 是否发注册欢迎 |
| `SYSTEM_NOTIFY_WELCOME_MSG` | 欢迎文案 |
| `SYSTEM_NOTIFY_GLOBAL_PUSH_ENABLED` | 全站是否分批 IM |
| `SYSTEM_NOTIFY_GLOBAL_PUSH_ACTIVE_DAYS` | 仅推活跃用户天数 |

---

## 9. 错误码与失败策略

| 场景 | HTTP / 行为 |
|------|-------------|
| IM 未配置 | 事务/公告 IM 推送跳过或记日志；注册/业务 **仍 200** |
| IM `sendmsg` 失败 | 记 `system_message_log`；可告警；**不阻塞**主业务事务 |
| 全站分批 Job 中单用户失败 | 记失败计数，继续下一批；支持 `push-im` 重试 |
| 公告已撤回 | `GET /me/announcements` 不返回；已发 IM 无法撤回（客户端以拉取为准） |

---

## 10. 实施阶段（开发排期）

| 阶段 | 内容 | 状态 |
|------|------|------|
| **P0** | `99Messenger` 初始化；`friend_add`；`SystemNotifyService`（注册 + 充值/提现通知） | **已实现** |
| **P0b** | 老用户批量补好友 Job（可选） | **已实现** |
| **P1** | `announcement` + `announcement_read` 表；Admin CRUD + 发布/撤回；`GET/POST /me/announcements` | **已实现** |
| **P2** | 全站公告发布后异步分批 IM Job；配置 `global-push-*` | 待实现 |
| **P3** | `system_message_log`；`GET /me/notifications` 聚合页；运营后台 UI | 可选 |

---

## 11. 迁移说明

| 现状 | 目标 |
|------|------|
| 注册 → `add_subscriber` + 欢迎 C2C | 注册 → **`friend_add`（双向）** + 欢迎 C2C |
| 发件人 `@TOA#_@TOA#d4CH` 公众号 ID | 发件人 **`sys_99chat` 普通 IM 号** |
| 用户需「订阅」才在公众号体系内 | **注册即好友**，无需关注 |
| `POST /official-accounts/.../subscribe` | 系统通知**不依赖**；可下线或仅作历史兼容 |
| `POST /admin/.../broadcast`（公众号广播） | 全站公告 `/admin/announcements` + 分批 C2C |

---

## 12. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05-29 | v1.0 设计稿：5 万用户「系统 IM 号 + 公告库 + 分级推送」 |
| 2026-05-29 | v1.2 P1：`announcement` 表、Admin CRUD、用户拉取/已读 API |
| 2026-05-29 | v1.1 明确「假公众号」：普通 IM 号 + 注册默认双向好友 + C2C 推送 |
