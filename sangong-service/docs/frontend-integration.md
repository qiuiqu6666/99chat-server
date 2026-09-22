# 三公前端对接文档

> 适用版本：Java 17 / Spring Boot 3 多租户版  
> 对外入口：主服务统一代理 `/sangong/**`  
> 当前管理鉴权：主服务 JWT + IM 用户有效游戏特权  
> 文档路径：`sangong-service/docs/frontend-integration.md`

---

## 前端怎么接（先看这里）

整套前端可以只做成：**1 个入口判断 + 3 个页面**。不要做全局切群。

### A. 页面结构

```text
① 聊天页入口按钮「三公」
② 我的配置（仅群主）
③ 成员管理（仅群主，用来加帮工）
④ 操作台（群主 + 帮工：定庄/封盘/上下分/结算/发图）
```

### B. 请求头（所有三公管理接口）

```http
Authorization: Bearer <主服务登录JWT>
X-Tenant-Id: <当前下注群ID>
Content-Type: application/json
```

Base：

```text
SANGONG_HTTP_BASE = ${MAIN_HTTP_BASE}/sangong
完整路径示例：${SANGONG_HTTP_BASE}/api/v1/admin/session
```

禁止直连 `8088`。`X-Settings-Key` 已废弃。

### C. 入口怎么显示（聊天页）

人在某个群聊天里时：

```text
1. GET ${MAIN_HTTP_BASE}/me/game
   → 不是特权用户：不显示

2. GET ${SANGONG_HTTP_BASE}/api/v1/admin/my-config
   → configured=false：仅群主引导去「我的配置」；帮工不显示入口
   → configured=true 且 当前聊天群ID == imGroupGameId：显示「三公」
   → 当前群不是他的下注群：不显示（防管错群）

3. 点入口时：
   tenantId = 当前聊天群ID（写死，不要再让用户选群）
   带上 Authorization + X-Tenant-Id 进操作台
```

### D. 两种人分别看到什么

| | 我的配置 | 成员管理 | 操作台（含上下分） |
|---|---|---|---|
| 群主 owner | ✅ `canEditConfig=true` | ✅ `canManageMembers=true` | ✅ |
| 帮工 admin | ❌ 隐藏 | ❌ 隐藏 | ✅ |

用 `GET /admin/my-config` 返回的字段控制菜单：

```text
myRole / canEditConfig / canManageMembers
```

### E. 群主第一次配置

```http
GET /api/v1/admin/my-config
PUT /api/v1/admin/my-config
```

```json
{
  "name": "一号厅",
  "imGroupGameId": "@TGS#下注群",
  "imGroupAdminStatsId": "@TGS#报表群",
  "imGroupWaterId": "@TGS#水群",
  "imBotUserId": "rqwm8onw3j"
}
```

`imBotUserId` 填 IM 用户号，**不要加 `@`**（`@` 只用于群 ID）。

保存成功后你就是该群 owner。之后进这个下注群聊天才会出现入口。

### F. 加帮工（让别人帮你上下分）

群主在成员页（推荐，避免群 ID 含 `#` 导致路径截断）：

```http
GET    /api/v1/admin/my-config/members
POST   /api/v1/admin/my-config/members
{ "imUserId": "对方主服务用户ID", "role": "admin" }

DELETE /api/v1/admin/my-config/members/{imUserId}
```

自动使用当前账号 `my-config` 绑定的下注群，**不要**把 `@TGS#...` 拼进 URL。

若仍用旧路径，群 ID 必须编码：

```text
encodeURIComponent("@TGS#xxxx")  →  %40TGS%23xxxx
POST /api/v1/admin/tenants/%40TGS%23xxxx/access
```

对方也必须是特权用户。他进**同一个下注群**聊天后看到入口，只能进操作台，改不了配置。

### G. 操作台进页顺序

```text
GET /settings
GET /admin/events/snapshot
建立 SSE GET /admin/events/stream
idle → POST /admin/session/start
定庄 → 发庄 →（可选合庄）→ preview → submit → draws → settle → 发图
打烊 POST /admin/session/stop
随时可上下分：POST /admin/users/credit | debit
```

