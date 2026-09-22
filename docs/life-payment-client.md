# 生活缴费 — 客户端对接文档

> 版本：v1.0  
> 适用：99chat Flutter  
> Base URL：`http://47.239.60.107:8081`（以部署为准）  
> 鉴权：`Authorization: Bearer <JWT>`（与登录 token 相同）  
> 相关：支付密码见 [wallet-client.md](./wallet-client.md)

面向 **App / Flutter 客户端**。覆盖手机充值、水费、电费、燃气费的查询、下单、轮询与取消。

成功响应统一信封：

```json
{ "code": 0, "message": "ok", "data": { ... } }
```

错误响应（非 2xx，**不**包信封）：

```json
{ "code": "<error_code>", "message": "<中文文案>" }
```

本文后续示例均为 **`data` 内内容**（已省略外层信封），除非特别说明。

---

## 1. 能力概览

| 服务 | `service_type` | 是否需先查户号 | 说明 |
|------|----------------|----------------|------|
| 手机充值 | `mobile` | 否 | 选面额 → 扣款 → 上游充值（当前可走大猿人，对客户端无感） |
| 水费 | `water` | 是 | 选城市/单位 → 查户号 → 确认地址 → 缴费 |
| 电费 | `electric` | 是 | 查户仍走插件；缴费扣款后可走大猿人（对客户端无感） |
| 燃气费 | `gas` | 是 | 同上 |

上游切换说明见 [life-payment-yuanren.md](./life-payment-yuanren.md)。

支付方式：

| `pay_method` | 含义 | 单位 |
|--------------|------|------|
| `coin_99` | 99币（平台币） | 元（后端按分扣账） |
| `usdt` | USDT | 元等值换算为 micro |

下单时需传支付密码字段 `pay_password`（与钱包支付 PIN 相同）。未设置 PIN 会返回 `PAY_PIN_NOT_SET` / `pay_password_error`。

---

## 2. 推荐页面流程

### 2.1 首页

1. `GET /life-payments/home` — 服务入口 + 本月已缴 + 最近订单  
2. 或 `GET /life-payments/services` — 仅服务开关列表  

### 2.2 手机充值

```
输入手机号
  → GET /life-payments/accounts/profile?service_type=mobile&account_no=...
  → 若 need_owner_last_char=true，展示「机主姓名最后一个字」输入框
  → GET /life-payments/mobile/amount-options?phone=...
  → 选面额 + 支付方式 + 支付密码
  → POST /life-payments/mobile/orders
  → 轮询 GET /life-payments/orders/{order_no}
     · success → 成功页（可展示 receipt）
     · need_owner_last_char → 引导补录 → POST .../owner-last-char
     · failed / need_manual → 失败页
     · paid / running / processing / cashier_confirm → 继续等待
  → 可取消：POST /life-payments/orders/{order_no}/cancel（成功/收银台确认后不可取消）
```

### 2.3 水/电/燃气

```
选 service_type + 城市
  → GET /life-payments/providers?service_type=...&city_name=...
  → 选缴费单位，输入户号
  → POST /life-payments/utility/queries
  → 轮询 GET /life-payments/utility/queries/{query_no}
     · query_success → 展示地址/余额/建议金额，用户确认地址
     · query_failed → 失败提示
     · expired → 重新查询
  → GET /life-payments/utility/amount-options?service_type=...
  → POST /life-payments/utility/orders（必须带 confirmed_user_address）
  → 轮询 GET /life-payments/orders/{order_no}
```

**幂等**：`client_order_id` 由客户端生成（建议 UUID），同一用户重复提交同一 `client_order_id` 会返回已有订单，不会重复扣款。

---

## 3. 接口明细

### 3.1 `GET /life-payments/home`

首页聚合。

**响应 `data`：**

```json
{
  "city_name": "",
  "month_paid_amount": "150.00",
  "services": [
    {
      "service_type": "mobile",
      "title": "手机充值",
      "subtitle": "三网通充",
      "enabled": true
    },
    {
      "service_type": "water",
      "title": "水费",
      "subtitle": "水务缴费",
      "enabled": true
    },
    {
      "service_type": "electric",
      "title": "电费",
      "subtitle": "国网缴费",
      "enabled": true
    },
    {
      "service_type": "gas",
      "title": "燃气费",
      "subtitle": "燃气缴费",
      "enabled": true
    }
  ],
  "recent_orders": [
    {
      "order_no": "mobile-xxxx",
      "service_type": "mobile",
      "title": "手机充值",
      "account_mask": "138****8000",
      "amount": "50.00",
      "order_status": "success",
      "created_at": "2026-07-10 01:20:00"
    }
  ]
}
```

