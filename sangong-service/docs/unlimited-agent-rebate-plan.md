# 无限代理与实时返水改造计划

## 1. 目标

将当前“单层代理、按局返水”升级为“统一用户树、无限层级代理、实时增量返水”。本次仅制定计划，不修改业务代码。

## 2. 核心模型

代理和玩家是同一种用户节点：

```text
租户
└── 用户 A
    ├── 用户 B（可下注、可做代理）
    │   ├── 用户 C（玩家）
    │   └── 用户 D（代理，也可下注）
    └── 用户 E（玩家）
```

- 每个用户都可以下注、做庄、合庄和领取本人返水。
- 拥有下级的用户就是代理。
- 代理也可以同时参与游戏。
- 每个租户独立维护用户关系树。
- 每个用户只有一个直属上级。
- 支持无限层级，但必须防止循环绑定。

### 2.1 跨租户用户隔离

同一个 IM 用户可以同时存在于多个租户，并且在不同租户中拥有完全独立的身份关系：

```text
用户 U
├── 租户 A：总代理，返水比例 5%，余额/团队/账本 A
└── 租户 B：普通玩家或一级代理，返水比例 2%，余额/团队/账本 B
```

必须区分：

- 全局身份：`im_user_id`，用于识别主服务用户。
- 租户成员身份：`tenant_id + user_id`，用于保存该用户在租户中的状态。
- 租户代理关系：`tenant_id + user_id + parent_user_id`。
- 租户余额：每个租户独立一份，不能使用全局余额。
- 租户比例：玩家返水比例和代理比例都不能跨租户继承。
- 租户流水、返水、账本、团队和划转记录全部必须带 `tenant_id`。

因此不能把 `parent_user_id`、`group_id`、`player_rebate_pct` 或代理比例直接作为全局用户属性使用。

## 2.2 多租户数据安全原则

所有 Repository 查询和更新必须满足以下条件：

```text
WHERE tenant_id = 当前 TenantContext.require()
```

禁止只根据 `user_id`、`im_user_id` 或全局自增 ID 查询业务数据。

建议使用以下复合唯一键和索引：

```text
tenant_id + user_id
tenant_id + im_user_id
tenant_id + user_id + parent_user_id
tenant_id + user_id + account_type
tenant_id + stat_date + user_id
```

用户进入租户时，通过 `sangong_tenant_users` 建立租户成员记录；不存在成员记录时，不允许访问该租户业务数据。

所有跨租户后台操作必须显式切换租户并重新执行权限校验，不能复用上一个请求的用户树或代理缓存。

ThreadLocal 的 `TenantContext` 必须在请求结束、异步任务结束和 Kafka 消费结束时清理。异步执行必须显式传递租户 ID，不能依赖原请求线程上下文。

数据库层面优先使用带 `tenant_id` 的复合外键或唯一约束；无法增加外键时，在 Repository 和 Service 两层同时做租户校验。

## 3. 返水规则

### 3.1 流水

```text
总流水 = 闲流水 + 庄流水
```

- 闲流水：用户实际下注金额。
- 庄流水：主庄或合庄成员承担的庄方流水。
- 合庄庄流水按合庄金额占比拆分：

```text
成员庄流水 = 庄方总流水 × 成员合庄金额 ÷ 合庄总金额
```

- 撤回下注、无效局、冲正局不计入最终流水。
- 代理自己的游戏流水也计入本人玩家返水。

### 3.2 增量返水

```text
待返水流水 = 当前累计流水 - 已返水流水
本次返水 = 待返水流水 × 当前返水比例
```

已返水流水不得再次计算。没有返水比例的用户不产生返水。

### 3.3 代理差额

每个用户分别拥有两类收益：

1. `PLAYER_REBATE`：本人庄/闲流水产生的玩家返水。
2. `AGENT_DIFF`：下级流水产生的代理差额。

```text
代理差额 = 下级有效流水 ×
          （当前代理比例 - 下级实际返水比例）
```

例如：A=5%、B=3%、C=1%、D=1%，D 流水 100,000：

```text
D 玩家返水：1,000
C 代理差额：0
B 代理差额：2,000
A 代理差额：2,000
```

每一级都有独立领取游标，不能共用一个 `claimed` 字段。

## 4. 比例规则

- 直属上级可以为直属下级设置或修改返水比例。
- `0 <= 下级比例 <= 直属上级可分配比例`。
- 代理自身比例由其直属上级修改。
- 只有目标用户没有未返水流水时才允许修改：

```text
当前累计流水 - 已返水流水 = 0
```

- 代理有未结算的下级差额时，不允许直接修改代理比例。
- 修改时锁定返水账户，写入比例变更审计记录。
- 新比例只作用于后续流水，不影响历史账务。

