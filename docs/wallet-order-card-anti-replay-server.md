# 钱包 IM 卡片防重放 / 防伪造 — 服务端改造说明

> 适用：`99chat-server` IM 回调 + 钱包模块  
> 客户端对照：[wallet-order-card-anti-replay-client.md](./wallet-order-card-anti-replay-client.md)  
> 背景案例：用户在群内对同一 `orderId` 再次发出 `TIMCustomElem`（无新的 `POST /wallet/red-packet/send`）

## 1. 现状

| 环节 | 现状 |
|------|------|
| 扣款 / 建单 | `POST /wallet/red-packet/send`、转账 REST，服务端账本 |
| IM 卡片 | **客户端**发 `TIMCustomElem`（见 `wallet-client.md` §11） |
| `Group.CallbackBeforeSendMsg` | 当前主要走表情包守卫等；**未校验** `wallet_order` |
| `C2C.CallbackBeforeSendMsg` | 好友关系等；**未校验**钱包卡 |

因此：任意登录用户可用 IM SDK 重放旧卡或伪造展示字段；资金仍受 REST 保护，但会话会被刷假卡。

## 2. 改造分两期

### 期 1（P0）：BeforeSend 闸门 + 同单去重

在群 / 单聊发前回调中识别钱包自定义消息并校验。

#### 2.1 识别条件

`MsgBody` 中存在 `TIMCustomElem`，且 `Data` JSON 满足其一：

- `businessID == wallet_order`
- `customType` / `type` ∈ `wallet_transfer` | `wallet_red_packet` | `wallet_group_transfer`

#### 2.2 校验规则（全部通过才放行）

| # | 规则 | 失败建议 ErrorCode |
|---|------|-------------------|
| 1 | `orderId`（或 `publicId`）能查到订单 | `WALLET_CARD_NOT_FOUND` |
| 2 | `From_Account`（转业务 userId）== 订单发起人 | `WALLET_CARD_SENDER_MISMATCH` |
| 3 | 会话匹配：群消息 `GroupId` == 订单 `groupId`；C2C 与转账双方会话一致 | `WALLET_CARD_CONV_MISMATCH` |
| 4 | 卡片 `amount` / `currency`（若有）与库一致 | `WALLET_CARD_PAYLOAD_MISMATCH` |
| 5 | **同 `orderId` + 同会话仅允许成功发送 1 次**（Redis SETNX 或 DB 唯一键） | `WALLET_CARD_DUP` |
| 6 | 管理员代发账号可绕过「发送者==发起人」仅当 `OnlineOnlyFlag`/管理员通道（若走 REST 代发） | — |

拒绝时返回腾讯云 IM 回调拒绝结构（与现有 `ImCallbackVerifier.ImCallbackResponse.reject` 一致），消息**不会**进入会话。

#### 2.3 接入点（代码位置）

| 类 | 说明 |
|----|------|
| `ImGroupBeforeSendMsgCallbackService` | 群发前；今日群重放走这里 |
| `ImC2cBeforeSendMsgCallbackService` | 单聊转账卡 |
| 新建如 `WalletOrderCardSendGuardService` | 解析 Custom + 查 `WalletRedPacket` / 转账单 + 去重 |

注意：群回调目前若 `stickerSendGuard` 未启用会直接 `return null` 跳过处理——钱包闸门应**独立于**表情开关，始终执行（可用独立配置 `im.wallet-card-guard.enabled`）。

#### 2.4 去重存储建议

- Key：`im:wallet-card:{conversationType}:{conversationId}:{orderId}`
- 值：首次放行时的 msg 时间 / requestId
- TTL：略大于红包最长有效期（或永久 + 运营清理）
- 仅在回调**即将返回 OK** 时写入；拒绝不占名额

#### 2.5 日志与观测

结构化日志：`from`、`groupId`/`to`、`orderId`、`rejectCode`、`clientIp`。  
指标：拒绝次数按 ErrorCode 分桶，便于抓重放/伪造。

