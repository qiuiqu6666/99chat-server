# 机器人玩家数据同步 — 接口对接文档

> 版本：v1.1  
> 更新：2026-07-20  
> 适用：**Windows 机器人**  
> 实现清单：[robot-sync-checklist.md](./robot-sync-checklist.md)  
> 数据落库：`jiqiren` 专用库（与主库 `chat99` 隔离）  
> App 侧请看：[robot-app-integration.md](./robot-app-integration.md)  
> Windows 侧请看：[robot-windows-integration.md](./robot-windows-integration.md)  
> App 查反水细则：[agent-rebate-client.md](./agent-rebate-client.md)

---

## 1. 总览

| 接口 | 方法 | 调用方 | 鉴权 | 说明 |
|------|------|--------|------|------|
| `/api/internal/robot-machines/register` | `POST` | 机器人 | 无（限流） | 首次启动申码 |
| `/api/internal/robot-machines/unregister` | `POST` | 机器人 | `X-Machine-Code` | 整码注销（级联清租户） |
| `/api/internal/robot-machines/bind-group` | `POST` | 机器人 | `X-Machine-Code` | 群聊「配对」 |
| `/api/internal/robot-machines/enable` | `POST` | 机器人 | `X-Machine-Code` | 群聊「开启」 |
| `/api/internal/robot-sync` | `POST` | 机器人 | `X-Machine-Code` | 快照 / 日归档 / 控制事件 |
| `/api/internal/robot-rebate-tasks/pull` | `POST` | 机器人 | `X-Machine-Code` | 领取反水任务 |
| `/api/internal/robot-rebate-tasks/result` | `POST` | 机器人 | `X-Machine-Code` | 任务回执 |

**已弃用**：`X-Robot-Secret` / 全局 `ROBOT_SYNC_SECRET`。机器码本身即 API Key。

### 架构

```text
Windows 机器人
    │  首次：POST /api/internal/robot-machines/register
    │  之后：Header X-Machine-Code
    │  POST /api/internal/robot-sync | rebate-tasks | bind/enable
    ▼
robot-service（8091，可经主入口/Nginx）
    ▼
MySQL 库 jiqiren
    ├─ robot_machine / robot_group_binding
    ├─ robot_sync_event
    ├─ robot_player_snapshot     （player_group_id = machine_code）
    ├─ robot_player_daily_summary
    └─ robot_runtime_state
```

### 与 App 端响应格式的区别

`/api/internal/**` **不走** App 统一包装 `{code, message, data}`，直接返回业务 JSON。

---

## 2. 环境与地址

| 项 | 值 |
|---|---|
| 生产 Base URL | `http://47.239.60.107:8081`（或 Nginx → 8091） |
| Content-Type | `application/json; charset=utf-8` |
| 鉴权 Header | `X-Machine-Code: <机器码>` |
| 机器码形态 | 新码 `XXXX-XXXX-XXXX`（12 位 Crockford）；存量可能为原 `player_group_id` |

落库分区键 **强制** 使用 Header 中的机器码；body 里的 `playerGroupId` / `robotId` 会被覆盖，不能用来窜租户。

---

## 2.1 机器码生命周期（Windows）

### `POST /api/internal/robot-machines/register`

无 Header。可选 body：`{"label":"主机名"}`。

```json
{"success":true,"machineCode":"ABCD-EFGH-JKMN"}
```

本地持久化后，后续所有内部接口带：

```http
X-Machine-Code: ABCD-EFGH-JKMN
```

同一 IP 约 60 秒内最多申码 20 次。

### `POST /api/internal/robot-machines/unregister`

Header：`X-Machine-Code`。整码注销，不可逆：级联删除该码下上下分流水 / 日报 / 快照 / sync 事件 / runtime / 导出任务 → 全部群绑定 → 机器码行。

```json
{
  "success": true,
  "machineCode": "ABCD-EFGH-JKMN",
  "deletedBindings": 2,
  "deletedSnapshots": 20,
  "deletedDailySummaries": 0,
  "deletedUpdownRecords": 0,
  "deletedRuntimeStates": 1,
  "deletedSyncEvents": 62,
  "deletedExportTasks": 0,
  "message": "unregistered"
}
```

重复注销或未知码 → `401` `invalid machine code`。Telegram 控制群本轮无注销指令。

