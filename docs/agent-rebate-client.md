# 代理反水业务 — App 前端对接文档

**版本**：v1.2  
**更新**：2026-07-20  
**适用**：99chat App 客户端（代理查反水、历史汇总、文件导出）  
**数据源**：机器人同步落库 `jiqiren`（按机器码隔离；App 用群 ID 定位）  
**关联设计**：[机器人联合.md](./机器人联合.md)（本文档为字段细则）  
**Windows 对接（推荐）**：[robot-windows-integration.md](./robot-windows-integration.md)  
**App 对接（推荐）**：[robot-app-integration.md](./robot-app-integration.md)  
**Windows 同步字段**：[robot-sync-api.md](./robot-sync-api.md)

---

## 1. 实现状态

| 能力 | 状态 | 接口 |
|------|------|------|
| 群绑定状态查看 | ✅ | `GET /me/robot/groups/{groupId}` |
| 群绑定机器码 | ✅ | `POST /me/robot/groups/{groupId}/bind` |
| 群开启 robotId | ✅ | `POST /me/robot/groups/{groupId}/enable` |
| 玩家资料 + 是否代理 | ✅ | `GET /me/agent/player` |
| 当前反水汇总 | ✅ | `GET /me/agent/rebate/current` |
| 历史按日汇总（JSON） | ✅ | `GET /me/agent/rebate/history` |
| 下级列表（当前快照） | ✅ | `GET /me/agent/descendants` |
| 下级详情（当前快照） | ✅ | `GET /me/agent/descendants/{userId}` |
| 下级历史明细（逐人按日） | ✅ | `GET /me/agent/descendants/history` |
| 历史导出提交 | ✅ | `POST /me/agent/rebate/history/export` |
| 导出任务查询 | ✅ | `GET /me/agent/rebate/history/export/{taskId}` |
| 导出文件下载 | ✅ | `GET /me/agent/rebate/history/export/{taskId}/download` |
| 个人/代理反水申请 | ✅ | `/me/rebate/apply`、`/me/agent/rebate/apply` |
| IM 仅本人可见消息 | ❌ 未实现 | — |

---

## 2. 通用约定

### 2.1 鉴权与群隔离

所有 `/me/agent/**`、`/me/rebate/**` 接口均需：

```http
Authorization: Bearer <JWT>
X-Group-Id: <@TGS#当前IM群ID>
```

- `userId` 从 JWT `sub` 获取，与机器人 `wxid` 一致。
- `X-Group-Id` → 查 `robot_group_binding` → `machine_code`；未绑定 `404 GROUP_NOT_BOUND`；未开启 `403 GROUP_NOT_ENABLED`。
- 数据按 `(machine_code, wxid)` 查询，同一用户在不同机器人做代理时不会窜数。

`/me/robot/groups/**` 只需 JWT（查看/绑定时不要求已开启）。路径中的 `groupId` 请 `encodeURIComponent`（群 ID 常含 `#`）。

### 2.2 响应格式

除**文件下载**外，成功响应经 `GlobalResponseWrapper` 包装：

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

错误响应（HTTP 非 2xx）**不包装**，格式：

```json
{
  "code": "ERROR_CODE",
  "message": "可读说明"
}
```

前端判断逻辑：

- 成功：`HTTP 200` 且 `code === 0`（JSON 接口）
- 失败：看 HTTP 状态码 + `code` 字段

### 2.3 文件下载响应

`GET .../download` 返回**原始文件流**，不走 `{code, message, data}` 包装。

响应头示例：

```http
HTTP/1.1 200
Content-Type: text/csv; charset=utf-8
Content-Disposition: attachment; filename*=UTF-8''%E4%BB%A3%E7%90%86%E5%8F%8D%E6%B0%B4%E5%8E%86%E5%8F%B2_7552_2026-07-13_2026-07-13.csv
```

前端下载时需携带同一 JWT，并处理 `Content-Disposition` 文件名。

### 2.4 业务日口径（与 App 对齐）

