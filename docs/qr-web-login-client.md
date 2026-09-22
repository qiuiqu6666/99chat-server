# Web 扫码登录 — 前端对接文档

> 版本：v1  
> 日期：2026-08-02  
> 状态：**后端已实现**（99chat-server）  
> 适用：99chat Flutter（Web 出码 + App 扫码确认）  
> 关联：`auth_api.dart`（QrLogin*）、`login.dart`、`QrWebLoginConfirmPage`、`qr_web_login_payload.dart`  
> **App 专用完整说明**：[qr-web-login-app.md](./qr-web-login-app.md)（鉴权白名单、假「验证已过期」、确认页）

---

## 1. 产品流程

```
Web 匿名创建会话 → 展示二维码
       ↓
已登录 App 扫码 → 二次确认页（禁止扫到即授权）
       ↓
用户点确认 → Web 轮询拿到 TokenResult → 进首页
用户点取消 / 超时 → Web 提示并可刷新二维码
```

| 端 | 职责 |
|----|------|
| **Web** | 创建会话、画码、约 2s 轮询、拿 token 登录 |
| **App** | 识别 `type=web_login`、调 scan、展示确认页、调 confirm |
| **未登录手机** | 不引导登录后再确认（产品非目标） |

---

## 2. 状态机

```
pending ──(App scan)──► scanned ──(approve=true)──► confirmed
                │                      │
                │                      └──(approve=false)──► cancelled
                │
                └──(TTL)──► expired
```

| status | 含义 | Web UI | App |
|--------|------|--------|-----|
| `pending` | 已出码，未扫 | 「等待扫码」 | — |
| `scanned` | 已扫，待确认 | 「已扫码，请在手机上确认」 | 确认页 |
| `confirmed` | 已确认 | 取 token，停轮询，进首页 | Toast 后返回 |
| `cancelled` | 用户取消 | 提示取消，可刷新码 | Toast 后返回 |
| `expired` | 过期/无效 | 提示过期，可刷新码 | confirm 应失败 |

**TTL**：默认 **120 秒**（创建响应 `expiresIn`；缺省客户端可回退 120）。

---

## 3. 二维码载荷

Web **必须**用创建接口返回的 `qrPayload` **原样**画码，不要本地拼装。

解码后 JSON：

```json
{
  "type": "web_login",
  "sessionId": "<uuid>",
  "v": 1
}
```

| 字段 | 要求 |
|------|------|
| `type` | 必须为 `web_login`（与个人/群码 `user`/`group` 区分） |
| `sessionId` | 非空，交给 scan/confirm |
| `v` | 当前为 `1` |

---

## 4. 通用约定

- Base path：与现有 App API 相同（无额外前缀）。
- JSON：camelCase；成功体**直接**为业务字段（可无 `data` 包裹；若网关有解包，客户端 `_authMap` 仍兼容）。
- 错误体：`{ "code": "<REASON>", "message": "..." }`（与现有 `/auth/login/*` 一致）。
- 建议 Header（与登录一致，可选）：
  - `X-Client-Platform`
  - `X-Client-Version`
  - `X-Device-Model`（body 有 `deviceModel` 时以后者为准）

---

## 5. API

### 5.1 创建会话（Web，匿名）

`POST /auth/login/qr/session`

**请求**

