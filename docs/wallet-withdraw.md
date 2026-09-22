# USDT 链上提现 — 完整对接文档

> 版本：v1.0  
> 适用：99chat 客户端（Flutter）及运营后台  
> Base URL：`http://47.239.60.107:8081`（以部署为准）  
> 相关：[wallet-client.md](./wallet-client.md)（钱包总览）、[wallet.md](./wallet.md)（模块与运维）

---

## 1. 能力说明

| 项目 | 说明 |
|------|------|
| 可提现币种 | **仅 USDT**（账本 `usdtMicro`，TRC20） |
| 不可提现 | 平台币 `99`（`platformFen`） |
| 链类型 | TRON 主网，合约默认 `TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t` |
| 流程 | 用户提交 → **立即扣账本** → 后台定时任务热钱包广播 → 链上到账 |
| 手续费 | 从用户余额**另扣**，链上打款金额 = 用户填写的 `amountMicro`（`payoutMicro`） |

---

## 2. 金额与字段约定

| 字段 | 单位 | 示例 |
|------|------|------|
| `amountMicro` | USDT，6 位小数 | `5000000` = 5 USDT |
| `feeMicro` | USDT，6 位小数 | `1000000` = 1 USDT |
| `payoutMicro` | 链上实际转出 | 通常等于 `amountMicro` |
| `minWithdrawUsdtMicro` | 最低提现额（micro） | `1000000` = 1 USDT |

**扣款公式（提交时）：**

```
totalDebit = amountMicro + feeMicro
```

用户余额需满足：`balanceUsdtMicro >= totalDebit`。

---

## 3. 用户端 API（JWT）

鉴权：

```http
Authorization: Bearer <token>
Content-Type: application/json
```

### 3.1 钱包首页（提现前必读）

#### `GET /wallet/me`

用于展示余额、是否已设支付密码。

**200 节选**

```json
{
  "depositAddress": "TXYZ...",
  "usdtContract": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
  "minDepositUsdt": "1",
  "balances": {
    "usdtMicro": 2500000,
    "platformFen": 128800
  },
  "payPinSet": true
}
```

| 字段 | 提现相关 |
|------|----------|
| `balances.usdtMicro` | 可提现余额上限参考 |
| `payPinSet` | `false` 时须先 `POST /wallet/pay-pin/set`，否则提现返回 `403 PAY_PIN_NOT_SET` |

> **说明**：`GET /wallet/me` **不返回**提现手续费、最低提现额。手续费以提交后响应或本文 §5 公式为准；最低提现额默认 1 USDT，运营可在 Admin 调整（§6.3）。

---

### 3.2 发起提现

#### `POST /wallet/withdraw`

**请求体**

