# 聊天大附件运维

独立私有桶 + 99chat-server 模块。不要改旧公共桶 ACL / Block Public Access。

## 必须配置

环境变量（写入部署，不要复用公开桶名）：

| 变量 | 说明 |
| --- | --- |
| `CHAT_ATTACHMENT_OSS_BUCKET` | **独立私有桶名**，禁止与 `OSS_BUCKET` 相同。当前环境：`99chat-attachments`（香港，private） |
| `CHAT_ATTACHMENT_OSS_ENDPOINT` | 可空，空则回退 app_setting 的 OSS_ENDPOINT。当前环境留空，用现网阿里云 |
| `CHAT_ATTACHMENT_OSS_ACCESS_KEY_ID` / `SECRET` | 可空，空则回退现有账号。当前环境留空，用现网阿里云密钥 |
| `CHAT_ATTACHMENT_OSS_CDN_DOMAIN` | 现网 `https://image.99chat.vip`。原生视频对象地址走该域名 |
| `CHAT_ATTACHMENT_UPLOAD_ENABLED` | 默认 false。当前环境已开 |
| `CHAT_ATTACHMENT_SEND_ENABLED` | 默认 false。当前环境已开 |
| `CHAT_ATTACHMENT_READ_ENABLED` | 默认 true |
| `CHAT_ATTACHMENT_EMERGENCY_DISABLED` | 紧急断流；与日常灰度开关分开 |

数据库：执行 `scripts/migrate-chat-attachment.sql`、`scripts/migrate-chat-native-video.sql` 与 `scripts/migrate-chat-attachment-media-probe.sql`。

原生视频代发（默认关闭）：

| 变量 | 说明 |
| --- | --- |
| `CHAT_ATTACHMENT_NATIVE_VIDEO_MESSAGE_ENABLED` | 默认 false。验收完成前保持 false |
| `CHAT_ATTACHMENT_MEDIA_PUBLIC_BASE_URL` | 原生视频 `VideoUrl`/`ThumbUrl` 前缀。现网固定 `https://image.99chat.vip` |
| `CHAT_ATTACHMENT_OSS_CDN_DOMAIN` | 可选。现网与媒体域名一致：`https://image.99chat.vip` |
| `CHAT_ATTACHMENT_MEDIA_HMAC_SECRET` | 媒体 URL HMAC 密钥，与登录 JWT 密钥分开；禁止写入消息或日志 |

`GET|HEAD /chat-media/v1/{token}` 仍从私有桶按 Range 拉流，供旧消息使用。新发送的原生视频/封面对象会尝试设为 `public-read`，经 `https://image.99chat.vip/{objectKey}` 访问。不要把公开桶 `99chat` 和附件桶混用。若桶开启 Block Public Access，需在 OSS 放行这些对象的匿名 GET，否则播放 403。

## 新桶要求

1. 桶级 Block Public Access 开启，对象 private。
2. CORS：允许客户端 PUT/GET/HEAD，暴露 `ETag`、`Content-Type`、`Content-Range`、`Content-Length`，允许 `Range`。
3. 生命周期：**仅**清理 2 天未完成 multipart。不要按年龄删除 `chat-attachments/v1/originals/`、`thumbnails/` 已完成对象（由后端 30 天策略删除）。
4. 前缀：`chat-attachments/v1/originals/`、`chat-attachments/v1/thumbnails/`。

启动时若 bucket 缺失、与公共桶同名、或 HEAD 失败：新上传返回 `STORAGE_UNAVAILABLE`，不会回退公开桶。

## 保留与清理

- 上传会话：创建后 24 小时过期，`ChatUploadExpireJob` 中止 multipart 并释放预留配额。
- 无有效引用的 ready 附件：72 小时后进入 7 天延迟删除。
- **所有 ready 附件：ready 后 30 天到期，`ChatAttachmentCleanupJob` 删除 OSS 原件/封面并释放存储额度**（即使用户已确认发送）。
- reserved 引用 7 天未绑定 → `manualRequired` 并打 `manualRequired referenceId=` 告警；对象仍受 30 天总保留限制。

## 回滚

1. 关 `CHAT_ATTACHMENT_UPLOAD_ENABLED` / `CHAT_ATTACHMENT_SEND_ENABLED`：停新上传和新引用。
2. 已有会话仍可 complete/cancel；`readEnabled` 保持则历史附件在 30 天内仍可授权下载。
3. `CHAT_ATTACHMENT_EMERGENCY_DISABLED=true`：拒绝新上传、分片 URL、新引用；查询/取消仍可用。

## 监控关联字段

日志使用 `uploadId`、`attachmentId`、`referenceId`、`clientOperationId`。禁止打印完整预签名 URL、Token、原始文件名。

观察：上传成功率、complete 耗时、403/过期、未确认 reserved 数量、清理失败、桶容量。

视频时长来源计数日志：`native_video_duration_source source=client|server|fallback`。`fallback` 比例在样本量 >= 20 且 >= 5% 时打告警日志。探测依赖本机 `ffprobe`（`FFPROBE_PATH`，默认 `/www/server/ffmpeg/ffmpeg-6.1/ffprobe`）。
