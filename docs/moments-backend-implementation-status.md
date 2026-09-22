# 朋友圈后端实现状态

**更新时间**：2026-07-05  
**面向**：后端 / 客户端联调  
**代码路径**：`src/main/java/com/chat99/server/moments/`  
**相关文档**：
- 前端对接明细（部分字段已过时，以本文为准）：`docs/moments-frontend-api.md`
- 数据库迁移：`scripts/migrate-moments.sql`、`scripts/migrate-moment-settings.sql`

---

## 1. 总览

| 类别 | 状态 | 说明 |
|------|------|------|
| 基础动态 REST | ✅ 已实现 | Feed、发布、点赞、评论、通知等 |
| 权限与偏好 REST | ✅ 已实现 | 设置读写、封面上传、单条可见性、Feed 过滤 |
| TCP 实时推送 | ⚠️ 部分实现 | 服务端已推送 `moment_changed`；客户端尚未接入 |
| 可选增强 | ❌ 未实现 | 见第 5 节 |

**通用约定**（已实现）：

- 鉴权：`Authorization: Bearer <JWT>`
- 成功响应：`{ "code": 0, "message": "ok", "data": ... }`（由 `GlobalResponseWrapper` 包装）
- 失败响应：`{ "code": "<ERROR_CODE>", "message": "..." }`
- 分页：`cursor` + `pageSize`，返回 `items` / `nextCursor` / `hasMore`
- 时间：毫秒时间戳 `createdAt`
- 发布 / 评论：请求头 `Idempotency-Key: <uuid>`（幂等）

---

## 2. 已实现接口

### 2.1 基础动态

| 优先级 | 方法 | 路径 | 状态 | 说明 |
|--------|------|------|------|------|
| P0 | GET | `/moments/feed` | ✅ | 好友动态流；过滤 `hiddenAuthorIds`、单条可见性、全局屏蔽 |
| P0 | GET | `/moments/users/{userId}` | ✅ | 个人/他人朋友圈；含 `visibleRangeDays`；被屏蔽返回 403 |
| P0 | GET | `/moments/{momentId}` | ✅ | 动态详情；含完整 likes / comments |
| P0 | POST | `/moments/media/upload` | ✅ | 发布前上传图片/视频（`file` + `type` + 可选 `clientMediaId`） |
| P0 | POST | `/moments` | ✅ | 发布动态；支持 `FRIENDS` / `EXCLUDE` / `PARTIAL` |
| P0 | DELETE | `/moments/{momentId}` | ✅ | 删除自己的动态（软删除） |
| P0 | POST | `/moments/{momentId}/likes` | ✅ | 点赞（幂等） |
| P0 | DELETE | `/moments/{momentId}/likes/me` | ✅ | 取消点赞（幂等） |
| P0 | POST | `/moments/{momentId}/comments` | ✅ | 评论 / 回复 |
| P1 | DELETE | `/moments/{momentId}/comments/{commentId}` | ✅ | 删除评论（作者或动态作者） |
| P0 | GET | `/moments/notifications` | ✅ | 朋友圈消息（点赞/评论/回复） |
| P0 | POST | `/moments/notifications/read` | ✅ | 标记消息已读（指定 ID 或 `readAll`） |

#### `POST /moments` 请求体（已实现）