```json
{
  "toAddress": "TUserExternalTronAddress...",
  "amountMicro": 5000000,
  "payPin": "123456"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `toAddress` | 是 | 收款地址明文字符串（服务端仅做最小校验：非空、长度≤256、不可含控制字符；**不保证**是有效链上地址） |
| `amountMicro` | 是 | 希望链上到账的 USDT 数量（micro） |
| `payPin` | 是 | 6 位支付密码 |

**服务端处理顺序**

1. 最小校验 `toAddress`（非空、长度、控制字符）→ `400 INVALID_TRON_ADDRESS`
2. `amountMicro >= minWithdrawUsdtMicro` → `400 WITHDRAW_MIN_NOT_MET`
3. 校验支付密码 → `403 PAY_PIN_*`
4. 单笔/单日限额（场景 `WITHDRAW`，按 **`amountMicro`** 计，不含手续费）→ `409 LIMIT_EXCEEDED`
5. 计算 `feeMicro`（场景 `WITHDRAW` + `USDT`，见 §5）
6. 扣款 `amountMicro + feeMicro` → 不足 `409 INSUFFICIENT_BALANCE`；手续费 ≥ 金额 `400 FEE_EXCEEDS_AMOUNT`
7. 创建提现单 `status=PENDING`，返回对象

**200 响应 `WalletWithdrawal`**

```json
{
  "id": 7,
  "userId": "abc12def34",
  "toAddress": "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuW9",
  "amountMicro": 5000000,
  "feeMicro": 1000000,
  "payoutMicro": 5000000,
  "status": "PENDING",
  "txId": null,
  "failReason": null,
  "createdAt": "2026-05-26T11:00:00Z",
  "completedAt": null
}
```

| 字段 | 说明 |
|------|------|
| `amountMicro` | 用户填写的到账金额 |
| `feeMicro` | 本次实际收取的手续费（**权威值**，用于 UI 展示） |
| `payoutMicro` | 热钱包链上转出金额（= `amountMicro`） |
| `status` | 见 §4 |
| `txId` | 链上交易 ID，`COMPLETED` 时有值，可跳转 Tron 浏览器 |
| `failReason` | `FAILED` 时失败原因 |

**注意**：接口返回 200 表示**账本已扣款**，不代表链上已完成；需轮询 §3.3。

---

### 3.3 提现记录

#### `GET /wallet/withdrawals?page=0&size=20`

| 参数 | 默认 | 说明 |
|------|------|------|
| `page` | `0` | 页码（从 0） |
| `size` | `20` | 每页条数 |

**200**：Spring Data `Page`，`content[]` 元素结构同 §3.2 的 `WalletWithdrawal`，按 `createdAt` 倒序，仅当前用户。

**前端建议**：提交提现后每 3～15 秒轮询列表（或按 `id` 查找单条），直到 `status` 为 `COMPLETED` 或 `FAILED`。

---

### 3.4 统一资金流水（可选）

#### `GET /wallet/ledger?ledgerType=WITHDRAW&page=0&size=20`

提现扣款：`ledgerType=WITHDRAW`，`amount` 为负，绝对值 = `amountMicro + feeMicro`（一笔扣款）。

链上失败退款：账本会以 `WITHDRAW` 类型、`refType=WITHDRAW_REFUND` 记回 `amountMicro + feeMicro`。

`refType=WITHDRAW` + `refId` = 提现单 `id`，`remark` 为收款地址。

---

## 4. 提现状态机

| status | 含义 | 用户余额 |
|--------|------|----------|
| `PENDING` | 已扣款，等待热钱包广播 | 已扣 |
| `BROADCASTING` | 正在发送链上交易 | 已扣 |
| `COMPLETED` | 链上成功，`txId` 有效 | 已扣（不退） |
| `FAILED` | 广播失败 | **已退回** `amountMicro + feeMicro` |

后台任务 `WithdrawBroadcastJob` 默认每 **15s** 扫描 `PENDING` 订单（配置项 `chat99.wallet.withdraw-broadcast-interval-ms`）。

若未配置环境变量 `TRON_HOT_WALLET_PRIVATE_KEY`，订单会长期停留在 `PENDING`，不会上链。

---

## 5. 手续费规则（无用户端「试算」接口）

### 5.1 现状说明

| 类型 | 路径 | 说明 |
|------|------|------|
| 用户端 | **无** `GET /wallet/withdraw/fee-preview` 等试算接口 | 手续费在 `POST /wallet/withdraw` 时计算并写入 `feeMicro` |
| 运营端 | `GET /admin/wallet/fee-config` | 查询全部手续费配置 |
| 运营端 | `PUT /admin/wallet/fee-config/{id}` | 修改某条配置 |

客户端在**确认页展示手续费**的可行做法：

1. **推荐**：首次提现或运营未改配置前，使用下文默认规则本地试算；提交后以响应 `feeMicro` 为准。
2. 读取用户历史提现记录最近一条的 `feeMicro`（仅当运营未改费时准确）。
3. **运营后台**通过 Admin API 拉取 `scene=WITHDRAW` 配置后下发给 App（需自建配置下发，非公开用户 API）。

### 5.2 计算逻辑（与 `WalletFeeService` 一致）

配置键：`scene = WITHDRAW`，`currency = USDT`。

| feeType | 计算 |
|---------|------|
| `NONE` 或未配置 / `enabled=false` | `feeMicro = 0` |
| `FIXED` | `feeMicro = feeValue` |
| `PERCENT` | `feeMicro = amountMicro * feeValue / 10000`（`feeValue` 为万分比，如 100 = 1%），再夹在 `minFee`～`maxFee`（若配置） |

若 `feeMicro >= amountMicro` 且 `amountMicro > 0` → `400 FEE_EXCEEDS_AMOUNT`。

**系统默认种子数据**（首次启动 `WalletBootstrap`）：

| scene | currency | feeType | feeValue | 含义 |
|-------|----------|---------|----------|------|
| `WITHDRAW` | `USDT` | `FIXED` | `1000000` | 固定 **1 USDT** 手续费 |

### 5.3 最低提现额

来源：`wallet_exchange_config.min_withdraw_usdt_micro`（Admin §6.3），默认 `1000000`（1 USDT）。  
未读到配置时回退 `chat99.wallet.min-deposit-usdt-micro`（默认同为 1 USDT）。

### 5.4 限额（与手续费独立）

场景 `WITHDRAW` + `USDT`，Admin `GET /admin/wallet/limit-config` 中 `scene=WITHDRAW` 条目：

| 字段 | 说明 |
|------|------|
| `perTxMax` | 单笔 `amountMicro` 上限 |
| `dailyMax` | 自然日（UTC）累计 `amountMicro` 上限 |

校验与累计均按 **`amountMicro`**，**不含** `feeMicro`。

默认种子：`perTxMax` / `dailyMax` 极大（等效未限制），`enabled=true`。

---

## 6. 运营 Admin API（IP 白名单）

请求须来自 `chat99.admin.ip-whitelist`（环境变量 `ADMIN_IP_WHITELIST`，默认含 `127.0.0.1`）。  
无 JWT，由 `AdminGuard` 校验来源 IP。

### 6.1 手续费配置列表

#### `GET /admin/wallet/fee-config`

**200**：`WalletFeeConfig[]`

```json
[
  {
    "id": 1,
    "scene": "WITHDRAW",
    "currency": "USDT",
    "feeType": "FIXED",
    "feeValue": 1000000,
    "minFee": null,
    "maxFee": null,
    "enabled": true,
    "updatedAt": "2026-05-23T10:00:00Z"
  }
]
```

| 字段 | 说明 |
|------|------|
| `scene` | `WITHDRAW` / `TRANSFER_PLATFORM` / `RED_PACKET_SEND` / `EXCHANGE` |
| `currency` | `USDT` 或 `PLATFORM`（库内枚举，API 可能序列化为 `USDT`/`PLATFORM`） |
| `feeType` | `NONE` / `FIXED` / `PERCENT` |
| `feeValue` | 固定：micro 绝对值；比例：万分比（bps） |
| `minFee` / `maxFee` | 仅 `PERCENT` 时可选上下限（micro 或 fen，与 currency 一致） |
| `enabled` | 是否启用 |

### 6.2 更新手续费

#### `PUT /admin/wallet/fee-config/{id}`

**请求体 `FeeConfigDto`**

```json
{
  "id": 1,
  "scene": "WITHDRAW",
  "currency": "USDT",
  "feeType": "PERCENT",
  "feeValue": 50,
  "minFee": 500000,
  "maxFee": 5000000,
  "enabled": true
}
```

> `scene` / `currency` 在 DTO 中但更新实现**仅修改** `feeType`、`feeValue`、`minFee`、`maxFee`、`enabled`（不改 scene/currency）。

**200**：更新后的 `WalletFeeConfig`。

**提现示例**：改为 0.5% 手续费、最低 0.5 USDT、最高 5 USDT：

```json
{
  "id": 1,
  "scene": "WITHDRAW",
  "currency": "USDT",
  "feeType": "PERCENT",
  "feeValue": 50,
  "minFee": 500000,
  "maxFee": 5000000,
  "enabled": true
}
```

### 6.3 最低提现额与汇率浮动

#### `GET /admin/wallet/exchange-config`

**200**

```json
{
  "id": 1,
  "markupBps": 0,
  "floatBps": 50,
  "minWithdrawUsdtMicro": 1000000
}
```

#### `PUT /admin/wallet/exchange-config`

**请求体**

```json
{
  "markupBps": 0,
  "floatBps": 50,
  "minWithdrawUsdtMicro": 2000000
}
```

将最低提现改为 **2 USDT** 示例：`minWithdrawUsdtMicro: 2000000`。

### 6.4 提现限额配置

#### `GET /admin/wallet/limit-config`

**200**：`WalletLimitConfig[]`，查找 `scene=WITHDRAW` 且 `currency=USDT`。

#### `PUT /admin/wallet/limit-config/{id}`

```json
{
  "id": 4,
  "scene": "WITHDRAW",
  "currency": "USDT",
  "perTxMax": 100000000000,
  "dailyMax": 500000000000,
  "enabled": true
}
```

### 6.5 平台统计（含累计手续费）

#### `GET /admin/wallet/stats`

```json
{
  "totalExchangeSurplusFen": 0,
  "totalFeeUsdtMicro": 15000000,
  "totalFeePlatformFen": 0
}
```

`totalFeeUsdtMicro` 含提现等场景收取的 USDT 手续费累计。

---

## 7. 客户端集成流程

```
┌─────────────┐
│ GET /wallet/me │ 余额、payPinSet
└──────┬──────┘
       │ payPinSet=false → 引导设置支付密码
       ▼
