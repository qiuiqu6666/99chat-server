# Windows 业务机器人对接文档

> 版本：v1.0  
> 更新：2026-07-20  
> 适用：**Windows 业务机器人**（代理反水同步 / 结算）  
> 服务：`robot-service`（经 Nginx / 主入口转发）  
> App 侧请看：[robot-app-integration.md](./robot-app-integration.md)  
> 同步字段细节可参考：[robot-sync-api.md](./robot-sync-api.md)

---

## 1. 一句话模型

```text
本机机器码 = API Key + 数据租户 ID
每次写接口带 X-Machine-Code
开群特权 = 群里发两条指令（配对 → 开启）
App 用群 ID 查数，不会窜到别的机器人
```

**已弃用**：`X-Robot-Secret` / 全局 `ROBOT_SYNC_SECRET`。不要再发旧密钥。

---

## 2. 环境

| 项 | 值 |
|---|---|
| Base URL | `http://47.239.60.107:8081`（生产入口；内部落到 8091） |
| Content-Type | `application/json; charset=utf-8` |
| 鉴权 Header | `X-Machine-Code: <机器码>`（register 除外） |
| 响应格式 | **裸 JSON**（不是 App 的 `{code,message,data}`） |
| 机器码形态 | 新码 `XXXX-XXXX-XXXX`（12 位 Crockford）；存量可能是旧 `player_group_id`（如 `@2HGQG6M5CD`） |

落库分区键 **强制等于 Header 机器码**。body 里的 `playerGroupId` / `robotId` 会被服务端覆盖，不能用来窜租户。

---

## 3. 启动与申码

### 流程

```text
启动
 → 本地有 machineCode？
     是 → 后续请求带 X-Machine-Code
     否 → POST /api/internal/robot-machines/register
           → 持久化 machineCode（本地加密存储）
```

### `POST /api/internal/robot-machines/register`

- **无** `X-Machine-Code`
- 可选 body：`{"label":"主机名或备注"}`
- 同一 IP 约 60 秒最多申码 20 次

**成功：**

```json
{"success":true,"machineCode":"ABCD-EFGH-JKMN"}
```

之后所有内部接口：

```http
X-Machine-Code: ABCD-EFGH-JKMN
```

本地应把机器码展示给运营（便于在群里发「配对」指令），例如：`配对ABCD-EFGH-JKMN`。

### `POST /api/internal/robot-machines/unregister`

整码注销（不可逆）。Header 必填 `X-Machine-Code`。

按顺序删除该码租户数据：`robot_player_updown_record` / `daily_summary` / `snapshot` / `sync_event` / `runtime_state` / `agent_report_export_task` → 全部 `robot_group_binding` → `robot_machine`。

**成功：**

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

码不存在 / 已注销后再调 → `401` + `invalid machine code`。  
注销后本地应清除持久化码并重新 `register`。本轮 **无** Telegram 注销指令。

---

## 4. 开群特权（群聊指令，唯一正式入口）

以后业务群开通机器人特权，**只认群聊两条指令**（顺序固定）：

```text
配对xxxx-xxxx-xxxx
开启@2EYHG6M5CJ
```

示例：

```text
配对ABCD-EFGH-JKMN
开启@2EYHG6M5CJ
```

| 步骤 | 群消息 | 含义 | Windows 动作 |
|------|--------|------|----------------|
| 1 | `配对xxxx-xxxx-xxxx` | 把本机机器码绑到**当前群** | `POST .../bind-group` |
| 2 | `开启@机器人ID` | 指定本群启用的机器人账号并开通特权 | `POST .../enable` |

说明：

- 必须先配对、再开启；未配对就开启 → `404 GROUP_NOT_BOUND`
- 配对成功后 `enabled=false`；开启成功后 `enabled=true`，App 才能查反水
- **一码多群**（同一机器码可绑多个 IM 群）；**一群一码**（一个群只能绑一个机器码）

Windows 在业务群监听文本（建议仅群主/管理员指令生效），解析后调服务端。

建议正则：

- `^配对\s*([0-9A-Za-z-]{14,})\s*$`
- `^开启\s*(@\S+)\s*$`

| 错误码 / 场景 | 含义 |
|---------------|------|
| `409 GROUP_ALREADY_BOUND` | 该群已绑其他机器码 |
| `404 GROUP_NOT_BOUND` | 开启时群尚未配对 |
| `403 MACHINE_GROUP_MISMATCH` | Header 机器码与群绑定不一致 |

