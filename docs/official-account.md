# 99chat 公众号对接文档

> 版本：v1.0  
> 适用：99chat-server (Spring Boot 3.5) + 99chat (Flutter) + 运营后台  
> 范围：腾讯云 Chat **原生公众号**（订阅、广播、单聊）；不含社群模拟方案（WhatsApp Channel）

> **若仅需系统推送**（注册欢迎、支付通知、全站/个人公告），请优先阅读 [system-notify-and-announcements.md](./system-notify-and-announcements.md)。  
> **推荐形态**：普通 IM 账号（如 `sys_99chat`）作「假公众号」— 注册自动 `friend_add` 双向好友，消息走 **C2C**。  
> 本文档描述腾讯云**原生公众号**产品（`@TOA#_`、订阅、广播），新业务可不采用。

---

## 1. 总览

### 1.1 能力说明

公众号用于平台向用户推送**系统通知、活动、公告**，交互形态类似微信订阅号：

| 能力 | 服务端 | 客户端（IM SDK） |
|------|--------|------------------|
| 发现可关注的号 | `GET /official-accounts` | — |
| 订阅 / 取关 | `POST/DELETE .../subscribe` | `subscribeOfficialAccount` / `unsubscribeOfficialAccount` |
| 接收广播（推文） | 管理端 `broadcast` 或腾讯云控制台 | 消息监听器 |
| 与公众号单聊 | — | 会话 ID：`c2c_{officialAccountId}` |
| 查已订阅列表 | `GET /official-accounts/subscribed/me` | `getOfficialAccountsInfo` |

后端职责：

1. 在腾讯云 IM 创建公众号，并在本地表 `official_accounts` 登记（供 App 列表展示）。
2. 代理订阅关系（调用 IM `add_subscriber` / `delete_subscriber`）。
3. 提供运营侧创建、改资料、群发文本广播。

客户端仍需：**登录 IM**（`GET /im/user-sig`）后，通过 SDK 收消息、展示会话。

### 1.2 与 TRTC 的区别

- **公众号**属于 **即时通信 Chat / IM** 产品，不是 TRTC 音视频。
- 公众号 ID 必须以 `@TOA#_` 为前缀（腾讯云规则）。

### 1.3 端到端时序（推荐）

```
运营后台/脚本          99chat-server              Tencent IM              Flutter
      |                     |                         |                      |
      |--POST /admin/official-accounts------------->|                      |
      |                     |--create_official_account--------------------->|
      |                     |--INSERT official_accounts                     |
      |<--200 OfficialAccountView-------------------|                      |
      |                     |                         |                      |
      |                     |                         |    用户已登录 JWT     |
      |                     |<--GET /official-accounts----------------------|
      |                     |--返回 enabled 列表---->|                      |
      |                     |                         |                      |
      |                     |<--POST .../subscribe-------------------------|
      |                     |--add_subscriber------------------------------>|
      |                     |                         |                      |
      |                     |                         |<--IM login(userSig)---|
      |                     |                         |<--onNewMessage 广播--|
      |                     |                         |                      |
      |--POST .../broadcast>|                         |                      |
      |                     |--send_official_account_msg----------------->|
      |                     |                         |--推送给订阅者------->|
```

### 1.4 前置条件

1. 腾讯云 IM 应用已开通**公众号**能力（旗舰版 / 企业版，或开发版体验，上限约 5 个号）。
2. 服务端已配置 IM 密钥（与应用设置 / 环境变量一致，见 §3）。
3. 创建公众号时的 **Owner** 必须是已注册且已 `account_import` 的用户（10 位平台 ID）。
4. 默认服务端口：`8081`；下文路径均相对于 API 根路径。

---

## 2. 数据模型

