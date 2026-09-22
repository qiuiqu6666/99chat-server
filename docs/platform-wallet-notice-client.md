# 支付助手通知（platform_wallet_notice）— 后端对接

> 版本：v1.0（已实现 P0）  
> 适用：Flutter 客户端 + 99chat-server  
> 发件 IM 账号：**`99Chat`**（伪公众号，普通 IM 用户 + 注册自动好友）

---

## 1. 与现有能力的关系（冲突说明）

| 能力 | 发件账号 | 消息格式 | 是否冲突 |
|------|----------|----------|----------|
| **支付助手通知（本文）** | `99Chat` | `customType` / `businessID` = **`platform_wallet_notice`** | — |
| 系统欢迎语 | `99Messenger` | 普通文本 `TIMTextElem` | **不冲突**（不同会话） |
| ~~旧版钱包推送~~ | ~~`99Messenger`~~ | ~~`type: wallet_deposit` 嵌套 payload~~ | **已移除**，勿再依赖 |
| 红包/转账卡片 | 用户/群 | `businessID: wallet_order` | **不冲突**（不同 businessID、不同会话） |
| 腾讯云原生公众号 | `@TOA#_@TOA#d4CH` 等 | 订阅 + 广播 API | **不冲突**（本方案不用订阅） |

### 客户端必须配置

```dart
// lib/config.dart
PLATFORM_OFFICIAL_ACCOUNT_ID = '99Chat'
```

会话 ID：`c2c_99Chat`

### 注册后关系

服务端自动执行（无需客户端 `addFriend` / `subscribeOfficialAccount`）：

1. `99Messenger` ↔ 用户：双向好友 + 欢迎文本  
2. `99Chat` ↔ 用户：双向好友（无欢迎语，等首条支付通知）

---

## 2. 自动触发（已实现）

| noticeType | 触发点 | 说明 |
|------------|--------|------|
| `deposit` | USDT 充值确认入账 | `DepositConfirmationJob` / `DepositScanService` |
| `withdraw` | 提币成功 / 失败 | `WithdrawService.broadcastPending` |
| `flashExchange` | 闪兑完成 | `WalletExchangeService.exchange` |
| `setTradePassword` | 首次设置资金密码 | `POST /wallet/pay-pin` |
| `changeTradePassword` | 修改 / 短信重置资金密码 | `PUT /wallet/pay-pin`、`POST /wallet/pay-pin/reset` |
| `redPacketRefund` | 红包过期退回 | `RedPacketService.expireRefund` |
| `lifePayment` | 生活缴费成功 / 失败 / 取消 | `LifePaymentOrderUpdateNoticeService`（终态订单） |

失败仅记日志，**不阻塞**主业务 HTTP 响应。

---

## 3. 管理端手动下发（联调 / 运营）

**`POST /admin/im/platform-wallet-notice`**

鉴权：Admin **IP 白名单**（与其它 `/admin/**` 相同）

**Request（camelCase）**

```json
{
  "toUserId": "abc12def34",
  "noticeType": "general",
  "title": "测试通知",
  "serviceName": "支付助手",
  "statusLabel": "成功",
  "summary": "这是一条测试",
  "rows": [
    { "label": "金额", "value": "1.00 USDT", "emphasize": true }
  ],
  "actionLabel": "查看详情",
  "actionUrl": "https://example.com",
  "orderId": "TEST001"
}
```

**Response 200**

```json
{ "ok": true, "fromAccount": "99Chat" }
```

---

## 4. IM 自定义消息 `Data` 契约

服务端发送 **单层 JSON 字符串**（与客户端 `platform_wallet_notice_message.dart` 一致）。

### 4.1 识别字段

| 字段 | 值 |
|------|-----|
| `customType` | `platform_wallet_notice` |
| `businessID` | `platform_wallet_notice` |
| `version` | `1` |
| `title` | **必填** |

### 4.2 展示字段

| 字段 | 说明 |
|------|------|
| `noticeType` | §5 枚举 |
| `serviceName` | 顶栏，默认「支付助手」 |
| `statusLabel` | 标题右侧状态 |
| `summary` | 灰色摘要 |
| `rows[]` | `{ label, value, emphasize? }` |
| `actionLabel` / `actionUrl` | 底部按钮 |
| `orderId` | 业务单号 |