## 5. 数据库改造

建议新增迁移：

```text
scripts/migrations/005_user_hierarchy.sql
scripts/migrations/006_rebate_turnover.sql
scripts/migrations/007_rebate_accounts.sql
scripts/migrations/008_user_transfer.sql
```

### 5.1 用户关系表

新增 `sangong_user_hierarchy`：

```text
tenant_id, user_id, parent_user_id, level, path,
is_active, created_at, updated_at
```

唯一键为 `tenant_id + user_id`。旧的 `group_id` 和代理表保留用于兼容迁移，新逻辑改读用户关系树。

另外新增 `sangong_tenant_users` 作为租户成员表：

```text
tenant_id
user_id
im_user_id
status
created_at
updated_at
```

唯一键为 `tenant_id + user_id`，并建议建立 `tenant_id + im_user_id` 唯一键。这样同一个全局用户可以在多个租户各有一份成员资料，但同一租户内不会重复建档。

### 5.2 流水表

新增 `sangong_rebate_turnover`：

```text
tenant_id, stat_date, user_id,
banker_turnover, player_turnover, total_turnover,
source_round_count, created_at, updated_at
```

唯一键：`tenant_id + stat_date + user_id`。

### 5.3 返水账户表

新增 `sangong_rebate_accounts`，每个用户至少两条账户：

```text
tenant_id, user_id, account_type,
turnover_total, turnover_claimed,
amount_total, amount_claimed, rebate_pct,
last_claimed_at, updated_at
```

`account_type` 为 `PLAYER_REBATE` 或 `AGENT_DIFF`。

### 5.4 返水账本和比例审计

新增：

- `sangong_rebate_ledger`：记录手动返水、每日自动返水、差额收益和冲正。
- `sangong_rebate_rate_changes`：记录原比例、新比例、操作人、原因和时间。

账本必须保存比例和流水快照，避免未来修改比例影响历史数据。

### 5.5 用户划转

新增或扩展统一用户划转记录，支持：

```text
AGENT_TO_CHILD
USER_TO_USER
CORRECTION
```

代理和玩家统一使用用户余额，不再维护互斥的资金模型。

## 6. 流水统计改造

### 6.1 闲流水

下注成功后累计，撤回下注时反向扣除，并关联 `tenant_id、round_id、bet_id、user_id`。

### 6.2 庄流水

结算时计算庄方总流水，按主庄/合庄金额占比写入各用户流水账户。代理作为庄家或合庄成员时，同样计入本人流水。

### 6.3 幂等

重复结算、Kafka 重复消息、重试接口不能重复累计。建议以：

```text
tenant_id + round_id + user_id + turnover_type
```

作为流水明细唯一业务键。

## 7. 接口计划

### 7.1 本人返水

```http
GET  /api/v1/me/rebate
POST /api/v1/me/rebate/claim
GET  /api/v1/me/rebate/ledger
```

查询返回庄流水、闲流水、总流水、已返水流水、待返水流水、本人比例和可领取金额。领取时同时处理本人返水和代理差额，后台仍分账户记账。

### 7.2 下级管理

```http
GET  /api/v1/me/children
POST /api/v1/me/children
PUT  /api/v1/me/children/{userId}/rebate
GET  /api/v1/me/agent-rebate
POST /api/v1/me/agent-rebate/claim
GET  /api/v1/me/agent-rebate/ledger
```

旧管理端接口保留兼容，底层统一调用用户树服务。

### 7.3 积分划转

```http
POST /api/v1/me/transfer-to-child
GET  /api/v1/me/transfers
```

只允许向直接或间接下级划转，不能向上级、同级或其他租户划转。

### 7.4 团队报表

每个代理需要能够查看自己的团队资金和成员明细。

```http
GET /api/v1/me/team/summary
GET /api/v1/me/team/members
GET /api/v1/me/team/members/{userId}
GET /api/v1/me/team/ledger
```

团队范围默认包含当前用户的全部直接和间接下级，不包含当前用户本人；接口支持 `scope=direct` 只查看直属下级，支持日期筛选和分页。

团队汇总至少返回：

- 团队人数、直属人数、代理人数、玩家人数。
- 总上分：团队向用户余额增加的有效划转金额。
- 总下分：团队从用户余额减少的有效划转金额。
- 团队总盈亏：团队成员游戏净输赢合计。
- 团队返水总额。
- 团队代理差额收益。
- 当前团队余额合计。
- 可领取返水合计。
- 查询日期范围和统计口径。

建议统一定义：

```text
总上分 = agent_to_child / user_to_user 中流入团队成员的有效金额
总下分 = 从团队成员流出的有效金额
游戏盈亏 = 结算产生的玩家净结果合计
团队净资金变化 = 总上分 - 总下分 + 游戏盈亏 + 返水入账
```

