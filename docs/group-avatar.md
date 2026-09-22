# 群头像接口对接文档

> 版本：v1.1（2026-05-22 调整：撤销后端自动写回 IM，统一由客户端用 IM SDK 写回 `faceUrl`）
> 适用：99chat-server v0.0.1-SNAPSHOT + 99chat (Flutter) + 腾讯 IM Community
> 范围：群头像图片上传（仅 OSS）。腾讯 IM 群 `FaceUrl` 由**客户端**调 IM SDK `updateGroupProfile` 写回

---

## 1. 概述

后端提供两个接口，对应**建群前**与**改群头像**两个场景，**均只做 OSS 上传并返回三尺寸 URL，不再调用腾讯 IM**：

| 接口 | 路径 | 用途 | 后端是否写回腾讯 IM `FaceUrl` |
| --- | --- | --- | --- |
| 建群前上传 | `POST /group/avatar/upload` | 用户准备建群，先上传头像拿 URL | ❌ |
| 改群头像 | `POST /group/{groupId}/avatar` | 已存在群修改头像，并校验调用者属于该群 | ❌（**由客户端调 IM SDK `updateGroupProfile({groupID, faceUrl})` 写回**） |

两个接口**共用上传逻辑**（三尺寸 JPEG 处理 + 阿里云 OSS 存储）。

---

## 2. 通用规范

| 项 | 取值 |
| --- | --- |
| Base URL（开发环境） | `http://47.239.60.107:8081` |
| 鉴权 | `Authorization: Bearer <JWT>`（沿用登录返回 token） |
| Content-Type | `multipart/form-data` |
| 文件字段名 | `file` |
| 允许的 MIME | `image/jpeg`、`image/png`、`image/webp`；客户端若只能发 `application/octet-stream`，则文件名（`originalFilename`）必须带 `.jpg/.jpeg/.png/.webp` 后缀，否则返回 `UNSUPPORTED_TYPE` |
| 单文件大小上限 | 10 MB（`chat99.oss.max-upload-bytes`） |
| 整个请求上限 | 12 MB（Spring `spring.servlet.multipart.max-request-size`） |
| 响应格式 | JSON，UTF-8 |
| 失败响应体 | `{"code": "<错误码>", "message": "<错误码>"}` |

---

## 3. 接口详情

### 3.1 建群前上传：`POST /group/avatar/upload`

任意登录用户均可调用。**不**与具体群组绑定，仅返回图床 URL。

#### 请求

```
POST /group/avatar/upload HTTP/1.1
Authorization: Bearer <JWT>
Content-Type: multipart/form-data; boundary=----xxxx

------xxxx
Content-Disposition: form-data; name="file"; filename="avatar.jpg"
Content-Type: image/jpeg

<二进制图片字节>
------xxxx--
```

#### 200 响应

```json
{
  "originUrl":  "https://<oss-domain>/group-avatar/pending/<userId>/<ts>_<uuid>_origin.jpg",
  "previewUrl": "https://<oss-domain>/group-avatar/pending/<userId>/<ts>_<uuid>_preview.jpg",
  "thumbUrl":   "https://<oss-domain>/group-avatar/pending/<userId>/<ts>_<uuid>_thumb.jpg"
}
```

#### 错误响应

| HTTP | code | 说明 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 缺失或过期的 JWT |
| 400 | EMPTY_FILE | 上传了空文件 |
| 413 | FILE_TOO_LARGE | 文件超过 10 MB |
| 415 | UNSUPPORTED_TYPE | MIME 不在白名单 |
| 503 | OSS_NOT_CONFIGURED | 后端未配置阿里云 OSS（管理员需在后台配置） |
| 400 | INVALID_IMAGE | 字节流无法被解码为图片 |

#### OSS 路径前缀

`group-avatar/pending/{userId}/{ts}_{uuid8}_xxx.jpg`

`pending/` 前缀表示"未关联群"。未来由运维通过 OSS 生命周期规则按此前缀定期清理（建议 7 天）。

---

### 3.2 改群头像：`POST /group/{groupId}/avatar`

**该群任意成员**（Owner / Admin / Member）均可调用，非群成员调用返回 `403 NOT_GROUP_MEMBER`。后端只上传 OSS 并返回三尺寸 URL，**不再自动写回腾讯 IM**。客户端拿到 `previewUrl` 后，需要自行调 IM SDK `updateGroupProfile({ groupID, faceUrl })` 把 `previewUrl` 设置为群 `FaceUrl`。

