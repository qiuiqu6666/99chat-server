# 群转账（客户端）

> 状态：已实现  
> 底座：红包表 + 新类型 `GROUP_TRANSFER`（直接到账）  
> 完整字段与错误码见 [wallet-client.md](./wallet-client.md) §9 / §6.6 / §11.3

## 要点

| 项 | 约定 |
|----|------|
| 发送 | `POST /wallet/red-packet/send`，`packetType=GROUP_TRANSFER`，`conversationType=GROUP`，必填 `groupId` + `toUserId` + `totalAmount` + `payPin` |
| 到账 | 同步直达，无待领取 / 无超时退回 |
| 成员 | 付款方、收款方均须在群（服务端**本地投影**点查） |
| 限额/手续费 | 与现有红包同一套 `RED_PACKET` |
| 流水 | `ledgerType` 仍为 `RED_PACKET_SEND` / `RED_PACKET_RECEIVE`；`packetType=GROUP_TRANSFER` 且 `displayTitle=群转账` |
| IM | 客户端发群消息 `customType: wallet_group_transfer`（全员可见）；服务端不代发 |
| 单聊专属/红包 | `EXCLUSIVE` / `NORMAL_C2C` 文案仍按「红包」，不受影响 |
| DB | 需执行 `scripts/migrate-wallet-red-packet-group-transfer.sql`（`packet_type` ENUM 含 `GROUP_TRANSFER`） |

## 推荐流程

1. 校验双方在群（客户端可先做；服务端再强校验）
2. `POST /wallet/red-packet/send`
3. 成功后向**当前群会话**发送 `wallet_group_transfer` 卡片
4. 点击卡片 → `GET /wallet/red-packet/{id}`（`id` 可用数字主键或 `publicId`）

详情 / 发单返回的 `packet` 含：`groupId`、`senderUserId`、`toUserId`（=`exclusiveUserId`）、`amount`（=`totalAmount`）、`orderId`（=`id`）、`displayTitle=群转账`、`currency` API 码（`USDT`/`99`）。

> **不要**用 `GET /wallet/transfer/{id}` 查群转账（订单在红包表，不在转账表）。
