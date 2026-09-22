# IM 消息归档（Kafka）

> 版本：v1.0  
> 默认关闭：`MSG_ARCHIVE_ENABLED=false`

## 架构

```
腾讯 IM AfterSendMsg → POST /webhook/im/message
    ├─ 同步：离线 Push / VoIP / 通话落库（不等待 Kafka）
    └─ 异步：Kafka (chat99.im.after-send) → Consumer chat99-im-archive-writer → MySQL chat_message_YYYYMM
```

`BeforeSendMsg` 仍为同步好友校验，不经 Kafka。

**推送路径**：IM webhook 收到消息后**立即**执行 Push；Kafka 仅负责消息归档。默认关闭 `chat99-im-push-dispatcher` consumer（`MSG_ARCHIVE_PUSH_ENABLED=false`）。

## 启用

```bash
# 1. Topic（同机一键）
./scripts/kafka-topics-init.sh

# 2. 数据库表
mysql -u chat99 -p chat99 < scripts/migrate-chat-message-init.sql
mysql -u chat99 -p chat99 < scripts/migrate-chat-history-clear.sql
# 已有分表需补群 seq 索引（可选，提升 fromSeq/toSeq 查询）
mysql -u chat99 -p chat99 < scripts/migrate-chat-message-group-seq-index.sql

# 3. .env
MSG_ARCHIVE_ENABLED=true
KAFKA_BOOTSTRAP=127.0.0.1:9092

# 4. 重启
./scripts/start-jar-with-env.sh
```

可选读写分离：

```bash
export MSG_ARCHIVE_READ_JDBC_URL=jdbc:mysql://replica:3306/chat99?...
export MSG_ARCHIVE_WRITE_JDBC_URL=jdbc:mysql://primary:3306/chat99?...
```

## Kafka Topic

| Topic | Partitions | 说明 |
|-------|------------|------|
| `chat99.im.after-send` | 32 | 主消息流 |
| `chat99.im.archive.dlq` | 8 | 写库失败死信 |

Producer：`acks=all`，Webhook 在 Kafka 确认后才 return 200。

## 落库格式

按月分表 `chat_message_YYYYMM`，核心字段：

- `msg_key`：C2C 用腾讯 `MsgKey`；群聊 `{groupId}:{MsgSeq}`
- `msg_body_json`：腾讯 `MsgBody[]` 原样 JSON
- `preview_text` / `elem_type`：列表摘要
- `status`：`1=正常`，`0=已撤回`

详见 PLAN / `scripts/migrate-chat-message-init.sql`。

## 撤回同步

IM 控制台需额外勾选：

| 回调 | 命令字 |
|------|--------|
| 单聊消息撤回之后 | `C2C.CallbackAfterMsgWithDraw` |
| 撤回群消息之后 | `Group.CallbackAfterRecallMsg` |

流程：

```
IM 撤回回调 → POST /webhook/im/message
    → ImMessageRecallService.syncRecall
    → UPDATE chat_message_YYYYMM SET status=0, preview_text='[消息已撤回]'
```

- 按 `EventTime` 定位当月及前 2 个月分表（共 3 张）按 `msg_key` 更新
- 同步写库，不经 Kafka
- 历史 API 仍返回该条记录，`status=0` 时客户端展示「已撤回」

## 前端历史 API

### 游标翻页（默认，按时间倒序）

```http
GET /me/messages/c2c?peerUserId=xxx&cursor=1718450000000&limit=30
Authorization: Bearer <token>

GET /me/messages/group?groupId=xxx&cursor=1718450000000&limit=30
Authorization: Bearer <token>
```

- `cursor`：上一页最后一条的 `msgTimeMs`（首次不传）
- 返回按 `msgTimeMs` **降序**；`hasMore` 时用响应里的 `nextCursor` 继续翻页

### 区间拉取

**单聊按时间闭区间**（升序）：

```http
GET /me/messages/c2c?peerUserId=xxx&fromTimeMs=1718450000000&toTimeMs=1718453600000&limit=40
Authorization: Bearer <token>
```

