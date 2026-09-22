# 钱包 IM 卡片防重放 / 防伪造 — 客户端改造说明

> 适用：Flutter / Android / iOS  
> 相关：`wallet-client.md` §11、`wallet-red-packet-claim-notice-client.md`  
> 服务端对照：[wallet-order-card-anti-replay-server.md](./wallet-order-card-anti-replay-server.md)

## 1. 问题

当前转账 / 红包 / 群转账成功后，由**客户端**自行发 `TIMCustomElem`（`businessID=wallet_order`）。  
IM SDK 可再次发送同一 `orderId` 的自定义消息，或篡改金额等展示字段。  
**资金以 REST 账本为准**；本改造解决的是「会话里假卡 / 重复卡」。

## 2. 目标行为

| 场景 | 期望 |
|------|------|
| 正常发红包/转账 | 业务 REST 成功后，**仅一次**发出对应 IM 卡片 |
| 冷启动 / 重进会话 | **不得**因本地未发成功缓存而再次 `sendMessage` 同一 `orderId` |
| 长按转发 / 多选转发 | **禁止**转发 `wallet_order` 类自定义消息 |
| 列表渲染 | 可先用卡片占位；**金额、状态、能否领取**必须走 REST |
| 无效卡 | 订单不存在 / 发送者不符 / 会话不符 → 显示「无效卡片」，不可点领 |

## 3. 必须改的客户端逻辑

### 3.1 发送幂等（P0，立刻可做）

对 `wallet_transfer` / `wallet_red_packet` / `wallet_group_transfer`：

1. REST 成功拿到 `orderId`（或 `publicId`）后，本地记：`cardSent:{orderId} = true`（含磁盘持久化）。
2. 真正调用 IM `sendMessage` 前检查该标记；已发送则**跳过**，只刷新 UI。
3. `sendMessage` 成功回调后再写标记；失败可重试，但同一 `orderId` 成功后禁止再发。
4. **禁止**在 `didLogin` / 打开会话 / 拉历史后，用本地草稿或「补发卡」逻辑重发。

> 案例：群 `m25KMR3N5CY` 中红包 `368` 在 21:13 已发过卡片；22:49 冷启动后又发了同一 `orderId` 的自定义消息。属客户端重放，不是二次扣款。

### 3.2 展示以 REST 为准（P0）

| 接口 | 用途 |
|------|------|
| `GET /wallet/red-packet/{id}` | 详情、金额、状态 |
| `GET /wallet/red-packet/{id}/claim-state` | 领取态 |
| 转账查单 | 见 `backend-wallet-api.md` |

规则：

- IM `Data` 里的 `amount` / `status` / `greeting` **仅作占位**，打开或可见时用 REST 覆盖。
- REST 404 / 无权限 / `senderUserId` ≠ 消息 `From_Account` / 群 ID 与订单 `groupId` 不一致 → **无效卡 UI**。
- 领取、收款**只能**走 `POST /wallet/red-packet/{id}/claim` 等正式接口，禁止「点卡片就假装到账」。

### 3.3 禁用转发与复制发送（P0）

对 `businessID=wallet_order`（及 `customType` 为上述三类）：

- 关闭转发、多选转发、合并转发入口。
- 若 SDK 仍允许「再发送一条相同 Custom」：拦截并 toast「钱包消息不可转发」。

### 3.4 适配服务端 BeforeSend 拒发（P0/P1）

上线服务端闸门后，重复/伪造卡会被 IM 回调拒绝。客户端应：

- 识别发送失败（错误码由服务端文档约定，如 `WALLET_CARD_DUP` / `WALLET_CARD_INVALID`）。
- **不要**无限重试同一卡片。
- 对用户提示：「该红包/转账卡片已发送过」或「无效钱包消息」。

### 3.5 服务端代发后的最终形态（P1）

服务端改为管理员代发 IM 后：

- 客户端在 REST 成功后**停止**自行 `sendMessage` 钱包卡。
- 只监听会话内新消息 / 拉取历史展示卡片。
- 本地仍保留「本单已有卡片」去重，避免旧版 App 双发。

## 4. 联调清单

- [ ] 发红包：REST 一次 + IM 卡片一次；杀进程重进**不会**再出第二张同 `orderId`
- [ ] 群会话打开：可 `GET` 详情刷新状态，但**不**触发新 `sendMessage`
- [ ] 伪造 `orderId` / 改金额：UI 无效或发送被拒
- [ ] 转发入口不可用
- [ ] 真正领取仍只能 claim 成功才到账

## 5. 非目标

- 不改变扣款/入账账本语义。
- 不要求客户端校验腾讯云回调签名（那是服务端职责）。