写成功后：用响应更新 UI → 再拉一次 snapshot → SSE 做校准。

### H. 前端最少状态

```text
jwt                 主服务登录 Token
tenantId            当前聊天群 ID（= X-Tenant-Id）
myRole              owner | admin
canEditConfig       是否显示配置页
canManageMembers    是否显示成员页
```

### I. 建议实现顺序

```text
1. HTTP 封装（Base=/sangong，自动带 JWT + X-Tenant-Id）
2. 聊天页入口判断
3. 操作台（跑局 + 上下分 + SSE）
4. 群主「我的配置」
5. 群主「成员管理」
```

先不要做：全局租户列表、切群下拉、邀请码。

---

## 0. 旧接入说明校正

以下以当前已部署代码为准：

1. Base 不再直连公网 `8088`，统一使用主服务 `/sangong`。
2. 管理接口不再使用 `X-Settings-Key`，必须携带主服务登录 JWT。
3. JWT 对应用户必须满足 `GAME_PRIVILEGE_MASTER_ENABLED=true && users.game_privileged=true`。
4. `GET /me/game` 用于展示游戏入口；管理接口仍会在服务端实时校验权限。
5. `/admin/betting/preview` 不支持 `send:true`；发送下注图使用 `/admin/reports/bet-image`。
6. `/admin/session/stop` 不要求当前局已结算；未结算局会作废并退款。
7. `/admin/reports/user-flow` 不支持 `section/scope`，只支持 `userId/imUserId/sessionId`。
8. `/admin/rounds/{id}/stats/send` 当前不存在，管理统计群账单使用 `/admin/reports/settle-bill`。
9. 群绑定用 `/admin/my-config`；帮工用 `/admin/tenants/{id}/access` 授权。

## 1. 接入方式

### 1.1 Base

```text
SANGONG_HTTP_BASE = ${MAIN_HTTP_BASE}/sangong
```

示例：

```text
MAIN_HTTP_BASE=https://api.example.com
SANGONG_HTTP_BASE=https://api.example.com/sangong
```

接口完整地址：

```text
${SANGONG_HTTP_BASE}/api/v1/settings
${SANGONG_HTTP_BASE}/api/v1/admin/session
```

前端禁止直接访问公网 `8088`。三公服务只绑定本机，由主服务转发。

### 1.2 多租户

```text
tenantId = imGroupGameId
```

除租户列表和注册外，所有业务请求携带：

```http
X-Tenant-Id: <当前游戏群ID>
```

不同租户的用户余额、局、下注、流水、规则和报表完全隔离。

租户在管理端还按账号隔离：群主用 `/admin/my-config` 自填要管的群；可授权其他特权用户为 admin 帮忙上下分（见第 9 章）。

### 1.3 管理鉴权

```http
Authorization: Bearer <主服务登录JWT>
X-Tenant-Id: <当前游戏群ID>
Content-Type: application/json
```

主服务实时校验：

```text
JWT 有效
&& 登录会话未撤销
&& 用户状态正常
&& GAME_PRIVILEGE_MASTER_ENABLED=true
&& users.game_privileged=true
```

权限不缓存，取消用户特权后下一次请求立即失效。

```text
401 UNAUTHORIZED：Token 缺失/无效、账号停用、会话撤销
403 GAME_PRIVILEGE_REQUIRED：不是有效游戏特权用户
503 PRIVILEGE_CHECK_UNAVAILABLE：主服务权限校验不可用
```

`X-Settings-Key` 已废弃，客户端不要再发送。

### 1.4 特权入口开关

```http
GET ${MAIN_HTTP_BASE}/me/game
Authorization: Bearer <主服务登录JWT>
```

客户端可继续由 `GroupGameApi` 调用该接口控制入口展示，但不能把本地结果当成管理权限；服务端仍会强制校验。

### 1.5 客户端结构

```text
SangongGameHttp
├── SangongSettingsApi
├── SangongAdminApi
└── SangongPlayerApi
```

