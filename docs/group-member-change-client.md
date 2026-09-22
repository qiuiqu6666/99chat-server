# 群成员变动灰字 — 前端说明（已换轨）

> **状态：GroupTips / change-events 补拉方案已废弃**  
> 版本：v3.0  
> 日期：2026-08-02  

---

## 当前约定

成员变动相关**聊天灰字**由 **前端 App 在业务 REST 成功后自行 `sendMessage`（建议 `TIMCustomElem`，`businessID=group_tip`）** 写入会话，与转账/红包展示消息类似：

- 服务端负责：权限校验、调 IM 管理 API、更新投影、TCP `group_changed` **资料同步**
- 服务端**不**再约定：靠 IM 原生 GroupTips 展示灰字、或靠 `GET .../change-events` 驱动补拉灰字
- 客户端**不要**再：用 TCP `member_*` 本地注入假 tips，或按旧文档接 change-events 补拉
- **全员禁言**：软禁言下普通成员发纯 `group_tip` **不会**被拦（见 [group-governance-client.md](./group-governance-client.md) §8）；个人禁言仍会拦 tip

自定义消息字段 / `customType` 由 App 侧自定并保持多端一致即可；识别 tip 时服务端认 `businessID` / `customType` / `type` = `group_tip`。

---

## 仍保留、与灰字无关

| 能力 | 说明 |
|------|------|
| TCP `group_changed`（`member_added` 等） | 刷新成员数 / 成员列表 / 群列表 |
| `GET /me/groups/{groupId}/members/changes` | 断线成员游标增量（见 [group-member-incremental-sync-client.md](./group-member-incremental-sync-client.md)） |
| REST 邀请 / 踢人 / 退群 / 解散 | 业务写操作不变 |
| `GET /group/.../change-events`（若仍存在） | **非灰字必需**；可忽略，除非另有运维用途 |

---

## 历史文档

| 文档 | 状态 |
|------|------|
| 本文 v2.x（IM GroupTips + change-events） | **废弃** |
| [group-member-tips-im-backend-todo.md](./group-member-tips-im-backend-todo.md) | 后端待办中「灰字靠 GroupTips」部分 **废弃** |
| [group-member-tips-im-client.md](./group-member-tips-im-client.md) | 入口已指向本文 |

---

## 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v2.x | 2026-08-02 | IM GroupTips + change-events（已废） |
| v3.0 | 2026-08-02 | 改由 App `sendMessage` 写灰字 |
