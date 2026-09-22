# 建群 — 客户端对接与防重复指南

> 版本：v1.0（2026-06-19）  
> 适用：Flutter 客户端  
> 关联：[group-governance-client.md](./group-governance-client.md) · [group-avatar.md](./group-avatar.md) · [group-profile-client.md](./group-profile-client.md)

---

## 1. 背景：为什么会出现「建一次群、列表里多个群」

历史问题链路（**服务端已按方案 5 修复：建群投影只走 REST，IM 回调不再写 `group_profile`**）：

1. 客户端调 `POST /group`
2. 腾讯 IM **已建群成功**
3. ~~IM 回调与 `POST /group` 并发写 `group_profile`~~ → 曾导致 500 / 客户端重试 / 多群
4. 客户端以为失败，**再次点击创建** → 又调一次 `POST /group` → IM 里再建一个新群
5. 会话/群列表出现 **2 个或多个同名群**（`groupId` 不同）

当前：`CallbackAfterCreateGroup` 仅做看板统计与建群限额计数；**`group_profile` / 成员投影 exclusively 由 `POST /group` → `syncFullGroupFromIm` 写入**。客户端仍须防连点、双通道建群、列表未去重。

---

## 2. 核心原则（必须遵守）

| # | 原则 |
|---|------|
| 1 | **建群只调 `POST /group`**，禁止再调 IM SDK `createGroup` |
| 2 | 建群进行中 **禁用创建按钮**，同一页面只允许 **一个 in-flight 请求** |
| 3 | 本地群列表、会话列表 **按 `groupId` 去重** |
| 4 | 收到 **5xx / 网络超时** 时 **不要无脑重试**；先查群是否已创建 |
| 5 | 建群成功后 **只插入一条** 本地记录；不要「REST 插一条 + IM SDK 回调再插一条」 |

---

## 3. 推荐建群流程

```
选好友 / 填群名
    ↓
（可选）POST /group/avatar/upload  → 拿到 previewUrl / thumbUrl
    ↓
POST /group  （仅此一步创建 IM 群 + 本地投影）
    ↓
201 + GroupProfileView（含 groupId）
    ↓
写入本地群列表（upsert by groupId）→ 进入群聊
```

**不要**：

```
❌ timManager.createGroup(...)        // 旧 SDK 路径，与 REST 双建群
❌ POST /group 失败 → 立即再 POST      // 可能 IM 已成功
❌ 201 成功 + onGroupCreated 再 add  // 本地重复
```

---

## 4. `POST /group` 接口

鉴权：`Authorization: Bearer <JWT>`

### 4.1 请求

```http
POST /group
Content-Type: application/json
```

```json
{
  "groupType": "Public",
  "groupName": "产品讨论群",
  "avatarUrl": "https://cdn.../group-avatar/.../thumb.jpg",
  "memberUserIds": ["friend01", "friend02"],
  "joinOptions": {
    "applyJoinOption": "need_permission",
    "inviteJoinOption": "need_permission"
  },
  "introduction": "可选群简介"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `groupType` | ✅ | `Public` / `Meeting` / `Community` / `Work` |
| `groupName` | ✅ | 群名称 |
| `memberUserIds` | 否 | 初始成员（**不含创建者**）；须双向好友；最多 100 |
| `avatarUrl` | 否 | 建议用 `POST /group/avatar/upload` 返回的 **thumbUrl**；**省略时使用默认群头像**（见下） |

**默认群头像**（未传 `avatarUrl` 或传空时，服务端自动使用）：

`https://99chat.oss-cn-hongkong.aliyuncs.com/moren/qun.png`

客户端建群时可不传 `avatarUrl`；响应 `201` 的 `avatarUrl` 会返回该默认地址。
| `joinOptions` | 否 | 省略时默认 `applyJoinOption` / `inviteJoinOption` 均为 `need_permission` |
| `introduction` | 否 | 群简介 |

### 4.2 成功响应 `201`

结构与 `GET /group/{groupId}` 相同（`GroupProfileView`），至少包含：

| 字段 | 说明 |
|------|------|
| `groupId` | 群 ID，如 `@TGS#2EPAHMM5CR` |
| `groupName` | 群名称 |
| `groupType` | 群类型 |
| `avatarUrl` | 列表用小图 |
| `myRole` | 创建者一般为 `400`（群主） |
| `memberCount` | 成员数（含创建者 + 初始成员） |

### 4.3 错误码

| HTTP | code | 客户端处理 |
|------|------|------------|
| 400 | `INVALID_INPUT` | 提示参数错误 |
| 400 | `BATCH_TOO_LARGE` | 初始成员超过 100 |
| 403 | `NOT_FRIEND` | 初始成员非双向好友 |
| 403 | `GROUP_JOIN_LIMIT_EXCEEDED` / `GROUP_CREATE_LIMIT_COMMUNITY` | 加入或社群创建超限，见 [group-create-limit.md](./group-create-limit.md)；响应含 `overLimitUsers` |
| 502 | `IM_REST_ERROR` | IM 侧失败，**可**在用户确认后重试（见 §6） |
| 503 | `IM_NOT_CONFIGURED` | 环境未配置 IM |

---

## 5. 客户端实现要点

### 5.1 单飞（single-flight）锁

同一建群页 / 同一用户操作周期内，只允许一个建群请求：

```dart
class GroupCreateController {
  bool _creating = false;

  Future<GroupProfileView?> create(CreateGroupParams params) async {
    if (_creating) return null;
    _creating = true;
    try {
      return await _api.postGroup(params);
    } finally {
      _creating = false;
    }
  }
}
```

UI：`创建` 按钮在 `_creating == true` 时 **disabled + loading**。

### 5.2 列表 upsert（按 groupId）

