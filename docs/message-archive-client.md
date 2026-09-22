# IM 消息归档前端对接文档

> 版本：v1.0  
> 适用端：Flutter App / Web 客户端  
> Base URL：`http://<host>:8081`（生产环境以实际 HTTPS 域名为准）

## 1. 接入说明

消息归档接口用于查询已落库的单聊、群聊历史消息。实时消息仍使用腾讯云 IM SDK；客户端进入会话时使用本接口补历史消息。

后端配置：

```env
MSG_ARCHIVE_ENABLED=true
```

所有用户接口都需要登录 JWT：

```http
Authorization: Bearer <App JWT>
Content-Type: application/json
```

接口未开启、请求超时或返回错误时，客户端应保留腾讯 IM SDK 的降级逻辑，不应阻塞登录或进入会话。

## 2. 单聊历史

### 2.1 游标分页（推荐）

```http
GET /me/messages/c2c?peerUserId=user_b&limit=30
Authorization: Bearer <token>
```

下一页将响应中的 `nextCursor` 作为 `cursor`：

```http
GET /me/messages/c2c?peerUserId=user_b&cursor=1718450000000&limit=30
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `peerUserId` | 是 | 对方用户 ID |
| `cursor` | 否 | 上一页的 `nextCursor`；首次请求不传 |
| `limit` | 否 | 默认 30，最大 100 |

游标模式返回 `msgTimeMs` 倒序，最新消息在前。

### 2.2 时间区间

```http
GET /me/messages/c2c?peerUserId=user_b&fromTimeMs=1718450000000&toTimeMs=1718453600000&limit=40
Authorization: Bearer <token>
```

`fromTimeMs` 和 `toTimeMs` 必须同时传入，且满足：

```text
fromTimeMs <= msgTimeMs <= toTimeMs
```

区间模式返回时间正序；分页时使用 `fromTimeMs = nextCursor + 1`，`toTimeMs` 保持不变。区间参数存在时优先使用区间模式，并忽略 `cursor`。

## 3. 群聊历史

### 3.1 游标分页（推荐）

```http
GET /me/messages/group?groupId=%40TGS%232J4SZEAEL&limit=30
Authorization: Bearer <token>
```

下一页：

```http
GET /me/messages/group?groupId=%40TGS%232J4SZEAEL&cursor=1718450000000&limit=30
Authorization: Bearer <token>
```

参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `groupId` | 是 | 腾讯群 ID，例如 `@TGS#2J4SZEAEL` |
| `cursor` | 否 | 上一页的 `nextCursor`；首次请求不传 |
| `limit` | 否 | 默认 30，最大 100 |

游标模式按 `msgTimeMs` 倒序返回。

### 3.2 MsgSeq 区间

```http
GET /me/messages/group?groupId=%40TGS%232J4SZEAEL&fromSeq=100&toSeq=150&limit=40
Authorization: Bearer <token>
```

`fromSeq` 和 `toSeq` 必须同时传入，且满足：

```text
fromSeq <= msgSeq <= toSeq
```

区间模式按 `msgSeq` 正序返回；下一页使用 `fromSeq = nextCursor + 1`。

## 4. 响应格式

```json
{
  "items": [
    {
      "msgKey": "3358721060_1876410779_1784319889",
      "msgId": "144115268026882536-1784319889-1876410779",
      "fromAccount": "user_b",
      "peerAccount": "user_a",
      "groupId": null,
      "msgSeq": null,
      "msgTimeMs": 1718450000000,
      "elemType": "TIMTextElem",
      "previewText": "你好",
      "msgBody": [
        {
          "MsgType": "TIMTextElem",
          "MsgContent": { "Text": "你好" }
        }
      ],
      "status": 1
    }
  ],
  "nextCursor": 1718450000000,
  "hasMore": true
}
```

字段：

| 字段 | 说明 |
|---|---|
| `msgKey` | 归档唯一键；群聊通常为 `{groupId}:{msgSeq}` |
| `msgId` | 腾讯真正的 MsgId，可能为空 |
| `fromAccount` | 发送者 ID |
| `peerAccount` | 单聊对方 ID |
| `groupId` | 群 ID；单聊为 `null` |
| `msgSeq` | 群消息序号；单聊通常为 `null` |
| `msgTimeMs` | 消息时间，毫秒时间戳 |
| `elemType` | 消息元素类型，例如 `TIMTextElem` |
| `previewText` | 消息预览文本 |
| `msgBody` | 腾讯 IM 原始消息体 |
| `status` | `1` 正常；`0` 已撤回 |
| `nextCursor` | 下一页游标 |
| `hasMore` | 是否还有下一页 |