- **业务日窗口**（中国时间 UTC+8）：`[当日 07:00, 次日 07:00)`
- **businessDate / startDate / endDate 标签** = 该窗口起始日的日历日（`yyyy-MM-dd`）
- 例：`businessDate=2026-08-20` ⇒ `[2026-08-20 07:00 CST, 2026-08-21 07:00 CST)`
- 「今天」在 **07:00 前**仍属前一业务日
- `GET /me/agent/rebate/history`、`descendants/history`、历史导出：`startDate`/`endDate` 按业务日**闭区间**解释
- `GET /me/agent/rebate/current` 的 `businessDate` 为当前业务日标签；`dataTime` 为 ISO-8601 带 `+08:00`
- 下级列表/详情中的流水、输赢、待反水来自 Windows 同步的**当前业务日快照**（非自然日 00:00）

### 2.5 金额与比例

| 字段 | 说明 |
|------|------|
| 金额字段 | `BigDecimal`，保留 4 位小数（如 `700.0000`） |
| `rebateRate` | 万分比，计算方式：`待反水 = remainingFlow × rebateRate ÷ 10000` |
| `dataTime` | ISO-8601 带时区，如 `2026-07-13T03:16:57+08:00` |

### 2.6 代理判定

- 玩家在 `robot_player_snapshot` 有档案，且 **`rebateRate > 0`**（有返水比例）→ `isAgent: true`
- `GET /me/agent/player` 返回 `isAgent: true/false`
- 汇总/历史/导出等代理接口同样按「有返水比例」准入，否则 `403 NOT_AGENT`
- 说明：下级树里的人数统计仍可按「是否有下级」区分节点类型，与 `isAgent` 准入条件独立

---

## 3. API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/me/robot/groups/{groupId}` | 查看群绑定状态（机器码掩码） |
| `POST` | `/me/robot/groups/{groupId}/bind` | `{machineCode}` 绑定 |
| `POST` | `/me/robot/groups/{groupId}/enable` | `{robotId}` 开启 |
| `GET` | `/me/agent/player` | 当前用户玩家资料 |
| `GET` | `/me/agent/rebate/current` | 当前反水汇总 |
| `GET` | `/me/agent/rebate/history` | 历史按日汇总 |
| `GET` | `/me/agent/descendants` | 下级列表 |
| `GET` | `/me/agent/descendants/{userId}` | 下级详情 |
| `GET` | `/me/agent/descendants/history` | 下级历史明细 |
| `POST` | `/me/agent/rebate/history/export` | 提交历史导出 |
| `GET` | `/me/agent/rebate/history/export/{taskId}` | 查询导出任务 |
| `GET` | `/me/agent/rebate/history/export/{taskId}/download` | 下载导出文件 |

Base URL 示例：`http://47.239.60.107:8081`（以实际部署为准）

### 群绑定示例

```http
GET /me/robot/groups/%40TGS%23xxxx
Authorization: Bearer <JWT>
```

```json
{
  "code": 0,
  "data": {
    "groupId": "@TGS#xxxx",
    "bound": true,
    "enabled": true,
    "robotId": "@2EYHG6M5CJ",
    "machineCodeMasked": "ABCD****JKMN"
  }
}
```

```http
POST /me/robot/groups/%40TGS%23xxxx/bind
Authorization: Bearer <JWT>
Content-Type: application/json

{"machineCode":"ABCD-EFGH-JKMN"}
```

```http
POST /me/robot/groups/%40TGS%23xxxx/enable
Authorization: Bearer <JWT>
Content-Type: application/json

{"robotId":"@2EYHG6M5CJ"}
```

---

## 4. 玩家资料

### `GET /me/agent/player`

判断当前用户是否有机器人玩家档案、是否为代理。可用于控制「查反水」「历史记录」入口显示。

**响应 `data`：**

