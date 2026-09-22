# App 代理反水对接文档（机器码多租户）

> 版本：v1.0  
> 更新：2026-07-20  
> 适用：**99chat App**（查反水 / 历史 / 导出 / 申请结算 / 群绑定查看）  
> Windows 侧请看：[robot-windows-integration.md](./robot-windows-integration.md)  
> 字段细则可参考：[agent-rebate-client.md](./agent-rebate-client.md)

---

## 1. 一句话模型

```text
用户在某个 IM 群里查反水
 → 请求带 X-Group-Id = 当前群 ID
 → 服务端：群 → 已开群特权的机器码 → (机器码, wxid) 查数据
 → 同一人在不同机器人当代理，数据不会窜
```

**开群特权（群聊正式流程，由运营在机器人业务群发送）：**

```text
配对xxxx-xxxx-xxxx
开启@2EYHG6M5CJ
```

Windows 负责：申机器码、监听上述指令、同步流水、执行结算。  
App **不申码**；进群先查绑定状态，再决定是否显示「查反水」。

---

## 2. 环境与通用约定

| 项 | 值 |
|---|---|
| Base URL | `http://47.239.60.107:8081`（以实际为准） |
| 用户鉴权 | `Authorization: Bearer <JWT>` |
| 群隔离 | `/me/agent/**`、`/me/rebate/**` **必须** `X-Group-Id` |
| 成功包装 | `{ "code": 0, "message": "ok", "data": { } }` |
| 失败 | HTTP 非 2xx，`{ "code": "...", "message": "..." }`（一般不包装） |

`userId` = JWT `sub` = 机器人侧 `wxid`。

群 ID 常含 `#`：路径参数务必 `encodeURIComponent`（如 `@TGS#abc` → `%40TGS%23abc`）。Header `X-Group-Id` 可直接传原始群 ID。

### 必带 Header（查反水 / 申请）

```http
Authorization: Bearer <JWT>
X-Group-Id: @TGS#当前聊天群ID
Content-Type: application/json
```

| 错误 | 含义 | 建议提示 |
|------|------|----------|
| `404 GROUP_NOT_BOUND` | 尚未「配对」 | 请先在群内发送：`配对xxxx-xxxx-xxxx` |
| `403 GROUP_NOT_ENABLED` | 已配对未「开启」 | 请先在群内发送：`开启@机器人ID` |
| `404 PLAYER_NOT_FOUND` | 当前群租户下无此 wxid 档案 | — |
| `403 NOT_AGENT` | `rebateRate` 未大于 0，非代理 | — |

### 金额与比例

| 字段 | 说明 |
|------|------|
| 金额 | `BigDecimal`，通常 4 位小数 |
| `rebateRate` | **万分比**：待反水 = `remainingFlow × rebateRate ÷ 10000` |
| 级差待结算 | 用同步字段 `agentPending*`，**不要**用下级 `remainingFlow` 重算 |

代理判定：`rebateRate > 0` → `isAgent: true`。

---

## 3. 推荐前端流程

```text
进入群聊
 → GET /me/robot/groups/{groupId}
 → bound=false：
      提示开群特权第 1 步：配对xxxx-xxxx-xxxx
 → bound=true 且 enabled=false：
      提示开群特权第 2 步：开启@2EYHG6M5CJ
 → enabled=true（已开群特权）：
      GET /me/agent/player + X-Group-Id
      isAgent → 显示「查反水 / 历史」
```

开群特权文案可写死给运营/客服：

```text
开群特权请在本群依次发送：
1. 配对xxxx-xxxx-xxxx
2. 开启@2EYHG6M5CJ
```

（机器码与机器人 ID 以 Windows 本机展示为准。）

---

## 4. 群绑定状态 API（App）

只需 JWT。用于判断开群特权是否完成；**不替代群聊指令作为正式开群入口**（App bind/enable 仅联调备用）。

### `GET /me/robot/groups/{groupId}`

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

未绑定时：`bound=false`，`machineCodeMasked=null`。完整机器码**不回显**（仅掩码）。

### `POST /me/robot/groups/{groupId}/bind`

与 Windows「配对」等价（正式开群特权仍以群聊指令为准）。

