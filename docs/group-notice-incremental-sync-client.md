# 群系统通知 Inbox 增量 Sync

> 状态：已实现（P0+P1）  
> 目标：断线补偿时用游标增量补齐系统通知（设/撤管理员、转让群主），**勿**与 `GET /me/groups/changes`（群 Entity）混用。

关联：快照 `GET /me/group-notices` · 已读 `PUT /me/group-notices/read` · 软删 `DELETE /me/group-notices` · [group-notice-client.md](./group-notice-client.md)

## API

```http
GET /me/group-notices/changes?since_seq={lastSeq}&limit=100
```

| 参数 | 说明 |
|------|------|
| `since_seq` | 上次拿到的最大 `seq`；首次用 `0` |
| `limit` | 默认 100，上限 200 |

### 成功响应（camelCase；客户端可双读 snake）

```json
{
  "nextSeq": 10045,
  "hasMore": false,
  "events": [
    {
      "seq": 10041,
      "type": "NOTICE_UPSERTED",
      "noticeId": "grant_administrator|g1|op|tg|1786520412000",
      "groupId": "m2225Q3N5CC",
      "groupName": "产品讨论群",
      "groupAvatarUrl": "https://…",
      "noticeType": "grant_administrator",
      "operatorUserId": "10001",
      "operatorNickName": "张三",
      "targetUserId": "10002",
      "targetNickName": "李四",
      "createdAtMs": 1786520412000
    },
    {
      "seq": 10042,
      "type": "NOTICE_DELETED",
      "noticeId": "grant_administrator|g1|op|tg|1786520412000"
    },
    {
      "seq": 10043,
      "type": "READ_WATERMARK",
      "lastReadAtMs": 1786520500000
    }
  ]
}
```

| `type` | 含义 |
|--------|------|
| `NOTICE_UPSERTED` | 新增/可见通知快照 |
| `NOTICE_DELETED` | 当前用户软删（dismiss） |
| `READ_WATERMARK` | 已读水位 |

`noticeType`：`grant_administrator` / `revoke_administrator` / `transfer_owner`

空页：`events=[]`，`nextSeq = max(since_seq, 库内最大 seq)`。

### 游标过期

`since_seq > 0` 且小于库内最小 inbox `seq` → **HTTP 410**，reason `CURSOR_EXPIRED`。  
客户端应一次性 `GET /me/group-notices` 快照后，用最新 `nextSeq` 续拉。

## TCP

`group_changed` · `action=group_system_notice`：

- **UPSERT**：`detail` 含 `noticeId`、`groupId`、`type`/`noticeType`、operator/target、群名头、`createdAtMs`、**`seq`**（与该用户 inbox 事件同源）
- **DELETE**：`detail.type=NOTICE_DELETED`，含 `noticeId`、`seq`（可选；以 changes 为准亦可）

与增量事件按 `seq` / `noticeId` 去重。同源离线 Push 随 TCP 恢复（UPSERT 文案；DELETE 通常无离线正文）。

## 与现有接口

| 接口 | 角色 |
|------|------|
| `GET /me/group-notices` | 首次登录、`CURSOR_EXPIRED` 时快照 |
| `GET /me/group-notices/changes` | 日常断线补偿（本能力） |
| `GET /me/groups/changes` | **群 Entity**（名/头/公告），勿混用 |

## 客户端建议

1. 持久化 `last_notice_inbox_seq`（与 `last_group_seq` 分开）  
2. 冷启：本地 inbox 秒开 → 后台 Sync → upsert/删除 → 刷新列表/红点  
3. TCP 与 Sync 按 `seq` 去重  
4. 勿用群公告 Entity 冒充系统通知 inbox  