```json
{
  "userId": "ithlxvup5h",
  "playerNo": "7552",
  "displayName": "六合彩机器人",
  "playerType": "0",
  "levelNo": 1,
  "balance": 700.0,
  "rebateRate": 200.0,
  "isAgent": true
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `userId` | string | 平台用户 ID（= wxid） |
| `playerNo` | string | 玩家编号 |
| `displayName` | string | 显示名称 |
| `playerType` | string | `"0"` 真人，`"1"` 手动托 |
| `levelNo` | int | 层级 |
| `balance` | number | 当前余额 |
| `rebateRate` | number | 反水比例（万分比） |
| `isAgent` | boolean | 是否有返水比例（`rebateRate > 0`） |

| HTTP | code | 说明 |
|------|------|------|
| 200 | 0 | 成功 |
| 401 | — | 未登录 |
| 404 | `PLAYER_NOT_FOUND` | 无玩家档案 |

---

## 5. 当前反水汇总

### `GET /me/agent/rebate/current`

返回：
1. `summary`：代理本人 + 全部下级（排除 `playerType=1` 手动托）的**团队当前快照**汇总  
2. `personal`：登录代理**本人**个人反水汇总框（含级差待结算）

**响应 `data`：**

```json
{
  "userId": "ithlxvup5h",
  "businessDate": "2026-08-20",
  "summary": {
    "agentWxid": "ithlxvup5h",
    "agentNo": "7552",
    "agentName": "六合彩机器人",
    "agentCount": 0,
    "playerCount": 1,
    "totalBalance": 2900.0,
    "totalFlow": 0.0,
    "playerProfitLoss": 0.0,
    "platformProfitLoss": 0.0,
    "totalUp": 200.0,
    "totalDown": 0.0,
    "totalRebated": 0.0,
    "pendingRebate": 0.0,
    "dataTime": "2026-07-13T03:16:57+08:00"
  },
  "personal": {
    "balance": 1250.5,
    "totalFlow": 50000,
    "totalProfitLoss": -1200,
    "totalRebate": 500,
    "remainingFlow": 20000,
    "pendingRebate": 600,
    "agentPendingFlow": 10000,
    "agentPendingRebate": 200,
    "totalPendingRebate": 800,
    "totalRebate1": 800,
    "rebateRate": 300,
    "rebateRateUnit": "per_10000"
  }
}
```

**`summary` 字段说明（团队口径）：**

| 字段 | 说明 |
|------|------|
| `agentCount` | 下级代理人数 |
| `playerCount` | 下级玩家人数 |
| `totalBalance` | 统计范围内总余额 |
| `totalFlow` | 总流水 |
| `playerProfitLoss` | 玩家总输赢 |
| `platformProfitLoss` | 平台总输赢（= 玩家输赢取反） |
| `totalUp` / `totalDown` | 总上分 / 总下分 |
| `totalRebated` | 已反水 |
| `pendingRebate` | 待反水（范围内 `remainingFlow × rebateRate ÷ 10000` 之和） |
| `dataTime` | 数据更新时间 |

**`personal` 字段说明（本人口径）：**

| 字段 | 来源 | 说明 |
|------|------|------|
| `balance` | 快照 | 玩家余额 |
| `totalFlow` | 快照 | 个人总流水 |
| `totalProfitLoss` | 快照 | 个人总输赢 |
| `totalRebate` | 快照 | 累计已领取反水（含本人反水 + 代理级差反水） |
| `remainingFlow` | 快照 | 本人剩余可反水流水 |
| `pendingRebate` | **后端计算** | `floor(remainingFlow × rebateRate ÷ 10000)` |
| `agentPendingFlow` | 快照（机器人同步） | 下级级差当前待结算流水；代理已领取级差后应由机器人清零 |
| `agentPendingRebate` | 快照（机器人同步） | 下级级差当前待领取金额；已领取后为 `0` |
| `totalPendingRebate` | **后端计算** | `pendingRebate + agentPendingRebate` |
| `totalRebate1` | **后端计算** | `pendingRebate + agentPendingRebate`（与 `totalPendingRebate` 同值） |

级差公式（机器人侧结算/同步用）：`floor(下级可结算流水 × max(代理比例 - 直属下级比例, 0) ÷ 10000)`。  
App **不**用下级 `remainingFlow` 重算级差：下级流水仍属其本人待反水，代理结清后不会被清零。
| `rebateRate` | 快照 | 本人反水比例（原始值） |
| `rebateRateUnit` | 快照 | 固定 `per_10000`（缺省时补此值） |

| HTTP | code | 说明 |
|------|------|------|
| 200 | 0 | 成功 |
| 403 | `NOT_AGENT` | 非代理 |
| 404 | `PLAYER_NOT_FOUND` | 无玩家档案 |

---

## 6. 历史按日汇总（JSON）

### `GET /me/agent/rebate/history`

Query 参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `startDate` | `YYYY-MM-DD` | 是 | 开始日期 |
| `endDate` | `YYYY-MM-DD` | 是 | 结束日期（含当天） |

示例：

```http
GET /me/agent/rebate/history?startDate=2026-07-13&endDate=2026-07-13
```

约束：日期范围最多 **93 天**，`endDate >= startDate`。

**响应 `data`：**

```json
{
  "userId": "ithlxvup5h",
  "startDate": "2026-07-13",
  "endDate": "2026-07-13",
  "days": [
    {
      "businessDate": "2026-07-13",
      "agentCount": 0,
      "playerCount": 1,
      "totalBalance": 2700.0,
      "totalFlow": 0.0,
      "playerProfitLoss": 0.0,
      "platformProfitLoss": 0.0,
      "totalUp": 1800.0,
      "totalDown": 500.0,
      "totalRebated": 0.0,
      "pendingRebate": 0.0
    }
  ],
  "total": {
    "agentCount": 0,
    "playerCount": 1,
    "totalBalance": 2700.0,
    "totalFlow": 0.0,
    "playerProfitLoss": 0.0,
    "platformProfitLoss": 0.0,
    "totalUp": 1800.0,
    "totalDown": 500.0,
    "totalRebated": 0.0,
    "pendingRebate": 0.0,
    "agentWxid": null,
    "agentNo": null,
    "agentName": null,
    "dataTime": null
  }
}
```

| HTTP | code | 说明 |
|------|------|------|
| 200 | 0 | 成功 |
| 400 | `DATE_RANGE_REQUIRED` | 缺日期 |
| 400 | `INVALID_DATE_RANGE` | 结束早于开始 |
| 400 | `DATE_RANGE_TOO_LARGE` | 超过 93 天 |
| 403 | `NOT_AGENT` | 非代理 |
| 404 | `PLAYER_NOT_FOUND` | 无玩家档案 |

> v1 说明：历史统计使用**当前**下级树关系，非历史时点关系快照。

---

## 7. 下级列表与明细

### 7.1 下级列表（当前快照）

#### `GET /me/agent/descendants`

Query 参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scope` | string | 否 | `all`（默认）全部下级；`direct` 仅直属下级 |