`MsgContent.Desc` = `title`（会话摘要 / 离线推送文案）。

### 4.3 提币成功示例

```json
{
  "customType": "platform_wallet_notice",
  "businessID": "platform_wallet_notice",
  "version": 1,
  "noticeType": "withdraw",
  "serviceName": "支付助手",
  "title": "提币成功",
  "statusLabel": "成功",
  "summary": "您的提币申请已处理完成，资产已从平台钱包转出。",
  "orderId": "WD123",
  "rows": [
    { "label": "提币数量", "value": "128.50 USDT", "emphasize": true },
    { "label": "到账地址", "value": "TXyz9k…8f2A" },
    { "label": "手续费", "value": "1.00 USDT" },
    { "label": "订单号", "value": "WD123" },
    { "label": "时间", "value": "2026-05-28 14:32:18" }
  ],
  "actionLabel": "查看详情"
}
```

---

## 5. `noticeType` 枚举

| 值 | 场景 |
|------|------|
| `withdraw` | 提币成功/失败 |
| `deposit` | 充币到账 |
| `flashExchange` | 闪兑完成 |
| `setTradePassword` | 首次设置资金密码 |
| `changeTradePassword` | 修改/重置资金密码 |
| `redPacketRefund` | 红包过期退回 |
| `general` | 运营手动下发 |

---

## 6. 配置项

```yaml
chat99:
  platform-wallet-notice:
    sender-user-id: "99Chat"
    sender-display-name: "99Chat支付助手"
    service-name: "支付助手"
    bootstrap-on-startup: true
    auto-friend-on-register: true
    notify-enabled: true
```

环境变量：`PLATFORM_WALLET_NOTICE_SENDER_ID`、`PLATFORM_WALLET_NOTICE_ENABLED` 等。

---

## 7. 客户端联调检查表

- [ ] `PLATFORM_OFFICIAL_ACCOUNT_ID` = `99Chat`
- [ ] 新注册用户好友列表含该账号（或首条通知前服务端已 `friend_add`）
- [ ] 聊天内为白底「支付助手」卡片，非 `wallet_order` 红包卡
- [ ] 会话列表摘要：`[支付助手] 提币成功` 或等价
- [ ] 勿与 `99Messenger` 欢迎会话混淆

---

## 8. 待办（P1+）

- [ ] `actionUrl` 深链与 H5 域名统一配置
- [ ] 同一 `orderId` + `noticeType` 幂等去重
- [x] 老用户批量补好友 Job（`POST /admin/notify/friend-backfill`）
- [ ] `POST /internal/im/platform-wallet-notice`（内网 Token，若需要与 admin 分离）

关联：[system-notify-and-announcements.md](./system-notify-and-announcements.md)、[wallet-client.md](./wallet-client.md)

---

## 9. 老用户批量补好友

对注册早于自动 `friend_add` 的用户，批量与 `99Messenger`、`99Chat` 建立双向好友（幂等，已是好友则跳过）。

**Admin 手动触发**（需 IP 白名单）：

```http
POST /admin/notify/friend-backfill
Content-Type: application/json

{ "maxUsers": 0, "resetCursor": true }
```

- `maxUsers`: `0` 表示不限；建议首次全量用 `0`，调试用小值如 `100`
- `resetCursor`: `true` 从第一页用户重新开始

**查询进度**：

```http
GET /admin/notify/friend-backfill/status
```

**配置**（`application.yml` → `chat99.notify-friend-backfill`）：

| 键 | 默认 | 说明 |
|----|------|------|
| `system-notify-enabled` | `true` | 补 `99Messenger` |
| `platform-wallet-enabled` | `true` | 补支付助手 |
| `batch-size` | `100` | DB 分页大小 |
| `delay-between-users-ms` | `50` | 每用户 IM 间隔 |
| `scheduled-enabled` | `false` | 定时增量 Job |
| `max-users-per-scheduled-run` | `500` | 每 tick 上限 |
| `run-on-startup` | `false` | 启动时异步跑一轮 |
