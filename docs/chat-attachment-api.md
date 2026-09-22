# 聊天大附件 API

> 状态：后端第一期已实现；生产默认 **关闭发送/上传**。  
> 用户接口前缀：`/me/chat`（JWT）。IM 绑定：`POST /internal/chat/attachments/im-events`（集成 Token）。  
> 储存：附件 ready 后保留 **30 天**，到期自动清理；无引用成品仍按 72 小时进入清理判断。

成功响应包装为 `{ "code": 0, "message": "ok", "data": ... }`。错误为 HTTP 状态 + `{ "code": "STRING", "message": "..." }`。

## 请求头（能力判定）

| Header | 作用 |
| --- | --- |
| `X-Client-Platform` | `android` / `ios` |
| `X-App-Version` | 如 `3.0.1+7` |
| `X-App-Version-Code` | 构建号，最低 `7` |
| `X-Chat-Attachment-Protocol-Version` | 最低 `1` |
| `X-Device-Id` | 写入设备能力表 |

缺头或非 Android/iOS 视为不兼容。策略接口返回的 `uploadEnabled`/`sendEnabled` 是对当前调用端计算后的有效值。

## 路由

`sizeBytes > min(104857600, nativeMaxBytes[nativeMessageKind])` 才走自建通道（严格大于）。

| nativeMessageKind | nativeMaxBytes |
| --- | --- |
| image / sound | 29360128（28MiB） |
| video / file | 104857600（100MiB） |

录音语音用 `kind=audio` + `nativeMessageKind=sound`；普通音频文件用 `kind=audio` + `nativeMessageKind=file`。

## 接口

### `GET /me/chat/attachment-policy`

返回 `uploadEnabled`、`sendEnabled`、`readEnabled`、`nativeVideoMessageEnabled`、`policyVersion`、`routingThresholdBytes`、`sizeComparison=strictGreaterThan`、`nativeMaxBytes`、配额/分片/TTL、平台、封面约束、`confirmedRetentionDays=30`。不限制发送方/接收方 App 版本。`nativeVideoMessageEnabled` 只有布尔 `true` 才开放超阈值视频后端代发；默认关闭，见 [chat-native-video-api.md](./chat-native-video-api.md)。

### `POST /me/chat/uploads`

```json
{
  "clientUploadKey": "dev-local-key",
  "conversationType": "c2c",
  "peerUserId": "10002",
  "kind": "video",
  "nativeMessageKind": "video",
  "originalName": "旅行.mp4",
  "mimeType": "video/mp4",
  "declaredSizeBytes": 268435456,
  "durationMs": 120000,
  "width": 1920,
  "height": 1080
}
```

`durationMs` / `width` / `height` 可选。非正整数、非数字、`null` 视为未提供，不报错。`durationMs` 上限 24 小时，超出记告警并忽略。`kind` 不是 `video`/`audio` 时忽略 `durationMs`。

群聊用 `conversationType=group` + `groupId`。返回 `uploadId`、`attachmentId`、`partSizeBytes=8388608`、`expectedPartCount`、`expiresAt`（创建后 24 小时，不可续期会话）。

### `POST /me/chat/uploads/{uploadId}/part-urls`

`{ "partNumbers": [1, 2] }`，最多 32 个。返回每片 `method=PUT`、短期 URL、`headers={"Content-Type":"application/octet-stream"}`、`expiresAt`。直传 PUT 必须带这个 Content-Type，且不能多带其它会参与签名的头。

### `GET /me/chat/uploads/{uploadId}`

状态 + 已确认分片。`afterPartNumber`、`limit` 分页。分片以 OSS `listParts` 为准。

### `POST /me/chat/uploads/{uploadId}/complete`

以存储端分片为准完成合并。成功后附件 `ready`，`expiresAt` 为 30 天后。`kind=video` 且仍无有效 `durationMs` 时，服务端用 ffprobe 探测对象（超时 10 秒）；失败不阻塞 complete。

### 封面（仅视频）

- `POST /me/chat/uploads/{uploadId}/thumbnail-upload`：JPEG PUT 凭证，最大 1MiB、最长边 1280。
- `POST /me/chat/uploads/{uploadId}/thumbnail-complete`：校验失败不阻塞主视频 ready。成功后把封面 `thumbnailAttachmentId` 写到主视频。主视频 complete 后也会从原件补帧。
- 主视频会话已完成后仍可再申请封面上传。