`SangongGameHttp` 负责：

- Base 指向主服务 `/sangong`。
- 添加主服务 `Authorization`。
- 添加当前 `X-Tenant-Id`。
- 统一错误解包。
- 普通接口超时 15～30 秒。
- 图片报表超时 60 秒。
- SSE 不设置总读取超时。

示例：

```ts
const BASE = `${MAIN_HTTP_BASE}/sangong/api/v1`;

async function sangongAdminRequest<T>(
  path: string,
  init: RequestInit = {},
  tenantRequired = true,
): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${authStore.accessToken}`,
  };
  if (tenantRequired) {
    headers["X-Tenant-Id"] = sangongStore.tenantId;
  }

  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { ...headers, ...init.headers },
  });
  const body = await response.json();
  if (!response.ok || body?.ok === false) throw body;
  return body as T;
}
```

## 2. 规则设置

### 2.1 读取规则

```http
GET /api/v1/settings
X-Tenant-Id: <tenantId>
```

读取不要求游戏特权，但必须指定租户。

```json
{
  "ok": true,
  "settings": {
    "doorCount": 6,
    "minBet": 10,
    "maxBet": 10000,
    "points": [
      {
        "point": 0,
        "label": "0点",
        "odds": 1,
        "bankerRakePoints": 0,
        "playerRakePoints": 0
      }
    ],
    "pair": {
      "label": "对子",
      "odds": 2,
      "bankerRakePoints": 0,
      "playerRakePoints": 0
    },
    "maxHand": {
      "label": "1.00",
      "odds": 3,
      "bankerRakePoints": 0,
      "playerRakePoints": 0
    },
    "imGroupGameId": "@TGS#GAME",
    "imGroupAdminStatsId": "@TGS#ADMIN",
    "imBotUserId": "bot_sangong"
  }
}
```

### 2.2 写规则

```http
PUT /api/v1/settings
Authorization: Bearer <主服务特权用户JWT>
X-Tenant-Id: <tenantId>
```

支持部分更新：

```json
{
  "doorCount": 6,
  "minBet": 10,
  "maxBet": 10000,
  "points": [
    {
      "point": 0,
      "odds": 1,
      "bankerRakePoints": 0,
      "playerRakePoints": 0
    }
  ],
  "pair": {
    "odds": 2,
    "bankerRakePoints": 0,
    "playerRakePoints": 0
  },
  "maxHand": {
    "odds": 3,
    "bankerRakePoints": 0,
    "playerRakePoints": 0
  },
  "imGroupGameId": "@TGS#GAME",
  "imGroupAdminStatsId": "@TGS#ADMIN",
  "imBotUserId": "bot_sangong"
}
```

校验：

```text
doorCount：2～10
minBet/maxBet：非负整数
maxBet > 0 时不能小于 minBet
point：0～9
odds：0.01～100
抽水点：0～100
```

## 3. 会话与实时

### 3.1 当前会话

```http
GET /api/v1/admin/session
```

```json
{
  "ok": true,
  "status": "running",
  "session": {
    "id": 10,
    "status": "running",
    "periodNo": 3,
    "currentRoundId": 88,
    "startedAt": "2026-07-18T16:00:00+07:00"
  },
  "round": {}
}
```

未开机时 `status=idle`，`session/round=null`。

### 3.2 开机

```http
POST /api/v1/admin/session/start
```

- 创建新 session。
- 期数从 1 开始。
- 自动创建第一局。
- 第一局状态为 `await_banker`。

成功 HTTP `201`。

### 3.3 关机

```http
POST /api/v1/admin/session/stop
```

- 未结算当前局会自动作废并退款。
- 有已结算局时会发送最终管理账单。
- session 变为 `idle`。

前端应在未结算时二次确认。

### 3.4 实时快照

```http
GET /api/v1/admin/events/snapshot
```

```json
{
  "ok": true,
  "state": {
    "version": 20,
    "at": "2026-07-18T16:20:00+07:00",
    "status": "running",
    "session": {},
    "round": {},
    "settings": {
      "doorCount": 6,
      "minBet": 10,
      "maxBet": 10000
    },
    "draw": null,
    "pending": {
      "open": true,
      "messageCount": 2,
      "doorTotals": { "1": 100, "2": 0 },
      "grandTotal": 100
    },
    "placed": {
      "doorTotals": { "1": 200, "2": 0 },
      "grandTotal": 200,
      "betCount": 1
    }
  }
}
```

- `pending`：IM 消息已识别、尚未正式落注。
- `placed`：已扣款并正式落注。
- `draw`：封盘后才返回。
- 门号作为 JSON key 时是字符串。

### 3.5 SSE

```http
GET /api/v1/admin/events/stream
Accept: text/event-stream
Authorization: Bearer <主服务特权用户JWT>
X-Tenant-Id: <tenantId>
```

```text
: connected

