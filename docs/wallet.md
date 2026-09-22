# 钱包与双币种支付

> 99chat-server 钱包模块：TRC20 USDT 充值、平台币（人民币分）、互兑、转账、红包、提现。

## 环境变量

| 变量 | 说明 |
|------|------|
| `TRON_DEPOSIT_MNEMONIC` | HD 收款助记词（BIP44 `m/44'/195'/0'/0/{users.id}`） |
| `TRON_HOT_WALLET_PRIVATE_KEY` | 提现热钱包私钥（hex，与收款助记词独立） |
| `TRONGRID_API_KEY` | TronGrid API Key（建议配置；请求头 `TRON-PRO-API-KEY`） |
| `TRONGRID_BASE_URL` | 主网默认 `https://api.trongrid.io` |
| `TRONGRID_QPS_LIMIT` | TronGrid 全局限流（默认 6 QPS） |
| `WALLET_DEPOSIT_MODE` | `address-poll`（默认）或 `block-scan`（5 万+ 用户推荐） |
| `WALLET_DEPOSIT_CONFIRM_SCAN_INTERVAL_MS` | 待确认充值跟踪间隔（默认 30000） |
| `WALLET_DEPOSIT_HOT_SCAN_INTERVAL_MS` | 热地址发现间隔（默认 60000） |
| `WALLET_DEPOSIT_COLD_SCAN_INTERVAL_MS` | 冷地址游标扫描间隔（默认 180000） |
| `WALLET_DEPOSIT_HOT_TTL_MINUTES` | 打开充值页后热标记 TTL（默认 120 分钟） |
| `TRON_USDT_CONTRACT` | 默认主网 USDT 合约 |

## 币种

- **USDT**：链上 TRC20 充值；平台内转账 **不上链、无手续费**；可提现。
- **PLATFORM**：1 单位 = 1 元展示，库内 **分（整数）**；不可提现。

## 汇率

