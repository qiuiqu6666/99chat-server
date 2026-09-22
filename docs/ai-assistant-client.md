# AI 助手（独立页）— 客户端对接

> 版本：v1.5  
> 适用端：Flutter App 独立页（本期仅服务端）  
> Base URL：`http://<host>:8081/ai-assistant`  
> 鉴权：`Authorization: Bearer <App JWT>`（与主服务登录 token 相同）

前端只请求主服务。不要直连 `127.0.0.1:8095`。

一用户一条会话。`copy` / `summarize` / `file` / `image` 仍由客户端显式传 `capability`。空 / `chat` 带图时由服务端分流（话里有「改」且附件全是图则结合原图生图，否则看图问答）。带表格或视频走文件模型分析，不生图。产品问答只靠模型，没有知识库。

默认模型（均可由助手服务环境变量覆盖；同一变量可逗号写备用，上游失败且尚未输出时服务端静默切换，客户端不选模型）：

| 客户端选择 | `capability` | 默认模型 |
|------------|--------------|----------|
| 空闲聊 / 产品问答 | 空 / 不传 / `chat` | `deepseek-chat,qwen3.7-plus,doubao-seed-2-0-lite-260215` |
| 总结聊天记录 | `summarize` | `deepseek-chat,qwen3.7-plus,doubao-seed-2-0-lite-260215` |
| 写文案 | `copy` | `qwen3.7-plus,deepseek-chat,doubao-seed-2-0-lite-260215` |
| 分析文件 | `file` | `gemini-2.5-flash-lite,doubao-seed-2-0-lite-260215,gemini-3-pro-image-preview` |
| 生成图片 | `image` | `gemini-3.1-flash-lite-image,gemini-3-pro-image-preview` |

其它 `capability` 字符串 → HTTP 400 `INVALID_INPUT`。

---

## 1. 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/health` | 健康检查，无 JWT |
| GET | `/api/v1/chat/history` | 拉本用户助手记录 |
| DELETE | `/api/v1/chat/history` | 硬删本用户助手记录、上传文件与 OSS 对象（不动 IM 归档） |
| POST | `/api/v1/chat/files` | 上传附件（分析文件 / 闲聊带图、表、视频 / 改图用） |
| GET | `/api/v1/chat/files/{fileId}` | 下载本用户文件（含生图） |
| POST | `/api/v1/chat/stream` | 发一条并流式收助手回复（生图无 delta） |

成功 JSON（除 health、SSE、文件字节）由助手服务包成 `{ "code": 0, "message": "ok", "data": ... }`。

错误 HTTP body：`{ "code": "...", "message": "..." }`。

---

## 2. 拉历史

```http
GET /ai-assistant/api/v1/chat/history?limit=50
Authorization: Bearer <token>
```

| 参数 | 默认 | 说明 |
|------|------|------|
| limit | 50 | 最大 100 |
| cursor | 无 | 上一页最小 `id`；不传则最新一页 |

`data.items` 按 `id` 升序。`hasMore=true` 时用 `nextCursor` 拉更旧消息。

每条：`id`、`role`（`user`/`assistant`）、`content`、`capability`、`analyzeType`、`analyzePeerUserId`、`analyzeGroupId`、`status`（`complete`/`streaming`/`failed`）、`compressed`、`sourceMessageCount`、`createdAt`（ms）。

有生图时额外：`imageUrl` = `/ai-assistant/api/v1/chat/files/{fileId}`（下载必须带同一个 JWT，不能当无鉴权图片 `src`）。  
有附件时额外：`fileIds` 字符串数组。

空 / `chat` 带图且话里有「改」且附件全是图时，该轮历史 `capability` 记为 `image` 且有 `imageUrl`。带图但不含「改」，或带表/视频时 `capability` 仍为 `chat`，带 `fileIds`。

---

## 3. 清空（硬删）

```http
DELETE /ai-assistant/api/v1/chat/history
Authorization: Bearer <token>
```

`data.deleted` 为删除的消息条数。同时删除该用户全部 `ai_file` 与 OSS 前缀 `ai-assistant/{userId}/`。会话行保留，下次发消息仍是同一用户一条会话。

---

## 4. 上传附件

