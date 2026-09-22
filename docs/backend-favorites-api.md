# 消息收藏 — 后端 API 规格

> 版本：v1.0（已实现）  
> 适用：99chat Server / Flutter 客户端  
> **注意**：与 **表情收藏** `GET/POST /me/stickers/favorites` 是两套独立能力。

---

## 1. 概述

| 项 | 说明 |
|------|------|
| 业务 | 微信式「我 → 收藏」：文本 / 图片 / 短视频（≤60s） |
| 鉴权 | `Authorization: Bearer <JWT>` |
| 字段命名 | **camelCase** |
| 错误体 | `{ "code": "...", "message": "..." }` |
| 媒体 | 落 OSS 快照；路径前缀 `user-favorite/{userId}/...` |

---

## 2. 接口速查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/me/favorites` | 分页列表，可选 `type=TEXT\|IMAGE\|VIDEO` |
| GET | `/me/favorites/{id}` | 详情 |
| POST | `/me/favorites` | JSON 创建（聊天收藏 / 笔记） |
| POST | `/me/favorites/upload` | multipart 手动添加图/视频 |
| PUT | `/me/favorites/{id}` | 更新文本或备注 |
| DELETE | `/me/favorites/{id}` | 删除单条 + OSS |
| DELETE | `/me/favorites` | 批量删除 body `{ "ids": [...] }` |

---

## 3. 列表 `GET /me/favorites`

Query：`type`（可选）、`page`（默认 0）、`size`（默认 20，最大 100）

**200**

```json
{
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "type": "TEXT",
      "text": "明天下午三点开会",
      "thumbUrl": null,
      "mediaUrl": null,
      "durationSec": null,
      "width": null,
      "height": null,
      "sourceSenderName": "张三",
      "sourceConvLabel": "产品讨论组",
      "sourceMsgId": "im_msg_abc",
      "sourceConvId": "group_xxx",
      "isManual": false,
      "favoritedAt": "2026-05-28T10:00:00Z",
      "updatedAt": "2026-05-28T10:00:00Z"
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20
}
```

---

## 4. 创建 `POST /me/favorites`

### 4.1 新建场景怎么选接口

| 场景 | 接口 | 必填 |
|------|------|------|
| 收藏页「新建文字笔记」 | `POST /me/favorites` | `text`（可省略 `type`，服务端推断为 `TEXT`） |
| 聊天里「收藏」文字/图片/视频 | `POST /me/favorites` | `type` 或 IM `elemType`/`messageType`；图/视频还要 `remoteMediaUrl` |
| 相册选图/视频加入收藏 | `POST /me/favorites/upload` | `file`；建议 `metadata.type` |

腾讯 IM 转发收藏时，`messageType` / `elemType` 常为 **1=文本、3=图片、5=视频**（不是 0/1/2）。

### 4.2 请求示例

```json
{
  "type": "IMAGE",
  "text": null,
  "remoteMediaUrl": "https://im-cdn/.../image.jpg",
  "remoteThumbUrl": "https://im-cdn/.../thumb.jpg",
  "durationSec": null,
  "sourceMsgId": "im_msg_abc",
  "sourceConvId": "group_xxx",
  "sourceSenderName": "张三",
  "sourceConvLabel": "产品讨论组",
  "remark": null
}
```

- `type=TEXT`：`text` 必填  
- `type=IMAGE|VIDEO`：`remoteMediaUrl` 必填；服务端下载后转存 OSS  
- `sourceMsgId` 幂等：已存在则 **200** 返回原记录  
- `durationSec` > 60 → `400 VIDEO_TOO_LONG`  
- `remark` 可写入 `sourceConvLabel`（备注）

---

## 5. 手动上传 `POST /me/favorites/upload`

`multipart/form-data`：

| 字段 | 说明 |
|------|------|
| `file` | 图片或视频（必填） |
| `snapshot` | 视频封面（可选） |
| `metadata` | JSON 字符串，如 `{"type":"VIDEO","durationSec":12,"sourceConvLabel":"备注"}` |