`status=0` 时客户端显示“消息已撤回”，但仍保留该消息的位置，不要直接删除记录。

## 5. 清空会话历史

清空只影响当前用户在自建历史接口中的可见记录，不会撤回腾讯 IM 消息，也不会影响对方。

单聊：

```http
DELETE /me/messages/c2c?peerUserId=user_b
Authorization: Bearer <token>
```

群聊：

```http
DELETE /me/messages/group?groupId=%40TGS%232J4SZEAEL
Authorization: Bearer <token>
```

响应：

```json
{ "clearedBeforeMs": 1718450000123 }
```

清空成功后客户端应清理本地消息缓存、重置分页游标，再从空列表开始拉取。清空之后新收到的消息仍然正常显示。

## 6. 解析单聊 MsgId

归档消息的 `msgId` 可能为空。需要腾讯真正 MsgId 时，可批量解析单聊消息：

```http
POST /me/messages/resolve-msg-ids
Authorization: Bearer <token>
Content-Type: application/json

{
  "chatType": "c2c",
  "peerId": "user_b",
  "msgKeys": ["3358721060_1876410779_1784319889"]
}
```

响应：

```json
{
  "items": [
    {
      "msgKey": "3358721060_1876410779_1784319889",
      "msgId": "144115268026882536-1784319889-1876410779"
    }
  ]
}
```

限制：仅支持 `chatType=c2c`；`msgKeys` 最多 100 个；当前用户必须是会话参与者。解析不到时 `msgId` 为空，客户端继续使用 `msgKey`。

## 7. Flutter 接入示例

```dart
Future<MessageHistoryPage> getC2cHistory({
  required String peerUserId,
  int? cursor,
  int limit = 30,
}) async {
  final response = await dio.get(
    '/me/messages/c2c',
    queryParameters: {
      'peerUserId': peerUserId,
      if (cursor != null) 'cursor': cursor,
      'limit': limit,
    },
  );
  return MessageHistoryPage.fromJson(response.data);
}
```

加载更多：

```dart
final page = await getC2cHistory(
  peerUserId: peerUserId,
  cursor: nextCursor,
);

for (final item in page.items) {
  await messageCache.upsert(
    chatType: 'c2c',
    peerId: peerUserId,
    msgKey: item.msgKey,
    item: item,
  );
}

nextCursor = page.nextCursor;
hasMore = page.hasMore;
```

本地消息主键建议使用：

```text
(chatType, peerId, msgKey)
```

不要只使用 `msgId`，因为 `msgId` 允许为空。

## 8. 推荐消息同步流程

```text
进入会话
  ↓
读取本地缓存并展示
  ↓
调用 /me/messages/c2c 或 /me/messages/group
  ↓
按 msgKey 去重并写入本地库
  ↓
腾讯 IM SDK 接收实时消息
  ↓
status=0 展示“消息已撤回”
```

消息来源约定：

| 场景 | 数据来源 |
|---|---|
| 实时消息 | 腾讯 IM SDK |
| 历史消息 | `/me/messages/c2c`、`/me/messages/group` |
| 未读数、草稿、免打扰 | 腾讯 IM SDK / 客户端本地 |
| 消息唯一去重 | `msgKey` |

## 9. 错误与降级

- `401`：JWT 失效，按现有登录刷新流程处理。
- `403`：当前用户不是该单聊或群聊参与者。
- `400`：参数缺失、区间参数不完整或起止范围非法。
- `429`：触发限流，短暂退避后重试；接口约束为单用户 10 req/s。
- `5xx`、超时或接口不存在：不阻塞登录/进入会话，继续使用腾讯 IM SDK 的历史分页或会话同步能力。

## 10. 联调验收清单

- [ ] JWT 正确添加到 `Authorization` 请求头。
- [ ] 单聊、群聊首次请求均不传 `cursor`。
- [ ] 下一页使用响应里的 `nextCursor`，并根据 `hasMore` 判断是否继续。
- [ ] 区间参数成对传递，且分页时使用 `nextCursor + 1`。
- [ ] 使用 `msgKey` 去重，本地不依赖 `msgId` 作为唯一主键。
- [ ] `status=0` 显示“消息已撤回”。
- [ ] 清空成功后清理本地缓存并重置游标。
- [ ] 归档接口失败时有腾讯 IM SDK 降级路径。