同一笔团队内部划转不能同时计入团队总上分和总下分，避免团队内部转账放大统计；团队外部流入计上分，团队外部流出计下分。

下级成员明细至少包含：

- 用户 ID、IM 用户 ID、昵称。
- 直属上级、代理层级、路径。
- 用户类型：玩家、代理或代理兼玩家。
- 当前余额。
- 玩家返水比例、代理可分配比例。
- 庄流水、闲流水、总流水。
- 已返水流水、待返水流水、可领取返水。
- 总上分、总下分、游戏盈亏。
- 代理差额收益、已领取差额、待领取差额。
- 最后下注时间、最后返水时间、最后划转时间。
- 启用状态和注册时间。

明细接口必须校验目标用户属于当前代理的下级树，不能通过传入 `userId` 查看其他团队成员。

## 8. 每日自动返水

每日关机流程：

1. 停止新下注。
2. 完成当前局结算。
3. 生成庄/闲流水。
4. 发放所有用户的 `PLAYER_REBATE`。
5. 发放所有用户的 `AGENT_DIFF`。
6. 写入 `DAILY_AUTO` 返水账本。
7. 标记当天批次完成并关闭营业会话。

增加失败补偿任务，手动领取过的用户只发放剩余部分，重复执行不得重复入账。

## 9. 服务层改造

新增：

- `UserHierarchyService`
- `TurnoverService`
- `RebateCalculationService`
- `RebateClaimService`
- `DailyRebateJob`
- `TeamReportService`

重点重构：

- `RebateService`
- `SettleService`
- `BetService`
- `AgentPrivilegeService`
- `AgentTransferService`
- `AgentController`
- `MeController`
- `CoBankRepository`

新增团队统计 Repository：

- `TeamReportRepository`
- `TurnoverRepository`
- `UserTransferRepository`
- `RebateLedgerRepository`

## 10. 权限和账务安全

- 所有操作必须带租户上下文。
- 代理只能访问自己的下级树。
- 修改比例、领取返水、划转和结算均使用事务与行锁。
- 禁止跨租户查询和划转。
- 禁止自绑定、环路绑定和越级修改。
- 禁止重复领取、重复冲正和重复流水。
- 每次账务操作写普通账本和专项账本。
- 同一个用户在不同租户中的余额、比例、代理关系、团队统计和账本完全分离。
- 代理权限判断必须使用 `tenant_id + user_id`，不能只按用户 ID 判断。
- 缓存键必须包含 `tenant_id`，例如 `tenant:{tenantId}:user:{userId}:rebate`。
- 所有报表查询必须先确定租户，再确定用户树范围。

## 11. 测试计划

### 用户树

- 无限层级创建。
- 自己绑定自己。
- 绑定自己的下级。
- 跨租户绑定。
- 更换上级和停用代理。

### 流水

- 普通闲下注。
- 主庄流水。
- 合庄流水按占比分配。
- 代理自己下注。
- 撤回下注。
- 重复结算不重复累计。

### 返水

- 无比例用户不返水。
- 玩家手动领取。
- 代理手动领取差额。
- 玩家和代理同时领取。
- 重复点击。
- 有未返水流水时禁止改比例。
- 无未返水流水时允许改比例。
- 多级代理差额。
- 每日自动返水和失败重试。

### 划转

- 向下级玩家划转。
- 向下级代理划转。
- 代理同时作为玩家。
- 向上级、同级、跨租户划转均拒绝。
- 余额不足和重复请求。

## 12. 实施阶段

### 阶段一：用户树与兼容迁移

创建表、迁移旧代理数据、实现防环和下级查询，保留旧接口兼容。

### 阶段二：流水统计

接入闲流水、主庄流水、合庄分摊、冲正和幂等控制。

### 阶段三：返水账户

创建两类返水账户，重构现有按局返水，增加领取游标和账本。

### 阶段四：实时领取和比例管理

增加查询、领取、比例修改、未返水流水校验和比例审计。

### 阶段五：代理能力

增加下级用户管理、无限代理层级、差额收益和向下级划转。

### 阶段六：自动任务和验收

接入每日关机返水、失败补偿、多租户测试、并发测试和账务核对。

## 13. 验收案例

```text
用户 A：5%
└── 用户 B：3%
    └── 用户 C：1%
        └── 用户 D：1%
```

用户 D：闲流水 60,000，庄流水 40,000，总流水 100,000。

```text
D 玩家返水：1,000
C 代理差额：0
B 代理差额：2,000
A 代理差额：2,000
```

D 或任一代理领取后，对应已领取流水不得再次参与计算。
