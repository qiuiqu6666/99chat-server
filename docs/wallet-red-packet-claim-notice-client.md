# 群红包领取通知 — 客户端对接文档

> 版本：v1.1  
> 适用：Flutter 客户端  
> 关联：[wallet-client.md](./wallet-client.md) §9 红包

---

## 1. 目标行为

| 时间 | 场景 | 期望 |
|------|------|------|
| 12:00 | 发包人发红包 | 红包卡片消息（客户端发 `wallet_red_packet`，已有） |
| 13:00 | 他人发普通群消息 | 正常群消息 |
| 14:00 | 有人领取 | **服务端**发一条 IM 群定向消息，自然落在 13:00 消息**下方** |
| 展示 | 领取提示 | **居中灰色系统提示**，无头像、无气泡 |
| 可见性 | 仅发包人 | 腾讯 IM `To_Account=[发包人]`，其他成员**收不到** |
| 单聊红包 | — | **不发**领取提示（本期不做） |

**核心原则**：领取提示是**真实 IM 群消息**，不是客户端根据 TCP 本地插入的假消息。

---

## 2. 通道分工（仅 IM）

```
有人调用 POST /wallet/red-packet/{id}/claim 成功
        │
        └─► IM 群定向自定义消息（仅发包人收到）
                  businessID = red_packet_claim_notice
                  → 渲染居中灰字「张三领取了你的红包」
```

| 通道 | 用途 | 谁处理 |
|------|------|--------|
| IM `red_packet_claim_notice` | 时间线上的灰字提示 | 消息列表渲染 |
| REST claim 响应 / 详情 / claim-state | 红包卡片 UI 状态 | 打开会话或详情时刷新 |

> **已废弃**（请勿再监听）：TCP `red_packet_changed`（含 `card_refresh` / `expired` / `claimed` / `completed`）。服务端已停发。

---

## 3. IM 自定义消息

### 3.1 识别方式

解析 `TIMCustomElem.Data` JSON：

```dart
final data = jsonDecode(customElem.data);
if (data['businessID'] == 'red_packet_claim_notice') {
  // 渲染系统灰字，不要当普通气泡
}
```

亦可用 `customType` 兼容字段不存在的情况——**以 `businessID` 为准**。

### 3.2 Payload 字段

```json
{
  "businessID": "red_packet_claim_notice",
  "version": 1,
  "noticeId": "rpcn_10_xyz99abcde_1718700000",
  "packetId": "10",
  "senderUserId": "abc12def34",
  "groupId": "@TGS#xxx",
  "claimerUserId": "xyz99abcde",
  "claimerName": "张三",
  "showFinishedSuffix": false,
  "text": "张三领取了你的红包"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `businessID` | String | 是 | 固定 `red_packet_claim_notice` |
| `version` | int | 是 | 当前 `1` |
| `noticeId` | String | 是 | 幂等键，客户端可用于去重 |
| `packetId` | String | 是 | 红包订单 id，关联原卡片 `orderId` |
| `senderUserId` | String | 是 | 发包人 userId |
| `groupId` | String | 是 | 群 ID |
| `claimerUserId` | String | 是 | 领取人 userId |
| `claimerName` | String | 是 | 展示名（可能为空，见 §3.4） |
| `showFinishedSuffix` | bool | 否 | `true` = 最后一次领完 |
| `text` | String | 推荐 | **优先展示**服务端拼好的完整文案 |

### 3.3 文案模板（服务端已拼进 `text`）

| 场景 | `showFinishedSuffix` | `text` 示例 |
|------|------------------------|-------------|
| 普通领取 | `false` | `张三领取了你的红包` |
| 最后一次领完 | `true` | `张三领取了你的红包，你的红包已被领完` |

客户端：**优先显示 `text`**；若缺失再本地拼：

```dart
String displayText(Map<String, dynamic> data) {
  final text = data['text'] as String?;
  if (text != null && text.isNotEmpty) return text;
  final name = (data['claimerName'] as String?)?.trim();
  final who = (name == null || name.isEmpty) ? '好友' : name;
  final finished = data['showFinishedSuffix'] == true;
  return finished
      ? '$who领取了你的红包，你的红包已被领完'
      : '$who领取了你的红包';
}
```

### 3.4 IM 消息特征

| 项 | 值 |
|----|-----|
| 消息类型 | `TIMCustomElem` |
| `From_Account` | 发包人 userId（与红包卡片发送者一致） |
| 接收范围 | 群定向 `To_Account=[发包人]`，**仅发包人 SDK 会收到** |
| 时间戳 | 领取成功时刻（自然排在当时段消息之后） |
| `MsgContent.Desc` | 与 `text` 相同（会话摘要用） |

### 3.5 UI 渲染要求

```
┌─────────────────────────────────────┐
│           张三领取了你的红包          │  ← 居中、灰色、小字号
└─────────────────────────────────────┘
```

| 要求 | 说明 |
|------|------|
| 布局 | 居中，全宽，**无左右气泡** |
| 样式 | 灰色系统提示文字（与微信「xxx领取了红包」一致） |
| 头像 | **不显示** |
| 点击 | 可选：跳转红包详情 `GET /wallet/red-packet/{packetId}` |
| 发送者头像/昵称 | **不要**显示成「我发的消息气泡」 |

### 3.6 去重

同一条消息可能因 IM 重推出现多次，建议：

```dart
// 以 noticeId 为键，已渲染则跳过
final seen = <String>{};
if (!seen.add(data['noticeId'])) return;
```

### 3.7 谁需要处理这条消息？

| 角色 | 是否收到 IM | 客户端动作 |
|------|-------------|------------|
| 发包人 | ✅ 收到 | 渲染灰字 |
| 其他群成员 | ❌ 收不到 | **无需**过滤逻辑 |
| 领取人本人 | ❌ 收不到 | — |

防御性判断（可选）：

```dart
if (data['senderUserId'] != currentUserId) {
  return; // 不应出现，定向消息仅发包人下行
}
```

---

## 4. TCP（已废弃）

服务端**不再**发送 `red_packet_changed`（`card_refresh` / `expired`）。客户端应删除相关监听与 Toast；卡片状态改走 REST。

---

## 5. 与原红包卡片的关系

### 5.1 发红包（不变）

客户端在 `POST /wallet/red-packet/send` 成功后自行发 IM：

```json
{
  "customType": "wallet_red_packet",
  "orderId": 10,
  "packetType": "LUCKY_GROUP",
  "status": "ACTIVE",
  "senderUserId": "abc12def34",
  "greeting": "恭喜发财"
}
```

### 5.2 领取（他人调用）

任意群成员调用：

```http
POST /wallet/red-packet/{orderId}/claim
Authorization: Bearer <JWT>
```

成功后：

- 领取人：拿到金额，自己的 UI 更新为「已领取」
- 发包人：收到 IM 灰字；卡片状态靠 REST 刷新
- 其他成员：仅可通过 `claim-state` 或打开详情看到红包状态变化

### 5.3 卡片状态刷新

| 方式 | 场景 |
|------|------|
| `GET /wallet/red-packet/{id}/claim-state` | 进入会话 / 下拉刷新 |
| `GET /wallet/red-packet/{id}` | 打开详情页 |
| 领取接口响应 | 领取人本地更新 |

---

## 6. 完整时间线示例

```
12:00  [红包卡片]  customType: wallet_red_packet  orderId=10  status=ACTIVE
       （客户端发送）

