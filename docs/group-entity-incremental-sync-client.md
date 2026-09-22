# 群 Entity 增量 Sync（group_seq）

> 状态：已实现（P0）  
> 目标：断线补偿时用游标增量补齐群名/头像/公告，**禁止**日常全量拉齐。

## API

```http
GET /me/groups/changes?since_seq={lastSeq}&limit=100
```

| 参数 | 说明 |
|------|------|
| `since_seq` | 上次拿到的最大 `seq`；首次用 `0` |
| `limit` | 默认 100，上限 200 |

### 成功响应

```json
{
  "nextSeq": 10045,
  "hasMore": false,
  "events": [
    {
      "seq": 10041,
      "type": "GROUP_INFO_UPDATED",
      "action": "group_name_changed",
      "groupId": "m2225Q3N5CC",
      "groupName": "新名称",
      "avatarUrl": "https://…",
      "avatarVersion": 8,
      "notice": "",
      "updatedAt": 1786520412000,
      "changeEventId": "…"
    }
  ]
}
```

无事件时：`events=[]`，`nextSeq` 为 `max(since_seq, 当前库最大 seq)`。

### 游标过期

`since_seq > 0` 且小于库内最小 `group_seq` → **HTTP 410**，body/reason `CURSOR_EXPIRED`。  
客户端应一次性 `GET /me/groups` 快照后，用最新 `nextSeq` 续拉。

## TCP

`group_changed` 中 `group_name_changed` / `group_avatar_changed` / `group_notice_changed` 的 `detail` 含：

- `groupName`、`avatarUrl`、`avatarPreviewUrl`（可选）、`avatarVersion`、`notice`、`groupSeq`、`updatedAt`

与增量事件同一 `groupSeq`，便于去重。

## 与现有接口

| 接口 | 角色 |
|------|------|
| `GET /me/groups` | 首次登录、`CURSOR_EXPIRED` 时快照 |
| `GET /me/groups/changes` | 日常断线补偿（本能力） |
| `GET /me/group-change-events` | 旧成员变动游标（`occurredAt`）；保留兼容 |
| `GET /group/{id}?refresh=true` | 单群纠偏 |

## 客户端建议

1. 持久化 `last_group_seq`  
2. 冷启：本地 Entity 秒开 → 后台 `Sync(since_seq)` → upsert → 局部刷新  
3. TCP 与 Sync 按 `groupSeq` / `groupId+avatarVersion` 去重  
4. 不进列表就全量 `/me/groups`