**响应 `data`：**

```json
{
  "userId": "ithlxvup5h",
  "scope": "all",
  "total": 1,
  "items": [
    {
      "userId": "srd4vjagnu",
      "playerNo": "7553",
      "displayName": "啦啦啦",
      "playerType": "0",
      "levelNo": 2,
      "isAgent": false,
      "directParentUserId": "ithlxvup5h",
      "directParentNo": "7552",
      "balance": 2200.0,
      "totalFlow": 0.0,
      "usedFlow": 0.0,
      "remainingFlow": 0.0,
      "totalUp": 200.0,
      "totalDown": 0.0,
      "playerProfitLoss": 0.0,
      "platformProfitLoss": 0.0,
      "rebateRate": 150.0,
      "totalRebated": 0.0,
      "pendingRebate": 0.0
    }
  ]
}
```

| HTTP | code | 说明 |
|------|------|------|
| 200 | 0 | 成功 |
| 400 | `INVALID_SCOPE` | scope 非 all/direct |
| 403 | `NOT_AGENT` | 非代理 |
| 404 | `PLAYER_NOT_FOUND` | 无玩家档案 |

### 7.1.1 直属一级代理及其全部层级下级

#### `GET /me/agent/first-level-agents`

只返回当前登录用户的**直属一级代理**（`directParentUserId = 当前用户` 且
`rebateRate > 0`）。每个一级代理同时返回：

- `children`：树形数据。每个节点的 `children` 是该用户直接邀请的人，可递归到全部层级。
- `descendants`：同一批全部层级人员的平铺数据，便于搜索和列表展示。

手动托 `playerType=1` 不返回。

```json
{
  "userId": "rootAgent",
  "agentCount": 1,
  "descendantTotal": 2,
  "agents": [
    {
      "agent": {
        "userId": "directAgent",
        "playerNo": "10002",
        "displayName": "一级代理",
        "isAgent": true,
        "directParentUserId": "rootAgent",
        "levelNo": 2
      },
      "children": [
        {
          "item": {
            "userId": "level3Player",
            "directParentUserId": "directAgent",
            "levelNo": 3
          },
          "childCount": 1,
          "descendantCount": 1,
          "children": [
            {
              "item": {
                "userId": "level4Player",
                "directParentUserId": "level3Player",
                "levelNo": 4
              },
              "childCount": 0,
              "descendantCount": 0,
              "children": []
            }
          ]
        }
      ],
      "descendantCount": 2,
      "descendants": [
        {
          "userId": "level3Player",
          "directParentUserId": "directAgent",
          "levelNo": 3
        },
        {
          "userId": "level4Player",
          "directParentUserId": "level3Player",
          "levelNo": 4
        }
      ]
    }
  ]
}
```

