# 微服务拆分 · 安全模拟与切流手册

> **原则：现网主服 `:8081` 默认行为不变；所有拆分在模拟环境验证；只有你明确授权后才改正在运行的生产配置。**

---

## 1. 现网默认（无需任何操作）

| 配置 | 默认值 | 行为 |
|------|--------|------|
| `WALLET_PROXY_ENABLED` | **未设置 / false** | 主服 `WalletController` 处理 `/wallet/**` |
| `wallet-service` | 可不部署 | 无影响 |
| `ROBOT_MODULE_ENABLED` | 见 `.env` | 机器人仍可在主服或已切流 proxy |

主服新增 `WalletServiceProxyController` 使用：

```java
@ConditionalOnProperty(name = "wallet.proxy-enabled", havingValue = "true")
```

**仅当显式 `wallet.proxy-enabled=true` 时才注册代理**，与本地 `WalletController`（`proxy-enabled=false` 时）互斥，避免双映射。

---

## 2. 已落地的模拟基础设施

| 组件 | 路径 | 说明 |
|------|------|------|
| 主服代理（默认关） | `WalletServiceHttpProxy` / `WalletServiceProxyController` | `application.yml` → `wallet.proxy-enabled: false` |
| wallet-service 骨架 | `/www/wwwroot/wallet-service` | `:8093` simulation stub |
| 直连冒烟 | `wallet-service/scripts/smoke.sh` | health + `/wallet/simulation/status` |
| 代理冒烟 | `wallet-service/scripts/simulate-proxy.sh` | 可选 `RUN_MAIN_PROXY_SIM=1` |

---

## 3. 模拟验证步骤（不动生产 `.env`）

### 3.1 仅验证 wallet-service 进程

```bash
cd /www/wwwroot/wallet-service
cp .env.example .env   # 可选
./scripts/build.sh
./scripts/start.sh
./scripts/smoke.sh
curl -s http://127.0.0.1:8093/wallet/simulation/status
```

### 3.2 一键模拟 + 全量接口契约（推荐）

```bash
cd /www/wwwroot/wallet-service
./scripts/simulation-all.sh          # wallet-service + sim-main :18081 + 契约测试
./scripts/api-contract-split.sh      # 仅跑契约（各微服务直连 + 主服代理）
./scripts/api-contract-jwt-read.sh     # 带 JWT 全链路读回归（自动 mint）
```

可选环境变量：

| 变量 | 说明 |
|------|------|
| `SIM_USER_JWT` | App JWT，跑主服 `/wallet/**` 读接口 200 校验 |
| `SIM_USER_ID` | 指定回归用户（默认自动选有钱包用户） |
| `SIM_ADMIN_JWT` / `MAIN_PRIVILEGED_JWT` | 三公特权 JWT |
| `RUN_MAIN_PROXY_SIM=0` | 不启 sim-main |
| `SKIP_WALLET_PROXY_SIM=1` | 契约测试跳过 :18081 钱包代理段 |

### 3.3 验证主服 → wallet-service 代理（独立端口，非 8081）

```bash
cd /www/wwwroot/wallet-service
RUN_MAIN_PROXY_SIM=1 ./scripts/simulate-proxy.sh
```

说明：

- 在 **`18081`** 启动一份**临时**主服实例，环境变量 `WALLET_PROXY_ENABLED=true`
- 请求 `http://127.0.0.1:18081/wallet/simulation/status` 应返回 wallet-service stub JSON
- **不会**修改 `/www/wwwroot/99chat-server/.env`
- **不会**停止或重启现网 `:8081` 进程

### 3.3 停止模拟进程

```bash
cd /www/wwwroot/wallet-service
./scripts/stop.sh
# 若跑过 simulate-proxy 且主进程仍在：
kill "$(cat run/sim-main-server.pid)" 2>/dev/null; rm -f run/sim-main-server.pid
```

---

## 4. 禁止在未授权时做的操作