┌─────────────────────┐
│ 填写地址 + 金额      │ 本地按 §5.2 试算 fee（默认 1 USDT）
│ 展示：到账、手续费、  │ totalDebit = amount + fee
│       合计扣款       │ 校验 >= minWithdraw（默认 1 USDT）
└──────┬──────────────┘
       ▼
┌─────────────────────┐
│ POST /wallet/withdraw │
└──────┬──────────────┘
       │ 200 → 用响应 feeMicro 校正 UI
       ▼
┌─────────────────────────┐
│ 轮询 GET /wallet/withdrawals │
└──────┬──────────────────┘
       ├─ COMPLETED → 展示 txId、区块浏览器链接
       └─ FAILED → 展示 failReason，提示余额已退回
```

**确认页文案建议**

| 项 | 来源 |
|----|------|
| 到账金额 | 用户输入 `amountMicro` |
| 手续费 | 本地试算 + 提交后 `feeMicro` |
| 合计扣款 | `amountMicro + feeMicro` |
| 实际链上到账 | `payoutMicro`（一般 = 到账金额） |

---

## 8. 错误码

| code | HTTP | 说明 |
|------|------|------|
| `INVALID_TRON_ADDRESS` | 400 | 地址非法（仅最小校验：非空、长度≤256、不可含控制字符） |
| `WITHDRAW_MIN_NOT_MET` | 400 | 低于最低提现额 |
| `FEE_EXCEEDS_AMOUNT` | 400 | 手续费 ≥ 提现金额 |
| `PAY_PIN_NOT_SET` | 403 | 未设置支付密码 |
| `PAY_PIN_INVALID` | 403 | 支付密码错误 |
| `PAY_PIN_LOCKED` | 403 | 支付密码已锁定 |
| `INSUFFICIENT_BALANCE` | 409 | 余额不足（需覆盖 amount + fee） |
| `LIMIT_EXCEEDED` | 409 | 超过单笔或单日提现限额 |
| `NOT_FOUND` | 404 | 钱包不存在等 |

错误体格式：

```json
{
  "code": "INSUFFICIENT_BALANCE",
  "message": "INSUFFICIENT_BALANCE"
}
```

---

## 9. 运维与环境变量

| 变量 | 说明 |
|------|------|
| `TRON_HOT_WALLET_PRIVATE_KEY` | 提现热钱包私钥（hex），**必填才能广播** |
| `TRON_DEPOSIT_MNEMONIC` | 用户充值地址派生（与提现独立） |
| `TRONGRID_API_KEY` | TronGrid API Key |
| `TRONGRID_BASE_URL` | 默认 `https://api.trongrid.io` |
| `TRON_USDT_CONTRACT` | USDT 合约地址 |
| `WALLET_DEPOSIT_SCAN_INTERVAL_MS` | 充值扫描间隔（与提现无关） |
| `ADMIN_IP_WHITELIST` | Admin API 来源 IP |