```json
{"machineCode":"ABCD-EFGH-JKMN"}
```

一码多群、一群一码；冲突时可能 `409 GROUP_ALREADY_BOUND`（该群已绑其他机器码）。

### `POST /me/robot/groups/{groupId}/enable`

```json
{"robotId":"@2EYHG6M5CJ"}
```

群必须已绑定；机器码从绑定表读取，body **不用**再传机器码。

---

## 5. 反水业务 API

以下全部需要：`Authorization` + `X-Group-Id`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/me/agent/player` | 资料 + `isAgent` |
| GET | `/me/agent/rebate/current` | 当前汇总 + 个人待反水 / 级差 |
| GET | `/me/agent/rebate/history` | 历史按日（`startDate`/`endDate`） |
| GET | `/me/agent/descendants` | 下级列表（`scope`） |
| GET | `/me/agent/descendants/{userId}` | 下级详情 |
| GET | `/me/agent/descendants/history` | 下级历史 |
| GET | `/me/agent/first-level-agents` | 一级代理分组 |
| POST | `/me/agent/rebate/apply` | 申请代理级差结算 |
| GET | `/me/agent/rebate/apply/status` | 代理申请状态 |
| POST | `/me/rebate/apply` | 申请个人待反水结算 |
| GET | `/me/rebate/apply/status` | 个人申请状态 |
| POST | `/me/agent/rebate/history/export` | 提交导出 |
| GET | `/me/agent/rebate/history/export/{taskId}` | 导出任务状态 |
| GET | `/me/agent/rebate/history/export/{taskId}/download` | 下载文件流 |

### 示例：当前反水

```http
GET /me/agent/rebate/current
Authorization: Bearer <JWT>
X-Group-Id: @TGS#xxxx
```

入口控制示例：

```http
GET /me/agent/player
Authorization: Bearer <JWT>
X-Group-Id: @TGS#xxxx
```

`data.isAgent === true` 再展示查反水入口。

### 申请结算

- **个人**：`POST /me/rebate/apply` — 结本人 `remainingFlow` 待反水  
- **代理**：`POST /me/agent/rebate/apply` — 结本人级差；金额由 Windows 本地算，接口预填常为 0  

状态轮询：`.../apply/status` → `NONE` / `PENDING` / `PROCESSING` / `SUCCESS` / `FAILED`。

实际改账在 Windows：pull → 本地结算 → result → sync。App 只创建任务与展示状态。

### 历史导出

```http
POST /me/agent/rebate/history/export
Authorization: Bearer <JWT>
X-Group-Id: @TGS#xxxx
Content-Type: application/json

{
  "startDate": "2026-07-01",
  "endDate": "2026-07-07",
  "fileType": "CSV",
  "includeDetail": false
}
```

下载接口返回**文件流**（非 `{code,data}`），需带同一 JWT + `X-Group-Id`，并处理 `Content-Disposition` 文件名。

日期跨度建议 ≤ 93 天（与现网校验一致）。

---

## 6. 与旧版差异（必改）

| 旧行为 | 新行为 |
|--------|--------|
| 只带 JWT，按 wxid 全局猜一个租户 | **必须** `X-Group-Id`，按群定租户 |
| 不关心群是否配对 | 先看 `/me/robot/groups/{id}`：`bound` / `enabled` |
| 同一人多机器人可能窜数 | 按群隔离，不会窜 |

---

## 7. 联调清单

- [ ] HTTP 封装：反水相关自动加 `X-Group-Id = 当前聊天群`  
- [ ] 进群先拉绑定状态再决定入口  
- [ ] `isAgent` 控制「查反水」显示  
- [ ] 申请后轮询 status，不要假定立即到账  
- [ ] 级差展示用接口返回的 `agentPending*`，不自己重算  
- [ ] 导出下载走文件流 + JWT  

---

## 8. 相关文档

- Windows 对接：[robot-windows-integration.md](./robot-windows-integration.md)  
- 同步字段全集：[robot-sync-api.md](./robot-sync-api.md)  
- 历史字段/示例更全版：[agent-rebate-client.md](./agent-rebate-client.md)  