event: state
data: {完整 state}

: heartbeat
```

浏览器原生 `EventSource` 无法设置 `Authorization`，必须使用 fetch-based SSE 客户端。

初始化：

```text
GET snapshot → 渲染 → 建立 SSE → 首个 state 覆盖本地快照
```

重连：`1s → 2s → 5s → 10s → 30s`。

SSE 不是严格的每次写操作都推送。写接口成功后先用响应更新 UI，再主动刷新 snapshot。

## 4. 定庄与合庄

### 4.1 设置门数或定庄

```http
POST /api/v1/admin/banker/setup
```

只设置门数：

```json
{ "door": 6 }
```

或：

```json
{ "doorCount": 6 }
```

完整定庄：

```json
{
  "imUserId": "100001",
  "nickname": "庄家",
  "door": 4,
  "limit": 10000
}
```

也可传三公内部 `userId`；`amount` 兼容作为 `limit`。

### 4.2 发送定庄通知并开窗

```http
POST /api/v1/admin/banker/send
```

- 发送游戏群通知。
- 设置 `betWindowOpenAt`。
- 清理旧 pending。
- 本局已有游戏数据时可能推倒重开，返回 `restarted=true`。

### 4.3 快速定庄

```http
POST /api/v1/admin/banker/quick-setup
```

```json
{ "messageId": 9001 }
```

或：

```json
{
  "imUserId": "100001",
  "nickname": "庄家",
  "text": "4.9999"
}
```

支持 `4`、`4.9999`、`4.5万`、`4；10000`、`4（10000）`、`4/99999`。

快速定庄会完成解析、定庄、通知和开窗。

### 4.4 添加合庄

```http
POST /api/v1/admin/rounds/current/co-bank
POST /api/v1/admin/rounds/{roundId}/co-bank
```

```json
{
  "userId": 101,
  "amount": 5000
}
```

- 使用三公内部 `userId`。
- 金额只用于占股，不在添加时扣余额。
- 用户必须余额充足。
- 已下注用户不能合庄。
- 主庄不能重复合庄。

### 4.5 发送合庄通知

```http
POST /api/v1/admin/co-bank/send
```

### 4.6 移除合庄

```http
POST /api/v1/admin/rounds/current/co-bank/remove
```

```json
{ "userId": 101 }
```

也支持 `/admin/co-bank/remove` 和 `/admin/rounds/{roundId}/co-bank/remove`。

不要优先使用带 Body 的 DELETE 别名，部分客户端会丢弃 DELETE Body。

### 4.7 关闭合庄

```http
POST /api/v1/admin/rounds/{roundId}/co-bank/close
```

## 5. 下注截止

支持：

```text
POST /api/v1/admin/betting/preview
POST /api/v1/admin/rounds/current/betting/preview
POST /api/v1/admin/rounds/{roundId}/betting/preview

