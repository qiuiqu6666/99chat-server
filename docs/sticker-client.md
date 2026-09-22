# 自定义表情（Sticker）— 服务端接口与数据模型

**版本**：v1.0  
**适用**：99chat 自建后端 + 腾讯 IM Face 消息  
**原则**：表情**资源**与**用户收藏关系**解耦；聊天展示不依赖「是否已收藏」；多端登录、换设备、删收藏后历史消息仍可正确渲染  
**关联客户端**：`lib/src/api/sticker_api.dart` · `lib/utils/sticker_constants.dart` · `lib/src/repository/sticker_repository.dart`

---

## 1. 实现状态

| 能力 | 状态 |
|------|------|
| `GET /stickers/{id}` 任意登录用户可读 | ✅ |
| `POST /stickers/batch` 批量查询 | ✅ |
| `POST /stickers/upload` + OSS `stickers/{stickerId}/` | ✅ |
| 收藏增删（仅关系表） | ✅ |
| `user_upload` 按用户维度（`user_sticker_pack_item`） | ✅ |
| 「添加到表情」不要求所有权 | ✅ |
| 封禁/删除返回 410 `STICKER_UNAVAILABLE` | ✅ |
| 虚拟 `favorites` 包（`GET /me/sticker-packs`） | ✅ |
| 视频上传自动转 GIF（FFmpeg） | ✅ |
| IM 发送前回调校验 | ✅ |

---

## 2. API 一览

鉴权：`Authorization: Bearer <JWT>`  
成功响应经 `GlobalResponseWrapper` 包装为 `{ "code": 0, "message": "ok", "data": ... }`。

### 2.1 获取表情元数据

`GET /stickers/{stickerId}` — 任意已登录用户，**不校验收藏**。

| HTTP | code | 说明 |
|------|------|------|
| 200 | — | 返回 `StickerItem` |
| 404 | `STICKER_NOT_FOUND` | 不存在 |
| 410 | `STICKER_UNAVAILABLE` | `banned` / `deleted_by_owner` |

### 2.2 批量查询

`POST /stickers/batch`

```json
{ "stickerIds": ["stk_1", "stk_2"] }
```

响应 `data`：

```json
{
  "items": [ { "stickerId": "...", "thumbUrl": "...", "originUrl": "...", "mediaType": "gif" } ],
  "missing": ["stk_3"]
}
```

约束：1–50 个 ID，服务端去重。

### 2.3 上传

`POST /stickers/upload` — `multipart/form-data`，字段 `file`；可选 `mediaType=gif|video`。

| 输入类型 | 格式 | 上限 | 存储 |
|----------|------|------|------|
| 静态图 | PNG / JPEG / WebP | 2MB | `mediaType=image` |
| 动图 | GIF | 5MB | `mediaType=gif` |
| 短视频 | MP4 / WebM / MOV | 50MB，≤10 秒 | **自动转 GIF**，`mediaType=gif` |

视频转 GIF 依赖服务器安装 **FFmpeg**（`ffmpeg` + `ffprobe`）。未安装时返回 `503 FFMPEG_NOT_CONFIGURED`。

- 新 ID 格式：`stk_{uuid32}`
- OSS 路径：`stickers/{stickerId}/thumb.jpg`、`stickers/{stickerId}/origin.{ext}`
- **尺寸**：`originUrl` 与 `thumbUrl` 均保持原比例；静态图/GIF 原图不缩放，缩略图最长边 ≤ `thumb-size`（JPEG 质量见 `thumb-jpeg-quality`）；视频转 GIF 最长边 ≤ `video-output-size`
- 自动写入当前用户 `user_upload` 包

配置项（`application.yml` → `chat99.sticker`）：

| 项 | 默认 | 说明 |
|----|------|------|
| `video-max-bytes` | 50MB | 原视频大小上限 |
| `video-max-duration-seconds` | 10 | 超过拒绝 `VIDEO_TOO_LONG` |
| `video-output-size` | 480 | 输出 GIF 最长边上限（**保持原比例**，不裁剪） |
| `thumb-size` | 480 | 缩略图最长边上限（**保持原比例**，不裁剪） |
| `thumb-jpeg-quality` | 95 | 缩略图 JPEG 压缩质量（1–100，越大越清晰、体积越大） |
| `video-max-fps` | 20 | 输出帧率 |
| `gif-max-bytes` | 5MB | 转码后 GIF 上限 |
| `ffmpeg-path` | `/www/server/ffmpeg/ffmpeg-6.1/ffmpeg` | 可环境变量 `FFMPEG_PATH` |
| `ffprobe-path` | `/www/server/ffmpeg/ffmpeg-6.1/ffprobe` | 可环境变量 `FFPROBE_PATH` |