### 3.2 `GET /life-payments/services`

```json
{
  "items": [
    {
      "service_type": "mobile",
      "enabled": true,
      "maintenance_message": ""
    }
  ]
}
```

`enabled=false` 时 `maintenance_message` 为维护文案；调用业务接口会返回 `503 service_disabled`。

### 3.3 `GET /life-payments/accounts/profile`

下单前档案预检（是否需要机主末字、历史地址等）。

| Query | 必填 | 说明 |
|-------|------|------|
| `service_type` | 是 | `mobile` / `water` / `electric` / `gas` |
| `account_no` | 是 | 手机号或户号 |
| `city_code` | 否 | 水电燃建议传 |
| `provider_code` | 否 | 水电燃建议传 |

**手机号示例：**

```json
{
  "service_type": "mobile",
  "account_no": "13800138000",
  "verified": false,
  "success_count": 0,
  "last_paid_at": null,
  "need_owner_last_char": true,
  "owner_last_char_exists": false
}
```

**水电燃示例：**

```json
{
  "service_type": "electric",
  "account_no": "1101010001",
  "verified": true,
  "success_count": 2,
  "last_paid_at": "2026-06-01 12:00:00",
  "city_name": "北京",
  "city_code": "",
  "provider_name": "国网北京市电力公司",
  "provider_code": "electric_000a5e75_211a97cb",
  "user_address": "北京市朝阳区xxx"
}
```

### 3.4 `GET /life-payments/mobile/amount-options`

| Query | 必填 | 说明 |
|-------|------|------|
| `phone` | 否 | 用于识别运营商 |
| `operator_name` | 否 | 手动指定运营商名 |

```json
{
  "phone": "13800138000",
  "operator_name": "中国移动",
  "items": [
    { "amount": 30.00, "label": "30元", "enabled": true },
    { "amount": 50.00, "label": "50元", "enabled": true },
    { "amount": 100.00, "label": "100元", "enabled": true },
    { "amount": 200.00, "label": "200元", "enabled": true }
  ]
}
```

> 手机充值金额必须是启用面额之一，不支持自定义金额。

### 3.5 `GET /life-payments/utility/amount-options`

| Query | 必填 |
|-------|------|
| `service_type` | 是（`water`/`electric`/`gas`） |
| `city_code` | 否 |
| `provider_code` | 否 |

```json
{
  "service_type": "electric",
  "allow_custom_amount": true,
  "items": [
    { "amount": 20.00, "label": "20元", "enabled": true },
    { "amount": 50.00, "label": "50元", "enabled": true },
    { "amount": 100.00, "label": "100元", "enabled": true }
  ]
}
```

`allow_custom_amount=true` 时允许用户输入自定义正数金额（最多 2 位小数）。

### 3.6 `GET /life-payments/providers`

缴费单位列表（已入库约 4000+）。

| Query | 必填 | 说明 |
|-------|------|------|
| `service_type` | 是 | `water`/`electric`/`gas` |
| `city_name` | 建议 | 如 `北京`、`南京`（中文需 URL encode） |
| `city_code` | 否 | 当前多数为空，优先用 `city_name` |
| `keyword` | 否 | 单位名称模糊搜索 |
| `page` | 否 | 默认 1 |
| `page_size` | 否 | 默认 50，最大 100 |

```json
{
  "items": [
    {
      "service_type": "electric",
      "city_name": "北京",
      "city_code": "",
      "provider_name": "国网北京市电力公司",
      "provider_code": "electric_000a5e75_211a97cb",
      "enabled": true
    }
  ],
  "page": 1,
  "page_size": 50,
  "total": 1
}
```

下单/查询时请同时传 `provider_name` + `provider_code`（`provider_code` 可空，服务端会按名称生成，但建议用列表返回值）。

### 3.7 `POST /life-payments/utility/queries`

创建户号查询任务。

同一 `service_type + account_no` 同时只允许一个水/电/燃气任务处于
`ready` 或 `running`。查询任务和缴费任务共用该限制；已有任务结束、
失败、转人工或取消后才能再次创建。

**请求：**

```json
{
  "service_type": "electric",
  "city_name": "北京",
  "city_code": "",
  "provider_name": "国网北京市电力公司",
  "provider_code": "electric_000a5e75_211a97cb",
  "account_no": "1101010001"
}
```

**响应：**

```json
{
  "query_no": "query-xxxx",
  "service_type": "electric",
  "query_status": "ready",
  "task_no": "task-query-xxxx",
  "message": "户号查询任务已创建"
}
```