```http
POST /ai-assistant/api/v1/chat/files
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

字段名 `file`。图片也走字段 `file`（不要 `image` / `files`）。允许 MIME：`image/jpeg`、`image/png`、`image/webp`、`image/gif`、`application/pdf`、xlsx（`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`）、xls（`application/vnd.ms-excel`）、`text/csv`、`video/mp4`、`video/quicktime`（`.mov`）、`video/webm`。扩展名与 MIME 不一致 → 400 `INVALID_INPUT`（`application/octet-stream` 按扩展名；`.csv` 允许 `text/plain` / `application/csv`）。超过 20MB → 400 `FILE_TOO_LARGE`。表格由服务端抽成文本再给模型；视频按 Base64 `file_data` 交给文件模型。随后在 stream 里：`capability=file` 必须带 1～3 个 `fileIds`（图 / 表 / 视频 / PDF）；空 / `chat` 可带 0～3 个图、表或视频 id（不要 PDF、不要重复）；`image` 只许 0～3 张图片 id（不要 PDF / 表 / 视频）。

`data`：`fileId`、`contentType`、`sizeBytes`、`fileName`。

下载：

```http
GET /ai-assistant/api/v1/chat/files/{fileId}
Authorization: Bearer <token>
```

仅上传者。响应原文件字节，`Content-Type` 为入库 MIME，`Cache-Control: private`。不属于当前用户 → 404 `FILE_UNAVAILABLE`。文件存在主服务同一套阿里云 OSS（对象前缀 `ai-assistant/`），不落助手本机磁盘。

---

## 5. 流式发送

```http
POST /ai-assistant/api/v1/chat/stream
Authorization: Bearer <token>
Content-Type: application/json
Accept: text/event-stream
```

请求体：`capability`、`content`、`analyze`、`fileIds`。`content` trim 后最长 8000 字。

| capability | content | analyze | fileIds |
|------------|---------|---------|---------|
| 空/`chat` | 必填，1～8000 字 | 禁止 | 可空；0～3 个已上传图 / 表 / 视频 id（jpeg/png/webp/gif、xlsx/xls/csv、mp4/mov/webm，不要 PDF、不要重复）。附件全是图且原文含「改」→ 结合原图生图；全是图但不含「改」→ 看图问答；含表或视频 → 文件模型分析（历史仍记 `chat`） |
| `copy` | 同上 | 禁止 | 禁止 |
| `summarize` | 可空（空则用户气泡为 `[分析聊天记录]`） | 必填，规则同下表 | 禁止 |
| `file` | 可空（空则用户气泡为 `[分析文件]`） | 禁止 | 必填，1～3 个已上传且属当前用户的 id（PDF 分析必须用这一档；也可用图 / 表 / 视频） |
| `image` | 必填，1～8000 字（提示词） | 禁止 | 可空；改图/优化时带 1～3 个已上传图片 id（jpeg/png/webp/gif，不要 PDF / 表 / 视频）。显式 `image` 不要求文案含「改」 |

`summarize` 的 `analyze.type`：

| analyze.type | 其它字段 |
|--------------|----------|
| `paste` | `text` 必填，1～100000 字 |
| `c2c` | `peerUserId` 必填，必须是双向好友，不能是自己；服务端最多最近 100 条归档 |
| `group` | `groupId` 必填，必须已入群；最多最近 100 条归档 |

客户端 JSON 仍传原始 `groupId`（可含 `#`）；助手调主服务 `GET /group/{id}` 时对路径段百分号编码。

不能分析别人的会话或未加入的群。归档由服务端用**当前用户 JWT** 调主服务 `GET /me/messages/*`。

响应：`Content-Type: text/event-stream`。事件：

| event | data |
|-------|------|
| meta | `userMessageId`、`assistantMessageId` |
| delta | `text`（增量，多次；**生图不发**） |
| done | `assistantMessageId`、`compressed`、`sourceMessageCount`；生图额外 `imageUrl` |
| error | `code`、`message` |

同一用户同时只能一条流。已有 `streaming` → HTTP 409 `CHAT_BUSY`。

校验失败（缺字段、非法 capability、文件不属于自己等）→ HTTP 400 `INVALID_INPUT`，不是 SSE。

权限失败、归档失败、模型失败：HTTP 200，SSE `error`。`NOT_FRIEND` / `NOT_GROUP_MEMBER` 不落库。

分析文件时读盘失败：SSE `error` `FILE_UNAVAILABLE`（已发 meta 则 assistant=`failed`）。

若记录被压缩，助手全文以「以下分析基于压缩后的记录（原 N 条文本）。」开头。

生图顺序：`meta` → `done`（含 `imageUrl`）或 `error`。`imageUrl` 固定为 `/ai-assistant/api/v1/chat/files/{fileId}`，客户端下载必须带 `Authorization`，不能当无鉴权图片 `src`。

改图 / 优化：先 `POST /api/v1/chat/files` 得到 `fileId`。可以显式 `capability=image` 且 `fileIds` 带上原图；也可以空 / `chat` + `fileIds` + 文案含「改」，服务端同样把原图和提示词一起交给生图模型。纯文生图用 `capability=image` 且不传 `fileIds`。

---

## 6. 错误码

| code | 出现位置 |
|------|----------|
| UNAUTHORIZED | HTTP 401 |
| INVALID_INPUT | HTTP 400 |
| FILE_TOO_LARGE | HTTP 400 |
| CHAT_BUSY | HTTP 409 |
| FILE_UNAVAILABLE | HTTP 404（下载）或 SSE error |
| NOT_FRIEND | SSE error |
| NOT_GROUP_MEMBER | SSE error |
| ARCHIVE_RATE_LIMITED | SSE error |
| LLM_NOT_CONFIGURED | SSE error |
| LLM_UPSTREAM | SSE error |
| MAIN_UNAVAILABLE | SSE error；线程池满时 HTTP 503 |

---

## 7. 联调注意

- 主服务反代对 `/api/v1/chat/stream` 不设 60s 超时；上传走普通 60s。
- 模型走 OpenAI 兼容 `/v1/chat/completions`，按能力换模型，厂商由助手服务环境变量配置。
- 健康检查：`GET /ai-assistant/api/v1/health`。