POST /api/v1/admin/betting/submit
POST /api/v1/admin/rounds/current/betting/submit
POST /api/v1/admin/rounds/{roundId}/betting/submit
```

### 5.1 Cutoff Body

```ts
interface SangongBetSubmitCutoff {
  untilMessageId?: number;
  untilMsgSeq?: number;
  excludeMessageId?: number;
  excludeMessageIds?: number[];
}
```

- `{}`：截止到本局当前最新有效 IM 消息。
- `untilMessageId`：按三公消息表主键截止，推荐优先使用。
- `untilMsgSeq`：按腾讯 IM MsgSeq 截止。
- 两者同时传时由服务端解析，不能做数值大小比较。
- `excludeMessageId(s)`：排除消息。

### 5.2 预览

```http
POST /api/v1/admin/betting/preview

{}
```

预览不扣款、不落注、不关窗，返回统计清单和截止信息。

```json
{
  "ok": true,
  "preview": {
    "roundId": 88,
    "periodNo": 3,
    "cutoffMessageId": 9000,
    "cutoffMsgSeq": 12345,
    "excludeMessageIds": [],
    "pendingMessageCount": 8,
    "excludedAfterCutoff": 0,
    "isRecutoff": false,
    "report": {}
  },
  "round": {}
}
```

当前不支持 `send:true`。发送预览下注图调用：

```http
POST /api/v1/admin/reports/bet-image
```

### 5.3 正式截止落注

```http
POST /api/v1/admin/betting/submit