### 3.8 `GET /life-payments/utility/queries/{query_no}`

轮询查询结果。建议间隔 **1.5～2s**，超时建议 **60～90s**。

```json
{
  "query_no": "query-xxxx",
  "service_type": "electric",
  "city_name": "北京",
  "city_code": "",
  "provider_name": "国网北京市电力公司",
  "provider_code": "electric_000a5e75_211a97cb",
  "account_no": "1101010001",
  "user_address": "北京市朝阳区测试路88号",
  "account_balance": "12.34",
  "suggest_amount": "50",
  "query_status": "query_success",
  "plugin_status": "query_success",
  "receipt": null,
  "expired_at": "2026-07-10 01:39:00"
}
```

| `query_status` | UI 建议 |
|----------------|---------|
| `ready` / `running` | 加载中 |
| `query_success` | 展示地址，要求用户确认后才能缴费 |
| `query_failed` | 失败，可重试新建 query |
| `expired` | 已过期（默认约 10 分钟），需重新查询 |
| `confirmed` | 已确认（内部态） |

### 3.9 `POST /life-payments/utility/orders`

查询成功后缴费。会立即校验支付密码并扣款。

**请求：**

```json
{
  "client_order_id": "app-uuid-001",
  "query_no": "query-xxxx",
  "service_type": "electric",
  "city_name": "北京",
  "city_code": "",
  "provider_name": "国网北京市电力公司",
  "provider_code": "electric_000a5e75_211a97cb",
  "account_no": "1101010001",
  "confirmed_user_address": "北京市朝阳区测试路88号",
  "amount": "50",
  "pay_method": "coin_99",
  "pay_password": "258369"
}
```

要点：

- `confirmed_user_address` **必填**，且必须与查询结果 `user_address` 一致（若查询返回了地址）
- `account_no` / `city_name` / `provider_name` 必须与 query 一致
- `amount` 正数，最多 2 位小数

**响应：**

```json
{
  "order_no": "electric-xxxx",
  "service_type": "electric",
  "order_status": "paid",
  "platform_pay_status": "paid",
  "plugin_status": "ready",
  "task_no": "task-xxxx",
  "message": "订单已创建，等待插件执行"
}
```

### 3.10 `POST /life-payments/mobile/orders`

**请求：**