```dart
void upsertGroupToLocalList(GroupProfileView group) {
  final idx = localGroups.indexWhere((g) => g.groupId == group.groupId);
  if (idx >= 0) {
    localGroups[idx] = group;
  } else {
    localGroups.insert(0, group);
  }
  notifyListeners();
}
```

**禁止** `localGroups.add(group)` 而不检查是否已存在。

### 5.3 移除 IM SDK 建群

| 旧代码 | 新代码 |
|--------|--------|
| `timManager.getGroupManager().createGroup(...)` | `POST /group` |
| `getJoinedGroupList` 作为展示源 | `GET /me/groups`（默认 `limit=100`，上限 200；日常**不要**带 `refresh=true`） |

> 群通知 / 入群审批请走 `GET /me/join-applications`，**禁止**对 `/me/groups` 结果按群扇出 `GET /group/{id}/join-applications`（会打满连接池、拖慢整页）。详见 `group-notice-client.md`。

IM SDK 仍用于：**进群后收消息、发消息**；不用于建群/退群/踢人等治理操作。

### 5.4 建群成功后的导航

```dart
final group = await createController.create(params);
if (group == null) return;

upsertGroupToLocalList(group);
Navigator.pushReplacement(context, GroupChatRoute(groupId: group.groupId));
```

不要依赖 IM SDK 的「群创建回调」再往本地列表插一条。

---

## 6. 失败与超时：如何安全重试

### 6.1 原则

`POST /group` 返回 **5xx 或客户端超时**，不代表 IM 一定没建群。**盲目再 POST 会多建群。**

### 6.2 推荐恢复流程

```
POST /group 失败/超时
    ↓
GET /me/groups?refresh=true&limit=20
    ↓
在列表中查找：groupName + 创建时间接近 + myRole=400(群主)
    ↓
找到 → 视为已成功，upsert 并进入该群
找不到 → 提示用户「创建失败，请重试」，且仍保持单飞锁
```

伪代码：

```dart
Future<GroupProfileView?> createWithRecovery(CreateGroupParams params) async {
  try {
    return await _api.postGroup(params);
  } on DioException catch (e) {
    if (!_shouldAttemptRecovery(e)) rethrow;
    final recovered = await _tryRecoverRecentlyCreatedGroup(params.groupName);
    if (recovered != null) return recovered;
    rethrow;
  }
}

bool _shouldAttemptRecovery(DioException e) {
  return e.type == DioExceptionType.receiveTimeout
      || e.type == DioExceptionType.connectionTimeout
      || (e.response?.statusCode ?? 0) >= 500;
}
```

### 6.3 不要做的事

| ❌ | 原因 |
|----|------|
| 失败后自动连发 3 次 `POST /group` | 每次成功都会新建一个 IM 群 |
| 失败后改调 `createGroup` SDK | 双通道，必然多群 |
| 不查列表直接让用户再点一次 | 用户连点 + 无单飞锁 = 多群 |

---

## 7. TCP / Push 与本地列表

建群成功后：

- **创建者**：主要依赖 `POST /group` 的 **201 响应** 写入本地列表
- **被邀请的初始成员**：可能收到 TCP `group_changed` / `member_added`（`detail` 含完整群字段），收到后同样 **upsert by groupId**

```dart
void onGroupChanged(Map<String, dynamic> event) {
  if (event['action'] == 'member_added') {
    final detail = event['detail'] as Map<String, dynamic>?;
    if (detail != null && detail['groupId'] != null) {
      upsertGroupFromDetail(detail); // 内部仍按 groupId upsert
    }
  }
}
```

---

## 8. 头像上传（可选，在建群前）

见 [group-avatar.md](./group-avatar.md)。

```dart
// 1. 上传
final upload = await api.uploadGroupAvatar(localFile);
// 2. 建群时传 thumbUrl
await api.postGroup(groupName: name, avatarUrl: upload.thumbUrl, ...);
```

---

## 9. 自测清单

| # | 场景 | 期望 |
|---|------|------|
| 1 | 正常建群 | 列表仅 **1** 个新群；`groupId` 唯一 |
| 2 | 快速连点「创建」 | 仅 **1** 次请求；按钮 disabled |
| 3 | 模拟 502 后 recovery | 不重复 POST；refresh 后进入已有群 |
| 4 | 带 2 名初始好友建群 | 创建者 1 群；被邀请者各 1 条（同 `groupId`） |
| 5 | 代码库无 `createGroup` SDK 调用 | grep 无 `createGroup` / `V2TIM_GROUP_CREATE` |
| 6 | 本地列表 upsert | 同一 `groupId` 不会出现两行 |

---

## 10. 迁移对照

| 原 IM SDK | 现 REST |
|-----------|---------|
| `createGroup` | `POST /group` |
| `getJoinedGroupList`（展示） | `GET /me/groups` |
| `getGroupsInfo`（展示） | `GET /group/{groupId}` |

完整治理接口见 [group-governance-client.md](./group-governance-client.md) §10。

---

## 11. FAQ

**Q：201 成功了，列表里还是两个？**  
A：检查是否 IM SDK 建群未删干净，或本地 `add` 未 upsert；用 `groupId` 排查是否两个不同 ID（双 POST）还是同一 ID 重复行（本地 bug）。

**Q：Community 群 `groupId` 要带 `@TGS#_` 前缀吗？**  
A：**不要**客户端指定 `groupId`；一律由 `POST /group` / IM 分配。历史文档中 SDK 自定义 ID 示例已废弃。

**Q：建群数量上限？**  
A：见 [group-create-limit.md](./group-create-limit.md)；建群前可 `GET /me/group-create-quota?groupType=Public` 展示剩余额度。