13:00  [普通消息]  李四：在吗
       （正常群聊）

14:00  [系统灰字]  张三领取了你的红包
       （服务端 IM 定向，仅发包人可见）

14:30  最后一份被领完

14:30  [系统灰字]  王五领取了你的红包，你的红包已被领完
       （showFinishedSuffix=true，一条消息合并领完文案）
```

---

## 7. 适用范围

| 红包类型 | 领取通知 IM |
|----------|-------------|
| `NORMAL_GROUP` 群普通 | ✅ |
| `LUCKY_GROUP` 群拼手气 | ✅ |
| `EXCLUSIVE` 专属 | ❌ |
| `NORMAL_C2C` 单聊 | ❌ |

---

## 8. 迁移清单（客户端）

- [ ] 解析 `businessID == red_packet_claim_notice`，渲染居中灰字
- [ ] **删除**根据 TCP `claimed`/`completed`/`card_refresh`/`expired` 插入本地消息或 Toast 的逻辑
- [ ] **删除**对 `red_packet_changed` 的 TCP 监听
- [ ] 卡片状态改用 claim 响应 / `claim-state` / 详情 REST
- [ ] 用 `noticeId` 做 IM 消息去重
- [ ] 灰字消息不参与普通气泡排版（无头像、无左右对齐）
- [ ] 离线：靠 IM 漫游拉历史即可看到灰字（定向消息会进发包人漫游）
- [ ] 发包人自己领自己的群红包：同样会收到灰字「你领取了你的红包」— 可按产品决定是否特殊文案（服务端当前用领取人昵称）

---

## 9. 常见问题

**Q：其他群成员会不会看到灰字？**  
A：不会。服务端使用群定向 `To_Account=[发包人]`，其他成员 IM SDK 收不到这条消息。

**Q：还要不要监听 TCP 来显示「xxx领取了」或刷卡片？**  
A：不要。文案只来自 IM；卡片状态走 REST。服务端已停发红包 TCP。

**Q：历史红包改造前的领取会有灰字吗？**  
A：V1 不补发。仅改造后新产生的领取有 IM 通知。

**Q：灰字消息要不要触发通知栏 Push？**  
A：可能随 IM 回调触发 Push（仅发包人）。可在 Push 处理里对 `businessID=red_packet_claim_notice` 静默或合并策略（产品自定）。

---

## 10. 关联接口

| 接口 | 说明 |
|------|------|
| `POST /wallet/red-packet/send` | 发红包 |
| `POST /wallet/red-packet/{id}/claim` | 领群红包（触发本通知） |
| `GET /wallet/red-packet/{id}/claim-state` | 轻量状态（气泡用） |
| `GET /wallet/red-packet/{id}` | 详情 + 领取列表 |

完整钱包文档：[wallet-client.md](./wallet-client.md)
