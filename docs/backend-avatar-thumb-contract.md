# Backend avatar thumb/preview contract

This document aligns the backend with the canonical avatar split contract for Plan 114 / 115.

## 1. Canonical model

For users and groups, avatar storage is split into three persisted values:

- `thumbUrl`: square 200x200 thumbnail, used in conversation lists / member cells / message metadata
- `previewUrl`: long-edge <= 750px, used for full-screen preview or larger profile rendering
- `avatarVersion`: integer version counter; must increase with every avatar change

The effective UI contract is:

- normal list / cell / mini profile => `thumbUrl`
- full-screen preview => `previewUrl`
- versioning / invalidation => `avatarVersion`

The source-of-truth file naming pattern is the same object identity (`origin`, `preview`, `thumb`) in one upload revision.

## 2. Backend contract checklist

### 2.1 Data model: persisted fields

Required persistence is:

- `users.avatar_url` => thumb URL
- `users.avatar_preview_url` => preview URL
- `users.avatar_version` => monotonically increasing version
- `group_profile.avatar_url` => thumb URL
- `group_profile.avatar_preview_url` => preview URL
- `group_profile.avatar_version` => monotonically increasing version

Implementation status:

- User entity already holds `avatarUrl`, `avatarPreviewUrl`, `avatarVersion`: see [src/main/java/com/chat99/server/user/User.java](../src/main/java/com/chat99/server/user/User.java)
- Group profile already holds `avatarUrl`, `avatarPreviewUrl`, `avatarVersion`: see [src/main/java/com/chat99/server/group/GroupProfile.java](../src/main/java/com/chat99/server/group/GroupProfile.java)
- Uploads set the thumb and preview URLs and increment the version: see [src/main/java/com/chat99/server/user/UserAvatarService.java](../src/main/java/com/chat99/server/user/UserAvatarService.java) and [src/main/java/com/chat99/server/group/GroupProfileService.java](../src/main/java/com/chat99/server/group/GroupProfileService.java)

### 2.2 Historical backfill

The migration script backfills preview URLs from legacy stored URLs and sets the baseline version to `0` when needed:

- [scripts/migrate-avatar-thumb-preview.sql](../scripts/migrate-avatar-thumb-preview.sql)

The intended behavior is:

- if legacy data only has a preview-ish URL, backfill `avatar_preview_url`
- if a record has no preview URL, keep it nullable and allow a clear migration state
- do not guess URL suffixes on the client; server should explicitly return `null` when preview or thumb is not available

### 2.3 Ordinary read projections return thumb + version

The backend read model must return `avatarUrl` as the thumb value and include `avatarVersion` in the projection.

Examples:

- [src/main/java/com/chat99/server/user/UserProfileService.java](../src/main/java/com/chat99/server/user/UserProfileService.java)
- [src/main/java/com/chat99/server/group/GroupProfileView.java](../src/main/java/com/chat99/server/group/GroupProfileView.java)

Contract rule:

- list / profile / contact / group views should expose the thumb URL as the avatar value
- `avatarVersion` should be carried with the projection so clients can invalidate caches

### 2.4 Full-screen preview endpoint

Preview is intentionally lazy-loaded via a dedicated endpoint instead of forcing the client to infer URLs.

Endpoint examples:

- `GET /users/{userId}/avatar-preview`
- `GET /groups/{groupId}/avatar-preview`

Implementation:

- [src/main/java/com/chat99/server/user/AvatarPreviewController.java](../src/main/java/com/chat99/server/user/AvatarPreviewController.java)

Response shape:

```json
{
  "previewUrl": "https://cdn.example.com/user-avatar/u123/123_preview.jpg",
  "avatarVersion": 7
}
```

This keeps the preview requirement lazy and avoids prefetching it before the user enters the fullscreen view.

### 2.5 Upload response contract

The upload response must include the versioned object URLs.

User avatar response currently includes:

