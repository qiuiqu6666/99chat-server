# 闪兑（互兑）— 客户端对接文档

> 版本：v1.0  
> 日期：2026-08-14  
> 适用：99chat App（Flutter / 其它客户端）  
> 总览文档：[wallet-client.md](./wallet-client.md)  
> 支付助手通知：[platform-wallet-notice-client.md](./platform-wallet-notice-client.md)

---

## 1. 能力说明

| 能力 | 说明 |
|------|------|
| 闪兑 / 互兑 | USDT ↔ 平台币（币种代码 `99`，锚定人民币） |
| 汇率来源 | 服务端拉取外部 USD/CNY，再叠加运营加价 / 买卖价差后下发 |
| 计价单位 | USDT 用 **micro**（6 位小数）；平台币用 **fen**（分，2 位小数） |
| Preview | **无**独立 preview 接口；展示价用 `GET /wallet/me`，成交价以 `POST /wallet/exchange` 返回为准 |
| 开关 | 运营可关闭闪兑；关闭后下单返回 `EXCHANGE_MAINTENANCE`（当前用户端**不下发** enabled 字段） |

### 1.1 金额单位（必读）

| 币种 | API 单位 | 展示换算 |
|------|----------|----------|
| USDT | `long` **micro** | `micro / 1_000_000` → USDT |
| `99`（平台币） | `long` **fen** | `fen / 100` → 元 |

示例：`1_000_000` micro = **1 USDT**；`660` fen = **6.60 元**。

### 1.2 鉴权与响应信封

```http
Authorization: Bearer <token>
Content-Type: application/json
```

成功（HTTP 2xx）统一信封：

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

下文示例均为 **`data` 内内容**。错误为非 2xx，体形如 `{ "code": "...", "message": "..." }`。

---

## 2. 汇率与互兑汇率（核心）

### 2.1 如何获取

**闪兑页 / 钱包首页请调：**

```http
GET /wallet/me
```

响应中的 `usdtPrice` 即当前互兑参考汇率（含运营浮动）：

```json
{
  "balances": {
    "usdtMicro": 2500000,
    "platformFen": 128800
  },
  "usdtPrice": {
    "cnyPerUsdt": 6.6702,
    "cnyPerUsdtBuy": 6.6001,
    "cnyPerUsdtSell": 6.7402,
    "quoteCurrency": "CNY",
    "updatedAt": "2026-08-13T21:49:04Z"
  },
  "platformCurrency": "99",
  "payPinSet": true
}
```

### 2.2 字段含义（互兑必须区分买卖）

| 字段 | 含义 | 闪兑方向 |
|------|------|----------|
| `cnyPerUsdtBuy` | **买入价**：1 USDT 可兑多少元平台币（用户卖出 USDT） | `USDT_TO_PLATFORM` |
| `cnyPerUsdtSell` | **卖出价**：兑回 1 USDT 需要多少元平台币（用户买入 USDT） | `PLATFORM_TO_USDT` |
| `cnyPerUsdt` | **中间价** `(buy + sell) / 2`，资产列表参考，**不要**拿来算成交预估 |
| `quoteCurrency` | 报价币，固定 `"CNY"` |
| `updatedAt` | 服务端汇率快照时间（ISO-8601） |
| `usdtPrice == null` | 汇率暂不可用：禁用提交，提示稍后重试 |

> UI 文案建议：  
> - 「USDT → 99币」旁展示 `cnyPerUsdtBuy`（如 `1 USDT ≈ 6.60 元`）  
> - 「99币 → USDT」旁展示 `cnyPerUsdtSell`（如 `1 USDT ≈ 6.74 元`）

### 2.3 服务端计价逻辑（便于理解，客户端勿自造加价）

1. 外部基准：Frankfurter `USD → CNY`  
2. 加价：`base × (1 + markupBps / 10000)`  
3. 价差：  
   - 买价（USDT→99）= `adjusted × (1 - floatBps / 10000)` → 对应 `cnyPerUsdtBuy`  
   - 卖价（99→USDT）= `adjusted × (1 + floatBps / 10000)` → 对应 `cnyPerUsdtSell`  
4. 金额一律 **向下取整**（少给用户，盈余进平台）

客户端**不要**自己算 markup/float；只使用接口返回的 buy/sell。

### 2.4 本地预估「预计获得」（向下取整）

```text
# USDT → 99币（用买价）
outputFen = floor( usdtMicro / 1_000_000 * cnyPerUsdtBuy * 100 )

# 99币 → USDT（用卖价）
outputUsdtMicro = floor( (platformFen / 100) / cnyPerUsdtSell * 1_000_000 )
```

示例（买价 6.6001、卖价 6.7402）：

| 方向 | 输入 | 预计输出 |
|------|------|----------|
| USDT→99 | 1 USDT（`1000000`） | `660` fen = **6.60 元** |
| 99→USDT | 100 元（`10000` fen） | ≈ `1_483_041` micro ≈ **1.483041 USDT** |

预估仅用于 UI；**最终以 `POST /wallet/exchange` 的 `outputAmount` 为准**（下单瞬间汇率可能已刷新）。

### 2.5 其它汇率入口（可选）

| 接口 | 说明 |
|------|------|
| `GET /wallet/currencies` | 资产列表；USDT 的 `price` 为**中间价**，不含独立买卖价；闪兑页请用 `/wallet/me` |
| 后台 `GET /api/v1/wallet/exchange-config` | **仅运营后台**；含 `rate_preview`、开关、加价参数；App **不要**调 |