#### 请求

```
POST /group/@TGS#_yourGroupId/avatar HTTP/1.1
Authorization: Bearer <JWT>
Content-Type: multipart/form-data; boundary=----xxxx

------xxxx
Content-Disposition: form-data; name="file"; filename="new-avatar.png"
Content-Type: image/png

<二进制图片字节>
------xxxx--
```

> `{groupId}` 直接写入路径；社群（Community）形如 `@TGS#_xxx`，普通群形如 `@TGS#xxx`。

#### 200 响应

```json
{
  "originUrl":  "https://<oss-domain>/group-avatar/<groupId>/<ts>_<uuid>_origin.jpg",
  "previewUrl": "https://<oss-domain>/group-avatar/<groupId>/<ts>_<uuid>_preview.jpg",
  "thumbUrl":   "https://<oss-domain>/group-avatar/<groupId>/<ts>_<uuid>_thumb.jpg"
}
```

#### 错误响应

| HTTP | code | 说明 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 缺失或过期的 JWT |
| 403 | NOT_GROUP_MEMBER | 调用者不是该群成员 |
| 400 | EMPTY_FILE | 上传了空文件 |
| 413 | FILE_TOO_LARGE | 文件超过 10 MB |
| 415 | UNSUPPORTED_TYPE | MIME 不在白名单 |
| 503 | OSS_NOT_CONFIGURED | 后端未配置 OSS |
| 400 | INVALID_IMAGE | 不是有效图片 |

#### 副作用

1. 文件上传到 OSS（三尺寸，路径 `group-avatar/{groupId}/...`）
2. **服务端**将 `previewUrl` 写入腾讯 IM，并更新群资料投影、推送 TCP `group_avatar_changed`（字段 `avatarUrl` 为 thumb）。
3. 客户端**无需**再调 IM SDK `updateGroupProfile`；展示以 [group-profile-client.md](./group-profile-client.md) REST 为准。

---

## 4. 三尺寸 URL 说明

| 字段 | 长边 | 用途建议 |
| --- | --- | --- |
| `originUrl` | 原图尺寸 | 群信息全屏查看头像 |
| `previewUrl` | 750 px（`chat99.oss.preview-long-edge`） | **客户端应把此值传给 `updateGroupProfile.faceUrl`**；群信息页头像 |
| `thumbUrl` | 200 × 200 中心裁剪正方形（`chat99.oss.thumb-size`） | 会话列表 / 群成员头像格 |

> 三个文件均为 JPEG（quality 85，`chat99.oss.jpeg-quality`），原始 PNG / WEBP 会被转码以减小体积、统一压缩规则。

---

## 5. 客户端对接流程

### 5.1 建群场景（推荐）

```
Flutter                  99chat-server            阿里云 OSS         腾讯 IM
   |                          |                         |                |
   |--POST /group/avatar/upload (file)----------------->|                |
   |                          |--putObject (3 sizes)--->|                |
   |<--{originUrl,previewUrl,thumbUrl}------------------|                |
   |                                                                     |
   |--timManager.getGroupManager().createGroup(                          |
   |     groupType:"Community",                                          |
   |     faceUrl: previewUrl,                                            |
   |     inviteJoinOption:NeedPermission,                                |
   |     applyJoinOption:NeedPermission)-------------------------------->|
   |<--groupId-----------------------------------------------------------|
```

Flutter 伪代码：

```dart
// 1) 先上传头像
final dio = Dio();
final form = FormData.fromMap({
  "file": await MultipartFile.fromFile(localPath, contentType: MediaType("image","jpeg")),
});
final upRes = await dio.post(
  "$baseUrl/group/avatar/upload",
  data: form,
  options: Options(headers: {"Authorization": "Bearer $jwt"}),
);
final previewUrl = upRes.data["previewUrl"] as String;

// 2) 用拿到的 URL 建群
final created = await timManager.getGroupManager().createGroup(
  groupType: "Community",
  groupName: groupName,
  faceUrl: previewUrl,
  inviteJoinOption: GroupAddOptTypeEnum.V2TIM_GROUP_ADD_AUTH,
  applyJoinOption:  GroupAddOptTypeEnum.V2TIM_GROUP_ADD_AUTH,
);
final groupId = created.data; // "@TGS#_..."
```

