# 好友申请历史 — 客户端对接文档

> 版本：v1.0
> 适用：99chat Flutter 通讯录/好友申请列表
> Base URL：`http://47.239.60.107:8081`

---

## 1. 说明

好友申请历史记录当前用户**发出的申请**与**收到的处理结果**（同意 / 拒绝），按申请时间倒序排列，供客户端展示「新的朋友」列表。

|| 项 | 说明 |
|---|----|------|
| 存储 | MySQL `friend_application_history` | |
| 记录方向 | 双向：当前用户 → 对方（申请方视角）；对方 → 当前用户（处理后亦写入） | |
| 分页 | cursor 游标分页，以 `addTime` 降序 | |
| 字段来源 | `addSource` 由**客户端上报**，其余字段由服务端填充 | |

---

## 2. 数据模型

### HistoryItem（历史记录条目）

```json
{
  "id": 1,
  "peerUserId": "xyz99abcde",
  "peerNickname": "小明",
  "peerFaceUrl": "https://.../avatar.png",
  "addWording": "我是同学张三推荐来的",
  "addSource": "qr_code",
  "addTime": "2026-05-23T10:00:00Z",
  "status": "accepted",
  "handledAt": "2026-05-23T10:05:00Z"
}
```

|| 字段 | 类型 | 说明 |
|------|------|------|------|
| `id` | Long | 记录 ID（本地分页用） |
| `peerUserId` | String | 对方用户 ID |
| `peerNickname` | String | 对方昵称 |
| `peerFaceUrl` | String? | 对方头像 URL（可能为空） |
| `addWording` | String? | 申请附言 |
| `addSource` | String | 添加方式，见 [§2.1](#21-addsource-添加方式) |
| `addTime` | Instant | 申请时间（ISO8601 UTC） |
| `status` | String | `accepted` / `rejected` |
| `handledAt` | Instant? | 处理时间，未处理时为 `null` |

### 2.1 `addSource` 添加方式

|| 值 | 含义 |
|------|------|------|
| `qr_code` | 扫码添加 |
| `search` | 搜索添加 |
| `phone` | 手机号搜索 |
| `nearby` | 附近的人 |
| `card` | 名片分享 |
| `group` | 群聊添加 |

---

## 3. 接口

### 3.1 历史列表 — `GET /friend-application/history`

获取当前用户的全部好友申请历史记录。

|| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `cursor` | Instant | 否 | 分页游标，传上次响应的 `nextCursor` |
| `limit` | int | 否 | 每页条数，默认 100，上限 200 |

**200**

```json
{
  "content": [
    {
      "id": 1,
      "peerUserId": "xyz99abcde",
      "peerNickname": "小明",
      "peerFaceUrl": "https://.../avatar.png",
      "addWording": "我是同学张三推荐来的",
      "addSource": "qr_code",
      "addTime": "2026-05-23T10:00:00Z",
      "status": "accepted",
      "handledAt": "2026-05-23T10:05:00Z"
    }
  ],
  "nextCursor": "2026-05-22T08:00:00Z",
  "hasMore": false
}
```

首次请求不传 `cursor`，服务端从最新记录返回。`hasMore = true` 时需用 `nextCursor` 拉取下一页。

---

### 3.2 同意申请 — `POST /friend-application/accept`

当前用户同意对方的好友申请，同时双向写入历史记录。

|| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `peerUserId` | String | 是 | 对方用户 ID |
| `addTime` | Instant | 是 | 原始申请时间（ISO8601 UTC） |
| `addWording` | String? | 否 | 申请附言 |
| `addSource` | String | 是 | 添加方式，见 [§2.1](#21-addsource-添加方式) |

> 注意：`addSource` 为客户端上报，需与 IM 侧填写的好友申请来源字段一致。

**200** 空 body。

|| HTTP | code | 说明 |
|------|------|------|------|
| 400 | — | 缺少必填字段 |
| 404 | — | `peerUserId` 用户不存在 |

---

### 3.3 删除历史记录 — `DELETE /friend-application/history/{id}`

删除当前用户的一条好友申请历史（仅删本人视角记录，不影响对方列表）。

**Path**

| 参数 | 说明 |
|------|------|
| `id` | 历史记录 ID（来自 `GET /history` 的 `content[].id`） |

**鉴权**：Bearer JWT

**`data` 成功**

```json
{ "ok": true, "id": 1 }
```

| code | HTTP | 说明 |
|------|------|------|
| `RECORD_NOT_FOUND` | 404 | 记录不存在或不属于当前用户 |

> 仅清除本地历史展示，不撤销 IM 好友关系，也不删除对方的历史记录。

---

## 4. UI 建议（新的朋友）

1. 进入「新的朋友」页 → `GET /friend-application/history`，按 `addTime` 降序展示。
2. `status = accepted` → 显示「已添加」按钮（禁用态）；`status = rejected` → 显示「已拒绝」；`handledAt = null` → 显示「接受」按钮。
3. 点击「接受」→ `POST /friend-application/accept`，成功后刷新列表。
4. 列表自动按 `addTime` 聚合：同一 `peerUserId` 的多条记录保留，仅展示最新一条即可。
5. 上拉加载更多：`hasMore = true` 时用 `nextCursor` 请求下一页。
6. 左滑/长按删除：`DELETE /friend-application/history/{id}`，成功后从列表移除。

---

## 5. Flutter 示例

```dart
class FriendApplicationApi {
  FriendApplicationApi(this.dio);
  final Dio dio;

  Future<FriendApplicationPage> loadHistory({
    String? cursor,
    int limit = 100,
  }) async {
    final r = await dio.get('/friend-application/history', queryParameters: {
      if (cursor != null) 'cursor': cursor,
      'limit': limit,
    });
    return FriendApplicationPage.fromJson(r.data);
  }

  Future<void> accept({
    required String peerUserId,
    required DateTime addTime,
    String? addWording,
    required String addSource,
  }) async {
    await dio.post('/friend-application/accept', data: {
      'peerUserId': peerUserId,
      'addTime': addTime.toUtc().toIso8601String(),
      if (addWording != null) 'addWording': addWording,
      'addSource': addSource,
    });
  }

  Future<void> deleteHistory(int id) async {
    await dio.delete('/friend-application/history/$id');
  }
}

class FriendApplicationPage {
  final List<HistoryItem> content;
  final String? nextCursor;
  final bool hasMore;
  // ...
}
```

---

## 6. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-06-07 | 初版：好友申请历史记录（cursor 分页、同意接口、addSource 上报） |
| 2026-06-14 | 新增 `DELETE /friend-application/history/{id}` 删除历史记录 |
