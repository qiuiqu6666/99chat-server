# 云端同步 — 数据库落库约定

> 版本：v1.0  
> 原则：**文件在 OSS，业务记录在 MySQL**。上传完成（或通讯录批次校验通过）后**必须写入/更新数据库**；客户端列表、去重、增量均以库表为准。

---

## 1. 写入时机总览

| 类型 | 何时写库 | 不写库的情况 |
|------|----------|----------------|
| 通讯录 | 每批 `POST /me/sync/contacts/batch` 处理完即 upsert | 校验失败整批 400；`fingerprint` 未变可返回 skipped 但仍可更新 `synced_at` |
| 相册 | **`POST .../photos/complete` 且 OSS 已存在对象后** 才 INSERT/UPDATE | 仅 `init-upload` 未 PUT OSS 不得落库；`check` 为 ALREADY_EXISTS 可只读库不写 OSS |

**禁止**：只传 OSS 不写库；或库里有记录但 OSS 无对象（complete 时需校验或异步对账）。

---

## 2. 表结构

### 2.1 同步会话 `sync_session`

记录每次全量/增量任务，便于断点与统计。

```sql
CREATE TABLE sync_session (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_uuid    VARCHAR(36) NOT NULL UNIQUE,
  user_id         VARCHAR(10) NOT NULL,
  device_id       VARCHAR(64) NOT NULL,
  sync_type       VARCHAR(16) NOT NULL,  -- CONTACTS | PHOTOS
  sync_mode       VARCHAR(16) NOT NULL,  -- FULL | INCREMENTAL
  status          VARCHAR(16) NOT NULL,  -- RUNNING | COMPLETED | FAILED
  uploaded_count  INT NOT NULL DEFAULT 0,
  skipped_count   INT NOT NULL DEFAULT 0,
  failed_count    INT NOT NULL DEFAULT 0,
  error_message   VARCHAR(512),
  started_at      TIMESTAMP NOT NULL,
  completed_at    TIMESTAMP NULL,
  INDEX idx_sync_user_type (user_id, sync_type, started_at)
);
```

### 2.2 用户同步状态 `user_sync_state`

每个用户、每种类型一行，记录上次同步时间与版本。

```sql
CREATE TABLE user_sync_state (
  user_id                  VARCHAR(10) PRIMARY KEY,  -- 与 sync_type 组合见下
  sync_type                VARCHAR(16) NOT NULL,
  last_full_sync_at        TIMESTAMP NULL,
  last_incremental_sync_at TIMESTAMP NULL,
  server_revision          BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, sync_type)
);
```

### 2.3 通讯录 `user_contact_item`

```sql
CREATE TABLE user_contact_item (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id           VARCHAR(10) NOT NULL,
  local_contact_id  VARCHAR(128) NOT NULL,
  fingerprint       CHAR(64) NOT NULL,
  display_name      VARCHAR(256),
  phones_json       JSON NOT NULL,       -- ["+86138..."] E.164 数组
  status            TINYINT NOT NULL DEFAULT 1,  -- 1=ACTIVE 0=DELETED
  taken_at          TIMESTAMP NULL,        -- 可选：联系人更新时间
  synced_at         TIMESTAMP NOT NULL,
  created_at        TIMESTAMP NOT NULL,
  updated_at        TIMESTAMP NOT NULL,
  UNIQUE KEY uk_user_local (user_id, local_contact_id),
  INDEX idx_user_fp (user_id, fingerprint),
  INDEX idx_user_status (user_id, status)
);
```

**写入逻辑（batch 内逐条）**：

```
INSERT ... ON DUPLICATE KEY UPDATE
  fingerprint=VALUES(fingerprint),
  display_name=VALUES(display_name),
  phones_json=VALUES(phones_json),
  status=1,
  synced_at=NOW(),
  updated_at=NOW()
```

全量结束 `deletedLocalContactIds` → `UPDATE status=0 WHERE user_id=? AND local_contact_id IN (...)`.

### 2.4 相册 `user_photo`

**核心：上传完成后再插入/更新本表。**

```sql
CREATE TABLE user_photo (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  photo_uuid       VARCHAR(36) NOT NULL UNIQUE,
  user_id          VARCHAR(10) NOT NULL,
  local_asset_id   VARCHAR(128) NOT NULL,
  content_hash     CHAR(64) NOT NULL,
  taken_at         TIMESTAMP NULL,
  width            INT,
  height           INT,
  size_bytes       BIGINT NOT NULL,
  mime_type        VARCHAR(64),
  oss_origin_key   VARCHAR(512) NOT NULL,
  oss_thumb_key    VARCHAR(512),
  oss_preview_key  VARCHAR(512),
  origin_url       VARCHAR(1024),
  thumb_url        VARCHAR(1024),
  preview_url      VARCHAR(1024),
  sync_session_id  VARCHAR(36),
  status           TINYINT NOT NULL DEFAULT 1,
  created_at       TIMESTAMP NOT NULL,
  updated_at       TIMESTAMP NOT NULL,
  UNIQUE KEY uk_user_hash (user_id, content_hash),
  UNIQUE KEY uk_user_local_asset (user_id, local_asset_id),
  INDEX idx_user_taken (user_id, taken_at DESC),
  INDEX idx_user_session (sync_session_id)
);
```