## 相关文档

- [IM 消息归档 Kafka 与服务端说明](./message-archive-kafka.md)
- [IM Snapshot 新设备冷启动](./im-snapshot-client.md)
- [文档索引](./README.md)

## 超级大群历史上滑接口（groupSeq v1）

超级大群向上加载更早消息使用：

```http
GET /groups/{groupId}/messages/history?direction=older&limit=50
GET /groups/{groupId}/messages/history?direction=older&limit=50&cursor=<olderCursor>
```

需要登录 JWT，并且当前用户必须是该群成员。服务端使用归档中的 `msgSeq` 作为 `groupSeq`，按群内序号分页；客户端不得使用时间戳、offset 或自行拼接游标。

### 外层响应

```json
{
  "groupId": "@TGS#xxx",
  "snapshotMaxSeq": 1204818,
  "minAvailableSeq": 800000,
  "oldestSeq": 1204720,
  "newestSeq": 1204769,
  "count": 6,
  "hasMoreOlder": true,
  "olderCursor": "<opaque-signed-cursor>",
  "unavailableRanges": [],
  "pageChecksum": "<sha256>",
  "items": [
    {
      "messageId": "msg_1204720",
      "groupSeq": 1204720,
      "senderUserId": "user_xxx",
      "messageType": "TIMTextElem",
      "payload": { "text": "晚上一起吃饭" },
      "serverTimestamp": 1788365000000,
      "status": 1
    },
    {
      "messageId": "msg_1204721",
      "groupSeq": 1204721,
      "senderUserId": "user_yyy",
      "messageType": "TIMImageElem",
      "payload": {
        "UUID": "img_uuid_001",
        "ImageFormat": 1,
        "ImageInfoArray": [
          { "Type": 1, "Size": 248321, "Width": 1080, "Height": 1920, "URL": "https://cdn.example.com/img_001_origin.jpg" },
          { "Type": 2, "Size": 132001, "Width": 0,    "Height": 0,    "URL": "https://cdn.example.com/img_001_large.jpg"  },
          { "Type": 3, "Size": 12001,  "Width": 240,  "Height": 240,  "URL": "https://cdn.example.com/img_001_thumb.jpg"  }
        ]
      },
      "serverTimestamp": 1788365001000,
      "status": 1
    },
    {
      "messageId": "msg_1204722",
      "groupSeq": 1204722,
      "senderUserId": "user_zzz",
      "messageType": "TIMVideoFileElem",
      "payload": {
        "VideoUrl": "https://cdn.example.com/vid_001.mp4",
        "VideoUUID": "vid_uuid_001",
        "VideoSize": 8421321,
        "VideoSecond": 17,
        "VideoFormat": "MP4",
        "VideoDownloadFlag": 2,
        "ThumbUrl": "https://cdn.example.com/vid_001_thumb.jpg",
        "ThumbUUID": "vid_thumb_uuid_001",
        "ThumbSize": 30021,
        "ThumbWidth": 320,
        "ThumbHeight": 180,
        "ThumbFormat": "JPG",
        "ThumbDownloadFlag": 2
      },
      "serverTimestamp": 1788365002000,
      "status": 1
    },
    {
      "messageId": "msg_1204723",
      "groupSeq": 1204723,
      "senderUserId": "user_aaa",
      "messageType": "TIMSoundElem",
      "payload": {
        "Url": "https://cdn.example.com/snd_001.aac",
        "UUID": "snd_uuid_001",
        "Size": 18421,
        "Second": 6,
        "DownloadFlag": 2
      },
      "serverTimestamp": 1788365003000,
      "status": 1
    },
    {
      "messageId": "msg_1204724",
      "groupSeq": 1204724,
      "senderUserId": "user_bbb",
      "messageType": "TIMFileElem",
      "payload": {
        "FileName": "周报-2026W36.pdf",
        "FileSize": 312001,
        "Url": "https://cdn.example.com/file_001.pdf",
        "UUID": "file_uuid_001",
        "DownloadFlag": 2
      },
      "serverTimestamp": 1788365004000,
      "status": 1
    },
    {
      "messageId": "msg_1204725",
      "groupSeq": 1204725,
      "senderUserId": "user_ccc",
      "messageType": "TIMCustomElem",
      "payload": {
        "Data": "{\"businessID\":\"wallet_transfer\",\"fromUserId\":\"user_ccc\",\"toUserId\":\"user_xxx\",\"amount\":\"12.34\",\"currency\":\"CNY\"}",
        "Desc": "[转账]",
        "Ext": ""
      },
      "serverTimestamp": 1788365005000,
      "status": 1
    }
  ]
}
```