### `DELETE /me/chat/uploads/{uploadId}`

取消未完成上传；已完成返回 `status=completed`，不删附件。

### `GET /me/chat/attachments/{attachmentId}`

经权限校验的元数据，不含 objectKey / 密钥。视频/音频额外返回：

| 字段 | 说明 |
| --- | --- |
| `durationMs` | 最终生效时长（毫秒），可能为 null |
| `width` / `height` | 最终生效像素，可能为 null |
| `mediaProbe` | `{"source":"client\|server\|fallback","probedAt":"<ISO8601>"}` 或 null |

### `POST /me/chat/attachments/{attachmentId}/references`

```json
{
  "clientOperationId": "msg-op-1",
  "conversationType": "c2c",
  "peerUserId": "10002"
}
```

附件必须 ready，目标会话必须与上传会话一致。返回 `referenceId`（reserved）。

### `POST /me/chat/attachments/{attachmentId}/access`

```json
{ "referenceId": "ref_xxx", "purpose": "download" }
```

`purpose`：`download` / `playback` / `thumbnail`。返回 15 分钟 GET URL，`rangeSupported=true`，OSS GET 支持 `Range`。消息里不要存这个 URL。

- `thumbnail`：用**主视频** `attachmentId` + 该消息的 `referenceId` 即可，不要求消息体里有封面 ID。服务端按主视频上的 `thumbnailAttachmentId` 查找封面；没有则从原件补一帧 JPEG 并绑定后再签发。
- 成功/失败 JSON 带 `requestId`，响应头 `X-Request-Id`。不要把签名 URL 打进日志。
- 发送方与会话内合法接收方均可 access；对象不存在或已过期返回 `ATTACHMENT_GONE`。

### `POST /internal/chat/attachments/im-events`

Header：`X-Integration-Token` 或 `X-Sign` + `X-Request-Time`（与现有 integration 相同）。Body 为腾讯 AfterSend JSON。现有 `/webhook/im/message` AfterSend 也会进程内绑定。

## 自定义消息

```json
{
  "type": "chat.attachment",
  "version": 1,
  "attachmentId": "att_xxx",
  "referenceId": "ref_xxx",
  "kind": "video",
  "name": "旅行.mp4",
  "sizeBytes": 268435456,
  "mimeType": "video/mp4",
  "durationMs": 125000,
  "width": 1920,
  "height": 1080,
  "thumbnailAttachmentId": "att_cover"
}
```

只放稳定 ID 与展示字段。BeforeSend 校验 reference 归属与会话，伪造会被拒绝。

## 错误码

| code | HTTP | 客户端 |
| --- | --- | --- |
| ATTACHMENT_DISABLED | 403 | 功能关闭 |
| NATIVE_CHANNEL_REQUIRED | 400 | 应走腾讯原生通道 |
| FILE_TOO_LARGE | 413 | 超出 2GiB 或声明不符 |
| UNSUPPORTED_MEDIA_TYPE | 415 | 封面非 JPEG 等 |
| QUOTA_EXCEEDED | 403 | 存储/日额度/并发会话 |
| CONVERSATION_FORBIDDEN | 403 | 非好友、非成员、非白名单 |
| UPLOAD_EXPIRED | 410 | 会话过期或已取消 |
| PART_MISSING | 400 | 补传分片 |
| CHECKSUM_MISMATCH | 400 | 分片大小/总数不符 |
| ATTACHMENT_NOT_READY | 409 | 等待 ready |
| ATTACHMENT_GONE | 410 | 已删或无封面 |
| ACCESS_DENIED | 403 | 无引用权限 |
| IDEMPOTENCY_CONFLICT | 409 | 换新幂等键 |
| RATE_LIMITED | 429 | 看 `retryAfter`（秒）。`part-urls` 现网不限次；init/complete/access 仍按分钟窗口限流 |
| STORAGE_UNAVAILABLE | 503 | 私有桶未配置或不可用 |

## 灰度

`sendEnabled=true` 后：

- `send-allow-user-ids` 为空：不限制发送方；非空则必须包含发送方
- 群聊 `send-allow-group-ids` 为空：不限制群；非空则必须包含 groupId
- 不限制发送方/接收方 App 版本；`require-peer-capability=false` 时不对端、群成员做能力校验