### 群聊指令 → 接口（开群特权）

以后开群特权就是群里依次发：

```text
配对xxxx-xxxx-xxxx
开启@2EYHG6M5CJ
```

| 群消息 | Windows 动作 |
|--------|----------------|
| `配对XXXX-XXXX-XXXX` | `POST /api/internal/robot-machines/bind-group` + Header 本机码，body `{"groupId":"<当前IM群ID>"}` |
| `开启@2EYHG6M5CJ` | `POST /api/internal/robot-machines/enable`，body `{"groupId":"...","robotId":"@2EYHG6M5CJ"}` |

约束：**一码多群、一群一码**。群已绑其他码 → `409 GROUP_ALREADY_BOUND`。

建议正则：`^配对\s*([0-9A-Za-z-]{14,})\s*$`、`^开启\s*(@\S+)\s*$`。

---

## 3. 机器人写入接口

### `POST /api/internal/robot-sync`

#### 请求头

| Header | 必填 | 说明 |
|--------|------|------|
| `Content-Type` | 是 | `application/json; charset=utf-8` |
| `X-Machine-Code` | 是 | 机器码（API Key） |

#### 请求体（公共结构）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `protocolVersion` | string | 是 | 固定 `1.0` |
| `eventId` | string | 是 | 全局唯一，幂等主键 |
| `eventType` | string | 是 | 见 §3.1 |
| `playerGroupId` | string | 是 | 玩家群 ID |
| `statisticsGroupId` | string | 是 | 统计群 ID |
| `entityId` | string | 是 | 当前为玩家 `wxid` |
| `businessTimestamp` | long | 是 | 业务时间戳（秒） |
| `businessTimezone` | string | 是 | 当前固定 `+08:00` |
| `sourceUpdatedAt` | long | 是 | 机器人数据更新时间（秒） |
| `syncReason` | string | 是 | 业务原因，见 §5 |
| `data` | object | 是 | 业务数据，结构因 `eventType` 而异 |

#### 3.1 支持的事件类型 `eventType`

| eventType | 用途 | 玩家定位键 |
|-----------|------|-----------|
| `player.snapshot.upsert` | 保存/更新玩家**当前状态** | `playerGroupId + databaseGeneration + wxid` |
| `player.daily.summary` | 保存玩家**每日归档**（不可变历史） | `playerGroupId + wxid + businessDate` |
| `player.updown.recorded` | 保存单笔上下分流水（**不改余额**） | `eventId` 幂等；同机器码下 `recordId` 唯一 |
| `robot.database.initialized` | 数据库重置初始化，进入 `SYNCING` | `robotId`（缺省时用 `playerGroupId`） |
| `robot.full_snapshot.completed` | 全量重建完成，恢复 `READY` | `robotId`（缺省时用 `playerGroupId`） |

其他 `eventType` 返回 `400`。

控制事件必填：`databaseGeneration`，以及 `robotId` 或 `playerGroupId` 之一。  
`entityId` 可缺省（缺省时回填为 robotId）；**不再要求** `entityId == robotId`（鉴权只认密钥）。

- `robot.database.initialized`：`syncReason=database_reset`；写入新代次、状态 `SYNCING`、旧代次快照 `active=0`、旧代次未完成反水申请失效
- `robot.full_snapshot.completed`：`syncReason=full_rebuild_completed`；要求当前为 `SYNCING` 且代次一致；`data.playerCount` 允许为 `0`；状态改为 `READY`

`SYNCING` 期间禁止：玩家/代理反水申请、反水任务拉取。

#### 3.2 `player.snapshot.upsert` 的 `data` 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `wxid` | string | 玩家唯一 ID |
| `playerNo` | string | 机器人玩家编号 |
| `nickname` | string | 原始昵称 |
| `displayName` | string | 显示名称（优先备注） |
| `remark` | string | 后台备注 |
| `playerType` | string | `0` 真人，`1` 手动托 |
| `balance` | decimal | 当前余额 |
| `directParentWxid` | string | 直属上级 wxid |
| `directParentNo` | string | 直属上级编号 |
| `parentPath` | string | 完整上级路径，如 `wxid_root###wxid_parent###` |
| `levelNo` | integer | 层级，从 1 开始 |
| `active` | boolean | 当前固定 `true` |
| `totalFlow` | decimal | 累计流水 |
| `usedFlow` | decimal | 已使用流水 |
| `remainingFlow` | decimal | 剩余流水 |
| `agentPendingFlow` | decimal | 代理待结算流水（机器人计算，可选） |
| `agentPendingRebate` | decimal | 代理待结算反水（机器人计算，可选） |
| `totalUp` | decimal | 总上分 |
| `totalDown` | decimal | 总下分 |
| `totalProfitLoss` | decimal | 总盈亏 |
| `rebateRate` | decimal | 反水比例原始值，**÷10000** 才是百分比 |
| `rebateRateUnit` | string | 固定 `per_10000` |
| `totalRebate` | decimal | 累计已反水 |