> **约定**：`payload` 字段是腾讯 IM 服务端原始 `MsgBody` 反序列化结果，**字段集以腾讯 IM 当前 SDK 为准**，个别字段缺失或新增均视为正常；不要在前端对 `payload` 做严格 schema 校验。

### items 公共字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `messageId` | string | 消息唯一键；归档回退时可能为 `msgKey`，仅作稳定唯一标识使用，不要解析其格式 |
| `groupSeq` | long | 群内序号；客户端可作为排序键 |
| `senderUserId` | string | 发送者 userId |
| `messageType` | string | 元素类型，取值见下文「payload 字段矩阵」 |
| `payload` | object | 腾讯 IM 原始 `MsgContent` 反序列化结果，结构随 `messageType` 变化 |
| `serverTimestamp` | long | 服务端毫秒时间戳 |
| `status` | int | `1` 正常；`0` 已撤回（按现有撤回消息 UI 处理） |

### payload 字段矩阵（按 messageType）

#### TIMTextElem

| 字段 | 类型 | 说明 |
|---|---|---|
| `text` | string | 纯文本正文 |

**前端最少必读**：`text`。

#### TIMImageElem

| 字段 | 类型 | 说明 |
|---|---|---|
| `UUID` | string | 图片资源 ID |
| `ImageFormat` | int | 图片格式枚举（1=JPG，2=GIF，3=PNG，4=BMP 等） |
| `ImageInfoArray` | array | 多规格图列表，按需挑选使用 |
| `ImageInfoArray[].Type` | int | `1`=原图、`2`=大图、`3`=缩略图 |
| `ImageInfoArray[].Size` | long | 字节数 |
| `ImageInfoArray[].Width` | int | 宽度（Type=2 时可为 0） |
| `ImageInfoArray[].Height` | int | 高度（Type=2 时可为 0） |
| `ImageInfoArray[].URL` | string | 直链 CDN URL，可能为相对路径或外链 |

**前端最少必读**：
- 列表/气泡缩略图：`Type=3`（缩略图）的 `URL`。
- 点击查看大图：`Type=1`（原图）或 `Type=2`（大图）的 `URL`，优先原图。
- 三档 `URL` 都缺失时使用本地占位图，不要弹错误提示。

#### TIMVideoFileElem

| 字段 | 类型 | 说明 |
|---|---|---|
| `VideoUrl` | string | 视频直链 |
| `VideoUUID` | string | 视频资源 ID |
| `VideoSize` | long | 视频字节数 |
| `VideoSecond` | int | 时长（秒，最小为 1） |
| `VideoFormat` | string | 容器格式，如 `MP4` |
| `VideoDownloadFlag` | int | 下载标记（一般 `2`） |
| `ThumbUrl` | string | 封面图直链 |
| `ThumbUUID` | string | 封面资源 ID |
| `ThumbSize` | long | 封面字节数 |
| `ThumbWidth` | int | 封面宽度 |
| `ThumbHeight` | int | 封面高度 |
| `ThumbFormat` | string | 封面格式，如 `JPG` |
| `ThumbDownloadFlag` | int | 封面下载标记（一般 `2`） |

**前端最少必读**：`VideoUrl`（播放）、`ThumbUrl`（气泡封面）、`VideoSecond`（时长文案）、`VideoSize`（大小文案）。

#### TIMSoundElem

| 字段 | 类型 | 说明 |
|---|---|---|
| `Url` | string | 语音直链 |
| `UUID` | string | 资源 ID |
| `Size` | long | 字节数 |
| `Second` | int | 时长（秒） |
| `DownloadFlag` | int | 下载标记（一般 `2`） |

**前端最少必读**：`Url`（播放）、`Second`（时长文案）。`Size` 可选展示。

#### TIMFileElem

| 字段 | 类型 | 说明 |
|---|---|---|
| `FileName` | string | 文件名（含扩展名） |
| `FileSize` | long | 字节数 |
| `Url` | string | 下载直链 |
| `UUID` | string | 资源 ID |
| `DownloadFlag` | int | 下载标记（一般 `2`） |

**前端最少必读**：`FileName`（标题）、`FileSize`（大小文案）、`Url`（点击下载/预览）。

