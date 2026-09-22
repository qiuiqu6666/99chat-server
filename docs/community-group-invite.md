# Community 群组：邀请 / 申请加群审批对接文档

> **已废弃 IM SDK 审批通知路径。** 请改用 [group-member-invite.md](./group-member-invite.md)：`POST /group/{id}/members`、审批 REST、TCP/Push/`GROUP_JOIN_NOTIFY` 通知。

> 版本：v1.0（历史文档，仅保留 IM 建群参数参考）
> 适用：99chat-server v0.0.1-SNAPSHOT + 99chat (Flutter) + 腾讯 IM Community
> 范围：仅 Community 群的"邀请 / 申请加群 + 审批 + 邀请结果通知邀请人"链路

---

## 1. 概述

### 1.1 业务目标

1. **所有群成员**（普通成员、群管理员、群主）均可邀请其他用户进群。
2. 邀请进群**必须经群主或群管理员审批**通过后，被邀请人才能正式进群。
3. 非成员通过精确搜索群 ID **申请加群**也**必须经群主或群管理员审批**通过后进群。
4. 审批结果（同意 / 拒绝）由**管理员客户端在审批操作之后，主动通过 C2C 自定义消息**通知邀请人；被邀请人沿用腾讯 IM 默认的群系统通知。

### 1.2 端责任分工

| 能力 | Flutter 客户端 | 99chat-server 后端 | 腾讯 IM |
| --- | --- | --- | --- |
| 建 Community 群 | ✅ 直接调 SDK `createGroup` | — | 落库群组 |
| 配置 `InviteJoinOption` / `ApplyJoinOption` | ✅ 建群入参显式传 | — | 持久化群属性 |
| 发起邀请 | ✅ 调 SDK `inviteUserToGroup` | — | 写入群未决 |
| 发起申请加群 | ✅ 调 SDK `joinGroup` | — | 写入群未决 |
| 拉取未决列表 | ✅ 群主/管理员调 `getGroupApplicationList` | — | 返回未决数据 |
| 同意 / 拒绝审批 | ✅ 调 `accept/refuseGroupApplication` | — | 持久化处理状态 |
| 通知邀请人（C2C 自定义消息） | ✅ 管理员客户端在 accept/refuse 后调 `sendC2CCustomMessage` | — | 投递消息 |
| 接收审批结果 | ✅ 邀请人客户端 `onRecvNewMessage` 拦截解析 | — | 推送消息 |

**99chat-server 本期对此能力链路无任何代码改动**。

---

## 2. 群组配置约束

| 项 | 要求 |
| --- | --- |
| 群类型 | `Community`（社群） |
| GroupId 前缀 | 必须以 `@TGS#_` 开头（自定义 ID 也要带此前缀） |
| `InviteJoinOption` | **必须显式设为 `NeedPermission`** |
| `ApplyJoinOption` | **必须显式设为 `NeedPermission`** |

**警告**：Community 默认 `InviteJoinOption=FreeAccess`、`ApplyJoinOption=FreeAccess`。建群时任一字段未显式传 `NeedPermission`，对应的审批流将完全失效，腾讯 IM 会直接放行邀请 / 申请，**前端 UI 无法补救**。

---

## 3. 时序图

### 3.1 建群（用户端）

```
邀请人 Flutter            腾讯 IM
   |                         |
   |--createGroup            |
   |   groupType=Community   |
   |   groupName=...         |
   |   inviteJoinOption=NeedPermission
   |   applyJoinOption=NeedPermission
   |------------------------>|
   |                         |--创建群 + 落属性
   |<----groupId=@TGS#_xxx---|
```

### 3.2 邀请 → 待审批 → 管理员审批 → 通知邀请人

```
邀请人 Flutter         群主/管理员 Flutter         腾讯 IM
   |                          |                         |
   |--inviteUserToGroup------------------------------>|
   |   (groupId, userList=[inviteeId])                 |
   |                          |                         |--生成 type=2 未决
   |                          |                         |   fromUser=邀请人
   |                          |                         |   toUser=被邀请人
   |                          |--getGroupApplicationList()->|
   |                          |<--[GroupApplication...]----|
   |                          |--acceptGroupApplication 或
   |                          |  refuseGroupApplication->|
   |                          |                         |--同意: 被邀请人入群 + 群系统通知
   |                          |                         |  拒绝: 不入群, 默认无通知
   |                          |--sendC2CCustomMessage---|
   |                          |   toUser=邀请人          |
   |                          |   payload={INVITE_RESULT...}
   |                          |------------------------>|
   |<--onRecvNewMessage(customElem)----------------------|
```