**反水比例示例**：`rebateRate = 35` → `35 / 10000 = 0.35%`（不是 35%）。

#### 3.3 `player.daily.summary` 的 `data` 字段

与 §3.2 相同，**额外多一个字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `pendingRebate` | decimal | 待反水 = `remainingFlow × rebateRate ÷ 10000` |

`businessDate` 由后端按 `businessTimestamp + businessTimezone` 计算，不用服务器默认时区。

#### 3.4 `player.updown.recorded` 的 `data` 字段

只写入流水表 `robot_player_updown_record`，**不会**增加或扣除 `robot_player_snapshot` 余额，也**不会**改写快照的 `totalUp` / `totalDown`。余额与累计上下分仍由 `player.snapshot.upsert` 负责。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `recordId` | string | 是 | 单笔流水 ID；同机器码下唯一。两种常见格式：① 审核同意：本地上下分库 ID；② 操作按钮：`OP_UP_...` / `OP_DOWN_...` |
| `wxid` | string | 是 | 玩家唯一 ID |
| `playerNo` | string | 否 | 机器人玩家编号 |
| `nickname` | string | 否 | 昵称 |
| `direction` | string | 是 | 仅 `UP`（上分）或 `DOWN`（下分），大小写敏感 |
| `amount` | decimal | 是 | **始终为正数**；`<= 0` 返回 `400` |
| `balanceDelta` | decimal | 是 | 余额变化量：上分为正、下分为负；与 `direction` 符号不符返回 `400`。服务端**不会**用此字段改余额 |
| `balanceAfter` | decimal | 是 | 操作后余额（只落流水，不回写快照） |
| `totalUpAfter` | decimal | 是 | 操作后累计上分 |
| `totalDownAfter` | decimal | 是 | 操作后累计下分 |
| `approvedAt` | long | 是 | 审批时间。Windows 可发 epoch **秒或毫秒**；服务端归一后库内统一存 **秒**（`>= 10^12` 视为毫秒并 `÷1000`） |
| `status` | string | 否 | 如 `APPROVED`；原样落库，不做枚举校验；缺省存 `NULL` |
| `approvalSource` | string | 否 | 如 `MANUAL`；原样落库，不做枚举校验；缺省存 `NULL` |

幂等：相同 `eventId` 重发返回 `duplicate:true`，不重复入库。  
同机器码下相同 `recordId`、不同 `eventId` → `409` + `RECORD_ALREADY_EXISTS`。  
推荐 `syncReason=updown_approve`（仍为审计字段，服务端不枚举校验）。

#### 请求示例：玩家快照

```http
POST /api/internal/robot-sync HTTP/1.1
Host: 47.239.60.107:8081
Content-Type: application/json; charset=utf-8
X-Machine-Code: <机器码>

{
  "protocolVersion": "1.0",
  "eventId": "group_001_player_snapshot_wxid_001_1720000000_1234",
  "eventType": "player.snapshot.upsert",
  "playerGroupId": "group_001",
  "statisticsGroupId": "stats_001",
  "entityId": "wxid_001",
  "businessTimestamp": 1724112000,
  "businessTimezone": "+08:00",
  "sourceUpdatedAt": 1720000000,
  "syncReason": "manual_balance_up",
  "data": {
    "wxid": "wxid_001",
    "playerNo": "10001",
    "nickname": "原始昵称",
    "displayName": "显示名称",
    "remark": "后台备注",
    "playerType": "0",
    "balance": 1250.5,
    "directParentWxid": "wxid_parent",
    "directParentNo": "10000",
    "parentPath": "wxid_root###wxid_parent###",
    "levelNo": 3,
    "active": true,
    "totalFlow": 5000,
    "usedFlow": 1000,
    "remainingFlow": 4000,
    "agentPendingFlow": 34500,
    "agentPendingRebate": 345,
    "totalUp": 3000,
    "totalDown": 500,
    "totalProfitLoss": -120,
    "rebateRate": 35,
    "rebateRateUnit": "per_10000",
    "totalRebate": 18.5
  }
}
```

