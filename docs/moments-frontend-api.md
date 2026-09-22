# 朋友圈前端对接文档

## 基本约定

- Base Path：`/moments`
- 鉴权：所有接口都需要 `Authorization: Bearer <JWT>`
- Content-Type：
  - JSON 接口：`application/json`
  - 上传接口：`multipart/form-data`
- 成功响应统一：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

- 失败响应统一：

```json
{
  "code": "MOMENT_NOT_FOUND",
  "message": "内容不存在"
}
```

- 时间字段统一为毫秒时间戳，例如：`1718452800000`
- 分页统一使用 cursor：

```json
{
  "items": [],
  "nextCursor": "1718452800000_mom_abc",
  "hasMore": true
}
```

前端下一页请求直接带上 `nextCursor`。当 `hasMore=false` 或 `nextCursor=null` 时没有下一页。

## 数据结构

### 用户快照

所有出现用户的地方统一使用此结构（`avatarUrl` 必填，须为真实 URL，不要用空字符串占位）：

```json
{
  "userId": "u_10001",
  "nickname": "林远",
  "avatarUrl": "https://cdn.example.com/avatar/u_10001.jpg",
  "remark": "老林"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| userId | ✅ | IM 用户 ID |
| nickname | ✅ | 昵称 |
| avatarUrl | ✅ | 用户真实头像 URL |
| remark | 否 | 当前登录用户对该用户的备注（好友才有，无则 `""`） |

展示名建议：`remark || nickname || userId`。

### 点赞预览

```json
{
  "user": {},
  "createdAt": 1718452800000
}
```

### 媒体

```json
{
  "mediaId": "mom_media_abc",
  "type": "IMAGE",
  "url": "https://...",
  "thumbUrl": "https://...",
  "width": 1080,
  "height": 1440,
  "durationSec": null,
  "sizeBytes": 382001
}
```

`type`：`IMAGE` / `VIDEO`。

### 评论

```json
{
  "commentId": "cmt_abc",
  "author": {},
  "replyToCommentId": null,
  "replyToUser": null,
  "text": "这个地方不错",
  "createdAt": 1718452800000,
  "canDelete": false
}
```

普通评论：`replyToCommentId=null`、`replyToUser=null`。

回复评论：`replyToCommentId` 不为空，`replyToUser` 返回被回复用户。前端按 `A 回复 B：内容` 展示即可，不需要楼中楼树。

### 动态列表项

```json
{
  "momentId": "mom_abc123",
  "author": {},
  "text": "周末出去走走。",
  "mediaList": [],
  "location": "深圳",
  "visibility": "FRIENDS",
  "createdAt": 1718452800000,
  "updatedAt": 1718452800000,
  "likedByMe": false,
  "likeCount": 2,
  "likesPreview": [
    {
      "user": {
        "userId": "u_10002",
        "nickname": "Alice",
        "avatarUrl": "https://cdn.example.com/avatar/u_10002.jpg",
        "remark": ""
      },
      "createdAt": 1718452800000
    }
  ],
  "commentCount": 1,
  "commentsPreview": [
    {
      "commentId": "cmt_abc",
      "author": {
        "userId": "u_10002",
        "nickname": "Alice",
        "avatarUrl": "https://cdn.example.com/avatar/u_10002.jpg",
        "remark": ""
      },
      "replyToCommentId": null,
      "replyToUser": null,
      "text": "11",
      "createdAt": 1718452800000,
      "canDelete": false
    }
  ],
  "canDelete": false
}
```

列表接口（Feed / 用户动态）返回 `likesPreview`（最多 8 个最近点赞）和 `commentsPreview`（最早 2 条评论）。详情接口返回完整 `likes` 和 `comments`。

**关键规则：**

| 场景 | 后端必须做 |
| --- | --- |
| `likeCount > 0` | `likesPreview` 不能为空，至少返回最近点赞用户（最多 8 个） |
| `commentCount > 0` | `commentsPreview` 不能为空，返回最早 2 条评论 |
| 列表接口 | 不要同时返回 `"likes": []` 与 `"likesPreview"` |
| 列表接口 | 不要同时返回 `"comments": []` 与 `"commentsPreview"` |

每条点赞、评论里的 `user` / `author` / `replyToUser` 都要带 `avatarUrl`。

## 接口

### 1. 好友动态流

```http
GET /moments/feed?cursor=xxx&pageSize=20
```

参数：

- `cursor`：可选，上一页返回的 `nextCursor`
- `pageSize`：可选，默认 20，最大 50

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [],
    "nextCursor": null,
    "hasMore": false
  }
}
```

