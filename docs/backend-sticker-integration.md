# 自定义表情包 — 联调文档

> Base：`http://47.239.60.107:8081`  
> 规格：[backend-sticker-api.md](./backend-sticker-api.md)

---

## 1. 新用户

注册成功后自动安装包：`4350`、`4351`、`4352`、`user_upload`。  
老用户首次 `GET /me/sticker-packs` 时补装。

---

## 2. curl 示例

```bash
BASE="http://127.0.0.1:8081"
TOKEN="<jwt>"

curl -sS "$BASE/me/sticker-packs" -H "Authorization: Bearer $TOKEN"

curl -sS -X POST "$BASE/stickers/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/sticker.png"

# 短视频 → 自动转 GIF（需服务器安装 ffmpeg）
curl -sS -X POST "$BASE/stickers/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/clip.mp4" \
  -F "mediaType=video"

curl -sS "$BASE/stickers/<stickerId>" -H "Authorization: Bearer $TOKEN"

curl -sS -X POST "$BASE/me/stickers/favorites" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"stickerId":"<uuid>"}'
```

---

## 3. 验收清单

- [ ] 新用户 GET `/me/sticker-packs` 含 4 个包，系统包 `stickers` 为空
- [ ] 上传后 `user_upload.stickers` 增加一项
- [ ] 上传 MP4（≤10s）返回 `mediaType=gif`，`originUrl` 为 `.gif`
- [ ] 服务器未装 ffmpeg 时上传视频返回 503 `FFMPEG_NOT_CONFIGURED`
- [ ] 收藏 POST 幂等，GET `items` 可见
- [ ] DELETE 上传项后 GET `/stickers/{id}` 仍可返回
- [ ] 删系统包 403 `PACK_NOT_REMOVABLE`
- [ ] 未登录 401

---

## 4. 表结构

| 表 | 用途 |
|----|------|
| `sticker` | 表情资源 |
| `sticker_pack` | 包定义 |
| `sticker_pack_item` | 包内表情 |
| `user_sticker_pack` | 用户已安装包及排序 |
| `user_sticker_favorite` | 收藏 |