#### TIMCustomElem

| 字段 | 类型 | 说明 |
|---|---|---|
| `Data` | string | **业务 JSON 字符串**（UTF-8），由发送方业务方自定义 schema |
| `Desc` | string | 摘要/离线推送文案，可空 |
| `Ext` 或 `Extension` | string | 扩展字段（JSON 字符串），优先读 `Extension`，无则回退 `Ext` |

**Data 内层字段（按业务约定）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `businessID` / `customType` / `type` | string | 业务类型标识，前端按此分发到具体卡片组件（钱包、名片、群提示等） |
| 其余字段 | — | 由各业务自行约定，参考各专项文档（转账、红包、名片、链接、群提示等） |

**前端最少必读**：
1. 永远先 `jsonDecode(payload.Data)` 再分发，**禁止把整段 `Data` 当字符串塞 UI**。
2. 解析失败时按顺序回退：`Desc` → `"[自定义消息]"`，不要弹错误。
3. 通过 `businessID` / `customType` / `type` 决定渲染哪个组件，不要假设只有一种自定义消息。

### 前端解析最佳实践

1. **统一解析入口**：建议在 Flutter 端新建 `lib/api/super_group_history_item.dart`，由 `SuperGroupHistoryItem.fromJson(Map<String, dynamic> json)` 工厂按 `messageType` 分发到 `_parseTextPayload` / `_parseImagePayload` / `_parseVideoPayload` / `_parseSoundPayload` / `_parseFilePayload` / `_parseCustomPayload`，**避免在 widget 层重复写 `as Map<String, dynamic>?` 强转**。
2. **payload 缺值降级**：`TIMImageElem` 的 `ImageInfoArray` 缺 `Type=1`/`Type=3` 的 `URL`、或 `TIMVideoFileElem.VideoUrl`/`TIMSoundElem.Url`/`TIMFileElem.Url` 为空时，**静默回退到本地占位资源**，不弹 toast、不打 error 日志；只在 `kDebugMode` 或远端日志通道打印 warn。
3. **TIMCustomElem.Data 必解析**：先 `try { jsonDecode(payload['Data'] ?? '{}') }` 拿到 Map，再按 `businessID` 字段分发；解析异常时按 `Desc → "[自定义消息]"` 顺序降级展示，**不要让整条消息变空白**。
4. **去重与排序**：唯一键固定为 `(messageId, groupSeq)`；新页 `items` 插入消息列表顶部后，再统一按 `groupSeq` ASC 整体排序一次（防御服务端偶发乱序），然后按 `messageId` 去重。
5. **status=0 已撤回**：保留位置不删除消息体，UI 按现有撤回消息样式展示（"此消息已撤回"）。
6. **unavailableRanges 非空**：必须展示历史缺口提示（"中间部分历史消息已不可用"），**不能静默当作无消息**。
7. **items=[] ≠ 历史结束**：`items.length == 0 && hasMoreOlder == true` 不应被理解为历史已读完；只有 `hasMoreOlder == false && items == []` 才视为服务端正常结束，但仍需结合 HTTP 状态码（`200`）确认。

### 分页与游标约定

首次请求固定 `snapshotMaxSeq`。后续请求必须原样回传 `olderCursor`，服务端按 `groupSeq < anchorSeq` 查询并在响应中按 `groupSeq` 升序返回；前端将新页插入消息列表顶部即可。`hasMoreOlder=false` 时 `olderCursor` 为空字符串。

游标包含群、用户、方向、快照上限、分页锚点、版本和过期时间，并带 HMAC 签名。不要解码或修改它。游标非法、跨用户/跨群或过期时，不要自动从最新位置重新开始，应按错误码重新发起首页快照：`INVALID_CURSOR`、`CURSOR_USER_MISMATCH`、`CURSOR_GROUP_MISMATCH`、`SNAPSHOT_EXPIRED`、`HISTORY_RETENTION_EXPIRED`。

数据库或历史存储异常返回 `HISTORY_STORAGE_UNAVAILABLE`，完整性无法证明返回 `HISTORY_RANGE_INCOMPLETE`；这些错误都不能当作空页或历史结束处理。

## 相关文档

- [社群历史查询（前端总入口）](./community-history-query-client.md)
- [IM 消息归档对接（详情）](./message-archive-client.md)（本文档）
- [IM 消息归档 Kafka 与服务端说明](./message-archive-kafka.md)