说明：返回自己和双向好友的未删除动态。

### 2. 用户个人动态

```http
GET /moments/users/{userId}?cursor=xxx&pageSize=20
```

权限：

- 看自己：允许
- 看别人：必须是双向好友

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "user": {},
    "items": [],
    "nextCursor": null,
    "hasMore": false
  }
}
```

### 3. 动态详情

```http
GET /moments/{momentId}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "momentId": "mom_abc123",
    "author": {},
    "text": "周末出去走走。",
    "mediaList": [],
    "location": "深圳",
    "visibility": "FRIENDS",
    "createdAt": 1718452800000,
    "updatedAt": 1718452800000,
    "likedByMe": true,
    "likeCount": 3,
    "likes": [
      {
        "user": {
          "userId": "u_10002",
          "nickname": "Alice",
          "avatarUrl": "https://cdn.example.com/avatar/u_10002.jpg",
          "remark": ""
        },
        "createdAt": 1718452800000
      }
    ],
    "commentCount": 12,
    "comments": [],
    "canDelete": false
  }
}
```

详情页返回完整 `likes`（全部点赞，含 `user.avatarUrl`）和 `comments`（全部评论）。列表用 `likesPreview` / `commentsPreview`，详情用 `likes` / `comments`，字段不混用。

### 4. 上传媒体

```http
POST /moments/media/upload
Content-Type: multipart/form-data
```

表单字段：

- `file`：必填，图片或视频文件
- `type`：必填，`IMAGE` / `VIDEO`
- `clientMediaId`：可选，客户端临时 ID，用于重复上传幂等

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "mediaId": "mom_media_abc",
    "type": "IMAGE",
    "url": "https://...",
    "thumbUrl": "https://...",
    "width": 1080,
    "height": 1440,
    "durationSec": null,
    "sizeBytes": 382001
  }
}
```

限制：

- 图片最大 10MB
- 视频最大 100MB
- 图片支持：`jpg` / `jpeg` / `png` / `webp`
- 视频支持：`mp4` / `webm` / `mov` / `m4v`

### 5. 发布动态

```http
POST /moments
Idempotency-Key: <uuid>
Content-Type: application/json
```

请求：

```json
{
  "text": "这一刻的想法...",
  "mediaIds": ["mom_media_abc"],
  "location": "深圳",
  "visibility": "FRIENDS"
}
```

规则：

- `text` 和 `mediaIds` 不能同时为空
- `mediaIds` 最多 9 个
- `visibility` 当前只支持 `FRIENDS`
- `mediaIds` 必须是当前用户上传且未绑定动态的媒体
- 建议前端每次发布生成一个 UUID 作为 `Idempotency-Key`

响应：`data` 为动态列表项结构。

### 6. 删除动态

```http
DELETE /moments/{momentId}
```

权限：仅动态作者。

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "momentId": "mom_abc123",
    "deleted": true
  }
}
```

### 7. 点赞

```http
POST /moments/{momentId}/likes
```

重复点赞是幂等成功。

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "momentId": "mom_abc123",
    "likedByMe": true,
    "likeCount": 4,
    "likesPreview": [
      {
        "user": {
          "userId": "u_10002",
          "nickname": "Alice",
          "avatarUrl": "https://cdn.example.com/avatar/u_10002.jpg",
          "remark": ""
        },
        "createdAt": 1718452800000
      }
    ]
  }
}
```

点赞/取消点赞后，客户端可继续调 `GET /moments/{momentId}` 刷新详情；`likesPreview` 返回最近 8 个点赞用户（含头像）。

### 8. 取消点赞

```http
DELETE /moments/{momentId}/likes/me
```