### 2.1 表：`official_accounts`（服务端维护）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增 |
| slug | VARCHAR(48) UK | 运营短名，如 `news` |
| official_account_id | VARCHAR(64) UK | IM 公众号 ID，如 `@TOA#_99chat_news` |
| name | VARCHAR(150) | 展示名称 |
| introduction | VARCHAR(400) | 简介 |
| face_url | VARCHAR(500) | 头像 URL |
| organization | VARCHAR(500) | 组织名（可选） |
| owner_account | VARCHAR(32) | IM Owner，须为已导入用户 ID |
| max_subscriber_num | INT | 最大订阅人数，默认 100000 |
| enabled | BOOLEAN | 是否在 App 列表展示 |
| sort_order | INT | 列表排序，升序 |
| created_at / updated_at | TIMESTAMP | — |

表由 JPA `ddl-auto: update` 自动创建/演进。

### 2.2 IM 侧概念

| 概念 | 说明 |
|------|------|
| `officialAccountId` | 全局唯一，前缀 `@TOA#_`，最长 48 字节可打印 ASCII |
| 订阅者 | 普通用户的平台 ID（与 IM `userId` 相同） |
| 广播消息 | 一对多推送，对应运营「推文」 |
| 单聊 | `From_Account`=公众号 ID，`To_Account`=用户 ID |

---

## 3. 配置项

### 3.1 `application.yml`

```yaml
chat99:
  im:
    user-sig-expire-seconds: 7776000
    rest-admin-account: administrator    # IM 管理员账号
    rest-base-url: https://adminapisgp.im.qcloud.com/v4/
  official-account:
    id-prefix: "@TOA#_99chat_"           # 必须以 @TOA#_ 开头，且不能含 @TOA#_@TOA#
    default-owner-user-id: ""            # 创建时未传 ownerUserId 则必填
    default-max-subscribers: 100000
  admin:
    ip-whitelist: 127.0.0.1,0:0:0:0:0:0:0:1
```

### 3.2 环境变量

| 变量 | 说明 |
|------|------|
| `IM_SDK_APP_ID` / `IM_KEY` | 与应用设置一致，供 UserSig 与 REST |
| `OFFICIAL_ACCOUNT_ID_PREFIX` | 覆盖 `id-prefix` |
| `OFFICIAL_ACCOUNT_OWNER` | 覆盖 `default-owner-user-id` |
| `OFFICIAL_ACCOUNT_PRIMARY_ID` | 主公众号 IM ID，默认 `@TOA#_@TOA#d4CH` |
| `OFFICIAL_ACCOUNT_PRIMARY_SLUG` | 本地短名，默认 `d4ch` |
| `OFFICIAL_ACCOUNT_BOOTSTRAP` | 启动时自动绑定主号到本地表，默认 `true` |
| `ADMIN_IP_WHITELIST` | 管理端 IP 白名单（逗号分隔） |

### 3.3 注册欢迎消息

| 配置项 | 默认 |
|--------|------|
| `register-welcome-enabled` | `true` |
| `register-welcome-message` | `欢迎使用99 Messenger。` |

**注册**（`POST /auth/register` 成功）与 **主动关注**（`POST /official-accounts/{id}/subscribe`）均会：

1. `add_subscriber` 订阅公众号  
2. `openim/sendmsg` 发送欢迎单聊（默认文案：`欢迎使用99 Messenger。`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `register-welcome-enabled` | `true` | 注册时是否执行上述流程（主公众号） |
| `subscribe-welcome-enabled` | `true` | 每次关注是否发欢迎语 |
| `welcome-message` | 见 yml | 欢迎文案（注册与关注共用） |

IM 失败时：注册仅打日志不影响 200；关注接口在订阅 IM 失败时返回 `IM_REST_ERROR`（发欢迎失败仍算关注成功，仅日志）。

环境变量：`OFFICIAL_ACCOUNT_REGISTER_WELCOME`、`OFFICIAL_ACCOUNT_SUBSCRIBE_WELCOME`、`OFFICIAL_ACCOUNT_WELCOME_MSG`。

**说明**：仅走 IM SDK `subscribeOfficialAccount` 且不调服务端关注接口时，**不会**自动发欢迎语；建议 App 关注时调用 `POST .../subscribe`。