### 5.2 改群头像场景（已存在群，任意成员）

```
Flutter                  99chat-server            阿里云 OSS         腾讯 IM
   |                          |                         |                |
   |--POST /group/{gid}/avatar (file)------------------>|                |
   |                          |--getRoleInGroup-------------------------->|
   |                          |<--Owner/Admin/Member--------------------- |
   |                          |--putObject (3 sizes)--->|                |
   |<--{originUrl,previewUrl,thumbUrl}------------------|                |
   |                                                                     |
   |--timManager.getGroupManager().setGroupInfo(                         |
   |     groupID, faceUrl: previewUrl)---------------------------------->|
   |<--ok----------------------------------------------------------------|
   |   (IM SDK 会广播群资料变更通知给所有成员)                           |
```

Flutter 伪代码：

```dart
final form = FormData.fromMap({
  "file": await MultipartFile.fromFile(localPath, contentType: MediaType("image","jpeg")),
});
final res = await dio.post(
  "$baseUrl/group/${groupId}/avatar",
  data: form,
  options: Options(headers: {"Authorization": "Bearer $jwt"}),
);
final urls = res.data;
final previewUrl = urls["previewUrl"] as String;

// 关键：客户端自行用 IM SDK 写回 faceUrl
await timManager.getGroupManager().setGroupInfo(
  info: V2TimGroupInfo(
    groupID: groupId,
    faceUrl: previewUrl,
  ),
);
```

> **重要**：本接口不再代为写回腾讯 IM。前端必须自行调 `setGroupInfo`/`updateGroupProfile`，否则群资料里的 `faceUrl` 不会变化，其他成员看不到新头像。

### 5.3 错误处理建议（Flutter）

```dart
try {
  final res = await dio.post(...);
} on DioException catch (e) {
  final code = e.response?.data?["code"] as String?;
  switch (code) {
    case "NOT_GROUP_MEMBER":         showSnack("你不是该群成员"); break;
    case "FILE_TOO_LARGE":           showSnack("图片不能超过 10 MB"); break;
    case "UNSUPPORTED_TYPE":         showSnack("仅支持 JPG / PNG / WEBP"); break;
    case "INVALID_IMAGE":            showSnack("图片格式无效"); break;
    case "OSS_NOT_CONFIGURED":       showSnack("服务暂不可用，请联系管理员"); break;
    case "EMPTY_FILE":               showSnack("文件为空"); break;
    default:
      if (e.response?.statusCode == 401) routeToLogin();
      else showSnack("上传失败，请重试");
  }
}
```

---

## 6. 错误码汇总

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | EMPTY_FILE | 上传空文件 |
| 400 | INVALID_IMAGE | 字节流不是合法图片 |
| 401 | UNAUTHORIZED | JWT 缺失或过期 |
| 403 | NOT_GROUP_MEMBER | 仅改群头像接口；调用者不是该群成员 |
| 413 | FILE_TOO_LARGE | 文件 > 10 MB |
| 415 | UNSUPPORTED_TYPE | MIME 不在 jpeg/png/webp 白名单，且文件名后缀也不是 jpg/jpeg/png/webp |
| 503 | OSS_NOT_CONFIGURED | 后端 OSS 未配置 |

> 错误响应体统一为 `{"code": "<上表 code>", "message": "<同 code>"}`（见 `GlobalExceptionHandler`）。

---

## 7. 已知限制与未来演进

1. **腾讯 `FaceUrl` 长度限制 100 字节**：若 OSS URL 含较长 CDN 域名 + 较长 key，可能超出。前端在 `setGroupInfo` 前自行校验，必要时改用 `thumbUrl`。本期不阻塞。
2. **IM 写回失败由客户端兜底**：后端不再代为写回，客户端在 `setGroupInfo` 失败时应自行重试或提示用户；不影响 OSS 上传的 200 响应。
3. **pending 文件清理**：本期未做。建议运维在 OSS 控制台对前缀 `group-avatar/pending/` 配置生命周期规则（7 天自动 Expire），无需后端代码。
4. **不限频**：未做调用频率限制。若后续出现刷量行为，可在 `JwtAuthFilter` 之后追加。
5. **群类型支持**：客户端用 IM SDK `setGroupInfo` 写 `faceUrl`，由 IM SDK 自身决定该群类型是否允许；当前 Community / Public / Meeting / Work 均支持。