热钱包需预充 **TRX** 支付 TRC20 转账能量/带宽。

---

## 10. 接口速查

### 用户端（JWT）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/wallet/me` | 余额、支付密码状态 |
| POST | `/wallet/pay-pin/set` | 首次设置支付密码 |
| POST | `/wallet/pay-pin/change` | 修改支付密码 |
| POST | `/wallet/pay-pin/reset` | 短信重置支付密码 |
| **POST** | **`/wallet/withdraw`** | **发起提现（含 feeMicro）** |
| **GET** | **`/wallet/withdrawals`** | **提现记录** |
| GET | `/wallet/ledger` | 流水（可选 `ledgerType=WITHDRAW`） |

### 运营端（IP 白名单）

| 方法 | 路径 | 说明 |
|------|------|------|
| **GET** | **`/admin/wallet/fee-config`** | **查询全部手续费配置** |
| **PUT** | **`/admin/wallet/fee-config/{id}`** | **更新手续费（含 WITHDRAW）** |
| GET | `/admin/wallet/limit-config` | 限额配置 |
| PUT | `/admin/wallet/limit-config/{id}` | 更新限额 |
| **GET** | **`/admin/wallet/exchange-config`** | **含 minWithdrawUsdtMicro** |
| **PUT** | **`/admin/wallet/exchange-config`** | **更新最低提现额等** |
| GET | `/admin/wallet/stats` | 含累计手续费统计 |

---

## 11. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05-26 | 初版：用户提现 API、手续费规则、Admin 手续费/限额/最低额配置 |
