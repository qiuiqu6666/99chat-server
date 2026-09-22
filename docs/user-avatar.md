# 用户头像 — 客户端对接文档

> 版本：v1.0  
> 适用：99chat Flutter / iOS / Android  
> 相关：[nickname-client.md](./nickname-client.md)（昵称）、[group-avatar.md](./group-avatar.md)（群头像，上传规则一致）

---

## 1. 概述

| 项 | 说明 |
|------|------|
| 接口 | `POST /me/avatar` |
| 鉴权 | `Authorization: Bearer <JWT>` |
| 格式 | `multipart/form-data`，字段名 `file` |
| 行为 | 上传 OSS（三尺寸 JPEG）→ 更新 `users.avatar_url` → 同步腾讯 IM `Tag_Profile_IM_Image` |

注册时头像为运营配置的默认图；用户可通过本接口更换。

---

## 2. 上传规则（与群头像一致）

| 项 | 取值 |
|------|------|
| 允许的 MIME | `image/jpeg`、`image/png`、`image/webp` |
| `application/octet-stream` | 文件名须带 `.jpg/.jpeg/.png/.webp` 后缀 |
| 单文件上限 | 10 MB（`chat99.oss.max-upload-bytes`） |
| 失败体 | `{"code":"...","message":"..."}` |

常见错误码：`EMPTY_FILE`、`FILE_TOO_LARGE`、`UNSUPPORTED_TYPE`、`INVALID_IMAGE`、`OSS_NOT_CONFIGURED`、`USER_NOT_FOUND`、`ACCOUNT_DISABLED`。

---

## 3. 接口详情

### `POST /me/avatar`

**请求**

```http
POST /me/avatar HTTP/1.1
Authorization: Bearer <token>
Content-Type: multipart/form-data; boundary=----xxxx

------xxxx
Content-Disposition: form-data; name="file"; filename="avatar.jpg"
Content-Type: image/jpeg

<二进制>
------xxxx--
```

**200 响应**

```json
{
  "avatarUrl": "https://<oss>/user-avatar/<userId>/<ts>_<uuid>_preview.jpg",
  "originUrl": "https://<oss>/user-avatar/<userId>/<ts>_<uuid>_origin.jpg",
  "previewUrl": "https://<oss>/user-avatar/<userId>/<ts>_<uuid>_preview.jpg",
  "thumbUrl": "https://<oss>/user-avatar/<userId>/<ts>_<uuid>_thumb.jpg"
}
```

| 字段 | 说明 |
|------|------|
| `avatarUrl` | 与 `previewUrl` 相同；已写入数据库与 IM，**客户端应优先用此值刷新 UI** |
| `originUrl` | 原图 |
| `previewUrl` | 长边 750px，资料页/列表展示 |
| `thumbUrl` | 200×200 裁剪，小图 |

**OSS 路径前缀**：`user-avatar/{userId}/{timestamp}_{uuid8}_*.jpg`

---

## 4. 客户端流程

```
1. 用户选图 → multipart POST /me/avatar
2. 200 → 用响应 avatarUrl 更新本地资料缓存
3. GET /me 应返回相同 avatarUrl
4. IM 资料由服务端 REST 已同步；若需即时刷新会话列表，可再调 IM SDK 拉取资料（可选）
```

---

## 5. 读取当前头像

`GET /me` 响应字段 `avatarUrl`（见 [nickname-client.md §4](./nickname-client.md#4-获取当前资料)）。

用户搜索等接口返回的 `avatarUrl` 同源。

---

## 6. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05-28 | 初版：`POST /me/avatar` |