- 数据源：[Frankfurter](https://api.frankfurter.dev/v1/latest?from=USD&to=CNY)
- Admin 配置 `markupBps`、`floatBps`、最低提现额。
- 互兑向下取整，零头计入全站 `totalExchangeSurplusFen`。

## 充值

- 最小 **1 USDT**
- **19** 个区块确认后入账
- 注册响应 `wallet.depositAddress`
- `GET /wallet/me` 会将充值地址标记为**热地址**，优先扫描（约 30s 内）

### 充值检测架构

| Job | 间隔 | 职责 |
|-----|------|------|
| `DepositConfirmationJob` | 30s | 仅跟踪 `CONFIRMING` 记录，满 19 确认入账 |
| `DepositDiscoveryJob`（热） | 60s | 扫描热地址（打开充值页 / 充值进行中） |
| `DepositDiscoveryJob`（冷） | 180s | 游标分批扫描其余地址 |
| `DepositBlockScannerJob` | 3s | **`block-scan` 模式**：扫 Tron 新区块 + Redis 地址簿 |

**模式**（`WALLET_DEPOSIT_MODE`）：

- `address-poll`（默认）：热/冷地址轮询，适合用户量 &lt; 5 万
- `block-scan`：按区块解析 USDT Transfer，适合 5 万+ 用户；需 Redis 地址簿（启动时自动 rebuild）

**加速**：`POST /wallet/deposits/report-tx` — body `{ "txId": "..." }`，JWT 必需；提交链上 txHash 后立即查询。

**到账预期**（热用户）：链上 19 确认（约 1 分钟）+ 确认 Job（≤ 15s）。

## 红包类型

| 类型 | 说明 |
|------|------|
| `NORMAL_GROUP` | 单个金额 × 包数，群内领取 |
| `LUCKY_GROUP` | 总金额随机拆分 |
| `EXCLUSIVE` | 指定用户，直接到账 |
| `NORMAL_C2C` | 单聊直接到账 |
| 群红包 | 24 小时未领完退回发送方 |

群 **普通 / 拼手气** 红包：有人领取后服务端发 **IM 定向群消息**（`businessID: red_packet_claim_notice`，`To_Account=发包人`）；过期退回走账本退款 + 支付助手通知，**不再**发 TCP。见 [wallet-client.md](./wallet-client.md) §9.4。

## IM 自定义消息

服务端**不**代发 IM 消息。转账/红包成功后由**客户端**自行发 `TIMCustomElem`（建议 `customType`：`wallet_transfer` / `wallet_red_packet`），格式见 [wallet-client.md](./wallet-client.md) §11。

## 支付密码

6 位数字，与**登录密码独立**。**发红包、转账、提现、互兑**前校验；**领取红包不需要**交易密码。

| 能力 | 接口 | 说明 |
|------|------|------|
| 首次设置 | `POST /wallet/pay-pin/set` | 须 JWT；已设置则 `409 PAY_PIN_ALREADY_SET` |
| 修改 | `POST /wallet/pay-pin/change` | 须 JWT + **旧 PIN** |
| 短信重置 | `POST /wallet/pay-pin/reset` | 须 JWT + 短信码；**不需要旧 PIN** |

**短信重置流程**

1. `POST /sms/send` — `{ "phone": "<绑定手机 E.164>", "scene": "PAY_PIN_RESET" }`
2. `POST /wallet/pay-pin/reset` — `{ "smsCode": "...", "payPin": "......" }`（Header 带 JWT）

服务端用 JWT 对应用户的 `users.phone` 校验验证码（body **无需**再传 `phone`）。成功后清除 `PAY_PIN_LOCKED` 与失败计数。

与登录密码重置（`scene=RESET` → `/auth/password/reset`）使用**不同**验证码桶，互不影响。

客户端对接详见 [wallet-client.md](./wallet-client.md)。

## 用户 API（JWT）

> **统一响应信封**：App 端接口成功响应（HTTP 2xx）经 `GlobalResponseWrapper` 包装为 `{code:0, message:"ok", data:...}`；排除 `/admin/**`、`/api/v1/**`、`/webhook/**`。错误响应保持 `GlobalExceptionHandler` 既有格式（`{code,message}`）。

| 方法 | 路径 |
|------|------|
| GET | `/wallet/me` |
| GET | `/wallet/currencies` | 币种列表（logo、名称、价格、余额、充提能力） |
| POST | `/wallet/pay-pin/set` |
| POST | `/wallet/pay-pin/change` |
| POST | `/wallet/pay-pin/reset` | 短信重置（scene=`PAY_PIN_RESET`，无需旧密码） |
| GET | `/wallet/deposits` | 链上充值记录 |
| POST | `/wallet/deposits/report-tx` | 提交 txHash 加速到账检测 |
| GET | `/wallet/withdrawals` | 提现记录 |
| GET | `/wallet/transfers` | 平台内转账记录（`direction=sent\|received\|all`） |
| GET | `/wallet/transfer/{orderId}` | 转账单笔查单（unknown recover） |
| GET | `/wallet/transfer/by-client-id/{clientOrderId}` | 按客户端幂等 ID 查转账 |
| GET | `/wallet/exchanges` | 闪兑/互兑订单记录 |
| GET | `/wallet/red-packets` | 红包记录（`role=sent\|received`） |
| GET | `/wallet/red-packet/{id}` | 红包详情（含领取列表，需 JWT） |
| GET | `/wallet/red-packet/{id}/claims` | 红包领取明细（`RedPacketClaimRecord`：币种 / 手气最佳 / 昵称头像） |
| GET | `/wallet/ledger` | 资金流水（富集 `WalletLedgerRecord` + 统一分页；可选 `ledgerType` 多选；可选 `source=CHAIN\|INTERNAL` 区分链上充提与运营调账） |
| POST | `/wallet/exchange` |
| POST | `/wallet/transfer` |
| POST | `/wallet/red-packet/send` |
| POST | `/wallet/red-packet/{id}/claim` |
| POST | `/wallet/withdraw` |

## Admin API（IP 白名单）

| 方法 | 路径 |
|------|------|
| GET | `/admin/wallet/fee-config` |
| PUT | `/admin/wallet/fee-config/{id}` |
| GET | `/admin/wallet/limit-config` |
| PUT | `/admin/wallet/limit-config/{id}` |
| GET/PUT | `/admin/wallet/exchange-config` |
| GET | `/admin/wallet/stats` |
| POST | `/admin/wallet/backfill-addresses` |

用户提现与手续费对接详见 [wallet-withdraw.md](./wallet-withdraw.md)。

## 运维

热钱包需预充 **TRX** 以支付 TRC20 转账能量/带宽。

### 环境加载方式

```bash
cp .env.example .env
# 编辑 .env 填入 TRON_DEPOSIT_MNEMONIC、TRON_HOT_WALLET_PRIVATE_KEY、TRONGRID_API_KEY 等
chmod +x scripts/run-with-env.sh
./scripts/run-with-env.sh
```

或在 systemd / 宝塔「环境变量」里逐条配置与 `.env` 相同变量名。