```json
{
  "deviceId": "<web-device-id>",
  "deviceModel": "<optional>"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `deviceId` | 是 | Web 设备 ID；确认后 token 绑定此设备 |
| `deviceModel` | 否 | 设备型号 |

**响应 200**

```json
{
  "sessionId": "a1b2c3d4-...",
  "qrPayload": "{\"type\":\"web_login\",\"sessionId\":\"a1b2c3d4-...\",\"v\":1}",
  "expiresIn": 120
}
```

| 字段 | 说明 |
|------|------|
| `sessionId` | 会话 ID，用于轮询 |
| `qrPayload` | 二维码字符串，原样绘制 |
| `expiresIn` | 秒；用于倒计时 / 超时 UI |

**客户端注意**

- `deviceId` 为空会 `400` / `INVALID_DEVICE_ID`。
- 刷新二维码 = 重新调本接口（旧 session 自然过期即可）。

---

### 5.2 轮询（Web，匿名）

`GET /auth/login/qr/session/{sessionId}`

建议间隔：**2 秒**；`confirmed` / `cancelled` / `expired` 后停止。

**未确认（含取消/过期）— HTTP 始终 200**

```json
{
  "status": "pending"
}
```

`status` 还可能为：`scanned` | `cancelled` | `expired`。

未确认态**不会**带 `token` / `userId` / `expiresIn` / `nextStep`（字段直接省略，避免把 `expiresIn: null` 误当成二维码 TTL）。
`displayHint` 当前亦省略（恒为无）。

> **重要**：无效 / 不存在的 `sessionId` 也返回 **200 + `status: "expired"`**，**不会** 404。  
> 请勿把「poll 404」当成会话无效；客户端若把 404 当成「功能未开放」，仅适用于路径未部署等场景。

**已确认 — 200，带登录态字段**

```json
{
  "status": "confirmed",
  "token": "<access-token>",
  "userId": "<user-id>",
  "expiresIn": 604800,
  "nextStep": "OK",
  "displayHint": null
}
```

| 字段 | 说明 |
|------|------|
| `token` | 与密码/短信登录成功相同的 access token |
| `userId` | 平台用户 ID |
| `expiresIn` | **token** 有效期（秒），不是二维码 TTL |
| `nextStep` | 固定 `"OK"` |

**客户端注意**

- `confirmed` 后 TTL 内可重复 poll，返回**同一** token；拿到后应立刻停轮询并走现有 `TokenResult` / `loginWithQrTokenResult`。
- `scanned` 阶段**不会**返回手机号等用户信息。
- `displayHint` 当前恒为 `null`，可忽略。

**Web 状态 → UI 建议**

| status | 动作 |
|--------|------|
| `pending` | 等待扫码 |
| `scanned` | 提示手机确认 |
| `confirmed` | 登录进首页，停轮询 |
| `cancelled` | 提示已取消，提供「刷新二维码」 |
| `expired` | 提示已过期，提供「刷新二维码」 |

---

### 5.3 扫码登记（App，需登录）

`POST /auth/login/qr/scan`

**Header**：`Authorization: Bearer <app-token>`（必填；否则 401）

**请求**

```json
{ "sessionId": "…" }
```

**响应 200**

```json
{
  "sessionId": "…",
  "status": "scanned",
  "siteLabel": "网页版登录"
}
```

| 字段 | 说明 |
|------|------|
| `siteLabel` | 确认页展示文案（当前固定「网页版登录」） |

**行为**

| 场景 | 结果 |
|------|------|
| `pending` → 首次扫 | 200，绑定当前用户 |
| 同用户再次扫（幂等） | 200，`scanned` |
| 另一用户扫已绑定会话 | `409` / `QR_SESSION_BOUND` |
| 过期 / 已确认 / 已取消 / 不存在 | `409` / `QR_SESSION_EXPIRED` |

**客户端注意**

- 未登录用户扫到 `web_login`：不要调本接口；产品不做「先登录再确认」。
- 扫码成功后进入二次确认页，**不要**在 scan 成功时直接调 confirm。

---

### 5.4 确认 / 取消（App，需登录）

`POST /auth/login/qr/confirm`

**Header**：`Authorization: Bearer <app-token>`

**请求**

```json
{
  "sessionId": "…",
  "approve": true
}
```

| 字段 | 说明 |
|------|------|
| `approve` | `true` 确认登录；`false` 取消 |

**响应 200**

```json
{
  "sessionId": "…",
  "status": "confirmed"
}
```

取消时 `status` 为 `"cancelled"`。

**错误**

| HTTP | code | 场景 |
|------|------|------|
| 401 | （未登录） | 无/无效 Bearer |
| 403 | `QR_SCANNER_MISMATCH` | 确认者 ≠ 扫描者 |
| 403 | `ACCOUNT_DISABLED` | 账号禁用 |
| 403 | `DEVICE_BANNED` | Web 设备被封禁 |
| 409 | `QR_SESSION_EXPIRED` | 不存在或已过期 |
| 409 | `QR_SESSION_INVALID_STATUS` | 非 `scanned`（如仍 pending、已 confirmed） |

**客户端注意**

- 必须先 scan 再 confirm；不能 pending 直接 confirm。
- 确认成功后 Web 侧靠轮询拿 token；App 只需 Toast 并返回即可。
- 确认会按现有登录管线为 **Web 的 deviceId** 登记设备并签发会话（含平台会话互踢）；**不会**再走密码登录的 `NEED_SMS`。

---

## 6. 错误码速查（扫码登录专用）

| code | HTTP | 端 | 建议文案 |
|------|------|----|----------|
| `INVALID_DEVICE_ID` | 400 | Web create | 设备信息无效 |
| `QR_SESSION_EXPIRED` | 409 | App | 二维码已失效，请让网页刷新 |
| `QR_SESSION_BOUND` | 409 | App | 该二维码已被其他账号扫描 |
| `QR_SESSION_INVALID_STATUS` | 409 | App | 状态异常，请刷新二维码重试 |
| `QR_SCANNER_MISMATCH` | 403 | App | 请使用扫码的账号确认 |
| `DEVICE_BANNED` | 403 | App | 设备不可用 |
| `ACCOUNT_DISABLED` | 403 | App | 账号已禁用 |

整站路径未部署时，create/poll 可能 **404** → 可提示「扫码登录暂不可用」（与「会话 expired」区分）。

---

## 7. 推荐时序

### Web

1. `POST /auth/login/qr/session` → 保存 `sessionId`、`expiresIn`，用 `qrPayload` 画码。  
2. 每 2s：`GET /auth/login/qr/session/{sessionId}`。  
3. `scanned` → 更新文案。  
4. `confirmed` → `TokenResult.fromJson` → `loginWithQrTokenResult` → 停轮询。  
5. `cancelled` / `expired` → 停轮询，提供刷新（回到步骤 1）。  
6. 本地倒计时可用 `expiresIn`；到点也可主动当 expired 处理并刷新。

### App

1. 扫码解析 JSON，`type == web_login` 且 `sessionId` 非空。  
2. 已登录：`POST /auth/login/qr/scan`。  
3. 进入确认页，展示 `siteLabel`。  
4. 确认：`approve: true`；取消：`approve: false`。  
5. 成功 Toast 后 pop；失败按 §6 提示。

---

## 8. 与现有登录的关系

| 项 | 说明 |
|----|------|
| Token | 与 `/auth/login/password` 成功态同构：`token` + `userId` + `expiresIn` + `nextStep=OK` |
| 进首页 | 复用现有登录协调器即可，无需新会话模型 |
| 短信/密码登录 | 不被替换，扫码为增量能力 |
| 个人/群二维码 | 互不影响（靠 `type` 分流） |

---

## 9. 联调验收清单

- [ ] Web 创建 → 出码 → App 扫码 → 确认页出现 `siteLabel`
- [ ] 确认后 Web ≤2s 内登录成功进首页
- [ ] 取消后 Web 显示取消，刷新可重新出码
- [ ] 超时后双方符合 `expired`
- [ ] 无效 sessionId 的 poll 为 200 + `expired`（不是 404）
- [ ] 同用户重复 scan 幂等成功
- [ ] 另一用户 scan 已绑定会话 → `QR_SESSION_BOUND`
- [ ] 未登录调 scan/confirm → 401
- [ ] 个人/群二维码路径不受影响
- [ ] 后端未部署时 404 → 「扫码登录暂不可用」

---

## 10. 后端实现索引（便于联调）

| 组件 | 路径 |
|------|------|
| Controller | `com.chat99.server.auth.QrLoginController` |
| Service | `com.chat99.server.auth.QrLoginService` |
| Redis | key `auth:qr-login:{sessionId}` |
| 配置 | `chat99.qr-login.ttl-seconds` / `site-label`（环境变量 `QR_LOGIN_TTL_SECONDS`） |

**上线注意**：需重启 99chat-server 加载新接口；鉴权已区分匿名 create/poll 与需登录的 scan/confirm。