#### 请求示例：每日归档

```json
{
  "protocolVersion": "1.0",
  "eventId": "group_001_player_daily_summary_wxid_001_1720000000_5678",
  "eventType": "player.daily.summary",
  "playerGroupId": "group_001",
  "statisticsGroupId": "stats_001",
  "entityId": "wxid_001",
  "businessTimestamp": 1724112000,
  "businessTimezone": "+08:00",
  "sourceUpdatedAt": 1720000000,
  "syncReason": "daily_archive",
  "data": {
    "wxid": "wxid_001",
    "playerNo": "10001",
    "nickname": "原始昵称",
    "displayName": "显示名称",
    "remark": "后台备注",
    "playerType": "0",
    "balance": 1250.5,
    "directParentWxid": "wxid_parent",
    "directParentNo": "10000",
    "parentPath": "wxid_root###wxid_parent###",
    "levelNo": 3,
    "active": true,
    "totalFlow": 5000,
    "usedFlow": 1000,
    "remainingFlow": 4000,
    "agentPendingFlow": 34500,
    "agentPendingRebate": 345,
    "pendingRebate": 14,
    "totalUp": 3000,
    "totalDown": 500,
    "totalProfitLoss": -120,
    "rebateRate": 35,
    "rebateRateUnit": "per_10000",
    "totalRebate": 18.5
  }
}
```

#### 请求示例：单笔上下分流水

```json
{
  "protocolVersion": "1.0",
  "eventId": "group_001_player_updown_rec_001_1720000000",
  "eventType": "player.updown.recorded",
  "playerGroupId": "group_001",
  "statisticsGroupId": "stats_001",
  "entityId": "wxid_001",
  "businessTimestamp": 1724112000,
  "businessTimezone": "+08:00",
  "sourceUpdatedAt": 1720000000,
  "syncReason": "updown_approve",
  "data": {
    "recordId": "rec_001",
    "wxid": "wxid_001",
    "playerNo": "10001",
    "nickname": "原始昵称",
    "direction": "UP",
    "amount": 100,
    "balanceDelta": 100,
    "balanceAfter": 1250.5,
    "totalUpAfter": 3100,
    "totalDownAfter": 500,
    "approvedAt": 1720000000000,
    "status": "APPROVED",
    "approvalSource": "MANUAL"
  }
}
```

---

## 4. 响应规范

### 4.1 成功（首次处理）

```http
HTTP/1.1 200 OK
```

```json
{
  "success": true,
  "updatedCount": 1,
  "duplicate": false,
  "eventId": "group_001_player_snapshot_wxid_001_1720000000_1234",
  "message": "accepted"
}
```

### 4.2 重复事件（幂等）

仍返回 `200`，**不是** `409`：

```json
{
  "success": true,
  "updatedCount": 0,
  "duplicate": true,
  "eventId": "group_001_player_snapshot_wxid_001_1720000000_1234",
  "message": "already processed"
}
```

### 4.2.1 未匹配到玩家 / 更新 0 条

仍返回 `200`，`success=false`：

```json
{
  "success": false,
  "updatedCount": 0,
  "duplicate": false,
  "eventId": "group_001_player_snapshot_wxid_001_1720000000_1234",
  "message": "玩家不存在或更新0条"
}
```

`agentPendingFlow` / `agentPendingRebate` 允许为 `0`，并必须写入库（代理结算后清零依赖此语义）。
### 4.3 鉴权失败

```http
HTTP/1.1 401 Unauthorized
```

```json
{
  "success": false,
  "message": "invalid machine code"
}
```

### 4.4 参数校验失败

```http
HTTP/1.1 400 Bad Request
```

```json
{
  "code": "INVALID_INPUT",
  "message": "eventType:must not be blank,data:must not be null,..."
}
```

常见触发：JSON 格式错误、必填字段缺失、不支持的 `eventType`、`data.wxid` 为空。

