# 机器人数据同步 — 后端实现清单

> 版本：v1.0  
> 更新：2026-07-13  
> 状态：**已实现（待机器人联调）**  
> 接口：`POST /api/internal/robot-sync`  
> 专用库：`jiqiren`（用户/密码/库名均为 `jiqiren`）  
> 机器人地址：`http://47.239.60.107:8081/api/internal/robot-sync`

---

## 实现状态总览

| 模块 | 状态 | 代码位置 |
|------|------|----------|
| 建表 SQL | ✅ | `scripts/migrate-robot-sync.sql` |
| 独立数据源 | ✅ | `robot/RobotSyncJdbcConfig.java` |
| 配置属性 | ✅ | `robot/RobotSyncProperties.java` |
| 请求/响应 DTO | ✅ | `robot/RobotSyncRequest.java` 等 |
| Repository | ✅ | `robot/RobotSync*Repository.java` |
| 业务 Service | ✅ | `robot/RobotSyncService.java` |
| Controller | ✅ | `robot/RobotSyncController.java` |
| 安全放行 | ✅ | `security/SecurityConfig.java` |
| 应用配置 | ✅ | `application.yml`、`.env` |
| 单元/集成测试 | ✅ | `test/.../RobotSync*Test.java` |
| 机器人联调 | ⬜ | — |

---

## 0. 前置条件

- [x] 机器人端已完成数据采集、HTTP POST、失败落盘、自动重试
- [x] 专用数据库 `jiqiren` 已创建（`utf8mb4`）
- [ ] 数据库账号 `jiqiren` / `jiqiren` 权限确认（本机 `127.0.0.1:3306` 可连）
- [ ] 环境变量 `ROBOT_SYNC_SECRET` 已配置（与机器人一致，勿提交公开仓库）
- [ ] 端口 `8081` 对机器人机器开放（防火墙 / 安全组）

---

## 1. 数据库（`jiqiren` 库）

### 1.1 建表

- [ ] `robot_sync_event` — 原始事件日志 + 幂等主键 `event_id`
- [ ] `robot_player_snapshot` — 玩家当前状态，唯一键 `(player_group_id, wxid)`
- [ ] `robot_player_daily_summary` — 每日归档，唯一键 `event_id`

### 1.2 索引

- [ ] `uk_robot_sync_event_id` on `robot_sync_event(event_id)`
- [ ] `uk_robot_player` on `robot_player_snapshot(player_group_id, wxid)`
- [ ] `uk_robot_daily_event` on `robot_player_daily_summary(event_id)`
- [ ] 辅助索引：`idx_robot_sync_entity`、`idx_robot_daily_player` 等

### 1.3 迁移执行

```bash
mysql -h127.0.0.1 -ujiqiren -p jiqiren < scripts/migrate-robot-sync.sql
```

- [ ] SQL 已在 `jiqiren` 库执行成功
- [ ] `SHOW TABLES` 可见三张表

---

## 2. 配置

### 2.1 `application.yml`

```yaml
robot:
  sync:
    secret: ${ROBOT_SYNC_SECRET}
    datasource:
      url: jdbc:mysql://${ROBOT_DB_HOST:127.0.0.1}:${ROBOT_DB_PORT:3306}/${ROBOT_DB_NAME:jiqiren}?sslMode=${ROBOT_DB_SSL_MODE:REQUIRED}&serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true
      username: ${ROBOT_DB_USERNAME:jiqiren}
      password: ${ROBOT_DB_PASSWORD:jiqiren}
      maximum-pool-size: 10
```

- [ ] `robot.sync` 配置块已添加
- [ ] `RobotSyncProperties` 已注册到 `ServerApplication`

### 2.2 `.env` / 环境变量

```env
ROBOT_DB_HOST=127.0.0.1
ROBOT_DB_PORT=3306
ROBOT_DB_NAME=jiqiren
ROBOT_DB_USERNAME=jiqiren
ROBOT_DB_PASSWORD=jiqiren
ROBOT_SYNC_SECRET=<与机器人一致的密钥>
```

- [ ] 生产环境变量已设置
- [ ] `.env.example` 已补充说明（不含真实密钥）

### 2.3 安全