### 3.4 当前生产公众号（平台主号）

| 项 | 值 |
|----|-----|
| **IM 公众号 ID** | `@TOA#_@TOA#d4CH` |
| **本地 slug** | `d4ch`（可配置） |
| **App 列表/订阅路径参数** | 使用完整 `officialAccountId`，URL 需编码 |

服务启动且 IM 配置正确时，会自动调用 IM 拉取资料并写入 `official_accounts`（若尚未登记）。也可手动：

```bash
POST /admin/official-accounts/link
{ "officialAccountId": "@TOA#_@TOA#d4CH", "slug": "d4ch" }
```

### 3.3 公众号 ID 生成规则

```
officialAccountId = id-prefix + slug
```

示例：`id-prefix=@TOA#_99chat_`，`slug=news` → `@TOA#_99chat_news`

`slug` 规则：`^[a-z0-9][a-z0-9_-]{0,30}$`（小写、数字、`_`、`-`）。

---

## 4. 鉴权

| 调用方 | 鉴权方式 |
|--------|----------|
| App 用户接口 | `Authorization: Bearer <jwt>`（登录接口返回的 `token`） |
| 管理端接口 | **无 JWT**；请求来源 IP 须在 `chat99.admin.ip-whitelist` 内 |
| IM 相关 | 用户接口需先登录；IM 登录用 `GET /im/user-sig` |

统一错误体（校验失败等）：

```json
{ "code": "ERROR_CODE", "message": "ERROR_CODE" }
```

IM 调用失败时额外带 `imErrorCode`：

```json
{
  "code": "IM_REST_ERROR",
  "message": "腾讯云返回的 ErrorInfo",
  "imErrorCode": 131000
}
```

广播频控触达时 HTTP **429**，`imErrorCode` 常为 `10023`。

---

## 5. API 契约

> 全部 `Content-Type: application/json`。  
> 路径参数 `{officialAccountId}` 需 URL 编码（含 `#` 等特殊字符）。

### 5.1 公共数据结构：`OfficialAccountView`

列表/详情/创建/更新均返回该结构（字段可能部分为 `null`）：

```json
{
  "slug": "news",
  "officialAccountId": "@TOA#_99chat_news",
  "name": "99chat 官方",
  "introduction": "系统通知与活动",
  "faceUrl": "https://cdn.example.com/oa/news.png",
  "organization": "99chat",
  "ownerAccount": "abc12def34",
  "maxSubscriberNum": 100000,
  "enabled": true,
  "sortOrder": 0,
  "subscriberNum": 1280,
  "createTime": 1716451200
}
```

| 字段 | 说明 |
|------|------|
| `subscriberNum` | 来自 IM 查询，IM 未配置或未开通时可能为 `null` |
| `createTime` | IM 侧创建时间（秒级 Unix），可能为 `null` |

---

### 5.2 用户端接口（需 JWT）

#### `GET /official-accounts`

返回所有 **`enabled=true`** 的公众号，按 `sortOrder`、`id` 升序。

**响应**：`OfficialAccountView[]`

---

#### `GET /official-accounts/{officialAccountId}`

单个公众号详情。未启用或不存在 → **404** `OFFICIAL_ACCOUNT_NOT_FOUND`。

**响应**：`OfficialAccountView`

---

#### `GET /official-accounts/subscribed/me`

查询当前登录用户在 IM 侧的**已订阅公众号**（非本地表）。

**Query**

| 参数 | 默认 | 说明 |
|------|------|------|
| `limit` | 50 | 1–200 |
| `offset` | 0 | ≥0 |

**响应**：腾讯云原始结构数组（`OfficialAccountInfoList` 元素），示例字段：

```json
[
  {
    "Official_Account": "@TOA#_99chat_news",
    "Name": "99chat 官方",
    "Introduction": "...",
    "FaceUrl": "...",
    "SubscriberNum": 1280,
    "SubscribeTime": 1716451300
  }
]
```