### 3.3 申请加群 → 待审批 → 管理员审批

```
申请人 Flutter         群主/管理员 Flutter         腾讯 IM
   |                          |                         |
   |--joinGroup(groupId)----------------------------->|
   |                          |                         |--生成 type=0 未决
   |                          |                         |   fromUser=申请人
   |                          |                         |   toUser=0
   |                          |--getGroupApplicationList()->|
   |                          |<--[GroupApplication...]----|
   |                          |--accept/refuseGroupApplication->|
   |                          |                         |--同意: 申请人入群 + 系统通知
   |                          |                         |  拒绝: 不入群
```

> 申请加群本期不要求通知申请人额外的自定义消息；腾讯默认会推送群系统通知给申请人。

---

## 4. Flutter 关键代码片段（伪代码）

> 下列示例基于腾讯 IM Flutter SDK；具体方法名以你接入的 `tencent_im_sdk_plugin` 版本为准。

### 4.1 建群

```dart
final res = await timManager.getGroupManager().createGroup(
  groupType: "Community",
  groupName: "<群名>",
  // 可选: groupID: "@TGS#_yourCustomId"
  inviteJoinOption: GroupAddOptTypeEnum.V2TIM_GROUP_ADD_AUTH, // = NeedPermission
  applyJoinOption:  GroupAddOptTypeEnum.V2TIM_GROUP_ADD_AUTH, // = NeedPermission
);
final groupId = res.data; // "@TGS#_xxxxx"
```

### 4.2 邀请

```dart
await timManager.getGroupManager().inviteUserToGroup(
  groupID: groupId,
  userList: [inviteeId],
  // 不传 reason
);
```

### 4.3 群主/管理员审批 + 通知邀请人

```dart
final list = await timManager.getGroupManager().getGroupApplicationList();

for (final app in list.data?.groupApplicationList ?? []) {
  // 仅处理 "邀请待管理员审批" 类型
  if (app.type != GroupApplicationTypeEnum.V2TIM_GROUP_INVITE_APPLICATION_NEED_APPROVED_BY_ADMIN) continue;
  if (app.groupID != currentGroupId) continue;
  if (app.handleStatus != GroupApplicationHandleStatus.V2TIM_GROUP_APPLICATION_HANDLE_STATUS_UNHANDLED) continue;

  // UI 展示: 邀请人=app.fromUser, 被邀请人=app.toUser, 时间=app.addTime

  // (a) 同意
  await timManager.getGroupManager().acceptGroupApplication(application: app, reason: "");
  await _sendInviteResult(
    toUser: app.fromUser,
    groupId: app.groupID,
    invitee: app.toUser,
    result: "ACCEPTED",
  );

  // (b) 或拒绝
  // await timManager.getGroupManager().refuseGroupApplication(application: app, reason: "");
  // await _sendInviteResult(
  //   toUser: app.fromUser,
  //   groupId: app.groupID,
  //   invitee: app.toUser,
  //   result: "REJECTED",
  // );
}

Future<void> _sendInviteResult({
  required String toUser,
  required String groupId,
  required String invitee,
  required String result,
}) async {
  final payload = jsonEncode({
    "type": "INVITE_RESULT",
    "groupId": groupId,
    "invitee": invitee,
    "result": result,
    "operator": myUserId,
    "timestamp": DateTime.now().millisecondsSinceEpoch ~/ 1000,
  });
  final msg = await timManager.getMessageManager().createCustomMessage(data: payload);
  await timManager.getMessageManager().sendMessage(
    id: msg.data!.id!,
    receiver: toUser,
    groupID: null, // C2C
  );
}
```

### 4.4 自定义消息 payload 约定