- `fromTimeMs` / `toTimeMs`：必须成对传入，且 `fromTimeMs <= toTimeMs`
- 条件：`fromTimeMs <= msgTimeMs <= toTimeMs`
- 返回按 `msgTimeMs` **升序**；`hasMore` 时用 `fromTimeMs = nextCursor + 1` 继续（`toTimeMs` 不变）

**群聊按 MsgSeq 闭区间**（升序）：

```http
GET /me/messages/group?groupId=xxx&fromSeq=12&toSeq=13&limit=40
Authorization: Bearer <token>
```

- `fromSeq` / `toSeq`：必须成对传入，且 `fromSeq <= toSeq`（对应落库 `msg_seq` / 腾讯 `MsgSeq`）
- 条件：`fromSeq <= msgSeq <= toSeq`
- 扫描近 12 个月分表；返回按 `msgSeq` **升序**
- `hasMore` 时用 `fromSeq = nextCursor + 1` 继续（`toSeq` 不变）

### 共用说明

- `limit`：默认 30，最大 100
- 传了区间参数时优先走区间模式（忽略 `cursor`）
- 实时消息仍走 IM SDK；历史走本 API
- 限流：单用户 10 req/s
- `status=0`：消息已撤回，客户端展示「已撤回」
- 每条 `HistoryItem` 字段：
  - `msgKey`：归档唯一键（单聊多为腾讯 `MsgKey`；群聊多为 `groupId:msgSeq`）
  - `msgId`：腾讯真正的 `MsgId`（可空；漫游窗口外或腾讯未返回时永久为空）
  - 其余：`fromAccount` / `peerAccount` / `groupId` / `msgSeq` / `msgTimeMs` / `elemType` / `previewText` / `msgBody` / `status`

### 后台 MsgId 回填（C2C + 群）

定时 Job（`chat99.message-archive.msg-id-backfill.enabled=true`，env：`MSG_ARCHIVE_MSG_ID_BACKFILL_ENABLED`）：

- **C2C**：`admin_getroammsg` 双向合并，按 `LastMsgKey` 翻页（上限 `max-pages-per-entity`，默认 20）
- **群**：`group_msg_get_simple` 按 `MsgSeq` 往更早翻页；归档键 = `{groupId}:{msgSeq}`
- 仅写入腾讯真正的 `MsgId`（禁止用 `MsgKey` 冒充）；匹配不到或漫游无数据 → **保持 `msg_id` 为空**
- 每个会话/群扫完后 Redis skip（默认 30 天），避免对漫游外缺口反复打腾讯

### 解析 MsgId（回填未完成时的止血）

```http
POST /me/messages/resolve-msg-ids
Authorization: Bearer <token>
Content-Type: application/json

{
  "chatType": "c2c",
  "peerId": "peerUserId",
  "msgKeys": ["3358721060_1876410779_1784319889"]
}
```

响应 `data`：

```json
{
  "items": [
    { "msgKey": "3358721060_1876410779_1784319889", "msgId": "144115268026882536-1784319889-1876410779" }
  ]
}
```

- 仅支持 `chatType=c2c`（群请依赖后台 Job）；需为会话参与者
- 先查归档 `msg_id`，仍空则调腾讯漫游并回写
- `msgKeys` 上限 100；限流同历史接口

### 清空会话历史（仅当前用户）

用户在本端「删除聊天记录」后，调用以下接口；**不删**归档表里的消息，只记录该用户的水位线，后续 `GET` 不再返回 `msgTimeMs <= clearedBeforeMs` 的记录。对方不受影响。

```http
DELETE /me/messages/c2c?peerUserId=xxx
Authorization: Bearer <token>

DELETE /me/messages/group?groupId=xxx
Authorization: Bearer <token>
```

响应：

```json
{ "clearedBeforeMs": 1718450000123 }
```

客户端应在清空成功后调用 `DELETE`，再拉历史时从空列表开始；清空之后新收到的消息仍会正常返回。

## 外部程序撤回 IM 消息

供外部系统（风控、运营脚本等）主动撤回腾讯 IM 消息；**不是**用户 JWT 的 `/me/*` 接口，也**不是** Admin 面板接口。