---

#### `POST /official-accounts/{officialAccountId}/subscribe`

为当前用户添加订阅（IM `add_subscriber`），并在 `subscribe-welcome-enabled=true` 时发送欢迎单聊。已订阅再次调用仍会尝试发欢迎语。

**响应**

```json
{ "officialAccountId": "@TOA#_99chat_news", "status": "subscribed" }
```

**错误**

| HTTP | code | 说明 |
|------|------|------|
| 404 | OFFICIAL_ACCOUNT_NOT_FOUND | 不存在或未启用 |
| 502 | IM_REST_ERROR | IM 失败（见 `imErrorCode`） |

---

#### `DELETE /official-accounts/{officialAccountId}/subscribe`

取消订阅（IM `delete_subscriber`）。

**响应**

```json
{ "officialAccountId": "@TOA#_99chat_news", "status": "unsubscribed" }
```

---

### 5.3 管理端接口（IP 白名单）

> 从白名单 IP 发起请求，**不需要** Bearer Token。

#### `GET /admin/official-accounts`

全部公众号（含 `enabled=false`）。

**响应**：`OfficialAccountView[]`

---

#### `POST /admin/official-accounts/link`

绑定**腾讯云控制台已存在**的公众号（不创建 IM 账号，只登记本地 + 从 IM 同步名称/头像等）。

**请求**

```json
{
  "officialAccountId": "@TOA#_@TOA#d4CH",
  "slug": "d4ch"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| officialAccountId | 是 | 须以 `@TOA#_` 开头 |
| slug | 否 | 缺省从 ID 尾部推导（如 `d4CH` → `d4ch`） |
| name / introduction / faceUrl 等 | 否 | 缺省从 IM 查询结果填充 |

**错误**：`OFFICIAL_ACCOUNT_NOT_IN_IM`（IM 查无此号）、`OFFICIAL_ACCOUNT_ID_EXISTS`

---

#### `POST /admin/official-accounts`

创建公众号（同步 IM + 写本地表）。控制台已建号请用 **`/link`**，不要用本接口重复创建。

**请求**

```json
{
  "slug": "news",
  "name": "99chat 官方",
  "introduction": "系统通知",
  "faceUrl": "https://cdn.example.com/oa/news.png",
  "organization": "99chat",
  "ownerUserId": "abc12def34",
  "maxSubscriberNum": 100000,
  "sortOrder": 0
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| slug | 是 | 见 §3.3 |
| name | 是 | 最长 150 字节（UTF-8） |
| introduction / faceUrl / organization | 否 | 同步到 IM |
| ownerUserId | 否* | 平台用户 ID；缺省用 `default-owner-user-id` |
| maxSubscriberNum | 否 | 缺省 100000 |
| sortOrder | 否 | 缺省 0 |

\* 若未传且未配置 `default-owner-user-id` → **400** `OWNER_USER_ID_REQUIRED`  
\* `ownerUserId` 在 `users` 表不存在 → **400** `OWNER_USER_NOT_FOUND`

**响应**：`OfficialAccountView`

**错误**

| HTTP | code | 说明 |
|------|------|------|
| 400 | INVALID_SLUG | slug 格式非法 |
| 409 | OFFICIAL_ACCOUNT_SLUG_EXISTS | slug 已占用 |
| 403 | ADMIN_FORBIDDEN | IP 不在白名单 |
| 502 | IM_REST_ERROR | IM 创建失败（如未开通公众号 `131000`） |

---

#### `PATCH /admin/official-accounts/{officialAccountId}`

更新资料（本地 + IM `modify_official_account_base_info`）。字段均为可选，只更新传入项。

**请求**

```json
{
  "name": "99chat 公告",
  "introduction": "更新后的简介",
  "faceUrl": "https://cdn.example.com/oa/news2.png",
  "organization": "99chat",
  "maxSubscriberNum": 200000,
  "enabled": true,
  "sortOrder": 1
}
```

**响应**：`OfficialAccountView`

---

#### `DELETE /admin/official-accounts/{officialAccountId}`

销毁 IM 公众号并删除本地记录。

**响应**

```json
{ "officialAccountId": "@TOA#_99chat_news", "status": "deleted" }
```

---

#### `POST /admin/official-accounts/{officialAccountId}/broadcast`

向**全部订阅者**发送文本广播（IM `send_official_account_msg`）。

**请求**

```json
{ "text": "欢迎使用 99chat，今晚系统维护 2:00–4:00。" }
```

| 约束 | 说明 |
|------|------|
| `text` | 必填，非空，最长约 12KB（IM 限制） |

**响应**（IM 原始应答摘要）

```json
{
  "ActionStatus": "OK",
  "ErrorCode": 0,
  "MsgTime": 1716451400,
  "MsgKey": "89541_1_1572870301"
}
```

**腾讯云频控**（由 IM  enforcement）：

- 约 **1 条/秒**
- **每小时最多 2 条**广播（超出 `imErrorCode=10023` → HTTP 429）

---

## 6. Flutter / 客户端集成指南

### 6.1 推荐流程

```
1. 用户登录 99chat-server → 拿到 JWT、userId（平台 ID）
2. GET /im/user-sig → sdkAppId, userSig
3. IM SDK login(userId, userSig)
4. GET /official-accounts → 展示「可关注」列表（头像、名称、简介）
5. 用户点击关注：
   - 方案 A：POST /official-accounts/{id}/subscribe（推荐，与后端一致）
   - 方案 B：SDK subscribeOfficialAccount(id)