```json
{
  "avatarUrl": "https://cdn.example.com/user-avatar/u123/123_thumb.jpg",
  "originUrl": "https://cdn.example.com/user-avatar/u123/123_origin.jpg",
  "previewUrl": "https://cdn.example.com/user-avatar/u123/123_preview.jpg",
  "thumbUrl": "https://cdn.example.com/user-avatar/u123/123_thumb.jpg",
  "avatarVersion": 8
}
```

This is now enforced in the upload contract and backed by the test in [src/test/java/com/chat99/server/user/AvatarThumbPreviewContractTest.java](../src/test/java/com/chat99/server/user/AvatarThumbPreviewContractTest.java).

### 2.6 Push payload

Push payloads should send the thumb URL, not the preview URL.

The canonical invariant is:

- `avatarThumbUrl` = thumb URL
- `avatarUrl` (legacy compatibility) = thumb URL as well
- no client-side filename inference from the URL path

The IM callback logic already emits the thumb variant in push data: see [src/main/java/com/chat99/server/im/ImChatPushCallbackService.java](../src/main/java/com/chat99/server/im/ImChatPushCallbackService.java).

### 2.7 Tencent IM faceUrl projection

IM face URLs should be normalized to the thumb variant to prevent the IM-sidecar from leaking the preview URL into the normal UI layer.

Current backend usage:

- user avatar upload uses `imAdmin.profileUpdateFaceUrl(..., thumbUrl)`
- group avatar upload calls `im.modifyGroupFaceUrl(groupId, upload.thumbUrl())`

This is the correct projection boundary:

- UI list / fluid chat use thumb
- preview-only path uses preview endpoint or explicit sidecar field
- IM faceUrl is not the canonical preview source

## 3. Response examples

### 3.1 User profile

```json
{
  "userId": "u123",
  "nickname": "Alice",
  "avatarUrl": "https://cdn.example.com/user-avatar/u123/123_thumb.jpg",
  "avatarVersion": 8,
  "phoneMasked": "138****1234"
}
```

### 3.2 User upload

```json
{
  "avatarUrl": "https://cdn.example.com/user-avatar/u123/123_thumb.jpg",
  "originUrl": "https://cdn.example.com/user-avatar/u123/123_origin.jpg",
  "previewUrl": "https://cdn.example.com/user-avatar/u123/123_preview.jpg",
  "thumbUrl": "https://cdn.example.com/user-avatar/u123/123_thumb.jpg",
  "avatarVersion": 8
}
```

### 3.3 Group upload (API contract)

```json
{
  "originUrl": "https://cdn.example.com/group-avatar/g123/456_origin.jpg",
  "previewUrl": "https://cdn.example.com/group-avatar/g123/456_preview.jpg",
  "thumbUrl": "https://cdn.example.com/group-avatar/g123/456_thumb.jpg",
  "avatarVersion": 3
}
```

## 4. Backend completion status

Current backend state is close to the plan target:

- ✅ Persisted thumb + preview + version in model
- ✅ Backfill migration script exists
- ✅ Preview lazy endpoint exists
- ✅ User profile projection includes thumb and version
- ✅ IM faceUrl projection keeps thumb as default
- ✅ Upload contract fixes the missing `avatarVersion` in the user response
- ⚠️ Push payload and offline migration state should be audited in the exact live message paths before broad rollout

## 5. References

- [src/main/java/com/chat99/server/user/UserAvatarService.java](../src/main/java/com/chat99/server/user/UserAvatarService.java)
- [src/main/java/com/chat99/server/user/AvatarPreviewController.java](../src/main/java/com/chat99/server/user/AvatarPreviewController.java)
- [src/main/java/com/chat99/server/user/UserProfileService.java](../src/main/java/com/chat99/server/user/UserProfileService.java)
- [src/main/java/com/chat99/server/group/GroupProfileService.java](../src/main/java/com/chat99/server/group/GroupProfileService.java)
- [src/main/java/com/chat99/server/group/GroupAvatarController.java](../src/main/java/com/chat99/server/group/GroupAvatarController.java)
- [src/test/java/com/chat99/server/user/AvatarThumbPreviewContractTest.java](../src/test/java/com/chat99/server/user/AvatarThumbPreviewContractTest.java)