- [ ] `SecurityConfig` 放行 `/api/internal/**`（`permitAll`）
- [ ] 接口鉴权改由 `X-Robot-Secret` 请求头承担
- [ ] 日志不输出完整密钥

---

## 3. Java 代码

### 3.1 包结构 `com.chat99.server.robot`

| 文件 | 职责 | 状态 |
|------|------|------|
| `RobotSyncProperties.java` | 配置绑定 | ⬜ |
| `RobotSyncJdbcConfig.java` | 独立 Hikari 数据源 + `robotJdbc` Bean | ⬜ |
| `RobotSyncRequest.java` | 请求 DTO（含 `@Valid` 校验） | ⬜ |
| `PlayerSnapshotData.java` | `player.snapshot.upsert` 的 `data` | ⬜ |
| `PlayerDailySummaryData.java` | `player.daily.summary` 的 `data` | ⬜ |
| `RobotSyncResult.java` | `accepted` / `duplicate` 结果 | ⬜ |
| `RobotSyncEventRepository.java` | 事件日志 CRUD | ⬜ |
| `RobotPlayerSnapshotRepository.java` | 玩家当前表 upsert | ⬜ |
| `RobotPlayerDailySummaryRepository.java` | 每日归档 insert | ⬜ |
| `RobotSyncService.java` | 核心业务逻辑 | ⬜ |
| `RobotSyncController.java` | `POST /api/internal/robot-sync` | ⬜ |

### 3.2 Controller 行为

- [ ] 读取 `X-Robot-Secret` 请求头
- [ ] 未传 / 错误密钥 → `401` + `{success:false, message:"invalid robot secret"}`
- [ ] 请求体 JSON 校验失败 → `400`
- [ ] 不支持的 `eventType` → `400`（不静默丢弃）
- [ ] 成功 → `200` + `{success:true, duplicate:false, eventId, message:"accepted"}`
- [ ] 重复 → `200` + `{success:true, duplicate:true, eventId, message:"already processed"}`
- [ ] 服务器异常 → `500`（事务回滚，机器人保留重试）

### 3.3 Service 核心逻辑

```
收到请求
  → 校验 eventType 白名单
  → 开启事务
  → 尝试插入 eventId（已存在 → duplicate，直接 200）
  → 保存原始事件日志（request_body 全文）
  → 按 eventType 分发
  → 标记 processed
  → 提交事务
```

- [ ] `eventId` 幂等：重复事件不修改业务数据
- [ ] `sourceUpdatedAt` 顺序保护：仅 `新 >= 旧` 时覆盖玩家当前快照
- [ ] 旧事件晚到：记日志但不覆盖较新快照，仍返回 200
- [ ] `businessDate` 按 `businessTimezone`（`+08:00`）计算，不用服务器默认时区
- [ ] `rebateRate` 按 `÷10000` 理解（35 = 0.35%，不是 35%）
- [ ] `playerType = "1"` 手动托：保存，默认统计排除
- [ ] `syncReason` 原样写入事件日志
- [ ] 事件日志与业务表在同一事务中

### 3.4 事件分发

#### `player.snapshot.upsert`

- [ ] 定位键：`playerGroupId + wxid`
- [ ] 不存在 → insert
- [ ] 存在且 `sourceUpdatedAt` 更新 → update
- [ ] 存在但 `sourceUpdatedAt` 更旧 → 跳过更新

#### `player.daily.summary`

- [ ] 每个 `eventId` 只插入一次
- [ ] 不覆盖玩家当前快照表
- [ ] 保存 `pendingRebate` 字段
- [ ] `businessDate = Instant.ofEpochSecond(ts).atOffset(offset).toLocalDate()`

---

## 4. 支持的 syncReason（审计用，无需独立接口）

- [ ] `player_created`
- [ ] `flow_update`
- [ ] `flow_rollback`
- [ ] `daily_archive`
- [ ] `daily_flow_clear`
- [ ] `manual_balance_up`
- [ ] `manual_balance_down`
- [ ] `updown_approve`
- [ ] `updown_reject`
- [ ] `relation_change`
- [ ] `profile_remark_change`
- [ ] `player_type_to_bot`
- [ ] `player_type_to_real`
- [ ] `rebate_rate_change`
- [ ] `rebate_execute`
- [ ] `rebate_one_click`
- [ ] `rebate_agent`
- [ ] `rebate_all`
- [ ] `unknown`（兜底）