{}
```

- 固定截止点并关窗。
- 截止点后消息标记忽略。
- 排除指定消息。
- 批量校验、扣款和落注。
- 返回成功和失败消息列表。

成功后使用响应 `round` 更新页面，并刷新 snapshot。

## 6. 开彩、结算与冲正

### 6.1 查询开彩

```http
GET /api/v1/admin/rounds/current/draws
GET /api/v1/admin/rounds/{roundId}/draws
```

```json
{
  "ok": true,
  "draw": {
    "roundId": 88,
    "periodNo": 3,
    "doorCount": 6,
    "bankerDoor": 4,
    "drawLockedAt": null,
    "requiredDoors": [1, 2, 3, 4, 5, 6],
    "missingDoors": [5, 6],
    "complete": false,
    "draws": []
  },
  "round": {}
}
```

### 6.2 批量录入

```http
POST /api/v1/admin/draws
```

```json
{
  "draws": [
    { "door": 1, "amount": "0.88" },
    { "door": 2, "amount": "0.90" },
    { "door": 3, "amount": "1.00" },
    { "door": 4, "amount": "0.66" },
    { "door": 5, "amount": "0.73" },
    { "door": 6, "amount": "0.55" }
  ]
}
```

`amount` 是牌面字符串，不是下注积分：

```text
"90" → 0.90
"00" → 1.00
"1"/"1.0"/"1.00" → 1.00
有效范围：0.01～0.99 或 1.00
```

必须先封盘，所有门（含庄门）都必须录入。

### 6.3 结算

```http
POST /api/v1/admin/rounds/{roundId}/settle
```

要求已定庄、已封盘、开彩完整且尚未结算。

当前结算为 1:1：下注时本金已扣；赢时返还 `2 × bet`；输时不再额外扣款。前端不要按设置中的牌型赔率自行预测入账。

### 6.4 冲正

```http
POST /api/v1/admin/rounds/{roundId}/void-settlement
```

- 回滚结算账务。
- 清空开彩。
- 保留下注和合庄。
- 保留封盘状态，不能继续下注。
- 下一局已开始时禁止冲正。

### 6.5 一步冲正重结

```http
POST /api/v1/admin/rounds/{roundId}/resettle
```

必须携带完整新 `draws`。这是 UI 推荐路径，必须二次确认。

## 7. 用户积分、分组与流水

### 7.1 用户列表

```http
GET /api/v1/admin/reports/users
GET /api/v1/admin/reports/users?groupId=12
GET /api/v1/admin/reports/users?groupId=0
```

`groupId=0` 表示未分组。

### 7.2 上分

```http
POST /api/v1/admin/users/credit
```

```json
{
  "imUserId": "100001",
  "nickname": "玩家A",
  "amount": 1000,
  "operator": "admin",
  "note": "人工上分"
}
```

也可传三公内部 `userId`。

### 7.3 下分

```http
POST /api/v1/admin/users/debit
```

Body 同上分。请求 `amount` 始终传正整数，接口决定方向。

### 7.4 用户分组

```http
PUT /api/v1/admin/users/group
```

```json
{
  "imUserId": "100001",
  "group": "A"
}
```

取消：

```json
{
  "imUserId": "100001",
  "group": "0"
}
```

不存在的非零编号会自动创建分组。

### 7.5 流水

```http
GET /api/v1/admin/reports/user-flow
```

支持 Query：

```text
userId
imUserId
sessionId
```

示例：

```text
/api/v1/admin/reports/user-flow?imUserId=100001&sessionId=10
```

当前不支持 `section/scope`。最多返回 500 条，按 `ledgerId` 倒序。

## 8. 报表群发

执行链路：

```text
内存生成 JPG → 上传 OSS → 腾讯 IM 发送 → 返回 OSS URL
```

图片不落本地，默认清理 7 天前 OSS 文件。报表请求超时建议 60 秒。

### 8.1 结算明细图

```http
POST /api/v1/admin/reports/settle-image
```

发送到游戏群，可选 `{ "roundId": 88 }`，只能发送已结算局。

### 8.2 管理结算账单

```http
POST /api/v1/admin/reports/settle-bill
```

发送到 `imGroupAdminStatsId`，可选 `{ "roundId": 88 }`。

该接口替代不存在的 `/rounds/{id}/stats/send`。

### 8.3 用户积分图

```http
POST /api/v1/admin/reports/users/points-image
```

```json
{
  "groupId": 12,
  "imGroupId": "@TGS#TARGET"
}
```

- `groupId` 可选，`0` 表示未分组。
- `imGroupId` 可选，默认当前游戏群。

### 8.4 走势图

```http
POST /api/v1/admin/reports/trend-image
```

发送当前 session 走势到游戏群。

### 8.5 下注清单图

```http
POST /api/v1/admin/reports/bet-image
```

```json
{
  "roundId": 88,
  "untilMessageId": 9000,
  "excludeMessageIds": [8991]
}
```

- 开窗时：`mode=preview`，不封盘。
- 已封盘/结算时：`mode=formal`。

### 8.6 一键预览

```http
POST /api/v1/admin/reports/preview-images/send
```

尝试发送下注图、结算图、积分图和走势图。

成功通常返回：

```json
{
  "ok": true,
  "sent": true,
  "imGroupId": "@TGS#GAME",
  "url": "https://oss.example.com/sangong/bet-reports/...jpg"
}
```

```text
422：NO_SESSION/NO_ROUND/NO_DATA/NO_USERS/NO_IM_GROUP
500：OSS_UPLOAD_FAILED/IMAGE_FAILED
502：IM_SEND_FAILED
```

## 9. 租户管理（我的配置 + 帮工授权）

### 9.0 推荐用法（最简单）

```text
群主：GET/PUT /api/v1/admin/my-config 自填下注群、报表群、机器人号
帮工：群主 POST /admin/my-config/members 加 admin（推荐；勿把含 # 的群 ID 直接拼进 URL）
入口：当前聊天群 ID == 配置的 imGroupGameId，且账号是成员 → 才显示
操作：X-Tenant-Id 固定为该下注群，不要做全局切群
```

角色：

```text
owner（群主）：改配置、加人/踢人、上下分、定庄、封盘等全部业务
admin（帮工）：只能做业务（含上下分），不能改配置、不能管成员
```

### 9.1 我的配置

不要求 `X-Tenant-Id`，只要特权 JWT：

```text
GET  /api/v1/admin/my-config
PUT  /api/v1/admin/my-config
GET  /api/v1/admin/my-config/members
POST /api/v1/admin/my-config/members
DELETE /api/v1/admin/my-config/members/{imUserId}
```

保存 body：

```json
{
  "name": "一号厅",
  "imGroupGameId": "@TGS#GAME_A",
  "imGroupAdminStatsId": "@TGS#REPORT_A",
  "imGroupWaterId": "@TGS#WATER_A",
            "imBotUserId": "rqwm8onw3j"
}
```

注意：`imBotUserId` 是 **IM 用户号**，不要加 `@`。群 ID 才是 `@TGS#...`。误填 `@rqwm8onw3j` 会导致发图/发消息失败（`From_Account invalid`）。

