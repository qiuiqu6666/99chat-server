# Telegram 开群特权运维说明

> 更新：2026-08-05  
> 控制群：`TELEGRAM_OPS_GAME_CONTROL_CHAT_ID`  
> 关联：[robot-windows-integration.md](./robot-windows-integration.md)

---

## 推荐指令（飞机运维群）

机器人数据以**机器码**为准；`开启` 后面跟 **IM 完整群 ID**（推荐 `@TGS#…` 或 `@TGS#_@TGS#…`）。

```text
配对CP5Y-4EX1-AC9V
开启@TGS#27PIAKM5CC
```

或双形态完整 ID：

```text
配对CP5Y-4EX1-AC9V
开启@TGS#_@TGS#c2SX4NMM62CZ
```

含义：

| 指令 | 效果 |
|------|------|
| `配对机器码` | **只选定**本机机器码（数据租户键），不 bind；绑群发生在显式 `配对码 @群ID` 或下方 `开启@群` |
| `配对机器码 @群ID` | 对该 IM 群立刻 bind（显式） |
| `开启@TGS#…` / `开启@TGS#_@TGS#…` | 把该 IM 群绑到已选机器码，并 enable（`robotId`=机器码）+ 打开 `game_enabled` |

**绑定规则**：同一机器码可绑定多个 IM 群；一个 IM 群只能绑定一个机器码。

---

## 直接发群 ID 查是否开启

控制群里**只发完整群 ID**（不加「查询」前缀），返回游戏开关是否开启：

```text
@TGS#2E2U6YN5CC
@TGS#_@TGS#c2SX4NMM62CZ
```

回执置顶 `游戏开关: 已开启 / 未开启`，并附带群名、完整群 ID、机器码绑定与开群特权状态。

---

## 兼容写法

### A. 一行写清目标群

```text
配对ABCD-EFGH-JKMN @TGS#2BN4LEN5C3
开启@TGS#2BN4LEN5C3
```

### B. 先选定群，再配对 / 开启

```text
@TGS#27PIAKM5CC
配对ABCD-EFGH-JKMN
开启@TGS#27PIAKM5CC
```

### C. 短码（兼容，不推荐）

短码仍可解析为库中 `@TGS#短码` / `@TGS#_@TGS#短码`，但运维请优先用完整群 ID。

```text
开启@27PIAKM5CC
```

### D. 旧「开启@机器人账号」（仍可用）

当 `@xxx` **不能**解析成库里的 IM 群时，仍按「机器人账号 + 已选群」处理：

```text
开启@2EYHG6M5CJ
开启@2EYHG6M5CJ @TGS#2BN4LEN5C3
```

群短码仍兼容，但推荐完整群 ID。

**绑定规则**：同一机器码可绑定多个 IM 群；一个 IM 群只能绑定一个机器码。

---

## 关闭游戏开关

```text
关闭@TGS#27PIAKM5CC
关闭@TGS#_@TGS#c2SX4NMM62CZ
```

只关 IM `game_enabled`，**不删除**机器码配对；要再用需重新 `开启` + 完整群 ID。

整码注销（删机器码 + 级联清租户/绑定）走 Windows HTTP：`POST /api/internal/robot-machines/unregister`（`X-Machine-Code`）。控制群本轮**无**注销指令。

---

## 依赖

`robot-service` 可达（`ROBOT_SERVICE_URL`），且 `ROBOT_PROBE_SECRET`（robot-service）与主服务 `TELEGRAM_PROBE_SECRET` 一致。