> **清晰度说明**：聊天面板若展示 `thumbUrl`，清晰度由 `thumb-size` + `thumb-jpeg-quality` 决定；动图/视频发送后展示 `originUrl` 时，GIF 原图不二次压缩，视频转 GIF 由 `video-output-size` / `video-max-fps` 决定。**已上传的历史表情不会自动重处理**，需重新上传后才会生效。

### 2.4 收藏

| 方法 | 路径 |
|------|------|
| GET | `/me/stickers/favorites` |
| POST | `/me/stickers/favorites` |
| DELETE | `/me/stickers/favorites/{stickerId}` |

GET 同时返回 `favorites` 与 `items`（兼容旧客户端）。

### 2.5 表情包

| 方法 | 路径 |
|------|------|
| GET | `/me/sticker-packs` |
| PUT | `/me/sticker-packs/order` |
| DELETE | `/me/sticker-packs/{packId}` |

响应含虚拟 `favorites` 包（`removable: false`）及 `user_upload`（`removable: false`）。

### 2.6 我的上传项

| 方法 | 路径 |
|------|------|
| POST | `/me/sticker-packs/custom/items` |
| DELETE | `/me/sticker-packs/custom/items/{stickerId}` |

「添加到表情」：任意 `active` 表情可加入当前用户 `user_upload`，不要求 `ownerUserId`。

---

## 3. 数据模型

| 表 | 说明 |
|----|------|
| `sticker` | 全局表情资源 |
| `user_sticker_favorite` | 用户收藏关系 |
| `user_sticker_pack` | 用户已安装包 |
| `user_sticker_pack_item` | 用户包内项（`user_upload` 按用户维度） |
| `sticker_pack` / `sticker_pack_item` | 系统包 / 商店包 |

`sticker.status`：`active` | `banned` | `deleted_by_owner`

---

## 4. IM 消息约定

| 字段 | 值 |
|------|-----|
| `elemType` | Face |
| `faceElem.index` | `99` |
| `faceElem.data` | `99chat://sticker/{stickerId}?thumbUrl=...&originUrl=...` |

接收方无内嵌 URL 时调用 `GET /stickers/{id}` 渲染。

---

## 5. 迁移

生产环境执行：`scripts/migrate-sticker-user-pack-items.sql`

---

## 6. 联调用例

| # | 步骤 | 期望 |
|---|------|------|
| 1 | A 上传，B 未收藏 | B `GET /stickers/{id}` 200 |
| 2 | A 发 Face 给 B | B 正常显示 |
| 3 | B 收藏再取消 | 历史消息仍正常 |
| 4 | B 新设备拉历史 | 同 #2 |
| 5 | batch 含无效 ID | `items` + `missing` 正确 |
| 6 | 仅 `99chat://sticker/{id}` | `GET` 可渲染 |

---

## 7. IM 发送前回调（P2）

腾讯云 IM **发消息之前**回调（`C2C.CallbackBeforeSendMsg` / `Group.CallbackBeforeSendMsg`）校验自定义 Face 表情：

| 检查项 | 动作 |
|--------|------|
| `faceElem.index == 99` | 解析 `data` 中 `stickerId`（`99chat://sticker/{id}`） |
| `stickers.status != active` | 拒绝发送，`ErrorInfo=STICKER_UNAVAILABLE` |
| stickerId 不存在 | 拒绝发送，`ErrorInfo=STICKER_NOT_FOUND` |
| 非 `99chat://sticker/` 协议 | 跳过（内置表情 / 直链不拦截） |

配置（`application.yml` → `chat99.sticker`）：

| 项 | 默认 | 说明 |
|----|------|------|
| `before-send-enabled` | `true` | 总开关 |
| `before-send-log-only` | `true` | 仅打日志，不拦截（灰度） |
| `before-send-enforce` | `false` | `true` 时真正拒发 |

---

## 8. 关联文档

- [backend-sticker-api.md](./backend-sticker-api.md) — HTTP 速查
- [backend-sticker-integration.md](./backend-sticker-integration.md) — 联调指南