`agent`、`children[].item` 和 `descendants[]` 的完整字段与 §7.1 的 `items[]` 相同。
树节点的 `childCount` 表示直接邀请人数，`descendantCount` 表示该节点名下全部层级人数。
`descendantTotal` 是所有一级代理组的下级人数之和；一级代理本人不计入该值。

| HTTP | code | 说明 |
|------|------|------|
| 200 | 0 | 成功；没有直属代理时 `agents=[]` |
| 403 | `NOT_AGENT` | 当前用户不是代理 |
| 404 | `PLAYER_NOT_FOUND` | 当前用户没有玩家档案 |

### 7.2 下级详情（当前快照）

#### `GET /me/agent/descendants/{userId}`

`userId` 为要查询的**下级**平台用户 ID，必须在当前代理可见范围内。

**响应 `data`：**

```json
{
  "userId": "ithlxvup5h",
  "item": { },
  "directChildCount": 0,
  "descendantCount": 0,
  "teamTotalUp": 5000.0,
  "teamTotalDown": 3200.0
}
```

`item` 字段结构与列表项相同；`directChildCount` 为该下级的直属下级数；`descendantCount` 为其全部下级数。

| 字段 | 说明 |
|------|------|
| `item.totalUp` / `item.totalDown` | **仅该下级本人**的总上分 / 总下分 |
| `teamTotalUp` / `teamTotalDown` | **该下级本人 + 其整棵下级树**的总上分 / 总下分（排除 `playerType=1` 手动托；口径与 `GET /me/agent/rebate/current` 的 visible 一致） |

| HTTP | code | 说明 |
|------|------|------|
| 200 | 0 | 成功 |
| 403 | `NOT_AGENT` / `NOT_IN_SCOPE` | 非代理或目标不在下级树内 |
| 404 | `PLAYER_NOT_FOUND` | 目标无档案 |

### 7.3 下级历史明细（逐人按日）

#### `GET /me/agent/descendants/history`

Query 参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `startDate` | `YYYY-MM-DD` | 是 | 开始日期 |
| `endDate` | `YYYY-MM-DD` | 是 | 结束日期 |
| `userId` | string | 否 | 指定某个下级；不传则返回全部下级 |

示例：

```http
GET /me/agent/descendants/history?startDate=2026-07-13&endDate=2026-07-13
GET /me/agent/descendants/history?startDate=2026-07-13&endDate=2026-07-13&userId=srd4vjagnu
```

**响应 `data`：**

```json
{
  "userId": "ithlxvup5h",
  "startDate": "2026-07-13",
  "endDate": "2026-07-13",
  "targetUserId": null,
  "total": 1,
  "items": [
    {
      "businessDate": "2026-07-13",
      "userId": "srd4vjagnu",
      "playerNo": "7553",
      "displayName": "啦啦啦",
      "playerType": "0",
      "directParentUserId": "ithlxvup5h",
      "balance": 2000.0,
      "totalFlow": 0.0,
      "totalUp": 800.0,
      "totalDown": 0.0,
      "playerProfitLoss": 0.0,
      "platformProfitLoss": 0.0,
      "totalRebated": 0.0,
      "pendingRebate": 0.0,
      "rebateRate": 150.0
    }
  ]
}
```

| HTTP | code | 说明 |
|------|------|------|
| 200 | 0 | 成功 |
| 400 | `DATE_RANGE_*` | 日期参数错误 |
| 403 | `NOT_AGENT` / `NOT_IN_SCOPE` | 非代理或目标不在范围内 |
| 404 | `PLAYER_NOT_FOUND` | 无玩家档案 |

---

## 8. 反水申请与机器人结算

### 8.1 玩家申请（PLAYER）

#### `POST /me/rebate/apply`

仅结算**申请人本人**待反水。JWT `sub` = 玩家 `wxid`。

**响应示例：**