### 4.5 业务逻辑错误

```http
HTTP/1.1 400 Bad Request
```

```json
{
  "code": "unsupported eventType",
  "message": "..."
}
```

### 4.6 服务器异常

```http
HTTP/1.1 500 Internal Server Error
```

机器人收到 `500` 会将事件保留在 `pending_sync.jsonl` 并自动重试。

---

## 5. `syncReason` 枚举（审计用）

后端按原样保存，**不需要**为每个原因单独建接口。

| syncReason | 说明 |
|------------|------|
| `player_created` | 新增玩家 |
| `flow_update` | 正常开奖结算 |
| `flow_rollback` | 流水回滚 |
| `daily_archive` | 每日归档 |
| `daily_flow_clear` | 每日流水清空后的状态 |
| `manual_balance_up` | 后台手动上分 |
| `manual_balance_down` | 后台手动下分 |
| `updown_approve` | 同意上下分 |
| `updown_reject` | 拒绝或回退上下分 |
| `relation_change` | 修改上下级关系 |
| `profile_remark_change` | 修改玩家备注 |
| `player_type_to_bot` | 真人切换为手动托 |
| `player_type_to_real` | 手动托恢复真人 |
| `rebate_rate_change` | 修改反水比例 |
| `rebate_execute` | 单人自身反水 |
| `rebate_one_click` | 一键反水 |
| `rebate_agent` | 代理反水 |
| `rebate_all` | 全部反水 |
| `unknown` | 未传原因时的兜底值 |

---

## 6. 已落库数据（供前端页面对齐字段）

以下为机器人写入后，后端持久化的数据结构。前端做展示时可按此对齐列名与含义。

### 6.1 玩家当前状态 `robot_player_snapshot`

| 库字段 | 类型 | 前端展示建议 |
|--------|------|-------------|
| `player_group_id` | string | 玩家群 |
| `statistics_group_id` | string | 统计群 |
| `wxid` | string | 玩家 ID |
| `player_no` | string | 玩家编号 |
| `nickname` | string | 昵称 |
| `display_name` | string | 显示名 |
| `remark` | string | 备注 |
| `player_type` | string | 身份：`0` 真人 / `1` 手动托 |
| `balance` | decimal | 余额 |
| `direct_parent_wxid` | string | 直属上级 |
| `direct_parent_no` | string | 上级编号 |
| `parent_path` | text | 上级链 |
| `level_no` | int | 层级 |
| `active` | boolean | 是否活跃（当前恒为 `true`） |
| `total_flow` | decimal | 累计流水 |
| `used_flow` | decimal | 已用流水 |
| `remaining_flow` | decimal | 剩余流水 |
| `total_up` | decimal | 总上分 |
| `total_down` | decimal | 总下分 |
| `total_profit_loss` | decimal | 总盈亏 |
| `rebate_rate` | decimal | 反水比例（原始值） |
| `rebate_rate_unit` | string | 固定 `per_10000` |
| `total_rebate` | decimal | 累计反水 |
| `source_updated_at` | long | 数据版本时间戳 |
| `last_sync_reason` | string | 最近一次同步原因 |
| `updated_at` | datetime | 后端更新时间 |

**唯一键**：`(player_group_id, wxid)`

### 6.2 每日归档 `robot_player_daily_summary`

在 §6.1 基础上增加：

| 库字段 | 类型 | 说明 |
|--------|------|------|
| `business_date` | date | 业务日标签（中国时间 UTC+8；窗口 `[当日 07:00, 次日 07:00)`，标签为窗口起始日历日） |
| `pending_rebate` | decimal | 当日待反水 |
| `event_id` | string | 归档事件 ID（不可变） |
| `sync_reason` | string | 一般为 `daily_archive` |

**唯一键**：`event_id`（每条归档只写一次）

### 6.3 事件日志 `robot_sync_event`

| 库字段 | 说明 |
|--------|------|
| `event_id` | 幂等主键 |
| `event_type` | 事件类型 |
| `sync_reason` | 业务原因 |
| `request_body` | 原始 JSON 全文 |
| `process_status` | `received` → `processed` |
| `created_at` / `processed_at` | 接收 / 处理时间 |

---

## 7. 业务规则（前端统计必读）

### 7.1 真人 vs 手动托

