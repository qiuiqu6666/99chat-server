# 钱包 API — 后端实现说明

> 版本：v1.1  
> 适用：99chat Flutter 联调、`wallet_api.dart`  
> 关联：[wallet.md](./wallet.md)、[wallet-client.md](./wallet-client.md)

---

## 转账查单（P0 — unknown recover）

### GET `/wallet/transfer/{orderId}`

按服务端订单 ID 查询（`orderId` 为数字字符串，对应 `wallet_transfer.id`）。

- **鉴权**：`Authorization: Bearer <token>`
- **权限**：仅付款方或收款方可查；否则 **404** `TRANSFER_NOT_FOUND`

**200 示例**

```json
{
  "id": "12",
  "clientOrderId": "transfer_20260603_abc",
  "status": "COMPLETED",
  "currency": "USDT",
  "amount": 500000,
  "fromUserId": "s9q2qry8lg",
  "toUserId": "2wcsfkchoi",
  "memo": "午饭",
  "createdAt": "2026-06-03T10:00:00Z"
}
```

### GET `/wallet/transfer/by-client-id/{clientOrderId}`

按付款方幂等 ID 查询（仅查**当前登录用户作为付款方**的记录）。

- **鉴权**：JWT
- **404**：不存在或非本人发起

响应体同上。

### status 与客户端 orderState 映射

| status | 说明 | 客户端 orderState |
|--------|------|-------------------|
| `COMPLETED` | 已落库并入账（当前唯一终态） | `success` |

平台内转账为**同步**入账，成功返回即 `COMPLETED`。超时 unknown 时应用查单接口恢复终态。

### POST `/wallet/transfer`（补充）

请求体可带可选幂等字段：

```json
{
  "toUserId": "xyz99abcde",
  "currency": "USDT",
  "amount": 500000,
  "payPin": "123456",
  "memo": "可选",
  "clientOrderId": "transfer_20260603_abc"
}
```

| 规则 | 说明 |
|------|------|
| 幂等 | 同一 `fromUserId` + `clientOrderId` 重复提交，参数一致则返回已有订单 |
| 冲突 | 同 `clientOrderId` 但收款人/币种/金额不同 → **409** `CLIENT_ORDER_ID_CONFLICT` |

**200** 返回与查单相同的 `TransferDetailResponse`（含 `id`、`status`、`clientOrderId`）。

---

## 其它用户 API（已实现，联调前可核对）

| 方法 | 路径 | 状态 |
|------|------|------|
| POST | `/wallet/exchange` | 已实现 |
| GET | `/wallet/transfers` | 已实现（`direction=sent\|received\|all`，分页） |
| GET | `/wallet/transfer/{orderId}` | **本版新增** |
| GET | `/wallet/transfer/by-client-id/{clientOrderId}` | **本版新增** |
| POST | `/wallet/transfer` | 已实现（支持 `clientOrderId`） |
| GET | `/wallet/withdrawals` | 已实现；单笔 GET 提现查单暂未提供 |

---

## 错误码

| HTTP | code | 场景 |
|------|------|------|
| 404 | `TRANSFER_NOT_FOUND` | 订单不存在或无权查看 |
| 409 | `CLIENT_ORDER_ID_CONFLICT` | 幂等 ID 已被另一笔不同参数订单占用 |
| 400 | `INVALID_INPUT` | `orderId` 非数字等 |

支付密码错误等业务码应返回 **4xx + 业务 code**（如 `PAY_PIN_INVALID`），勿用 HTTP **401**，避免客户端整站登出。

---

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-06-03 | 新增转账 GET 查单、`clientOrderId` 幂等 |