未点赞时也是幂等成功。

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "momentId": "mom_abc123",
    "likedByMe": false,
    "likeCount": 3,
    "likesPreview": [
      {
        "user": {
          "userId": "u_10003",
          "nickname": "Bob",
          "avatarUrl": "https://cdn.example.com/avatar/u_10003.jpg",
          "remark": ""
        },
        "createdAt": 1718452900000
      }
    ]
  }
}
```

### 9. 评论 / 回复评论

```http
POST /moments/{momentId}/comments
Idempotency-Key: <uuid>
Content-Type: application/json
```

普通评论：

```json
{
  "text": "这个地方不错",
  "replyToCommentId": null
}
```

回复评论：

```json
{
  "text": "是的",
  "replyToCommentId": "cmt_parent"
}
```

规则：

- `text` 必填，最大 500 字符
- `replyToCommentId` 为空表示普通评论
- `replyToCommentId` 不为空时，必须属于当前动态且未删除

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "momentId": "mom_abc123",
    "commentCount": 3,
    "comment": {
      "commentId": "cmt_abc",
      "author": {
        "userId": "u_10002",
        "nickname": "Alice",
        "avatarUrl": "https://cdn.example.com/avatar/u_10002.jpg",
        "remark": ""
      },
      "replyToCommentId": null,
      "replyToUser": null,
      "text": "这个地方不错",
      "createdAt": 1718452800000,
      "canDelete": false
    }
  }
}
```

回复评论时 `replyToUser` 同样须带 `avatarUrl`。

### 10. 删除评论

```http
DELETE /moments/{momentId}/comments/{commentId}
```

权限：

- 评论作者可以删除自己的评论
- 动态作者可以删除自己动态下任意评论

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "momentId": "mom_abc123",
    "commentId": "cmt_abc",
    "deleted": true,
    "commentCount": 2
  }
}
```

### 11. 朋友圈消息列表

```http
GET /moments/notifications?cursor=xxx&pageSize=20
```

消息类型：

- `LIKE`：别人赞了我的动态
- `COMMENT`：别人评论了我的动态
- `COMMENT_REPLY`：别人回复了我的评论

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "notificationId": "noti_abc",
        "type": "COMMENT_REPLY",
        "actor": {
          "userId": "u_10002",
          "nickname": "Alice",
          "avatarUrl": "https://cdn.example.com/avatar/u_10002.jpg",
          "remark": ""
        },
        "momentId": "mom_abc123",
        "momentAuthor": {},
        "momentText": "周末出去走走。",
        "momentPreviewMedia": {
          "type": "IMAGE",
          "thumbUrl": "https://..."
        },
        "comment": {},
        "replyToUser": {},
        "createdAt": 1718452800000,
        "read": false
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "unreadCount": 0
  }
}
```

### 12. 标记消息已读

```http
POST /moments/notifications/read
Content-Type: application/json
```

指定消息已读：

```json
{
  "notificationIds": ["noti_abc"],
  "readAll": false
}
```

全部已读：

```json
{
  "notificationIds": [],
  "readAll": true
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "updatedCount": 1,
    "unreadCount": 0
  }
}
```

## 权限与刷新建议

- 非好友不能查看动态详情、点赞、评论。
- 删除动态只能由动态作者操作。
- 删除评论由评论作者或动态作者操作。
- 自己给自己点赞、评论不生成通知。
- 回复自己的评论不生成通知。
- 发布、删除、点赞、评论成功后，前端可以局部更新当前动态；如果状态复杂，建议重新拉取 `GET /moments/{momentId}`。
- Feed 页收到未来实时事件后，建议轻量刷新当前动态或重新拉第一页。

## 常见错误码

```text
UNAUTHORIZED
MOMENT_FORBIDDEN
MOMENT_NOT_FOUND
MOMENT_NOT_OWNER
MOMENT_EMPTY_CONTENT
MOMENT_TEXT_TOO_LONG
MOMENT_MEDIA_TOO_MANY
MOMENT_MEDIA_INVALID
MOMENT_COMMENT_EMPTY
MOMENT_COMMENT_TOO_LONG
MOMENT_COMMENT_NOT_FOUND
INVALID_CURSOR
INVALID_PAGE_SIZE
UPLOAD_FILE_TOO_LARGE
UPLOAD_TYPE_NOT_ALLOWED
```