```text
新群 / 未认领 → 创建或认领，当前账号成为 owner，并设为默认群
已是 owner → 更新报表群/水群/机器人号
别人已管 / 自己只是 admin → 403 TENANT_ACCESS_DENIED
```

响应关键字段：

```text
configured        是否已绑定群
tenantId          当前默认游戏群
imGroupGameId     下注群
imGroupAdminStatsId  报表群
imGroupWaterId    水群（可空）
imBotUserId       机器人 IM 号
myRole            owner | admin
canEditConfig     是否可改配置（仅 owner）
canManageMembers  是否可管成员（仅 owner）
```

未配置时：

```json
{ "ok": true, "configured": false }
```

### 9.2 租户列表（高级）

以下接口要求特权 JWT，但不要求 `X-Tenant-Id`：

```text
GET  /api/v1/admin/tenants
GET  /api/v1/admin/tenants/{tenantId}
POST /api/v1/admin/tenants
PUT  /api/v1/admin/tenants/{tenantId}
```

`PUT` 仅 owner 可改群配置。创建：

```json
{
  "name": "二号厅",
  "imGroupGameId": "@TGS#GAME_B",
  "imGroupAdminStatsId": "@TGS#ADMIN_B",
  "imGroupLedgerId": "@TGS#LEDGER_B",
  "imGroupWaterId": "@TGS#WATER_B",
  "imBotUserId": "bot_b"
}
```

创建后 `tenantId=imGroupGameId`，创建者自动成为该群 `owner`。

`GET /admin/tenants` 只返回当前账号可见的游戏群，每行含：

```text
myRole      owner | admin | null（未认领）
isDefault   是否为当前账号默认游戏群
unclaimed   true 表示还没有任何账号认领（存量数据）
imGroupWaterId  水群 IM 群 ID（可为空）
```

### 9.3 帮工授权（多人上下分）

规则：

```text
- 每个游戏群有独立成员列表（sangong_tenant_access）。
- 账号只能列出/读取/操作自己是成员的游戏群。
- 非成员访问返回 403 TENANT_ACCESS_DENIED（包括所有带 X-Tenant-Id 的管理请求）。
- 未认领（无任何成员）的存量游戏群对所有特权账号开放，任何账号可先认领。
- owner 才能管理成员；不能移除/降级最后一个 owner。
- admin 可上下分、定庄、结算等，不可改 my-config / PUT tenants。
```

接口（均要求特权 JWT）：

```text
# 推荐：不经过路径里的群 ID
GET    /api/v1/admin/my-config/members
POST   /api/v1/admin/my-config/members              body: {imUserId, role}
DELETE /api/v1/admin/my-config/members/{imUserId}

# 高级：路径必须 encodeURIComponent(tenantId)
POST   /api/v1/admin/tenants/{tenantId}/claim
GET    /api/v1/admin/tenants/{tenantId}/access
POST   /api/v1/admin/tenants/{tenantId}/access
DELETE /api/v1/admin/tenants/{tenantId}/access/{imUserId}
PUT    /api/v1/admin/tenants/{tenantId}/default
```

注意：腾讯群 ID 形如 `@TGS#xxx`，URL 里的 `#` 会被浏览器当成锚点截断，直接拼路径会报 `TENANT_NOT_FOUND`。优先用 `/my-config/members`。

错误：

```text
403 TENANT_ACCESS_DENIED：无权操作此游戏群 / 仅 owner 可管理成员 / 已被认领
400 INVALID_INPUT：imUserId 缺失等
```

### 9.4 切换租户

切换租户时（一般不需要；推荐从当前聊天群锁定）：

1. 关闭旧 SSE。
2. 清空旧状态。
3. 更新 `X-Tenant-Id`。
4. 获取 settings 和 snapshot。
5. 建立新 SSE。

## 10. 局状态与 UI