### 配置

```bash
# .env — 必填，未配置时接口返回 503
INTEGRATION_API_TOKEN=your-long-random-secret
```

### 鉴权

任选其一：

1. **固定 Token**（推荐脚本/内网调用）

```http
X-Integration-Token: your-long-random-secret
```

2. **签名**（与 IM Webhook 相同算法，防重放 60s）

```http
X-Request-Time: 1718450000
X-Sign: sha256hex(apiToken + requestTime)
```

### 撤回单聊

```http
POST /integration/v1/im/messages/recall
Content-Type: application/json
X-Integration-Token: your-long-random-secret

{
  "chatType": "c2c",
  "fromAccount": "user_a",
  "toAccount": "user_b",
  "msgKey": "48374_2837546_1557481126",
  "syncArchive": true
}
```

- `msgKey`：腾讯 IM 的 `MsgKey`（发送回包、漫游拉取或归档表 `msg_key`）
- `syncArchive`：默认 `true`；为 `true` 且 `MSG_ARCHIVE_ENABLED=true` 时，立即更新 `chat_message_*` 的 `status=0`（不等 IM 撤回回调）

### 撤回群消息

```http
POST /integration/v1/im/messages/recall
Content-Type: application/json
X-Integration-Token: your-long-random-secret

{
  "chatType": "group",
  "groupId": "@TGS#2J4SZEAEL",
  "msgSeqList": [100, 101],
  "reason": "违规内容",
  "syncArchive": true
}
```

- 单次最多 10 条 `msgSeq`
- 归档侧 `msg_key` 格式：`{groupId}:{msgSeq}`

### 响应示例

```json
{
  "chatType": "group",
  "imSuccess": true,
  "imErrorCode": 0,
  "recalledMsgKeys": ["@TGS#2J4SZEAEL:100"],
  "groupResults": [
    { "msgSeq": 100, "retCode": 0 },
    { "msgSeq": 101, "retCode": 10030 }
  ],
  "archiveUpdated": 1
}
```

### 错误码

| HTTP | 说明 |
|------|------|
| 400 | 参数缺失、`chatType` 非法、群 `msgSeq` 超过 10 条 |
| 403 | Token/签名无效 |
| 503 | 未配置 `INTEGRATION_API_TOKEN` |
| 502 | 腾讯 IM REST 失败（body 含 `imErrorCode`） |

> **说明**：`DELETE /me/messages/*` 仅清空当前用户在自建历史 API 中的可见记录，不会撤回 IM 消息；本接口才会真正调用腾讯 IM 撤回。

## 灰度步骤

1. 部署代码，`MSG_ARCHIVE_ENABLED=false` 验证旧 Push 不变
2. 建 Kafka Topic + 跑 DDL
3. `MSG_ARCHIVE_ENABLED=true`：归档 + Push 均走 Kafka
4. 前端接入 `/me/messages/*`
5. Webhook / Consumer 分节点部署（可选）

## 关联

- [im-chat-push-callback.md](./im-chat-push-callback.md)
- [push-client.md](./push-client.md)


## 超级大群历史上滑（groupSeq v1）

超级大群历史读取使用 `GET /groups/{groupId}/messages/history?direction=older&limit=50`。本地归档字段 `msg_seq` 作为对外 `groupSeq`；该接口使用带 HMAC 的 opaque cursor 固定 `snapshotMaxSeq`，按 `groupSeq < anchorSeq` 做 keyset 分页，数据库倒序读取后按 `groupSeq` 升序返回。

当前按月归档表必须具备 `msg_seq` 和 `(chat_type, group_id, msg_seq)` 索引。请先执行 `scripts/audit-group-message-history.sql` 核对所有月表；脚本为只读审计，不会自动删除重复数据或伪造缺失序号。

数据库/历史存储不可用时接口返回 `HISTORY_STORAGE_UNAVAILABLE`，无法证明范围完整时返回 `HISTORY_RANGE_INCOMPLETE`，不能把故障返回为空页。客户端应原样保存和回传 `olderCursor`，终页 `hasMoreOlder=false` 且 `olderCursor` 为空字符串。