```json
{
  "code": 0,
  "data": {
    "userId": "w4ajsj6p3a",
    "playerNo": "7582",
    "settlementType": "PLAYER",
    "requestId": "RR023798ea1a604e2db514fe453bdc167e",
    "taskId": "@25EFG6M5CC:gen-1:w4ajsj6p3a:PLAYER:RR023798ea1a604e2db514fe453bdc167e",
    "leaseToken": "RLT04a3c45c2d6840b99e438fc6241c7bfa",
    "databaseGeneration": "gen-1",
    "status": "PENDING",
    "flowToConsume": 10000.0,
    "rebateAmount": 100.0,
    "existing": false
  }
}
```

| 字段 | 说明 |
|------|------|
| `taskId` | 稳定唯一，重复 pull 不变 |
| `leaseToken` | 执行凭证，回调必须一致 |
| `existing` | `true` 表示已有进行中的申请，未新建 |

#### `GET /me/rebate/apply/status`

查询当前用户最近一次申请状态（`NONE` / `PENDING` / `PROCESSING` / `SUCCESS` / `FAILED`）。

### 8.2 代理申请（AGENT）

#### `POST /me/agent/rebate/apply`

仅结算**申请代理本人**；金额由机器人本地重算，`flowToConsume`/`rebateAmount` 返回 0。

#### `GET /me/agent/rebate/apply/status`

同玩家状态查询。

### 8.3 机器人拉取任务（内部）

#### `POST /api/internal/robot-rebate-tasks/pull`

Header：`X-Machine-Code`（任务仅返回该机器码租户下的待处理项）

```json
{
  "robotId": "@25EFG6M5CC",
  "databaseGeneration": "gen-1",
  "limit": 20
}
```

返回 `data[]`，每项含 `taskId`、`leaseToken`、`databaseGeneration`、`applicantWxid`、`applicantNo`、`settlementType` 及 PLAYER 专用预期字段。

- 首次 pull：`PENDING` → `PROCESSING`
- 重复 pull：同一 `taskId` / `leaseToken` 不变
- `SUCCESS` / `FAILED` 不再返回

### 8.4 机器人结果回调（内部）

#### `POST /api/internal/robot-rebate-tasks/result`