```text
await_banker
await_banker_door
betting
co_bank_closed
settled
voided
```

推荐判断：

```text
等待定庄：status == await_banker
等待庄门：status == await_banker_door
已定庄未开窗：bankerUserId != null && betWindowOpenAt == null
下注中：betWindowOpenAt != null && betWindowCloseAt == null
已封盘待开彩：betWindowCloseAt != null && drawLockedAt == null
开彩中：drawLockedAt != null && draw.complete == false
可结算：draw.complete == true && status != settled
已结算：status == settled
已作废：status == voided
```

## 11. 时间与金额

时间使用带偏移的 ISO 字符串，例如：

```text
2026-07-18T16:20:00+07:00
```

不要假设固定 UTC 或 `+08:00`。IM `*MsgTime` 字段为 Unix 秒，转 JavaScript 时间乘 `1000`；`untilMsgSeq` 不是时间。

除开彩外，业务金额均为整数积分：

```text
balance / amount / minBet / maxBet / bankerLimit
poolTotal / doorTotal / grandTotal
```

- 不使用浮点金额。
- 不代表人民币分。
- TypeScript 校验 `Number.isSafeInteger`。
- 上下分请求 `amount` 始终为正整数。
- 账本返回 `amount` 是有符号 delta。

## 12. 推荐完整流程

```text
1. 主服务登录
2. GET /me/game 检查入口
3. GET /admin/my-config；未配置则 PUT 填写下注群/报表群/机器人
4. 需要帮工时 POST /admin/my-config/members 加 admin
5. GET /settings
6. GET /admin/events/snapshot
7. 建立 SSE
8. idle 时 POST /admin/session/start
9. POST /admin/banker/setup 或 quick-setup
10. POST /admin/banker/send
11. 可选合庄
12. 观察 pending
13. POST /admin/betting/preview
14. 确认截止与排除消息
15. POST /admin/betting/submit
16. POST /admin/draws
17. 确认 draw.complete=true
18. POST /admin/rounds/{id}/settle
19. 发送 settle-image/trend-image
20. 下一局重新定庄
21. 营业结束 POST /admin/session/stop
```

每次写成功：

```text
用响应更新 UI → 主动刷新 snapshot → SSE 后续校准
```

## 13. 错误处理

```json
{
  "ok": false,
  "code": "ERROR_CODE",
  "message": "中文错误说明"
}
```

```text
400 TENANT_REQUIRED：返回租户选择
401 UNAUTHORIZED：重新登录
403 GAME_PRIVILEGE_REQUIRED：退出三公后台
403 TENANT_ACCESS_DENIED：该账号无权操作此游戏群，返回租户选择
404 TENANT_NOT_FOUND：刷新租户
422：展示 message，并刷新 snapshot
502：代理/IM 下游异常，允许重试
503 PRIVILEGE_CHECK_UNAVAILABLE：稍后重试
```

## 14. 联调检查

- Base 只使用主服务 `/sangong`。
- 不再配置公网 `8088` Base。
- 不发送 `X-Settings-Key`。
- 管理请求携带主服务 JWT。
- 非特权用户得到 `403`。
- 群主用 `/admin/my-config` 配置；帮工由群主 `/access` 授权。
- 所有租户业务请求带 `X-Tenant-Id`（建议等于当前聊天群）。
- 切租户会关闭旧 SSE。
- SSE 使用支持 Header 的 fetch 客户端。
- 写操作后刷新 snapshot。
- preview 不传 `send:true`。
- user-flow 不传 `section/scope`。
- 不调用 `/rounds/{id}/stats/send`。
- 报表超时 60 秒。

## 15. 不给前端调用

```text
POST /api/v1/im/callback
POST /integration/v1/users/profiles
POST /integration/v1/users/game-privilege/check
POST /integration/v1/im/messages/recall
POST /integration/v1/oss/report-images
```

IM 发言和撤回由 Kafka 自动投递，前端不实现回调。

旧 Token 接口：

```http
POST /api/v1/auth/token
```

固定返回 HTTP `410 ENDPOINT_GONE`。
