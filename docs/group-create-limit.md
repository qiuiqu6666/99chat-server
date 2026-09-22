# 群加入数量限制 + 社群创建限制

> 版本：v2.0  
> 拦截点：业务 API + 腾讯 IM `Group.CallbackBeforeCreateGroup` /
> `Group.CallbackBeforeInviteJoinGroup` / `Group.CallbackBeforeApplyJoinGroup`

## 能力

| 额度 | 说明 |
|------|------|
| **非社群加入** | 用户所在未解散且类型 ≠ Community 的群数，全局一个上限 |
| **社群加入** | 用户所在未解散 Community 群数，单独上限（默认 1000） |
| **社群创建** | 用户作为群主创建的 Community 数（沿用旧逻辑） |
| Work 创建数 | **已取消**，不再限制 |

- 群主身份也计入「已加入」
- 建群时校验群主 + 初始成员；任一人超限则整单失败
- 邀请入群 / 申请入群 / 审批通过 / IM 侧加人同步拦截
- 解散群后成员投影删除即释放加入名额；社群创建名额由 `CallbackAfterGroupDestroyed` 释放

## 配置（数据库 app_setting + 管理后台）

| Key | 说明 | 默认 |
|-----|------|------|
| `group.create_limit.enabled` | 总开关 | `true` |
| `group.join_limit.max` | 非社群加入上限 | `10000` |
| `group.join_limit.max_community` | 社群加入上限 | `1000` |
| `group.create_limit.max_community` | 社群可创建上限 | `3` |
| `group.create_limit.enforce` | 是否真正拦截 | `true` |
| `group.create_limit.log_only` | 仅打日志 | `false` |
| `group.create_limit.use_im_count_fallback` | 仅用于**社群创建**计数兜底 | `false` |

`group.create_limit.max_work` 已废弃，不再参与业务。

初始化：

```bash
mysql ... < scripts/group-create-limit-config-init.sql
```

## IM 控制台必开回调

同一 URL `/webhook/im/message`：

| 回调 | 用途 |
|------|------|
| **创建群组之前** `Group.CallbackBeforeCreateGroup` | 加入超限 / 社群创建超限拒绝 |
| **拉人入群之前** `Group.CallbackBeforeInviteJoinGroup` | 加入超限拒绝 |
| **申请入群之前** `Group.CallbackBeforeApplyJoinGroup` | 加入超限拒绝 |
| 创建群组之后 / 群组解散之后 | 投影与社群创建名额 |

IM 拒绝 `ErrorInfo`：

- `GROUP_JOIN_LIMIT` — 非社群加入超限
- `GROUP_JOIN_LIMIT_COMMUNITY` — 社群加入超限
- `GROUP_CREATE_LIMIT_COMMUNITY` — 社群创建超限

## 客户端查询额度

```http
GET /me/group-create-limits
Authorization: Bearer <token>
```

**200 示例：**

```json
{
  "enabled": true,
  "joinGroups": {
    "max": 10000,
    "used": 3,
    "remaining": 9997,
    "limited": true
  },
  "communityJoinGroups": {
    "max": 1000,
    "used": 12,
    "remaining": 988,
    "limited": true
  },
  "communityGroups": {
    "groupType": "Community",
    "max": 3,
    "used": 1,
    "remaining": 2,
    "limited": true
  }
}
```

> 破坏性变更：已移除 `workGroups` 字段。

## 业务 API 失败体

HTTP `403`：

```json
{
  "code": "GROUP_JOIN_LIMIT_EXCEEDED",
  "message": "部分用户加入群数量已达上限",
  "overLimitUsers": [
    {
      "userId": "u1",
      "used": 10000,
      "max": 10000,
      "limitType": "join"
    }
  ]
}
```

`limitType`：`join` | `communityJoin` | `communityCreate`  
社群创建超限时 `code` 为 `GROUP_CREATE_LIMIT_COMMUNITY`。

整批失败：建群 / 邀请 / 申请 / 审批任一入口，有超限用户即整单失败并返回名单。

## 计数逻辑

- **加入**：纯本地 `group_member` ∩ 未解散 `group_profile`，按类型分桶
- **社群创建**：`max(本地 user_owned_group, IM Owner 数)`（当 `use_im_count_fallback=true`）