- **纯文本笔记**也可用 upload：仅传 `metadata`（无需 `file`），例如 `{"type":"TEXT","text":"笔记内容","sourceConvLabel":"备注"}`
- `type` 大小写不敏感（`TEXT` / `text` 均可）

图片：jpeg/png/webp，≤ **100MB**。视频：mp4/mov，≤ **100MB**（配置项 `chat99.favorite.max-upload-bytes`，默认 `104857600`）。

**新建文本笔记（推荐）**：`POST /me/favorites` + JSON `{"type":"TEXT","text":"..."}`，勿对文本走 upload 却不带 `type`。

---

## 6. 更新 / 删除

**PUT** `/me/favorites/{id}`

```json
{ "text": "新笔记", "sourceConvLabel": "备注" }
```

仅 `type=TEXT` 可改 `text`。

**DELETE** `/me/favorites/{id}` → 204 无 body

**DELETE** `/me/favorites` body `{ "ids": ["uuid1"] }` → `{ "deleted": 1 }`

---

## 7. 错误码

| code | HTTP | 说明 |
|------|------|------|
| `FAVORITE_NOT_FOUND` | 404 | 不存在或非本人 |
| `EMPTY_CONTENT` | 400 | 内容为空 |
| `EMPTY_FILE` | 400 | 上传空文件 |
| `UNSUPPORTED_TYPE` | 415 | MIME/后缀不允许 |
| `INVALID_IMAGE` | 400 | 无法解码图片 |
| `FILE_TOO_LARGE` | 413 | 超过 100MB（收藏专用上限） |
| `VIDEO_TOO_LONG` | 400 | 视频 >60s |
| `MEDIA_FETCH_FAILED` | 502 | 拉取 remote URL 失败 |
| `OSS_NOT_CONFIGURED` | 503 | OSS 未配置 |
| `INVALID_INPUT` | 400 | 参数非法（见下表） |

### 7.1 `INVALID_INPUT` 常见原因

| 场景 | 原因 | 正确做法 |
|------|------|----------|
| 新建文本笔记 | 走了 `upload` 且 `metadata` 无 `type`，或 `type` 写错 | `POST /me/favorites` + `{"type":"TEXT","text":"..."}`；或 upload 仅 `metadata={"type":"TEXT","text":"..."}` |
| 上传图/视频 | `metadata` 用了 `messageType` 但未映射、或 JSON 非法 | `metadata` 用 `type`（也支持别名 `messageType` / `favoriteType`） |
| 仅传 `file` 无 `metadata` | 旧版会报错 | 现已按文件 MIME/后缀推断 `IMAGE`/`VIDEO`；仍建议显式传 `type` |
| JSON 创建 | 缺 `type` 且无 `text`/`remoteMediaUrl` | 补 `type`，或只传 `text`（推断 TEXT）/ 只传 `remoteMediaUrl`（推断 IMAGE/VIDEO） |
| 字段名 | 用了 `content` 而非 `text` | 已支持别名 `content` / `body` / `note`；`remoteMediaUrl` 别名 `mediaUrl` / `url` |
| 编辑收藏 | 对图片/视频条目 PUT 了 `text` | 仅 `type=TEXT` 可改 `text`；图/视频只改 `sourceConvLabel` |
| `type` 取值 | 传了腾讯 IM 的 `elemType` 但未识别 | 支持 IM：`1`=文本、`3`=图片、`5`=视频；也可用 `elemType` 字段；字符串用 `TEXT`/`IMAGE`/`VIDEO` |
| 聊天收藏新建 | 只传 `messageType:1` 被当成图片 | 已按 IM 规则解析；文本需带 `text` 或 `content`，图/视频需 `remoteMediaUrl`/`mediaUrl` |

---

## 8. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05-28 | 服务端实现 P0+P1 |
| 2026-05-29 | 放宽 `type`/字段别名；upload 可按文件推断类型；兼容 IM `elemType` 1/3/5 |