6. 消息：
   - 广播：AdvancedMsgListener / 消息监听器，消息来自公众号 ID
   - 单聊：会话类型 C2C，conversationID = "c2c_" + officialAccountId
7. 已关注列表：GET /official-accounts/subscribed/me 或 SDK getOfficialAccountsInfo
```

### 6.2 与后端订阅方式的选择

| 方式 | 优点 | 注意 |
|------|------|------|
| 服务端 `POST .../subscribe` | 不依赖客户端 Friendship API；便于统计 | 需 JWT |
| SDK `subscribeOfficialAccount` | 与腾讯云文档一致 | 须已 IM login |

两种方式在 IM 侧等效；**任选其一**，避免重复调用导致困惑即可。

### 6.3 UI 建议

- **发现页**：`GET /official-accounts`，展示 `name`、`faceUrl`、`introduction`、`subscriberNum`。
- **详情页**：`GET /official-accounts/{id}` + 关注按钮。
- **消息列表**：将会话 ID `c2c_{officialAccountId}` 单独分组或置顶（可用 IM 会话分组 API）。
- **历史推文**：广播消息在 IM 漫游/本地缓存中拉取；复杂图文推文可在控制台创建，后续可扩展 REST。

### 6.4 相关已有接口

| 接口 | 说明 |
|------|------|
| `GET /im/user-sig` | IM 登录凭证（需 JWT） |
| `POST /auth/register` 等 | 注册时已将 `userId` 导入 IM |

---

## 7. 运营与联调

### 7.1 绑定已有公众号（当前主号）

```bash
curl -sS -X POST 'http://127.0.0.1:8081/admin/official-accounts/link' \
  -H 'Content-Type: application/json' \
  -d '{"officialAccountId":"@TOA#_@TOA#d4CH","slug":"d4ch"}'
```

用户订阅（`#` 需 URL 编码为 `%40TOA%23_%40TOA%23d4CH`）：

```bash
OA='%40TOA%23_%40TOA%23d4CH'
curl -sS -X POST "http://127.0.0.1:8081/official-accounts/${OA}/subscribe" \
  -H "Authorization: Bearer <jwt>"
```

### 7.2 新建公众号（curl 示例）