### 期 2（P1）：服务端代发 IM（根治）

1. `RedPacketService.send` / 转账成功后，服务端用 `ImAdminClient` 发群/C2C 自定义消息（内容与 §11 一致）。
2. 客户端停发同类消息（见客户端文档 §3.5）。
3. BeforeSend：对**普通用户**发来的 `wallet_order` **一律拒绝**；仅允许管理员账号代发（或关闭用户侧该 businessID）。

迁移：双写一段时间（服务端已代发 + BeforeSend 拒用户重发），再强制客户端升级。

## 3. 资金侧（确认无需为「假卡」改动）

- 领取 / 收款仍只认服务端订单状态与权限。
- 假卡或重复卡**不能**二次扣款；若发现重复扣款才是账本 bug，与 IM 重放分开排查。
- 详情接口继续对无权限返回错误，供客户端标无效卡。

## 4. 配置建议

```yaml
im:
  wallet-card-guard:
    enabled: true
    enforce: true          # false 时只打日志放行（灰度）
    allow-admin-send: true # 期 2 代发
```

灰度：先 `enforce=false` 观察会拒绝多少真实流量，再打开拒绝。

## 5. 验收

| 用例 | 期望 |
|------|------|
| 首次合法发红包卡 | BeforeSend OK，AfterSend 落库 |
| 同用户同群同 `orderId` 再发 | `WALLET_CARD_DUP`，会话无第二张 |
| 改金额再发 | `WALLET_CARD_PAYLOAD_MISMATCH` |
| 他人 orderId | `WALLET_CARD_SENDER_MISMATCH` |
| 假 orderId | `WALLET_CARD_NOT_FOUND` |
| 仅 `GET /wallet/red-packet/{id}` | 不影响；不产生新卡片 |

## 7. 实现状态（已落地）

| 项 | 状态 |
|----|------|
| `WalletOrderCardSendGuardService` | 已实现：订单存在、发送者、会话、金额/币种、Redis 同单去重 |
| 群 `Group.CallbackBeforeSendMsg` | 已接入（不再依赖表情开关才进回调） |
| 单聊 `C2C.CallbackBeforeSendMsg` | 已接入 |
| 配置前缀 | `chat99.im.wallet-card-guard` |
| bootstrap 排除 | `pom.xml` 已 exclude 上述 class，避免旧 jar 覆盖本地源码 |
| 默认 | `enabled=true`，`enforce=false`，`log-only=true`（只记日志，不拒发） |

环境变量：

| 变量 | 默认 | 含义 |
|------|------|------|
| `IM_WALLET_CARD_GUARD_ENABLED` | true | 总开关 |
| `IM_WALLET_CARD_GUARD_ENFORCE` | false | false=只记日志仍放行；true=拒绝进会话 |
| `IM_WALLET_CARD_GUARD_LOG_ONLY` | true | 打结构化日志 |
| `IM_WALLET_CARD_GUARD_DEDUPE_TTL_DAYS` | 90 | 同单去重 TTL |

拒绝码：`WALLET_CARD_NOT_FOUND` / `WALLET_CARD_SENDER_MISMATCH` / `WALLET_CARD_CONV_MISMATCH` / `WALLET_CARD_PAYLOAD_MISMATCH` / `WALLET_CARD_DUP`。

管理员账号 `administrator`（及特殊号）放行，便于后续服务端代发。

**生效条件**：重新 `package` 并重启主服务进程后，腾讯云 IM BeforeSend 回调才会走到新闸门。

## 6. 与「打开群 + 拉详情」的关系

用户打开群、拉 `GET /me/robot/groups/{id}`、两次 `GET /wallet/red-packet/{id}` **本身合法**，只是刷新 UI。  
真正写入聊天记录的是随后的 **IM `sendMessage` → `Group.CallbackBeforeSendMsg`**。  
闸门拦的是后者，不是前几类 REST。
