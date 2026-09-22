# 群成员变动增量 Sync

> 状态：已实现（P1）  
> 目标：断线/杀进程后用游标补齐成员增减与权威人数；**勿**与 `GET /me/groups/changes`（群 Entity）混用。

关联：快照 `GET /group/{groupId}/members`（**仅本地投影，不再回源 IM**；`refresh` 参数已忽略）· TCP `member_added` / `member_removed` / `member_left` · [group-member-change-client.md](./group-member-change-client.md)

## API

```http
GET /me/groups/{groupId}/members/changes?since_seq={lastSeq}&limit=100
```

| 参数 | 说明 |
|------|------|
| `since_seq` | 上次拿到的最大成员流 `seq`；首次用 `0` |
| `limit` | 默认 100，上限 200 |

需为群成员；鉴权 JWT。

### 成功响应（camelCase；客户端可双读 snake）

```json
{
  "nextSeq": 10045,
  "hasMore": false,
  "memberCount": 128,
  "events": [
    {
      "seq": 10041,
      "type": "MEMBER_UPSERTED",
      "groupId": "m2225Q3N5CC",
      "userId": "10002",
      "nickName": "李四",
      "avatarUrl": "https://…",
      "role": 200,
      "memberCount": 128
    },
    {
      "seq": 10042,
      "type": "MEMBER_REMOVED",
      "groupId": "m2225Q3N5CC",
      "userId": "10003",
      "memberCount": 127
    }
  ]
}
```

| `type` | 含义 |
|--------|------|
| `MEMBER_UPSERTED` | 入群 / 成员可见信息补齐 |
| `MEMBER_REMOVED` | 踢人 / 退群 |

页级 `memberCount`：当前投影权威人数；事件内 `memberCount`：写入当时人数。

空页：`events=[]`，`nextSeq = max(since_seq, 该群最大 seq)`。

### 游标过期

`since_seq > 0` 且小于该群库内最小成员流 `seq` → **HTTP 410**，reason `CURSOR_EXPIRED`。  
客户端应 `GET /group/{groupId}/members` 分页快照后重建游标。

## TCP

`member_added` / `member_removed` / `member_left` 的 `detail` 含：

- `memberCount`、`memberUserIds`、`updatedAt`
- **`seq`**：与本接口成员流同源（本批最大 seq）
- `groupSeq`：仍为全局展示/审计序号（与 Entity 同族，勿与成员 `seq` 混用）

## 与现有接口

| 接口 | 角色 |
|------|------|
| `GET /group/{id}/members` | 快照 / `CURSOR_EXPIRED` 回退 |
| `GET /me/groups/{id}/members/changes` | 断线补偿（本能力） |
| `GET /me/groups/changes` | 仅群名/头像/公告 Entity |
| `GET …/change-events` | 旧 `occurredAt` 游标；非本契约 |

## 客户端建议

1. 按群持久化 `last_member_seq`（与 `last_group_seq`、notice inbox seq 分开）  
2. 冷启 / 重连：本地列表秒开 → Sync → upsert/删除 → 刷新人数与头  
3. TCP 与 Sync 按 `seq` 去重；与 tip 单飞  
4. 勿把成员全量塞进 Entity changes  