```json
{
  "text": "这一刻的想法...",
  "mediaIds": ["mom_media_abc"],
  "location": "深圳",
  "visibility": "EXCLUDE",
  "visibleUserIds": ["u_10002", "u_10003"]
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `text` | 条件 | 与 `mediaIds` 不能同时为空 |
| `mediaIds` | 条件 | 最多 9 个，须为本人已上传且未绑定动态的媒体 |
| `location` | 否 | 客户端 UI 已移除，字段仍接受但可忽略 |
| `visibility` | 否 | 默认 `FRIENDS`；可选 `EXCLUDE` / `PARTIAL` |
| `visibleUserIds` | 条件 | `EXCLUDE` / `PARTIAL` 时必填，须为双向好友 |

响应 `data` 为完整 `MomentItem`，含 `visibility`、`visibleUserIds`。

#### `GET /moments/users/{userId}` 响应（已实现）

```json
{
  "visibleRangeDays": 90,
  "user": {
    "userId": "u_10001",
    "nickname": "林远",
    "avatarUrl": "https://...",
    "remark": "",
    "visibleRangeDays": 90
  },
  "items": [],
  "nextCursor": null,
  "hasMore": false
}
```

- 看自己：返回本人 `visibleRangeDays`，不做时间过滤
- 看好友：返回对方 `visibleRangeDays`，`items` 已按该范围过滤
- `visibleRangeDays = 0`：表示「全部」，客户端可不展示提示

---

### 2.2 偏好设置

| 优先级 | 方法 | 路径 | 状态 | 说明 |
|--------|------|------|------|------|
| P0 | GET | `/moments/settings` | ✅ | 读取封面、可见范围、屏蔽列表 |
| P0 | PUT | `/moments/settings` | ✅ | 部分更新（只传变更字段） |

#### GET `/moments/settings` 响应

```json
{
  "coverUrl": "https://cdn.example.com/moments/cover/u_10001.jpg",
  "visibleRangeDays": 90,
  "blockedViewerIds": ["u_10002"],
  "hiddenAuthorIds": ["u_10004"]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `coverUrl` | string \| null | 封面 URL；null 表示默认封面 |
| `visibleRangeDays` | int | 允许朋友查看的时间范围（见下表） |
| `blockedViewerIds` | string[] | **不让他(她)看我的朋友圈** |
| `hiddenAuthorIds` | string[] | **不看他(她)的朋友圈** |

`visibleRangeDays` 枚举：

| 值 | 含义 |
|----|------|
| `0` | 全部 |
| `3` | 最近三天 |
| `90` | 最近三个月 |
| `180` | 最近半年 |
| `365` | 最近一年 |

#### PUT `/moments/settings` 示例

```json
{ "visibleRangeDays": 90 }
```

```json
{ "blockedViewerIds": ["u_10002", "u_10003"] }
```

```json
{ "coverUrl": null }
```

清除封面：请求体包含 `"coverUrl": null`（字段必须出现，不能省略）。

**业务规则（已在读接口生效）**：

| 规则 | 生效接口 |
|------|----------|
| `hiddenAuthorIds` | `GET /moments/feed` 过滤对应作者 |
| `blockedViewerIds` | `GET /moments/users/{authorId}`、`GET /moments/feed`、`GET /moments/{momentId}` |
| `visibleRangeDays` | `GET /moments/users/{authorId}` 过滤 `items` |
| 单条 `visibility` + `visibleUserIds` | Feed / 用户页 / 详情 |
| 全局屏蔽与单条规则叠加 | 被 `blockedViewerIds` 屏蔽的用户无论如何不可见 |

---

### 2.3 封面上传

| 优先级 | 方法 | 路径 | 状态 | 说明 |
|--------|------|------|------|------|
| P1 | POST | `/moments/cover/upload` | ✅ | `multipart/form-data`，字段 `file`（图片） |

响应：

```json
{
  "code": 0,
  "data": {
    "coverUrl": "https://cdn.example.com/moments/cover/u_10001.jpg"
  }
}
```

上传成功后客户端再调用 `PUT /moments/settings { "coverUrl": "..." }`。

> 未采用「合并到 `POST /moments/media/upload?purpose=cover`」方案，而是独立端点。

---

### 2.4 TCP 实时事件（服务端已实现）

客户端收到后建议轻量刷新或拉详情。**客户端尚未接入。**

| action | 触发时机 | 推送对象 |
|--------|----------|----------|
| `created` | 发布成功 | 作者 + 有权查看的好友 |
| `deleted` | 删除动态 | 同上 |
| `like_changed` | 点赞/取消 | 动态作者 + 操作者 |
| `comment_created` | 新评论/回复 | 动态作者 + 被回复者 + 操作者 |

统一 envelope（经现有 TCP 通道，`type` 默认为 `event`）：

```json
{
  "type": "event",
  "event": "moment_changed",
  "action": "created",
  "momentId": "mom_abc123",
  "authorUserId": "u_10001",
  "ts": 1718452800000
}
```

`like_changed` 额外字段：`actorUserId`、`liked`、`likeCount`  
`comment_created` 额外字段：`commentId`、`replyToCommentId`、`actorUserId`、`commentCount`

实现类：`MomentRealtimePublisher`、`MomentRealtimeNotifier`

---

### 2.5 已实现错误码

| code | 场景 |
|------|------|
| `UNAUTHORIZED` | 未登录 |
| `MOMENT_FORBIDDEN` | 无权访问 |
| `MOMENT_NOT_FOUND` | 内容不存在 |
| `MOMENT_NOT_OWNER` | 非作者删除 |
| `MOMENT_EMPTY_CONTENT` | 文字和媒体均为空 |
| `MOMENT_TEXT_TOO_LONG` | 文字过长 |
| `MOMENT_MEDIA_TOO_MANY` | 媒体超过 9 个 |
| `MOMENT_MEDIA_INVALID` | 媒体不可用 |
| `MOMENT_COMMENT_EMPTY` | 评论为空 |
| `MOMENT_COMMENT_TOO_LONG` | 评论过长 |
| `MOMENT_COMMENT_NOT_FOUND` | 评论不存在 |
| `MOMENT_SETTINGS_INVALID` | `visibleRangeDays` 不在允许枚举内 |
| `MOMENT_VISIBILITY_INVALID` | `visibility` 非法或缺少 `visibleUserIds` |
| `MOMENT_VISIBLE_USER_NOT_FRIEND` | `visibleUserIds` 含非好友 |
| `MOMENT_COVER_UPLOAD_FAILED` | 封面上传失败 |
| `INVALID_CURSOR` / `INVALID_PAGE_SIZE` | 分页参数无效 |
| `UPLOAD_FILE_TOO_LARGE` / `UPLOAD_TYPE_NOT_ALLOWED` | 上传限制 |

---

### 2.6 已实现数据库表

| 表 | 迁移脚本 | 说明 |
|----|----------|------|
| `moment` | `migrate-moments.sql` | 动态主表 |
| `moment_media` | 同上 | 媒体 |
| `moment_like` | 同上 | 点赞 |
| `moment_comment` | 同上 | 评论 |
| `moment_notification` | 同上 | 通知 |
| `moment_settings` | `migrate-moment-settings.sql` | 用户偏好（封面、可见范围） |
| `moment_settings_blocked_viewer` | 同上 | 不让他看 |
| `moment_settings_hidden_author` | 同上 | 不看他 |
| `moment_visible_user` | 同上 | 单条 EXCLUDE/PARTIAL 选人 |

> 开发环境 JPA `ddl-auto: update` 可自动建表；生产建议手动执行上述 SQL。

---

## 3. 已实现核心类

| 类 | 职责 |
|----|------|
| `MomentController` | REST 路由 |
| `MomentService` | 业务逻辑、权限过滤、媒体上传 |
| `MomentSettingsService` | 偏好读写、可见性判定 |
| `MomentRepository` 等 | 数据访问 |
| `MomentRealtimePublisher` / `MomentRealtimeNotifier` | TCP 事件 |

---

## 4. 联调检查清单

- [ ] 好友 A 发布动态，B 的 Feed 可见，非好友不可见
- [ ] A 设置「不让他看 B」，B 访问 A 个人页 403，Feed 无 A 新动态
- [ ] A 设置「不看他 B」，A 的 Feed 不出现 B 的动态
- [ ] A 设置可见范围「最近三天」，B 看 A 个人页仅见三天内动态，且看到提示
- [ ] A 发布「不给谁看 B」，B 看不到该条，其他好友可见
- [ ] A 发布「部分可见 [C]」，仅 C 可见
- [ ] 点赞/评论后，作者通知列表有未读，已读接口生效
- [ ] 换设备登录，设置页数据与服务端一致
- [ ] 封面上传 + `PUT /moments/settings` 后封面同步
- [ ] TCP 在线时收到 `moment_changed` 事件（需客户端接入后验证）

---

## 5. 未实现 / 待完成

### 5.1 后端可选接口（规格中提及，当前未做）

| 优先级 | 方法 | 路径 | 说明 |
|--------|------|------|------|
| P0 可选 | PUT | `/moments/settings/blocked-viewers/{userId}` | 单人粒度增删「不让他看」；当前用 `PUT /moments/settings` 全量列表即可 |
| P0 可选 | PUT | `/moments/settings/hidden-authors/{userId}` | 单人粒度增删「不看他」；同上 |

资料页快捷开关可直接读写 `GET|PUT /moments/settings`，无需上述独立接口。

### 5.2 TCP / 推送

| 项 | 状态 | 说明 |
|----|------|------|
| `moment_settings_changed` 事件 | ❌ | P2 可选；设置变更后通知各端刷新 `GET /moments/settings` |
| 离线 Push（TCP 不可达时） | ❌ | 群/好友列表有离线补偿，朋友圈暂未做 |
| 向「当前页观众」广播点赞 | ❌ | 规格标注可选；当前仅推作者 + 操作者 |

### 5.3 媒体上传替代方案

| 项 | 状态 | 说明 |
|----|------|------|
| `POST /moments/media/upload?purpose=cover` | ❌ | 规格备选方案；已用独立 `/moments/cover/upload` 代替 |

### 5.4 测试与文档

| 项 | 状态 | 说明 |
|----|------|------|
| 单元 / 集成测试 | ❌ | `src/test` 下暂无 moments 相关测试 |
| `docs/moments-frontend-api.md` 同步 | ❌ | 该文档仍写「仅支持 FRIENDS」等旧描述，需更新或改引用本文 |
| `docs/moments-backend-api-implementation.md` | ❌ | 原设计文档不存在于仓库 |

### 5.5 客户端待改造（非后端，供联调参考）

| 本地逻辑 | 应替换为 | 状态 |
|----------|----------|------|
| `MomentsLocalPrefs.load/saveVisibleRangeDays` | `GET\|PUT /moments/settings` | 客户端待接 |
| `MomentsLocalPrefs.load/saveBlockedViewerIds` | 同上 | 客户端待接 |
| `MomentsLocalPrefs.load/saveHiddenAuthorIds` | 同上 | 客户端待接 |
| `MomentsLocalPrefs.load/saveCoverPath` | `coverUrl` + `/moments/cover/upload` | 客户端待接 |
| `MomentsApi.createPost` 固定 `FRIENDS` | 传 `visibility` + `visibleUserIds` | 客户端待接 |
| TCP `moment_changed` 刷新 | 接入现有 TCP 通道 | 客户端待接 |

### 5.6 运营 / 管理后台

| 项 | 状态 | 说明 |
|----|------|------|
| Admin 朋友圈管理 | ❌ | 无动态审核、封禁、统计等后台接口 |

---

## 6. 部署提醒

1. 执行迁移（若未用 `ddl-auto: update`）：
   ```bash
   mysql ... < scripts/migrate-moments.sql
   mysql ... < scripts/migrate-moment-settings.sql
   ```
2. 确认 OSS 已配置（媒体 / 封面上传依赖 `OssClient`）
3. 确认 TCP 实时通道已启用（`chat99.realtime.enabled=true`）
4. 重启服务后与客户端按第 4 节清单联调

---

## 7. 建议后续顺序

1. **联调**：客户端接入 settings REST + 发布可见性 + TCP 事件
2. **补测试**：Feed 过滤、屏蔽、EXCLUDE/PARTIAL、visibleRangeDays
3. **更新** `docs/moments-frontend-api.md` 或标记废弃，统一引用本文
4. **可选**：`moment_settings_changed` 事件、离线 Push、Admin 管理