| 类型 | `playerType` | 是否入库 | 默认是否计入真人统计 |
|------|-------------|---------|---------------------|
| 真人 | `"0"` 或非 `"1"` | 是 | **是** |
| 手动托 | `"1"` | 是 | **否** |
| TXT 测试账号 | — | 机器人不发 | — |

前端做业绩、余额汇总、日报时，默认过滤条件：

```text
playerType != "1"
```

手动托数据仍需展示身份变化，但不能与真人业绩混算。

### 7.2 幂等与顺序

| 规则 | 行为 |
|------|------|
| 相同 `eventId` 重复提交 | 返回 `200` + `duplicate: true`，**不重复改余额** |
| 批次整批重试 | 逐条 eventId 幂等；updown `recordId` 冲突也视为成功；**整批仍 2xx** |
| 旧快照晚到（`sourceUpdatedAt` 更小） | 返回 `200`，记事件，**不覆盖**；相等时用 `eventId` 字典序更大者覆盖 |
| 每日归档 | 只追加；`event_id` 重复则幂等成功 |
| `batch:true` | 同一事务处理 ≤200 条；控制事件禁止放进批次 |

### 7.3 当前不支持

- 删除玩家 / `player.deleted` 事件
- 按 `syncReason` 拆分的独立接口
- 批次内部分成功（禁止：客户端收到 2xx 会删 sending）

> `data.active=false`：**已支持落库**（停用快照，历史流水保留）。

---

## 8. 前端对接说明

### 8.1 当前阶段

| 能力 | 状态 | 说明 |
|------|------|------|
| 机器人写入 `/api/internal/robot-sync` | ✅ 已上线 | 仅机器人调用 |
| 运营后台查询玩家列表 | ⬜ 未实现 | 需后续 `GET` 接口 |
| 运营后台查询每日报表 | ⬜ 未实现 | 需后续 `GET` 接口 |
| 运营后台查询事件日志 | ⬜ 未实现 | 需后续 `GET` 接口 |

### 8.2 前端不应做的事

- **不要**在前端代码、静态资源、公开仓库中硬编码机器码 / 旧 `X-Robot-Secret`
- **不要**从浏览器直接调用 `/api/internal/robot-sync`（这是机器人专用写入接口）
- **不要**把 `rebateRate` 直接当百分比展示（需 ÷10000）

### 8.3 建议后续只读接口（待开发）

供前端参考，**尚未实现**：

| 建议接口 | 用途 |
|---------|------|
| `GET /api/v1/robot/players` | 玩家当前列表（分页、按群筛选） |
| `GET /api/v1/robot/players/{wxid}` | 玩家详情 |
| `GET /api/v1/robot/daily-summaries` | 每日归档列表 |
| `GET /api/v1/robot/sync-events` | 事件审计日志 |

上述接口建议使用运营后台鉴权（Admin JWT），与机器人密钥体系分离。

---

## 9. 联调检查清单

### 机器人侧

- [ ] 能访问 `http://47.239.60.107:8081/api/internal/robot-sync`
- [ ] `X-Machine-Code` 为已注册且 ACTIVE 的机器码；同步落库分区键等于该码
- [ ] 上分后收到 `syncReason = manual_balance_up`
- [ ] 响应为扁平 JSON：`success` / `duplicate` / `eventId` / `message`
- [ ] 断网重试后 `duplicate: true` 或正常 `accepted`

### 前端侧（数据展示准备）

- [ ] 页面字段与 §6 落库结构对齐
- [ ] 真人统计默认排除 `playerType = "1"`
- [ ] 反水比例展示使用 `rebateRate / 10000`
- [ ] 等待只读查询接口上线后再接 HTTP

---

## 10. 代码位置

| 模块 | 路径 |
|------|------|
| Controller | `src/main/java/com/chat99/server/robot/RobotSyncController.java` |
| Service | `src/main/java/com/chat99/server/robot/RobotSyncService.java` |
| 建表 SQL | `scripts/migrate-robot-sync.sql`；上下分流水：`robot-service/sql/migrate-robot-player-updown.sql`；上下分 status 列：`robot-service/sql/migrate-robot-player-updown-status.sql` |
| 配置 | `application.yml` → `robot.sync` |
| 单元测试 | `src/test/java/com/chat99/server/robot/` |