```bash
# 假设已从白名单 IP 访问，且已有用户 abc12def34
curl -sS -X POST 'http://127.0.0.1:8081/admin/official-accounts' \
  -H 'Content-Type: application/json' \
  -d '{
    "slug": "news",
    "name": "99chat 官方",
    "introduction": "系统通知",
    "faceUrl": "https://cdn.example.com/oa/news.png",
    "ownerUserId": "abc12def34",
    "sortOrder": 0
  }'
```

### 7.3 用户订阅（curl 示例）

```bash
# officialAccountId 含 #，需编码
OA_ID='%40TOA%23_99chat_news'

curl -sS -X POST "http://127.0.0.1:8081/official-accounts/${OA_ID}/subscribe" \
  -H "Authorization: Bearer <jwt>"
```

### 7.4 发送广播（主号示例）

```bash
OA_ID='%40TOA%23_%40TOA%23d4CH'

curl -sS -X POST "http://127.0.0.1:8081/admin/official-accounts/${OA_ID}/broadcast" \
  -H 'Content-Type: application/json' \
  -d '{"text":"测试广播，请忽略。"}'
```

### 7.5 控制台备选

仅运营自有、不对外的公众号，也可在 [IM 控制台](https://console.cloud.tencent.com/im) → **功能配置 → 公众号管理** 创建并发推文；客户端订阅流程不变。控制台创建的号若要在 App 列表展示，需自行写入 `official_accounts` 或扩展「绑定已有 ID」接口（当前版本仅支持 API 创建）。

---

## 8. 错误码对照表

| HTTP | code | 含义 |
|------|------|------|
| 400 | INVALID_INPUT | 请求体验证失败（如广播 text 为空） |
| 400 | INVALID_SLUG | slug 格式不合法 |
| 400 | OWNER_USER_ID_REQUIRED | 未传 owner 且未配置默认 owner |
| 400 | OWNER_USER_NOT_FOUND | owner 用户不存在 |
| 400 | MESSAGE_TOO_LONG | 广播文本超长 |
| 403 | ADMIN_FORBIDDEN | 管理端 IP 不在白名单 |
| 404 | OFFICIAL_ACCOUNT_NOT_FOUND | 公众号不存在或未启用 |
| 409 | OFFICIAL_ACCOUNT_SLUG_EXISTS | slug 冲突 |
| 429 | IM_REST_ERROR | IM 广播频控等（`imErrorCode=10023`） |
| 502 | IM_REST_ERROR | 其它 IM REST 失败 |
| 502 | IM_NOT_CONFIGURED | 未配置 IM_SDK_APP_ID / IM_KEY |

常见腾讯云 `imErrorCode`：

| imErrorCode | 含义 |
|-------------|------|
| 131000 | 应用未开通公众号服务 |
| 10010 / 130004 | 公众号不存在或已销毁 |
| 10023 | 发消息频率超限 |
| 80002 | 消息内容过长 |

---

## 9. 文档索引

| 文档 | 说明 |
|------|------|
| [registration-and-login.md](./registration-and-login.md) | 注册登录、JWT、平台 ID |
| [user-privacy-and-search.md](./user-privacy-and-search.md) | 隐私与搜索 |
| [group-avatar.md](./group-avatar.md) | 群头像 |
| [community-group-invite.md](./community-group-invite.md) | 社群邀请 |

腾讯云官方：

- [类微信公众号搭建方案](https://cloud.tencent.com/document/product/269/119453)
- [公众号系统说明](https://cloud.tencent.com/document/product/269/102326)
- [创建公众号 REST](https://cloud.tencent.com/document/product/269/102302)
- [发送广播 REST](https://cloud.tencent.com/document/product/269/102298)
- [控制台公众号管理](https://www.tencentcloud.com/zh/document/product/1047/77714)

---

## 10. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-05-23 | 首版：用户端 5 接口 + 管理端 5 接口 + Flutter 集成说明 |
| v1.1 | 2026-05-23 | 主号 `@TOA#_@TOA#d4CH`、启动绑定、`POST /admin/official-accounts/link` |