```json
{
  "taskId": "...",
  "leaseToken": "...",
  "robotId": "@25EFG6M5CC",
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

| 结果 | 状态变化 |
|------|----------|
| `success=true` | `SUCCESS`，保存 `consumedFlow` / `rebateAmount` |
| `success=false` 且 `retryable=true` | 回到 `PENDING` |
| `success=false` 且 `retryable=false` | `FAILED`，不再自动下发 |
| 旧 `databaseGeneration` | `409 STALE_DATABASE_GENERATION` |

### 8.5 状态字段（落在 `robot_player_snapshot`）

`rebate_request_id`、`rebate_request_type`、`rebate_request_status`、`rebate_lease_token`、`rebate_requested_at` 等；当前库代次存于 `robot_runtime_state.database_generation`。

---

## 9. 历史导出

### 9.1 提交导出

#### `POST /me/agent/rebate/history/export`

**请求体：**

```json
{
  "startDate": "2026-07-01",
  "endDate": "2026-07-07",
  "fileType": "CSV",
  "includeDetail": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `startDate` | string | 是 | `YYYY-MM-DD` |
| `endDate` | string | 是 | `YYYY-MM-DD` |
| `fileType` | string | 否 | `TXT` 或 `CSV`，默认 `CSV` |
| `includeDetail` | boolean | 否 | `true` 时同时包含代理明细 + 玩家明细 |
| `includeAgentDetail` | boolean | 否 | 仅下级代理明细 |
| `includePlayerDetail` | boolean | 否 | 仅玩家明细 |

`includeDetail=true` 等价于 `includeAgentDetail=true` 且 `includePlayerDetail=true`。

**响应 `data`（同步完成）：**

```json
{
  "taskNo": "ARE-89452a02205a402982de",
  "taskStatus": "COMPLETED",
  "progress": 100,
  "async": false,
  "downloadPath": "/me/agent/rebate/history/export/ARE-89452a02205a402982de/download"
}
```

**响应 `data`（异步任务）：**

```json
{
  "taskNo": "ARE-xxxxxxxx",
  "taskStatus": "PENDING",
  "progress": 0,
  "async": true,
  "downloadPath": null
}
```

| 字段 | 说明 |
|------|------|
| `taskNo` | 任务编号，后续查询/下载使用 |
| `taskStatus` | `PENDING` / `RUNNING` / `COMPLETED` / `FAILED` |
| `progress` | 0–100 |
| `async` | `true` 表示需轮询任务状态 |
| `downloadPath` | 相对路径，完成后可拼 Base URL 下载 |

**导出规则：**

| 条件 | 行为 |
|------|------|
| 日期范围 | 最多 93 天 |
| 行数 < 500 | 允许 TXT / CSV |
| 行数 ≥ 500 | 仅允许 CSV |
| 含明细且行数 ≥ 500 | 不允许 TXT |
| 行数 ≤ 5000 | 同步导出，提交后立即 `COMPLETED` |
| 行数 > 5000 | 异步导出，需轮询 |
| 行数 ≥ 50000 | 异步导出，文件自动 ZIP 压缩 |

### 9.2 查询任务状态

#### `GET /me/agent/rebate/history/export/{taskId}`

`taskId` = 提交时返回的 `taskNo`。

**响应 `data`：**

```json
{
  "taskNo": "ARE-89452a02205a402982de",
  "taskStatus": "COMPLETED",
  "progress": 100,
  "fileName": "代理反水历史_7552_2026-07-13_2026-07-13.csv",
  "fileSize": 299,
  "rowCount": 1,
  "errorMessage": null,
  "downloadPath": "/me/agent/rebate/history/export/ARE-89452a02205a402982de/download"
}
```

| HTTP | code | 说明 |
|------|------|------|
| 200 | 0 | 成功 |
| 403 | `EXPORT_TASK_FORBIDDEN` | 非本人任务 |
| 404 | `EXPORT_TASK_NOT_FOUND` | 任务不存在 |

**前端轮询建议：**

- `async=true` 或 `taskStatus` 为 `PENDING` / `RUNNING` 时，每 2–3 秒轮询一次
- `COMPLETED` 后调下载接口
- `FAILED` 展示 `errorMessage`

### 9.3 下载文件

#### `GET /me/agent/rebate/history/export/{taskId}/download`

- 需 JWT，仅任务创建者可下载
- 返回文件二进制流（CSV / TXT / ZIP）
- 编码：**UTF-8 with BOM**（兼容 Windows Excel）

| HTTP | code | 说明 |
|------|------|------|
| 200 | — | 文件流（非 JSON 包装） |
| 403 | `EXPORT_TASK_FORBIDDEN` | 非本人任务 |
| 404 | `EXPORT_TASK_NOT_FOUND` / `EXPORT_FILE_NOT_FOUND` | 任务或文件不存在 |
| 409 | `EXPORT_NOT_READY` | 任务未完成 |
| 410 | `EXPORT_EXPIRED` | 文件已过期（默认保留 7 天） |

---

## 10. 导出文件格式

### 9.1 文件名

```text
代理反水历史_{playerNo}_{startDate}_{endDate}.xlsx
代理反水历史_{playerNo}_{startDate}_{endDate}.txt
代理反水历史_{playerNo}_{startDate}_{endDate}.zip   # 大文件
```

> `fileType=CSV` 时实际生成 **Excel（.xlsx）** 文件：整张表仅 **一行表头**，表头带蓝色底色与白色加粗字体；汇总、下级代理、玩家明细通过「行类型」列区分。

### 9.2 统一表头（仅出现一次）

```text
行类型,日期,编号,名称,直属代理编号,代理人数,玩家人数,流水,玩家输赢,平台输赢,上分,下分,余额,已反水,待反水
```

| 行类型 | 说明 |
|--------|------|
| `汇总` | 每日汇总行，编号/名称为代理本人 |
| `下级代理` | 下级代理明细行 |
| `玩家` | 玩家明细行，「直属代理编号」有值 |

### 9.3 样式说明

- 第一行表头：蓝底白字、加粗、居中，并冻结首行
- 数值列保留两位小数
- 不适用字段留空

### 9.4 旧版多段 CSV 说明（已废弃）

此前汇总 / 代理明细 / 玩家明细各有一段独立表头；现已合并为单表结构。

### 9.5 TXT 格式

纯文本分段展示，含「代理反水历史汇总」标题及每日汇总块；含明细时追加「下级代理明细」「玩家明细」段落。

---

## 11. 错误码汇总

| code | HTTP | 说明 | 前端建议 |
|------|------|------|----------|
| — | 401 | 未登录 | 跳转登录 |
| `PLAYER_NOT_FOUND` | 404 | 无机器人玩家档案 | 隐藏代理功能入口 |
| `NOT_AGENT` | 403 | 有档案但无下级 | 隐藏代理汇总/导出入口 |
| `NOT_IN_SCOPE` | 403 | 目标不在下级树内 | 禁止查看 |
| `INVALID_SCOPE` | 400 | scope 参数错误 | 仅允许 all/direct |
| `DATE_RANGE_REQUIRED` | 400 | 缺日期参数 | 表单校验 |
| `INVALID_DATE_RANGE` | 400 | 日期顺序错误 | 表单校验 |
| `DATE_RANGE_TOO_LARGE` | 400 | 超过 93 天 | 限制日期选择器 |
| `INVALID_FILE_TYPE` | 400 | 非 TXT/CSV | 表单校验 |
| `TXT_NOT_ALLOWED_FOR_LARGE_EXPORT` | 400 | 大行数不能用 TXT | 自动切换 CSV |
| `DETAIL_EXPORT_REQUIRES_CSV` | 400 | 含明细不能用 TXT | 自动切换 CSV |
| `EXPORT_TASK_NOT_FOUND` | 404 | 任务不存在 | 提示重试 |
| `EXPORT_TASK_FORBIDDEN` | 403 | 非本人任务 | 禁止访问 |
| `EXPORT_NOT_READY` | 409 | 文件未生成完 | 继续轮询 |
| `EXPORT_FILE_NOT_FOUND` | 404 | 文件丢失 | 重新导出 |
| `EXPORT_EXPIRED` | 410 | 文件已过期 | 重新导出 |

---

## 12. 前端推荐流程

### 11.1 页面入口控制

```text
1. GET /me/agent/player
2. 无档案 → 不展示代理反水功能
3. isAgent=false → 仅展示个人资料（如有需要）
4. isAgent=true  → 展示「查反水」「历史记录」「导出」
```

### 11.2 查反水（当前汇总）

```text
GET /me/agent/rebate/current
→ 渲染 summary 各字段
→ dataTime 展示为「数据时间」
```

### 11.3 历史记录（页面内表格）

```text
用户选择日期范围
→ GET /me/agent/rebate/history?startDate=&endDate=
→ 渲染 days[] 列表 + total 合计行
```

### 11.4 下级列表与明细

```text
GET /me/agent/descendants?scope=all|direct
→ 渲染下级列表

GET /me/agent/descendants/{userId}
→ 下级详情页 + 子树人数

GET /me/agent/descendants/history?startDate=&endDate=&userId=
→ 下级历史逐人明细表
```

### 11.5 历史导出（文件）

```text
用户选择日期、格式（TXT/CSV）、是否含明细
→ POST /me/agent/rebate/history/export
→ 若 async=false 且 taskStatus=COMPLETED
     → 直接 GET downloadPath 下载
→ 若 async=true
     → 轮询 GET /me/agent/rebate/history/export/{taskNo}
     → COMPLETED 后 GET .../download
```

### 11.6 下载实现注意

- 使用带 `Authorization` 的请求下载，不要用公开 URL
- 解析 `Content-Disposition` 获取文件名
- `fileType=CSV` 下载的是 `.xlsx`，可直接用 Excel / WPS 打开
- ZIP 需客户端解压后查看内部 xlsx

---

## 13. 架构说明

```text
Windows 机器人
    │ POST /api/internal/robot-sync
    ▼
jiqiren 库（玩家快照 / 每日归档）
    │
    ▼
App 前端
    │ GET  /me/agent/player
    │ GET  /me/agent/rebate/current
    │ GET  /me/agent/rebate/history
    │ GET  /me/agent/descendants
    │ GET  /me/agent/descendants/{userId}
    │ GET  /me/agent/descendants/history
    │ POST /me/agent/rebate/history/export
    │ GET  /me/agent/rebate/history/export/{taskId}
    │ GET  /me/agent/rebate/history/export/{taskId}/download
    ▼
99chat-server（JWT 鉴权，按 userId 查询）
```

机器人同步接口文档：[robot-sync-api.md](./robot-sync-api.md)

---

## 14. 变更记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.1 | 2026-07-13 | 新增下级列表、下级详情、下级历史明细 3 个接口 |
| v1.0 | 2026-07-13 | 初版：6 个 `/me/agent/*` 接口，含历史 TXT/CSV 导出与异步任务 |