C2C 自定义消息 `V2TIMCustomElem.data` 为 UTF-8 JSON 字符串：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | ✅ | 固定值 `"INVITE_RESULT"`；邀请人客户端据此识别 |
| `groupId` | string | ✅ | 涉及的群 ID |
| `groupName` | string |  | 可选，便于通知文案展示 |
| `invitee` | string | ✅ | 被邀请人 userId |
| `result` | string | ✅ | `"ACCEPTED"` 或 `"REJECTED"` |
| `operator` | string | ✅ | 执行审批的管理员 userId |
| `timestamp` | number | ✅ | 审批操作的 Unix 秒级时间戳 |

**邀请人端接收**：

```dart
timManager.addAdvancedMsgListener(listener: V2TimAdvancedMsgListener(
  onRecvNewMessage: (V2TimMessage msg) {
    if (msg.elemType != MessageElemType.V2TIM_ELEM_TYPE_CUSTOM) return;
    final data = msg.customElem?.data;
    if (data == null || data.isEmpty) return;
    try {
      final map = jsonDecode(data) as Map<String, dynamic>;
      if (map["type"] == "INVITE_RESULT") {
        // 解析后弹本地通知 / 写入消息中心
      }
    } catch (_) {}
  },
));
```

---

## 5. 边界与已知限制

1. **未决数量上限**：单用户群未决列表最多保存 50 条，超出会丢弃最早记录。
2. **状态一致性**：`acceptGroupApplication` / `refuseGroupApplication` 仅对 `UNHANDLED` 状态生效；其他管理员已处理过的同一条未决再处理会返回错误，UI 需展示「已处理 / 已被他人处理」状态并刷新列表。
3. **拒绝通知**：腾讯 IM 默认在邀请被拒绝时**不**给被邀请人发系统通知（仅同意时入群附带通知）。
4. **管理员链路依赖**：「通知邀请人」依赖管理员客户端在线并通过本应用 SDK 流程执行 accept/refuse。若管理员在腾讯控制台或第三方端处理未决，**不会**触发该自定义消息（v2 兜底见 §6）。
5. **权限前置**：管理员必须先在该群中具备 `Owner` 或 `Admin` 角色，否则审批 SDK 调用会失败。

---

## 6. 与后端 99chat-server 的关系

本期 99chat-server **完全不参与**本能力链路。后端**不做**以下 4 件事：

1. 不接腾讯 IM webhook（`Before Inviting a User to a Group` / `After a User Joins a Group` 等均不订阅）
2. 不调 `modify_group_base_info` REST API
3. 不调 `add_group_member` REST API
4. 不调 `sendmsg` / `batchsendmsg` REST API

`ImAdminClient` 现有方法（`accountImport` / `profileUpdate` / `getRoleInGroup`）与本能力**无依赖关系**，保持不变。

### v2 占位（不在本期范围）

后续若需求扩展为「即使管理员从控制台处理审批，也要通知邀请人」，可在 v2 引入：

- 99chat-server 暴露公网 webhook 入口
- 订阅腾讯「After Group Application Processing」回调
- 在 `ImAdminClient` 增加 `sendC2CCustomMessage` 方法
- 后端兜底向邀请人下发同样格式的 `INVITE_RESULT` 自定义消息

---

## 7. UAT 测试要点

1. **群属性核对**：在腾讯 IM 控制台查询新建的 Community 群，确认 `InviteJoinOption=NeedPermission`、`ApplyJoinOption=NeedPermission`。
2. **非好友邀请**：普通成员对一个非群成员调用 `inviteUserToGroup`，被邀请人**不**直接入群。
3. **未决可见性**：群主/群管理员调用 `getGroupApplicationList`，能取到记录，且 `fromUser=邀请人 userId`、`toUser=被邀请人 userId`、`type=V2TIM_GROUP_INVITE_APPLICATION_NEED_APPROVED_BY_ADMIN`。
4. **同意路径**：管理员 accept 后 —— 被邀请人入群、邀请人收到 `type=INVITE_RESULT, result=ACCEPTED` 的 C2C 自定义消息。
5. **拒绝路径**：管理员 refuse 后 —— 被邀请人**不**入群、邀请人收到 `type=INVITE_RESULT, result=REJECTED` 的 C2C 自定义消息。
6. **权限校验**：普通成员尝试 accept/refuse 未决，SDK 返回权限错误。
7. **状态机校验**：对已处理过的未决再次 accept，SDK 报错；UI 展示「已处理」并刷新列表。
