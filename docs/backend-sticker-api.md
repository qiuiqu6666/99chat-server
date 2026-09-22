# 自定义表情包 — HTTP API 规格

> 状态：**已完成**（2026-06-20 对齐 sticker-client 规范）  
> 客户端：`StickerApi`（lib/src/api/sticker_api.dart）  
> 鉴权：`Authorization: Bearer <access_token>`

---

## 1. 数据模型

### StickerItem

```json
{
  "stickerId": "stk_abc123def456",
  "thumbUrl": "https://cdn.example.com/stickers/stk_abc/thumb.jpg",
  "originUrl": "https://cdn.example.com/stickers/stk_abc/origin.gif",
  "mediaType": "image",
  "width": 240,
  "height": 240,
  "sortOrder": 0
}
```

`mediaType`：`image` | `gif`  
`stickerId` 前缀：`stk_`

### StickerPack

```json
{
  "packId": "user_upload",
  "name": "我的上传",
  "iconUrl": null,
  "source": "custom",
  "sortOrder": 3,
  "removable": false,
  "stickers": []
}
```

`source`：`system` | `subscribed` | `custom`  
系统包：`4350`、`4351`、`4352`（`removable: false`，`stickers` 为空）  
虚拟包：`favorites`（收藏，由服务端合成）

---

## 2. 接口

### 2.1 我的表情包

| 方法 | 路径 |
|------|------|
| GET | `/me/sticker-packs` |
| PUT | `/me/sticker-packs/order` |
| DELETE | `/me/sticker-packs/{packId}` |

**GET 200**：`{ "packs": [ /* StickerPack[]，含 favorites 虚拟包 */ ] }`

### 2.2 收藏

| 方法 | 路径 |
|------|------|
| GET | `/me/stickers/favorites` |
| POST | `/me/stickers/favorites` |
| DELETE | `/me/stickers/favorites/{stickerId}` |

**GET 200**：`{ "favorites": [...], "items": [...] }`（同内容，兼容旧字段名）

### 2.3 上传与包项

| 方法 | 路径 |
|------|------|
| POST | `/stickers/upload` |
| POST | `/me/sticker-packs/custom/items` |
| DELETE | `/me/sticker-packs/custom/items/{stickerId}` |

**POST /stickers/upload** — `multipart/form-data`，字段 `file`；可选 `mediaType=gif|video`

| 类型 | 上限 | 说明 |
|------|------|------|
| png/jpeg/webp | 2MB | `mediaType=image` |
| gif | 5MB | `mediaType=gif` |
| mp4/webm/mov | 50MB，≤10s | 服务端 FFmpeg 转 GIF，响应 `mediaType=gif` |

### 2.4 单张 / 批量查询

| 方法 | 路径 |
|------|------|
| GET | `/stickers/{stickerId}` |
| POST | `/stickers/batch` |

**GET**：任意登录用户可读；`banned`/`deleted_by_owner` → 410  
**POST batch body**：`{ "stickerIds": ["stk_1", "stk_2"] }`（1–50，去重）  
**POST batch 响应**：`{ "items": [...], "missing": ["stk_3"] }`

---

## 3. 错误码

| HTTP | code |
|------|------|
| 401 | 未登录 |
| 403 | `FORBIDDEN` / `PACK_NOT_REMOVABLE` |
| 404 | `STICKER_NOT_FOUND` / `PACK_NOT_FOUND` |
| 410 | `STICKER_UNAVAILABLE` |
| 413 | `FILE_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA` / `UNSUPPORTED_MEDIA_TYPE` |
| 503 | `FFMPEG_NOT_CONFIGURED` / `VIDEO_CONVERSION_DISABLED` |
| 400 | `VIDEO_TOO_LONG` |

**禁止**：`STICKER_NOT_FAVORITED`（GET 单条元数据时）

### 3.1 IM 发送前回调

`C2C.CallbackBeforeSendMsg` / `Group.CallbackBeforeSendMsg` 校验 `index=99` 的 Face 消息：

- `99chat://sticker/{id}` → 查 `sticker` 表，`active` 才放行
- 不存在 → `STICKER_NOT_FOUND`
- `banned` / `deleted_by_owner` → `STICKER_UNAVAILABLE`

配置：`chat99.sticker.before-send-enabled`（默认 `true`）、`before-send-log-only`（默认 `true`）、`before-send-enforce`（默认 `false`）

---

## 4. IM 消息

- `FaceMessage.index` = **99**
- `FaceMessage.data` = `99chat://sticker/{stickerId}?thumbUrl=...&originUrl=...`

联调示例见 [backend-sticker-integration.md](./backend-sticker-integration.md)。  
完整规范见 [sticker-client.md](./sticker-client.md)。