- 修改 **生产** `99chat-server/.env` 设置 `WALLET_PROXY_ENABLED=true`
- 在未迁入账本/Job 前对现网开启钱包代理（会导致 `/wallet/**` 落到空壳服务）
- 重启现网主服 JAR 用于「试拆分」（应使用 `18081` 模拟实例）

---

## 5. 正式切流检查清单（仅当你明确说「可以切换」时执行）

由运维按顺序核对：

- [ ] wallet-service 已迁入：账本 API、充提 Job、红包、PayPin、限额手续费
- [ ] 模拟环境 `RUN_MAIN_PROXY_SIM=1` 全绿 + 业务回归（发/领红包、转账、充值查询）
- [ ] 数据库策略确定（同库过渡 or 独立 `wallet` 库）
- [ ] Admin 财务接口路径确认（`/admin/**` 是否一并代理或保留主服）
- [ ] IM 卡片防伪 `WalletOrderCardSendGuard` 已对接 wallet-service 或保留主服只读校验
- [ ] 生活缴费扣款、群直播打赏 Integration 已指向 wallet-service
- [ ] 备份 + 低峰窗口 + 回滚方案（`WALLET_PROXY_ENABLED=false` 并重启主服）

**正式切流（已执行）：** 资金节点用主服同一 JAR 跑在 `:8093`（连主库），主服 `WALLET_PROXY_ENABLED=true` 转发 `/wallet/**`；资金 Job 仅在 `:8093`。

回滚：`WALLET_PROXY_ENABLED=false` 后执行 `scripts/restart-main-8081.sh`（不要用会误杀 8093 的 `pkill -f server-*.jar`）。

---

## 6. 推荐拆分顺序（与模拟并行）

1. **机器人** — `ROBOT_MODULE_ENABLED=false`（已有 proxy）
2. **wallet-service** — 本手册 + 逐步迁入 `wallet` 包（资金节点已切流）
3. **message-archive** — Kafka 消费独立进程（见第 8 节，**已切流**）
4. **life-payment** — 依赖 wallet 扣款 API

**保留主服内核：** auth / user / group / im / integration / oss

---

## 7. 相关文档

- [wallet.md](./wallet.md) — 钱包业务
- [group-live-architecture.md](./group-live-architecture.md) — 代理 + Integration 范例
- [/www/wwwroot/wallet-service/README.md](../../wallet-service/README.md) — 资金节点
- [/www/wwwroot/message-archive-service/README.md](../../message-archive-service/README.md) — 归档节点

---

## 8. message-archive 拆分（已切流）

与资金节点一样：同一 `99chat-server` JAR，另存为 `message-archive-node.jar`，端口 **`:8094`** 仅 `127.0.0.1`。

| 配置 | 主服 `:8081` | 归档节点 `:8094` |
|------|--------------|------------------|
| `MSG_ARCHIVE_ENABLED` | `true`（保留 webhook 投递 + GET 历史/快照） | `true` |
| `MSG_ARCHIVE_WORKER_ENABLED` | 切流前默认 `true`（现网仍消费）；切流后 `false` | 备机 `false`；切流后 `true` |

拆出去：归档写入、DLQ、回填 Job、建表、对账、滞后监控。  
可留主服：`GET /im/snapshot`、`GET /me/messages/**`。

**禁止**在主服仍 `worker=true` 时把归档节点也设 `worker=true`：同一 group `chat99-im-archive-writer` 会 rebalance，现网分区被抢走。也不要用新 group + earliest，会回放整 topic、重复写库。

```bash
cd /www/wwwroot/message-archive-service
./scripts/start.sh    # 默认 worker=false
./scripts/smoke.sh
```

**正式切流（已执行）：** 归档节点 `:8094` 消费 `chat99-im-archive-writer`；主服 `MSG_ARCHIVE_WORKER_ENABLED=false`，保留 webhook 投递与 GET 历史/快照。

回滚：主服 `.env` 设 `MSG_ARCHIVE_WORKER_ENABLED=true`，再 `restart-main-8081.sh`；归档节点改回 `false` 或停掉。不要 `pkill` `server-*.jar`。