---

## 3. 下单互兑

### `POST /wallet/exchange`

**请求**

```json
{
  "direction": "USDT_TO_PLATFORM",
  "amount": 1000000,
  "payPin": "123456"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `direction` | string | 见下表 |
| `amount` | long | 用户**付出**的金额（单位随方向变化） |
| `payPin` | string | 6 位支付密码 |

| `direction` | 含义 | `amount` 单位 | 使用汇率字段 |
|-------------|------|---------------|--------------|
| `USDT_TO_PLATFORM` | USDT → 99币 | USDT **micro** | `cnyPerUsdtBuy` |
| `PLATFORM_TO_USDT` | 99币 → USDT | 平台币 **fen** | `cnyPerUsdtSell` |

**成功 200**

```json
{
  "id": 35,
  "userId": "abc12def34",
  "direction": "USDT_TO_PLATFORM",
  "inputAmount": 13300000,
  "outputAmount": 8968,
  "surplusFen": 45,
  "rateSnapshot": "{\"usdCny\":6.743,\"markupBps\":-108,\"floatBps\":105,\"fetchedAt\":\"...\"}",
  "createdAt": "2026-08-13T12:58:11.532727Z"
}
```

| 字段 | 说明 |
|------|------|
| `inputAmount` | 付出金额（单位同请求） |
| `outputAmount` | **实际获得**：USDT→99 时为 **fen**；99→USDT 时为 **micro** |
| `surplusFen` | 取整盈余（分）；客户端可忽略 |
| `rateSnapshot` | 成交时汇率快照 JSON（或历史兼容的纯数字字符串）；详情页可展示，勿当实时价 |

**前置条件**

1. `GET /wallet/me` → `payPinSet == true`，否则先 `POST /wallet/pay-pin/set`  
2. `usdtPrice != null`  
3. 余额足够；`amount > 0` 且换算后 `outputAmount > 0`

---

## 4. 闪兑记录

### `GET /wallet/exchanges?page=0&size=20`

`data` 为 Spring Data `Page`（含 `content`、`totalElements`、`number`、`size` 等）。

`content[]` 元素结构同下单成功响应中的订单对象。

流水侧也可查：

```http
GET /wallet/ledger?ledgerType=EXCHANGE_OUT&ledgerType=EXCHANGE_IN
```

`refType=EXCHANGE`，`refId` = 闪兑订单 id。

---

## 5. 推荐 UI 流程

```text
进入闪兑页
  → GET /wallet/me
  → 若 usdtPrice == null：展示「汇率暂不可用」
  → 展示买价 / 卖价（cnyPerUsdtBuy / cnyPerUsdtSell）
  → 用户选方向、输入金额 → 本地预估 output
  → 确认 + 支付密码
  → POST /wallet/exchange
  → 成功：展示真实 outputAmount，刷新 /wallet/me
  → 503 EXCHANGE_MAINTENANCE：整页「正在维护」
```

支付助手通知类型：`flashExchange`（详见 platform-wallet-notice 文档）。

---

## 6. 错误码（闪兑相关）

| code | HTTP | 客户端处理 |
|------|------|------------|
| `EXCHANGE_MAINTENANCE` | 503 | 「正在维护」；运营关闭闪兑开关 |
| `RATE_UNAVAILABLE` | 503 | 汇率不可用，稍后重试 |
| `EXCHANGE_AMOUNT_TOO_SMALL` | 400 | 换算结果过小（不足 1 分等） |
| `INSUFFICIENT_BALANCE` | 409 | 余额不足 |
| `LIMIT_EXCEEDED` | 409 | 超单笔/单日限额 |
| `PAY_PIN_NOT_SET` | 403 | 引导设置支付密码 |
| `PAY_PIN_INVALID` | 403 | 密码错误 |
| `PAY_PIN_LOCKED` | 403 | 锁定，提示稍后或重置 |
| `INVALID_PAY_PIN` | 400 | 非 6 位数字 |
| `WALLET_NOT_CONFIGURED` | 503 | 运维问题，提示稍后再试 |

> 用户端目前**没有** `exchangeEnabled` 字段；维护态只能通过下单 `EXCHANGE_MAINTENANCE` 或产品自行约定探测。

---

## 7. 联调检查表

- [ ] `GET /wallet/me` 能解析 `usdtPrice.cnyPerUsdtBuy` / `cnyPerUsdtSell`
- [ ] 两个方向分别用买价 / 卖价做预估，且 **floor** 取整
- [ ] 下单 `amount` 单位与 `direction` 匹配（micro / fen 不混用）
- [ ] 成功页展示服务端 `outputAmount`，不是本地预估
- [ ] 处理 `EXCHANGE_MAINTENANCE` / `RATE_UNAVAILABLE`
- [ ] `payPinSet == false` 时先设 PIN
- [ ] 记录页对接 `GET /wallet/exchanges`
- [ ] （可选）支付助手 `flashExchange` 通知可点进订单/钱包

---

## 8. 与运营后台的边界

| 端 | 接口 | 用途 |
|----|------|------|
| App | `GET /wallet/me`、`POST /wallet/exchange`、`GET /wallet/exchanges` | 查价、下单、记录 |
| 运营后台 | `GET/PUT /api/v1/wallet/exchange-config` | 开关、加价、价差、汇率预览 |

App **禁止**依赖后台配置接口；买卖价一律走用户态 `usdtPrice`。

---

## 9. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-08-14 | 初版：汇率字段、买卖价预估、下单/记录、错误码与联调表 |
