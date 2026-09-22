# 云端同步 API（通讯录 + 相册）

> **客户端完整对接**见 [cloud-sync-client.md](./cloud-sync-client.md)  
> 需 JWT：`Authorization: Bearer <token>`  
> 原则：通讯录 batch 即落库；相册 **`/photos/complete` 成功后才写入 `user_photo`**

> **范围说明**：本页仅覆盖设备通讯录 / 相册同步（`/me/sync/*`）。
> 好友 / 群 / 群成员 / 群通知等**业务数据**的双轨同步协议见 [sync-protocol.md](./sync-protocol.md)（`/sync/{domain}/*`），与本页接口无关。

---

## 1. 状态

`GET /me/sync/status` → 各类型 `lastFullSyncAt` / `lastIncrementalSyncAt`

---

## 2. 通讯录

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/me/sync/contacts/sessions` | 开始同步 `{ deviceId, mode: FULL\|INCREMENTAL }` |
| POST | `/me/sync/contacts/batch` | 批量落库，最多 500 条/次 |
| POST | `/me/sync/contacts/complete` | 结束同步，可传 `deletedLocalContactIds` |
| GET | `/me/sync/contacts` | 列表（读库） |

### batch 请求示例

```json
{
  "syncSessionId": "uuid",
  "items": [
    {
      "localContactId": "ios:abc",
      "fingerprint": "64位hex sha256",
      "displayName": "张三",
      "phones": ["+8613812345678"],
      "updatedAt": 1716451200
    }
  ]
}
```

---

## 3. 相册

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/me/sync/photos/sessions` | 开始同步 |
| POST | `/me/sync/photos/check` | 去重预判 NEED_UPLOAD / ALREADY_EXISTS |
| POST | `/me/sync/photos/init-upload` | 创建 PENDING 上传单 + 预签名 PUT URL |
| PUT | OSS 预签名 URL | 客户端上传原图 |
| POST | `/me/sync/photos/complete` | JSON：`{ uploadUuid }`（已 PUT OSS） |
| POST | `/me/sync/photos/complete` | multipart：`uploadUuid` + `file`（服务端代传） |
| POST | `/me/sync/photos/sessions/complete` | 结束相册同步会话 |
| GET | `/me/sync/photos?page=0&size=50` | 分页列表（读库） |
| GET | `/me/sync/photos/{photoUuid}` | 单条详情 |

### check

支持**图片 + 视频**混批（同一相册会话 `PHOTOS`）。视频条目须带 `mediaType` / `mimeType` / `duration`。

**图片示例**

```json
{
  "syncSessionId": "相册会话UUID",
  "items": [
    {
      "localAssetId": "ios:asset-id",
      "contentHash": "64位hex",
      "sizeBytes": 1024000,
      "takenAt": 1716451200,
      "width": 4032,
      "height": 3024,
      "mediaType": "IMAGE",
      "mimeType": "image/jpeg"
    }
  ]
}
```

**视频示例（相册会话内）**

```json
{
  "syncSessionId": "相册会话UUID",
  "items": [
    {
      "localAssetId": "ios:XXXXXXXX",
      "contentHash": "64位hex sha256",
      "sizeBytes": 12345678,
      "takenAt": 1655129427,
      "width": 1920,
      "height": 1080,
      "duration": 35,
      "mediaType": "VIDEO",
      "mimeType": "video/quicktime"
    }
  ]
}
```

### init-upload → complete 流程

`init-upload` 请求体字段与 check 条目一致；视频可走 `/photos/init-upload` + `/photos/complete`，也可走 `/videos/*`。

1. `init-upload` 返回 `uploadUuid`、`photoUuid`、`presignedPutUrl`、`ossOriginKey`
2. 客户端 PUT 原文件到 `presignedPutUrl`
3. `POST /photos/complete` 或 `/videos/complete`：`{ "uploadUuid": "..." }`
4. 图片生成 thumb/preview；视频仅保存原文件 URL，**写入 `user_photo`**

`contentHash` 必须为 **64 位小写 hex**（SHA-256）。

---

## 4. 视频

与相册流程一致，复用 `user_photo` / `user_photo_upload` 表（`media_type=VIDEO`）。视频 **不生成** thumb/preview，仅保存原文件 URL。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/me/sync/videos/sessions` | 开始视频同步 |
| POST | `/me/sync/videos/check` | 去重预判 |
| POST | `/me/sync/videos/init-upload` | 创建上传单 + 预签名 PUT URL |
| PUT | OSS 预签名 URL | 客户端上传原视频 |
| POST | `/me/sync/videos/complete` | JSON：`{ uploadUuid }` |
| POST | `/me/sync/videos/sessions/complete` | 结束视频同步会话 |
| GET | `/me/sync/videos?page=0&size=50` | 分页列表 |
| GET | `/me/sync/videos/{photoUuid}` | 单条详情 |

### check / init-upload（视频专用会话）

与相册 check 条目格式相同，可使用 `VIDEOS` 或 `PHOTOS` 会话 ID：

```json
{
  "syncSessionId": "43990acd-506b-4c13-a0ad-d1bd5357d0ab",
  "items": [
    {
      "localAssetId": "android:4076",
      "contentHash": "64位hex sha256",
      "sizeBytes": 12345678,
      "takenAt": 1655129427,
      "width": 1920,
      "height": 1080,
      "duration": 35,
      "mediaType": "VIDEO",
      "mimeType": "video/mp4"
    }
  ]
}
```

### init-upload 单条示例

```json
{
  "syncSessionId": "43990acd-506b-4c13-a0ad-d1bd5357d0ab",
  "localAssetId": "android:4076",
  "contentHash": "64位hex sha256",
  "sizeBytes": 12345678,
  "takenAt": 1655129427,
  "width": 1920,
  "height": 1080,
  "duration": 35,
  "mediaType": "VIDEO",
  "mimeType": "video/mp4"
}
```

支持 `mimeType`：`video/mp4`、`video/quicktime`、`video/webm`、`video/3gpp`、`video/x-m4v`。单文件上限默认 **100MB**（`chat99.sync.max-video-upload-bytes`）。

---

## 5. 配置

```yaml
chat99:
  sync:
    photo-prefix: user-backup/
    upload-expire-minutes: 60
    max-contacts-batch: 500
    max-photos-check: 100
    presign-expire-seconds: 3600
    max-video-upload-bytes: 104857600
```

---

## 6. 错误码

| code | 说明 |
|------|------|
| SYNC_SESSION_NOT_FOUND | 会话不存在 |
| SYNC_SESSION_NOT_RUNNING | 会话已结束 |
| BATCH_TOO_LARGE | 超批次上限 |
| INVALID_HASH | contentHash 格式错误 |
| OSS_NOT_CONFIGURED | 未配置 OSS |
| UPLOAD_NOT_FOUND / UPLOAD_EXPIRED | 相册上传单无效 |
| OSS_OBJECT_NOT_FOUND | complete 时 OSS 无原图 |

表结构见 [cloud-sync-database.md](./cloud-sync-database.md)。
