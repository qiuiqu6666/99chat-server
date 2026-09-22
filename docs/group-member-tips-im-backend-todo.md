# 群成员变动灰字 — 后端说明（部分废弃）

> 版本：v1.2  
> 日期：2026-08-02  

关联：[group-member-change-client.md](./group-member-change-client.md)

---

## 变更说明（重要）

**客户端已改由 App 在 REST 成功后自行 `sendMessage` 写聊天灰字。**

因此本文 v1.0/v1.1 中下列目标 **对前端灰字不再适用**：

- 以 IM 原生 GroupTips 为灰字唯一展示来源  
- 以 `GET .../change-events` 驱动客户端补拉 IM 历史来展示灰字  
- 要求客户端对接 change-events cursor  

后端仍可保留：

- REST 调 IM 成员增删、投影更新  
- `group_change_event` 落库 / change-events API（非灰字必需，可后续清理）  
- TCP `group_changed` **资料同步**（成员数、列表等）

---

## 当前后端职责（精简）

| 项 | 是否仍需要 |
|----|------------|
| IM add/delete/quit 等管理 API | ✅ 业务必须 |
| TCP `member_*` 资料同步 | ✅ |
| 客户端灰字文案 / tip 消息 | ❌ 改由 App `sendMessage` |
| change-events 给灰字补拉 | ❌ 前端不再依赖 |

---

## 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-03 | IM GroupTips + change-events |
| v1.1 | 2026-08-02 | REST-primary、离群可读等 |
| v1.2 | 2026-08-02 | 灰字改 App sendMessage；GroupTips/change-events 对接废弃 |