### `POST /api/internal/robot-machines/bind-group`

```http
X-Machine-Code: ABCD-EFGH-JKMN
Content-Type: application/json

{"groupId":"@TGS#xxxx"}
```

```json
{"success":true,"groupId":"@TGS#xxxx","machineCode":"ABCD-EFGH-JKMN","enabled":false,"robotId":null}
```

说明：`groupId` 用**当前聊天 IM 群 ID**。Header 用**本机已持久化的机器码**（配对消息里的码应与本机码一致；若用户输入的是本机展示码，先 canonicalize 再比对本机）。

推荐实现：

1. 解析到配对码后，与本地 `machineCode` 规范化比较  
2. 不一致 → 群内提示「请使用本机机器码配对」  
3. 一致 → 调 `bind-group`，`groupId` = 当前群  

### `POST /api/internal/robot-machines/enable`

```http
X-Machine-Code: ABCD-EFGH-JKMN
Content-Type: application/json

{"groupId":"@TGS#xxxx","robotId":"@2EYHG6M5CJ"}
```

```json
{"success":true,"groupId":"@TGS#xxxx","machineCode":"ABCD-EFGH-JKMN","robotId":"@2EYHG6M5CJ","enabled":true}
```

`robotId` 为 Windows 侧机器人账号 ID（示例 `@2EYHG6M5CJ`）。  
开群特权完成标志：`enabled=true`。此前 App 查反水会 `403 GROUP_NOT_ENABLED`。

开启成功后可在群内回一条确认文案（建议）：

```text
开群特权成功
机器码：ABCD-****-JKMN
机器人：@2EYHG6M5CJ
```

---

## 5. 玩家数据同步

### `POST /api/internal/robot-sync`

**Header 必填**：`X-Machine-Code`

支持**双协议**（URL / 认证不变）：

| 模式 | 判定 | 用途 |
|------|------|------|
| 单事件 | body 无 `batch:true` | 控制事件、兼容旧客户端 |
| 批次 | `"batch": true` | 玩家增量（snapshot / updown / daily），≤200 条 |

客户端增量：最多 **8 路**并发，每路同时 1 个请求；整批成功才 2xx（无逐条部分成功）。

| eventType | 用途 |
|-----------|------|
| `player.snapshot.upsert` | 玩家当前快照（含 `agentPendingFlow` / `agentPendingRebate`；支持 `data.active=false` 停用） |
| `player.daily.summary` | 每日归档（多 `pendingRebate`） |
| `player.updown.recorded` | 单笔上下分流水（只落库，不改余额） |
| `robot.database.initialized` | DB 重置 → `SYNCING`（**仅单事件**） |
| `robot.full_snapshot.completed` | 全量完成 → `READY`（**仅单事件**） |

公共字段：`protocolVersion=1.0`、`eventId`（幂等）、`playerGroupId`、`statisticsGroupId`、`entityId`、`businessTimestamp`、`businessTimezone=+08:00`、`sourceUpdatedAt`、`syncReason`、`data`。

批次 envelope 另含：`batchId`、`lane`(1–8)、`eventCount`、`events[]`。

> 注意：服务端会把 `playerGroupId` / `robotId` **改写成机器码** 再落库。body 仍需填合法非空字符串通过校验即可。

**鉴权失败：**

```http
HTTP/1.1 401
{"success":false,"message":"invalid machine code"}
```

**单事件成功 / 幂等：**

```json
{"success":true,"updatedCount":1,"duplicate":false,"eventId":"...","message":"accepted"}
```

```json
{"success":true,"updatedCount":0,"duplicate":true,"eventId":"...","message":"already processed"}
```

**批次成功（客户端只认 HTTP 2xx）：**

```json
{"success":true,"batchId":"lane_3_...","accepted":200,"duplicates":3}
```

`SYNCING` 期间禁止反水申请与任务 pull。

快照 `data` 关键字段：`wxid`、`playerNo`、`balance`、`parentPath`、`remainingFlow`、`agentPendingFlow`、`agentPendingRebate`、`rebateRate`（万分比）、`totalRebate`、`active` 等。完整表见 [robot-sync-api.md](./robot-sync-api.md) §3.2。

