# 原生视频消息（后端代发）

> 状态：接口已部署，**策略开关默认关闭**。未开启时客户端不得上传超阈值视频、不得调用本接口、不得改发 `chat.attachment` 自定义视频。  
> 用户接口前缀：`/me/chat`（JWT + 既有设备/协议版本头）。  
> 媒体入口：`GET|HEAD /chat-media/v1/{token}`（HMAC，无 JWT）。

成功响应包装为 `{ "code": 0, "message": "ok", "data": ... }`。`/chat-media/**` 不包装，直接返回媒体字节。

## 路由

- `sizeBytes <= 100MiB`：继续走 IM SDK 原生上传发送。
- `sizeBytes > min(104857600, nativeMaxBytes.video)`：前端上传原件 + JPEG 封面到自建附件，再调用本接口由后端发送真正的 `TIMVideoFileElem`。
- 不改变普通文件、图片、语音协议。已存在的 `chat.attachment` 视频只读兼容，不自动重发。

## 策略

`GET /me/chat/attachment-policy` 保留全部既有字段，新增：

```json
{ "nativeVideoMessageEnabled": false }
```

仅当值为布尔 `true` 时开放。缺失、`false`、非布尔均视为未开放。服务端在紧急断流、OSS 未就绪、上传/发送关闭、调用端不兼容时也会返回 `false`。联调完成前保持环境变量：

`CHAT_ATTACHMENT_NATIVE_VIDEO_MESSAGE_ENABLED=false`

## 发送

`POST /me/chat/native-video-messages`

发送人取当前登录身份，不接受客户端 `From_Account`、`VideoUrl`、`ThumbUrl`。

单聊：

```json
{
  "clientOperationId": "持久化上传taskId",
  "attachmentId": "att_video",
  "referenceId": "ref_conversation",
  "conversationType": "c2c",
  "peerUserId": "接收方业务ID",
  "durationMs": 120000
}
```

`durationMs` 可选，毫秒。优先级：本字段 > `POST /uploads` 的 `durationMs` > 服务端 ffprobe > 兜底。非正整数 / 非数字 / `null` 视为未提供。

群聊：`conversationType=group` + `groupId`，不传 `peerUserId`。

前置条件：

1. 附件策略 `nativeVideoMessageEnabled=true`
2. 原件已 complete 且 `ready`、kind=`video`、属于当前用户
3. 封面已通过 `thumbnail-complete` 绑定且 ready（含 JPEG/大小/尺寸）
4. `referenceId` 属于该发送人、该附件、该会话

成功（腾讯已接受，不是仅入库或排队）：

```json
{
  "clientOperationId": "持久化上传taskId",
  "attachmentId": "att_video",
  "referenceId": "ref_conversation",
  "status": "sent",
  "messageType": "TIMVideoFileElem",
  "msgKey": "腾讯返回的真实MsgKey"
}
```

群聊同样返回身份字段，并带正整数 `msgSeq`。不要把 `msgSeq` 当成客户端 SDK msgID。

其它状态仍返回三个身份字段：

| status | 含义 | 客户端 |
| --- | --- | --- |
| `pending` | 正在处理 | 结果待确认，只 GET |
| `unknown` | 已请求腾讯但结果未定 | 结果待确认，只 GET，约 15 秒一次 |
| `failed` | 已确认未发送成功 | 允许用户显式再 POST；不用于 HTTP 超时 |

身份字段不一致、成功缺少 `messageType` 或 C2C `msgKey` / 群聊 `msgSeq` 时，前端不得显示发送成功。

## 查询

`GET /me/chat/native-video-messages/{clientOperationId}`

只读，限当前登录发送人。**查询不得触发发送**。404 表示没有操作记录，不等于确定未发送。

幂等键：`(authenticatedSender, clientOperationId)`。同一键不同目标/附件/引用 → `409 IDEMPOTENCY_CONFLICT`。并发 POST / 超时重试只对应一次 IM 投递；腾讯 `MsgRandom`/`Random` 持久化，超时后不会换新随机数。确定 `failed` 后的显式重试才会换新随机数。

客户端超时后只 GET，不要自动再 POST。后端会在调用腾讯前落库，并用 AfterSend 回调与出站任务对账。

## 腾讯消息

- 单聊 `v4/openim/sendmsg`，`SyncOtherMachine=1`，`From_Account` 为业务发送者
- 群聊 `v4/group_open_http_svc/send_group_msg`，`From_Account` 为业务发送者，不以管理员身份冒充
- `MsgBody` 仅含 `TIMVideoFileElem`，`VideoDownloadFlag`/`ThumbDownloadFlag`=2
- `VideoSecond` = `ceil(durationMs / 1000)`；拿不到时长时先填 `0`（客户端显示「视频」而不是错误的 `0:01`）
- `VideoFormat` 来自文件扩展名；`ThumbWidth`/`ThumbHeight`/`ThumbSize`/`ThumbFormat` 来自已绑定封面
- 顶层 `CloudCustomData`（字符串，不改变消息类型）：

```json
{
  "type": "chat.native-video",
  "version": 1,
  "clientOperationId": "持久化上传taskId",
  "attachmentId": "att_video",
  "referenceId": "ref_conversation"
}
```

客户端用附件/引用匹配并隐藏本地临时气泡。不要再额外发一条自定义视频消息。

### VideoSecond = 0 实测

代码默认对未知时长发送 `VideoSecond=0`。若腾讯 REST 拒绝（非超时类错误），自动改发 `VideoSecond=1`，并在 `CloudCustomData` 增加：

```json
{ "chatAttachment": { "durationUnknown": true } }
```

日志关键字：`native video VideoSecond=0 rejected`。本任务未对腾讯 REST 做线上实测；上线后根据该日志确认是否走了重试分支。

## 媒体 URL

历史消息里的 `VideoUrl`/`ThumbUrl` 固定为 OSS 自定义域名对象地址，不再跟发送接口 Host 走：

`https://image.99chat.vip/{objectKey}`

封面同样是该域名下的 JPEG 对象。`/chat-media/v1/{hmacToken}` 仍可用于旧消息，新发送不再写入该入口。发送时会对视频和封面对象尝试 `public-read`；若桶开启了 Block Public Access，公网仍会 403，需在 OSS 控制台关闭拦截或放行匿名 GET。

OSS/CDN 应对 `Range: bytes=0-1` 返回 `206`、`Content-Range: bytes 0-1/{总字节}`、体长 2。不要把登录页或 JSON 当成媒体体。

产品保留期 30 天，到期后不再承诺可播。

## 错误码

沿用大附件错误码。本接口额外：

| 场景 | HTTP / code |
| --- | --- |
| 开关未开 | 403 `ATTACHMENT_DISABLED` |
| 封面未绑定或未 ready | 409 `ATTACHMENT_NOT_READY` |
| 幂等键冲突 | 409 `IDEMPOTENCY_CONFLICT` |
| 无此操作 | 404 `NOT_FOUND` |
| 伪造会话/引用/他人 taskId | 403 `ACCESS_DENIED` / `CONVERSATION_FORBIDDEN` |
| 原件或封面已删 | 410 `ATTACHMENT_GONE` |

## 客户端流程

1. 读策略，确认 `nativeVideoMessageEnabled=true`
2. 既有分片上传并 complete
3. 本地抽帧，`thumbnail-upload` / `thumbnail-complete` 绑定主视频
4. 既有 references 取 `referenceId`
5. `POST /me/chat/native-video-messages`，不要 `createCustomMessage`/`sendMessage`
6. `sent` 后等 IM 同步原生视频卡片；非确定状态只 GET