```json
{
  "client_order_id": "app-uuid-002",
  "phone": "13800138000",
  "amount": "50",
  "pay_method": "coin_99",
  "pay_password": "258369",
  "owner_last_char": "测"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `client_order_id` | 是 | 客户端幂等键 |
| `phone` | 是 | 11 位手机号 |
| `amount` | 是 | 必须是面额列表中的值 |
| `pay_method` | 是 | `coin_99` / `usdt` |
| `pay_password` | 是 | 支付 PIN |
| `owner_last_char` | 条件 | 该号首次充值必填（1～4 字） |

若缺机主末字：

```json
{ "code": "owner_last_char_required", "message": "首次充值需要填写机主姓名最后一个字" }
```

### 3.11 `GET /life-payments/orders/{order_no}`

订单详情（轮询主接口）。

**手机：**

```json
{
  "order_no": "mobile-xxxx",
  "client_order_id": "app-uuid-002",
  "service_type": "mobile",
  "amount": 50.00,
  "pay_method": "coin_99",
  "platform_pay_status": "paid",
  "plugin_status": "paid_success",
  "order_status": "success",
  "created_at": "2026-07-10 01:20:00",
  "updated_at": "2026-07-10 01:20:30",
  "account_no": "13800138000",
  "phone": "13800138000",
  "owner_last_char": "测",
  "recharge_status": "success",
  "receipt": "https://..."
}
```

**水电燃额外字段：** `city_name`、`city_code`、`provider_name`、`provider_code`、`user_address`、`account_balance`、`execution_status`、`receipt`。

### 3.12 `GET /life-payments/orders`

订单列表。

| Query | 说明 |
|-------|------|
| `service_type` | 可选过滤 |
| `order_status` | 可选过滤 |
| `page` | 默认 1 |
| `page_size` | 默认 20 |

```json
{
  "items": [
    {
      "order_no": "mobile-xxxx",
      "service_type": "mobile",
      "amount": 50.00,
      "order_status": "success",
      "plugin_status": "paid_success",
      "account_no": "13800138000",
      "provider_name": "",
      "created_at": "2026-07-10 01:20:00"
    }
  ],
  "page": 1,
  "page_size": 20,
  "total": 1
}
```

### 3.13 `POST /life-payments/mobile/orders/{order_no}/owner-last-char`

当 `order_status=need_owner_last_char` 时补录。

```json
{ "owner_last_char": "赵" }
```

```json
{
  "order_no": "mobile-xxxx",
  "order_status": "paid",
  "plugin_status": "ready",
  "task_no": "task-xxxx",
  "message": "已补充机主姓名最后一个字，任务重新排队"
}
```

非该状态调用会返回 `409 INVALID_INPUT`。

### 3.14 `POST /life-payments/orders/{order_no}/cancel`

```json
{ "reason": "用户取消" }
```

可取消：未成功、未进入收银台确认的订单。  
不可取消：`success` / `plugin_status=paid_success|cashier_confirm` → `409 order_cannot_cancel`。

已扣款取消会自动退回平台余额（`platform_pay_status` → `refunded`）。

---

## 4. 订单状态机（客户端）

| `order_status` | 含义 | UI |
|----------------|------|-----|
| `created` | 刚创建（极少见，扣款前） | 处理中 |
| `paid` | 已扣款，等待插件 | 处理中 |
| `running` | 插件执行中 | 处理中 |
| `processing` | 支付处理中 | 处理中 |
| `cashier_confirm` | 已到支付确认页 | 处理中（不可取消） |
| `success` | 成功 | 成功页 |
| `failed` | 失败 | 失败页 |
| `need_manual` | 需人工 | 提示稍后/联系客服 |
| `need_owner_last_char` | 需补机主末字 | 弹窗补录 |
| `cancelled` | 已取消 | 已取消 |

建议轮询：创建后立即查一次，之后每 **2s**，最长 **3～5 分钟**；离开详情页可停，回列表再刷新。

---

## 5. 错误码

| `code` | HTTP | 文案 / 处理 |
|--------|------|-------------|
| `UNAUTHORIZED` | 401/403 | 请先登录 |
| `service_disabled` | 503 | 服务维护中 |
| `INVALID_INPUT` | 400/409 | 参数错误 / 状态不允许 |
| `invalid_amount` | 400 | 金额不支持 |
| `invalid_phone` | 400 | 手机号格式错误 |
| `owner_last_char_required` | 400 | 首次充值需要填写机主姓名最后一个字 |
| `account_no_required` | 400 | 户号不能为空 |
| `provider_not_found` | 400 | 缴费单位不存在或已禁用 |
| `insufficient_platform_balance` | 400 | 用户平台余额不足 |
| `pay_password_error` | 400 | 平台支付密码错误 |
| `PAY_PIN_NOT_SET` / `PAY_PIN_LOCKED` | 403 | 引导设置/解锁支付密码 |
| `duplicate_client_order_id` | 409 | 前端订单号重复（参数不一致时） |
| `account_task_already_active` | 409 | 该户号已有排队中/执行中的查询或缴费任务；禁止重复提交，等待现有任务结束 |
| `order_not_found` | 404 | 订单/查询不存在 |
| `order_cannot_cancel` | 409 | 当前状态不能取消 |

---

## 6. 联调示例（curl）

```bash
BASE=http://127.0.0.1:8081
TOKEN=<jwt>

# 首页
curl -sS "$BASE/life-payments/home" -H "Authorization: Bearer $TOKEN"

# 北京电费单位
curl -sS "$BASE/life-payments/providers?service_type=electric&city_name=%E5%8C%97%E4%BA%AC&page=1&page_size=20" \
  -H "Authorization: Bearer $TOKEN"

# 查户号
curl -sS -X POST "$BASE/life-payments/utility/queries" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"service_type":"electric","city_name":"北京","provider_name":"国网北京市电力公司","provider_code":"electric_000a5e75_211a97cb","account_no":"1101010001"}'

# 轮询查询
curl -sS "$BASE/life-payments/utility/queries/<query_no>" -H "Authorization: Bearer $TOKEN"

# 缴费
curl -sS -X POST "$BASE/life-payments/utility/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"client_order_id":"demo-1","query_no":"<query_no>","service_type":"electric","city_name":"北京","provider_name":"国网北京市电力公司","provider_code":"electric_000a5e75_211a97cb","account_no":"1101010001","confirmed_user_address":"<地址>","amount":"50","pay_method":"coin_99","pay_password":"******"}'

