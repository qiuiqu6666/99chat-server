# 会话自定义分组多端同步 — 后端契约

> 版本：v1.2  
> 日期：2026-07-20  
> 状态：**后端已实现**；客户端 API / Store / Sync / Chip UI 已对接  
> **产品变更（v1.2）**：单聊与群聊**共用同一套分组**（`scope=all`），不再按 c2c/group 拆分

## 实现要点

| 项 | 说明 |
|----|------|
| `scope` | 新建默认 / 推荐 `all`；兼容读历史 `c2c`/`group` |
| 成员 | **不**校验 `chatType` 与 folder.scope；`all` 可混放 c2c+group |
| 上限 | 每用户 ≤ 20（不分 scope） |
| 归档互斥 | 入组清归档；归档清全部分组成员 |
| TCP | `conversation_folder_changed`（`upsert`/`delete`/`members` 或 `batch=true`） |

## REST

| 方法 | 路径 |
|------|------|
| GET | `/me/conversation-folders?scope=`（`all`/`c2c`/`group`，省略=全量） |
| PUT | `/me/conversation-folders` |
| DELETE | `/me/conversation-folders/{folderId}` |
| PUT | `/me/conversation-folders/{folderId}/members` |
| PUT | `/me/conversation-folders/replace` |

迁移脚本：`scripts/migrate-conversation-folder.sql`