---

## 5. 测试用例

### 5.1 鉴权

| # | 场景 | 期望 | 状态 |
|---|------|------|------|
| 1 | 无 `X-Robot-Secret` | 401 | ⬜ |
| 2 | 错误密钥 | 401 | ⬜ |
| 3 | 正确密钥 | 进入业务校验 | ⬜ |

### 5.2 `player.snapshot.upsert`

| # | 场景 | 期望 | 状态 |
|---|------|------|------|
| 4 | 首次发送 | 200，插入事件表 + 快照表 | ⬜ |
| 5 | 同 `eventId` 重发 | 200 + `duplicate:true`，余额不变 | ⬜ |
| 6 | 先 `sourceUpdatedAt=200` 再 `100` | 两次均 200，当前表保持 200 | ⬜ |
| 7 | 中文昵称 / emoji / 特殊字符 | UTF-8 正常存储 | ⬜ |

### 5.3 `player.daily.summary`

| # | 场景 | 期望 | 状态 |
|---|------|------|------|
| 8 | 首次发送 | 200，正确 `businessDate` | ⬜ |
| 9 | 含 `pendingRebate` | 字段正确保存 | ⬜ |
| 10 | 同 `eventId` 重发 | 200 + `duplicate:true`，不重复插入 | ⬜ |

### 5.4 异常

| # | 场景 | 期望 | 状态 |
|---|------|------|------|
| 11 | 缺少必填字段 | 400 | ⬜ |
| 12 | 未知 `eventType` | 400 | ⬜ |
| 13 | 数据库异常 | 500，事务回滚 | ⬜ |

---

## 6. 机器人联调

### 6.1 正常联调

- [ ] 机器人执行真人上分
- [ ] 后端收到 `eventType=player.snapshot.upsert`
- [ ] `syncReason=manual_balance_up`
- [ ] 机器人日志显示「后端同步成功」

### 6.2 失败队列恢复

- [ ] 停止后端服务
- [ ] 机器人执行上分 → 事件进入 `pending_sync.jsonl`
- [ ] 启动后端服务
- [ ] 机器人自动重试成功
- [ ] 失败队列对应条目被移除

---

## 7. 上线前检查

- [ ] `/api/internal/robot-sync` 已部署
- [ ] 端口 `8081` 机器人机器可访问
- [ ] 防火墙已放行
- [ ] `X-Robot-Secret` 两端一致
- [ ] 请求体大小限制足够
- [ ] UTF-8 解析正常
- [ ] `event_id` 唯一索引已建立
- [ ] 重复事件返回 200
- [ ] `sourceUpdatedAt` 防旧覆盖已实现
- [ ] 两种 `eventType` 已支持
- [ ] 手动托默认不进入真人统计
- [ ] 原始事件日志可追踪
- [ ] 500 时事务回滚
- [ ] 联调通过
- [ ] 失败队列恢复测试通过

---

## 8. 明确不需要实现

- 删除玩家 / `player.deleted` 事件
- `active = false` 处理
- 为每个 `syncReason` 建独立接口
- TXT 批量测试账号逻辑
- 改动主库 `chat99` 现有业务

---

## 9. 建议实施顺序

```
1. scripts/migrate-robot-sync.sql
2. RobotSyncProperties + RobotSyncJdbcConfig
3. Repository 层
4. RobotSyncService（幂等 + 顺序保护）
5. RobotSyncController
6. SecurityConfig 放行
7. application.yml + .env
8. 单元/集成测试
9. 机器人联调
10. 失败队列恢复测试
```

---

## 10. 验收标准

1. 正确校验 `X-Robot-Secret`
2. 支持 `player.snapshot.upsert`
3. 支持 `player.daily.summary`
4. `eventId` 幂等
5. 重复事件返回 HTTP 200
6. 旧快照不能覆盖新快照
7. 正确保存玩家当前状态
8. 正确保存每日历史归档
9. 正确区分真人和手动托
10. 接口异常返回 500，机器人可保留并重试
11. 机器人真实上分联调成功
12. 断网后自动补发联调成功