# 手机充值
curl -sS -X POST "$BASE/life-payments/mobile/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"client_order_id":"demo-m1","phone":"13800138000","amount":"50","pay_method":"coin_99","pay_password":"******","owner_last_char":"测"}'
```

---

## 7. IM 订单状态推送

订单状态变更时，服务端通过 **支付助手**（`99Chat` 系统账号）向用户发送 IM 自定义消息，并附带离线 Push。

### 7.1 识别

监听 TIM 自定义消息，当：

```text
data.businessID == "life_payment_order_update"
```

或 `data.customType == "life_payment_order_update"`。

### 7.2 Payload 示例

```json
{
  "businessID": "life_payment_order_update",
  "customType": "life_payment_order_update",
  "version": 1,
  "order_no": "lp-electric-20260710-xxxx",
  "order_status": "success",
  "plugin_status": "paid_success",
  "service_type": "electric",
  "amount": "50.00",
  "pay_method": "coin_99",
  "message": "缴费成功",
  "updated_at": "2026-07-10 12:00:00",
  "query_no": "query-xxxx"
}
```

| 字段 | 说明 |
|------|------|
| `order_no` | 订单号，用于跳转详情 `GET /life-payments/orders/{order_no}` |
| `order_status` | 与 REST 一致：`paid` / `running` / `success` / `failed` / `cancelled` / `need_manual` / `need_owner_last_char` 等 |
| `plugin_status` | 插件细粒度状态 |
| `service_type` | `mobile` / `water` / `electric` / `gas` |
| `message` | 展示用简短文案 |

### 7.3 客户端处理建议

1. 收到推送后 **刷新订单详情** 或列表对应项（不必全量拉列表）。  
2. 终态（`success` / `failed` / `cancelled`）可 Toast + 更新 UI；中间态（`running` / `processing`）可仅更新进度。  
3. `need_owner_last_char`：引导用户到补充机主姓名字段（见 §2.3）。  
4. 离线 Push `data.type` 同为 `life_payment_order_update`，`data.orderNo` 为订单号。  
5. 开关：服务端 `LIFE_PAYMENT_ORDER_UPDATE_NOTIFY_ENABLED`（默认 `true`）。  
6. **终态卡片**：订单 `success` / `failed` / `cancelled` 时，同一支付助手会话还会收到 `platform_wallet_notice`（`noticeType=lifePayment`），展示金额、户号/手机号等明细，见 [platform-wallet-notice-client.md](./platform-wallet-notice-client.md)。

### 7.4 触发时机

| 场景 | 典型 order_status |
|------|-------------------|
| 下单扣款成功 | `paid` |
| 插件领取任务 | `running` |
| 插件中间回调 | `processing` / `cashier_confirm` |
| 插件成功 | `success` |
| 插件失败 / 需人工 | `failed` / `need_manual` / `need_owner_last_char` |
| 用户取消 | `cancelled` |

插件对接详见 [life-payment-worker.md](./life-payment-worker.md)。

---

## 8. 客户端实现注意

1. **城市筛选**：当前 `city_code` 多为空，UI 用 `city_name` 过滤即可。城市列表可从本地静态省市数据取，再按城市拉 `providers`。  
2. **中文 Query**：`city_name` / `keyword` 必须 URL encode（如 `北京` → `%E5%8C%97%E4%BA%AC`）。  
3. **确认地址**：水电燃下单前必须让用户看到并确认 `user_address`，原样回传 `confirmed_user_address`；缺该字段或与查询结果不一致会 `400 INVALID_INPUT`。  
4. **支付密码**：字段名是 `pay_password`（不是 `pay_pin`）；不要本地缓存明文；失败按钱包 PIN 错误码处理。  
5. **幂等**：同一业务操作固定一个 `client_order_id`，网络重试可安全重复 POST。  
6. **时间字段**：`created_at` / `expired_at` 等为 `Asia/Shanghai` 的 `yyyy-MM-dd HH:mm:ss`。  
7. **金额展示**：列表/首页多为字符串 `"50.00"`，详情里 `amount` 可能是 number；UI 统一格式化为两位小数。  
8. **首次话费**：`GET .../accounts/profile` 返回 `need_owner_last_char=true` 时，下单必须带 `owner_last_char`；否则 `400 owner_last_char_required`。  
9. **查询未完成不可缴费**：`query_status` 必须是 `query_success`（或 `confirmed`）后才能 `POST /utility/orders`。  
10. **IM 推送**：订单状态变更会收到 `life_payment_order_update` 自定义消息（§7）；仍建议详情页进入时拉一次 REST 兜底。
11. **户号任务去重**：收到 `409 account_task_already_active` 时不要重试创建，引导用户等待该户号当前任务结束。

---

## 9. 与钱包的关系

- 扣款币种：`coin_99` → 平台币；`usdt` → USDT  
- 支付密码：同 `POST /wallet/pay-pin/*`  
- 余额不足：先引导充值/互兑，再回来缴费  
- 取消成功订单会退回对应平台币余额
