# 名片 / 二维码 / 群 — 添加好友隐私联调文档

> 状态：**已完成**（2026-05-23）  
> 关联客户端：99chat Flutter  
> Base URL：`http://47.239.60.107:8081`

---

## 1. 背景

腾讯 IM `addFriend` 不区分添加渠道，无法在 IM 层按 `allowViaCard` 拦截。客户端需在加好友前查询**目标用户**的隐私开关。

| 渠道 | 字段 | 查询方式 |
|------|------|----------|
| 名片 | `allowViaCard` | **GET /users/{userId}/privacy** 或 **POST /users/add-friend/check** |
| 二维码 | `allowViaQrCode` | 同上，`channel=qr` |
| 群资料 | `allowViaGroup` | 同上，`channel=group` |
| 手机号 | `allowViaPhone` | 仍用 **POST /users/search** |
| UID | `allowViaUid` | 仍用 **POST /users/search** |

---

## 2. 接口

### 2.1 GET /users/{userId}/privacy（P0）

查询**他人**的 5 项开关（与 `GET /me/privacy` 结构一致）。

```http
GET /users/{userId}/privacy
Authorization: Bearer <token>
```

**200**

```json
{
  "allowViaQrCode": true,
  "allowViaCard": false,
  "allowViaGroup": true,
  "allowViaPhone": true,
  "allowViaUid": true
}
```

| HTTP | code | 说明 |
|------|------|------|
| 401 | — | 未登录 |
| 404 | `USER_NOT_FOUND` | 用户不存在或已禁用（防枚举） |

---

### 2.2 POST /users/add-friend/check（P1）

按渠道预检是否允许添加。

```http
POST /users/add-friend/check
Authorization: Bearer <token>
Content-Type: application/json

{
  "targetUserId": "a1b2c3d4e5",
  "channel": "card"
}
```

`channel`：`card` | `qr` | `group`（小写）

**允许 200**

```json
{
  "allowed": true,
  "reason": null
}
```

**不允许 200**

```json
{
  "allowed": false,
  "reason": "ADD_FRIEND_VIA_CARD_DISABLED"
}
```

| reason | 渠道 |
|--------|------|
| `ADD_FRIEND_VIA_CARD_DISABLED` | 名片 |
| `ADD_FRIEND_VIA_QR_DISABLED` | 二维码 |
| `ADD_FRIEND_VIA_GROUP_DISABLED` | 群 |

| HTTP | code | 说明 |
|------|------|------|
| 400 | `INVALID_INPUT` | channel 非法或缺字段 |
| 404 | `USER_NOT_FOUND` | 目标用户不存在 |

---

## 3. 客户端对接（Flutter）

1. `UserApi.fetchUserPrivacy(userId)` → `GET /users/{userId}/privacy`
2. 点击名片 / 添加页 / 点「添加」：
   - 优先远端 `allowViaCard`
   - 其次名片 JSON 内嵌快照
   - **未知则拒绝**（fail-closed）
3. 可选：`POST /users/add-friend/check` 统一预检 card / qr / group
4. 发本人名片：`GET /me/privacy` 写入 `allowViaCard` 快照（非唯一依据）

---

## 4. 验收用例

| # | 步骤 | 期望 |
|---|------|------|
| 1 | B 关闭名片 `PUT /me/privacy` | `GET /users/B/privacy` → `allowViaCard: false` |
| 2 | A 调 `GET /users/B/privacy` | 200，`allowViaCard: false` |
| 3 | A 点 B 名片 | App 不可添加 |
| 4 | B 重新开启名片 | `GET` 为 true，可添加 |
| 5 | 不存在 userId | 404 |
| 6 | 无 Token | 401 |
| 7 | `POST .../check` channel=card，B 已关 | `allowed: false` |

---

## 5. curl 示例

```bash
TOKEN="<jwt>"
B="target_user_id"

curl -sS "http://127.0.0.1:8081/users/${B}/privacy" \
  -H "Authorization: Bearer ${TOKEN}"

curl -sS -X POST "http://127.0.0.1:8081/users/add-friend/check" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"targetUserId\":\"${B}\",\"channel\":\"card\"}"
```

---

## 6. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05-22 | 待办初稿 |
| 2026-05-23 | P0/P1 实现完成 |