结算后务必再 sync 一次，把 `agentPending*` / `remainingFlow` 等更新到服务端。

---

## 6. 反水任务（App 申请 → Windows 执行）

App 在群内申请后，任务落在**该群绑定的机器码**租户下。Windows 轮询本机码的任务。

### `POST /api/internal/robot-rebate-tasks/pull`

```http
X-Machine-Code: ABCD-EFGH-JKMN
Content-Type: application/json

{"databaseGeneration":"gen-1","limit":20}
```

- 只返回**本机机器码**下的 `PENDING` / `PROCESSING` 任务  
- body 里旧字段 `robotId` 可忽略；作用域以 Header 为准  
- 首次 pull：`PENDING` → `PROCESSING`  
- 重复 pull：同一 `taskId` / `leaseToken`

`data[]` 每项含：`taskId`、`leaseToken`、`databaseGeneration`、`applicantWxid`、`applicantNo`、`settlementType`（`PLAYER` / `AGENT`）。  
`PLAYER` 另有服务端预填的 `flowToConsume` / `rebateAmount` 及预期余额流水；`AGENT` 金额由 Windows **本地重算**。

### `POST /api/internal/robot-rebate-tasks/result`

```http
X-Machine-Code: ABCD-EFGH-JKMN
Content-Type: application/json

{
  "taskId": "...",
  "leaseToken": "...",
  "databaseGeneration": "gen-1",
  "settlementType": "PLAYER",
  "applicantWxid": "w4ajsj6p3a",
  "success": true,
  "retryable": false,
  "resultCode": "SUCCESS",
  "resultMessage": "执行成功",
  "consumedFlow": 10000,
  "rebateAmount": 100
}
```

| 结果 | 状态 |
|------|------|
| `success=true` | `SUCCESS` |
| `success=false` 且 `retryable=true` | 回 `PENDING` |
| `success=false` 且 `retryable=false` | `FAILED` |
| `taskId` 内嵌租户 ≠ Header 机器码 | `403 MACHINE_CODE_MISMATCH` |
| 代次过期 | `409 STALE_DATABASE_GENERATION` |

执行成功后 **sync 更新快照**。

---

## 7. 推荐本地状态机

```text
[无码] --register--> [有码]
[有码] --unregister--> [无码]（级联清租户+绑定）
[有码] --配对--> [已绑定群]
[已绑定群] --开启--> [开群特权 ENABLED]
[有码] --群「配对xxxx-xxxx-xxxx」--> [已绑群]（enabled=false）
[已绑群] --群「开启@机器人ID」--> [已开群特权]（enabled=true，App 可查反水）
[已开群特权] --持续 sync / pull / result-->
[有码] --database.initialized--> SYNCING（暂停 pull）
SYNCING --full_snapshot.completed--> READY
```

本地至少持久化：

- `machineCode`
- 可选：`boundGroupId`、`robotId`、`databaseGeneration`

---

## 8. 联调清单

- [ ] 无本地码时 register，有码则不重复申  
- [ ] 所有内部请求（除 register）带 `X-Machine-Code`  
- [ ] 作废重来：`unregister` 后清本地码再 register  
- [ ] 不再发送 `X-Robot-Secret`  
- [ ] 开群特权指令：`配对xxxx-xxxx-xxxx` → bind；`开启@机器人ID` → enable  
- [ ] 顺序校验：未配对禁止开启；成功后群内可回执  
- [ ] sync 幂等：`eventId` 唯一；断网可重试  
- [ ] pull 仅处理本机任务；result 后 sync  
- [ ] `agentPending*` 由本机计算并同步，App 不重算级差  

---

## 9. 接口速查

| 方法 | 路径 | Header |
|------|------|--------|
| POST | `/api/internal/robot-machines/register` | 无 |
| POST | `/api/internal/robot-machines/unregister` | `X-Machine-Code` |
| POST | `/api/internal/robot-machines/bind-group` | `X-Machine-Code` |
| POST | `/api/internal/robot-machines/enable` | `X-Machine-Code` |
| POST | `/api/internal/robot-sync` | `X-Machine-Code` |
| POST | `/api/internal/robot-rebate-tasks/pull` | `X-Machine-Code` |
| POST | `/api/internal/robot-rebate-tasks/result` | `X-Machine-Code` |