### 2.5 相册上传暂存 `user_photo_upload`（可选，防刷预签名不落库）

init-upload 时写入 PENDING，complete 成功改 COMPLETED 并合并到 `user_photo`；超时清理 PENDING。

```sql
CREATE TABLE user_photo_upload (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  upload_uuid     VARCHAR(36) NOT NULL UNIQUE,
  user_id         VARCHAR(10) NOT NULL,
  local_asset_id  VARCHAR(128) NOT NULL,
  content_hash    CHAR(64) NOT NULL,
  oss_origin_key  VARCHAR(512) NOT NULL,
  expected_size   BIGINT,
  status          VARCHAR(16) NOT NULL,  -- PENDING | COMPLETED | EXPIRED
  expires_at      TIMESTAMP NOT NULL,
  created_at      TIMESTAMP NOT NULL,
  INDEX idx_upload_user (user_id, status)
);
```

---

## 3. 相册：上传 → 落库流程（必须按序）

```
1. POST /me/sync/photos/check
   → 只读 user_photo (by user_id + content_hash)
   → 返回 NEED_UPLOAD | ALREADY_EXISTS（已存在则带 photoUuid + urls，不写 OSS）

2. POST /me/sync/photos/init-upload
   → INSERT user_photo_upload (PENDING, oss_origin_key, ...)
   → 返回 presignedUrl, uploadUuid, photoUuid(预生成)

3. 客户端 PUT OSS

4. POST /me/sync/photos/complete
   → 校验 upload 记录 PENDING、未过期
   → HEAD OSS 对象存在（或 size 一致）
   → 生成 thumb/preview 上传 OSS（服务端，复用 ImageProcessor）
   → INSERT user_photo ... ON DUPLICATE KEY UPDATE
        (冲突键 uk_user_hash：同图只保留一条，更新 local_asset_id / urls)
   → UPDATE user_photo_upload SET status=COMPLETED
   → UPDATE sync_session 计数
   → 返回 photoUuid, originUrl, thumbUrl, previewUrl, takenAt
```

**事务边界**：`complete` 内「写 user_photo + 更新 upload 状态」同一事务；OSS 上传在事务外，失败则 upload 保持 PENDING 可重试 complete。

---

## 4. 通讯录：批次 → 落库流程

```
1. POST /me/sync/contacts/sessions { mode: FULL|INCREMENTAL }
   → INSERT sync_session (RUNNING)

2. POST /me/sync/contacts/batch { syncSessionId, items[] }
   → 每条 upsert user_contact_item
   → 累加 session uploaded/skipped

3. POST /me/sync/contacts/complete { syncSessionId, deletedLocalContactIds[] }
   → 软删 contacts
   → UPDATE sync_session COMPLETED
   → UPDATE user_sync_state.last_full_sync_at 或 last_incremental_sync_at
```

通讯录**无需 OSS**（除非另做加密快照备份）；数据只在 `user_contact_item`。

---

## 5. 查询接口与数据库字段对应

| API | 主要查表 |
|-----|----------|
| `GET /me/sync/photos` | `user_photo` WHERE user_id=? AND status=1 ORDER BY taken_at DESC |
| `GET /me/sync/photos/{photoUuid}` | `user_photo` BY photo_uuid |
| `GET /me/sync/contacts` | `user_contact_item` WHERE status=1 |
| `GET /me/sync/status` | `user_sync_state` + 最近 `sync_session` |

---

## 6. 去重与库表的关系

| 场景 | 库行为 |
|------|--------|
| 同 `content_hash` 再次 complete | `ON DUPLICATE KEY UPDATE` 更新 url、local_asset_id、synced_at |
| 同 `local_asset_id` 内容变了 | 更新 fingerprint/hash 字段（若 hash 变则新 INSERT 可能触发 uk_user_hash，需先删旧 hash 或改逻辑为 UPDATE by local_asset_id） |
| check 返回 ALREADY_EXISTS | **不写 OSS**，直接返回库中 `photo_uuid` 与 urls |
| 仅 init 未 complete | 只有 `user_photo_upload` PENDING，**不出现在** `user_photo` 列表 |

**推荐**：业务上以 `content_hash` 为内容主键；`local_asset_id` 变更时 UPDATE 同一 `user_photo` 行（按 uk_user_local_asset）。

---

## 7. JPA 实体规划（实现阶段）

| 实体 | 表名 |
|------|------|
| `SyncSession` | sync_session |
| `UserSyncState` | user_sync_state |
| `UserContactItem` | user_contact_item |
| `UserPhoto` | user_photo |
| `UserPhotoUpload` | user_photo_upload（可选） |

包路径建议：`com.chat99.server.sync`

---

## 8. 完成定义（落库相关）

- [ ] 相册 `complete` 成功后，`user_photo` 必有对应行，且 `oss_*_key` 可访问  
- [ ] 通讯录 batch 后，`user_contact_item` 与客户端条数一致（含 skipped 统计）  
- [ ] 重复上传同 hash，库中仅一条 ACTIVE 记录，接口返回 ALREADY_EXISTS  
- [ ] 列表 API 只读数据库，不列举 OSS bucket  

---

## 9. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-05-23 | 明确上传后落库时机与表结构 |
