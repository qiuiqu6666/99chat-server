# 星标好友 — 客户端对接文档

> 版本：v1.0  
> 适用：99chat Flutter 通讯录页  
> Base URL：`http://47.239.60.107:8081`

---

## 1. 说明

星标好友为**当前登录用户**对 IM 好友的本地标记，用于通讯录 UI（置顶、星标图标等）。

| 项 | 说明 |
|----|------|
| 存储 | MySQL `user_starred_friend` |
| 好友来源 | 仍以腾讯 IM 好友列表为准；本接口只记录「谁被星标」 |
| 唯一性 | 同一用户对同一 `friendUserId` 仅一条记录 |

---

## 2. 接口

### 2.1 列表 — `GET /me/starred-friends`

拉取当前用户全部星标好友（按星标时间倒序）。

**200**

```json
{
  "items": [
    {
      "friendUserId": "a1b2c3d4e5",
      "starredAt": "2026-05-23T10:00:00Z"
    }
  ]
}
```

无星标时：`{ "items": [] }`。

---

### 2.2 设置星标 — `PUT /me/starred-friends/{friendUserId}`

幂等：已星标则直接返回当前记录。

**200**

```json
{
  "friendUserId": "a1b2c3d4e5",
  "starred": true,
  "starredAt": "2026-05-23T10:00:00Z"
}
```

| HTTP | code | 说明 |
|------|------|------|
| 400 | `INVALID_INPUT` | friendUserId 为空 |
| 400 | `CANNOT_STAR_SELF` | 不能星标自己 |
| 404 | `USER_NOT_FOUND` | 对方不是平台用户或已禁用 |

---

### 2.3 取消星标 — `DELETE /me/starred-friends/{friendUserId}`

幂等：未星标也返回成功。

**200**

```json
{
  "friendUserId": "a1b2c3d4e5",
  "starred": false,
  "starredAt": null
}
```

---

## 3. UI 建议

1. 进入通讯录 → `GET /me/starred-friends`，构建 `Set<friendUserId>`。  
2. 与 IM 好友列表合并排序：星标在前，可按 `starredAt` 或昵称二次排序。  
3. 长按/菜单「星标」→ `PUT`；「取消星标」→ `DELETE`。  
4. 乐观更新失败时以接口结果为准刷新。

---

## 4. Flutter 示例

```dart
class StarredFriendApi {
  StarredFriendApi(this.dio);
  final Dio dio;

  Future<Set<String>> loadStarredIds() async {
    final r = await dio.get('/me/starred-friends');
    final items = r.data['items'] as List<dynamic>;
    return items.map((e) => e['friendUserId'] as String).toSet();
  }

  Future<void> star(String friendUserId) async {
    await dio.put('/me/starred-friends/$friendUserId');
  }

  Future<void> unstar(String friendUserId) async {
    await dio.delete('/me/starred-friends/$friendUserId');
  }
}
```

---

## 5. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05-23 | 初版 |
