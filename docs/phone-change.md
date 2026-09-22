# 修改手机号码接口文档

> 版本：v1.0（2026-05-22）
> 适用：99chat-server + 99chat (Flutter)
> 范围：已登录用户换绑手机号（旧号验证 + 新号验证）

---

## 1. 概述

换绑手机号需 **4 步**完成，全程需 JWT 登录态：

| 步骤 | 接口 | 说明 |
| --- | --- | --- |
| 1 | `POST /me/phone/change/start` | 向**当前绑定手机号**发送验证码 |
| 2 | `POST /me/phone/change/verify-old` | 校验旧号验证码 |
| 3 | `POST /me/phone/change/send-new` | 填写新手机号并发送验证码 |
| 4 | `POST /me/phone/change/confirm` | 校验新号验证码并完成换绑 |

**副作用**：

- 换绑成功后 `users.phone` / `phone_country` 更新
- **所有信任设备清空**（其他设备下次密码登录需重新短信验证）
- JWT **无需重新获取**（token 内只有 `userId`）
- 不写回腾讯 IM

**会话**：`changeId` 有效期 **15 分钟**（`chat99.phone-change.session-ttl-seconds`），超时需从 step 1 重新开始。

---

## 2. 通用规范

| 项 | 值 |
| --- | --- |
| Base URL（开发环境） | `http://47.239.60.107:8081` |
| 鉴权 | `Authorization: Bearer <JWT>` |
| Content-Type | `application/json` |
| 失败响应体 | `{"code": "<错误码>", "message": "<错误码或描述>"}` |

---

## 3. 接口详情

### 3.1 POST /me/phone/change/start

向当前绑定手机号发送验证码。

**请求**

```http
POST /me/phone/change/start HTTP/1.1
Authorization: Bearer <jwt>
```

Body 可为空 `{}` 或不传 body。

**成功响应（200）**

```json
{
  "changeId": "550e8400-e29b-41d4-a716-446655440000",
  "phoneMasked": "+86188****8899",
  "expiresIn": 900
}
```

| 字段 | 说明 |
| --- | --- |
| `changeId` | 换绑会话 ID，后续 3 步必传 |
| `phoneMasked` | 当前绑定手机号脱敏展示 |
| `expiresIn` | 会话剩余秒数 |

---

### 3.2 POST /me/phone/change/verify-old

校验旧手机号验证码。

**请求**

```json
{
  "changeId": "550e8400-e29b-41d4-a716-446655440000",
  "smsCode": "123456"
}
```

**成功响应（200）**

```json
{
  "changeId": "550e8400-e29b-41d4-a716-446655440000",
  "expiresIn": 780
}
```

**错误码**

| HTTP | code | 说明 |
| --- | --- | --- |
| 410 | `SMS_CODE_INVALID` | 旧号验证码错误或过期 |
| 410 | `CHANGE_SESSION_EXPIRED` | changeId 过期或步骤顺序错误 |
| 403 | `CHANGE_SESSION_FORBIDDEN` | changeId 不属于当前用户 |

---

### 3.3 POST /me/phone/change/send-new

向新手机号发送验证码。

**请求**

```json
{
  "changeId": "550e8400-e29b-41d4-a716-446655440000",
  "newPhone": "+8613800002222",
  "phoneCountry": "CN"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `changeId` | ✅ | step 1 返回的会话 ID |
| `newPhone` | ✅ | E.164（`+86138...`）或纯数字本地号 |
| `phoneCountry` | 本地号时可选，默认 `CN` | ISO 3166-1 alpha-2 |

**成功响应（200）**

```json
{
  "phoneMasked": "+86138****2222",
  "expiresIn": 720
}
```

**错误码**

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | `INVALID_PHONE` | 新手机号格式非法 |
| 400 | `SAME_PHONE` | 新号与当前绑定号相同 |
| 409 | `PHONE_EXISTS` | 新号已被其他账号占用 |
| 410 | `CHANGE_SESSION_EXPIRED` | 未完成旧号验证或会话过期 |
| 429 | `RATE_LIMITED` | 短信频控 |

---

### 3.4 POST /me/phone/change/confirm

校验新号验证码并完成换绑。

**请求**

```json
{
  "changeId": "550e8400-e29b-41d4-a716-446655440000",
  "smsCode": "654321"
}
```

**成功响应（200）**

```json
{
  "phone": "+8613800002222",
  "phoneMasked": "+86138****2222"
}
```

换绑完成后建议客户端：

1. 再调 `GET /me` 刷新本地用户信息
2. 提示用户「其他设备需重新验证登录」

**错误码**

| HTTP | code | 说明 |
| --- | --- | --- |
| 410 | `SMS_CODE_INVALID` | 新号验证码错误或过期 |
| 410 | `CHANGE_SESSION_EXPIRED` | 会话过期或未发送新号验证码 |

---

## 4. 完整流程时序

```
Flutter                  99chat-server              短信 / Redis
   |                          |                          |
   |--POST .../start--------->|                          |
   |                          |--SMS 旧号---------------->|
   |                          |--SET phone:change:{id}--->|
   |<--{changeId,phoneMasked}-|                          |
   |                          |                          |
   |--POST .../verify-old---->|                          |
   |                          |--校验 sms:change_old:*--->|
   |                          |--stage=OLD_VERIFIED------>|
   |<--{changeId,expiresIn}---|                          |
   |                          |                          |
   |--POST .../send-new------->|                          |
   |                          |--校验新号未占用-----------> DB
   |                          |--SMS 新号---------------->|
   |                          |--stage=AWAIT_NEW-------->|
   |<--{phoneMasked}-----------|                          |
   |                          |                          |
   |--POST .../confirm------->|                          |
   |                          |--校验 sms:change_new:*--->|
   |                          |--UPDATE users.phone-----> DB
   |                          |--clearAllTrusted---------> DB
   |<--{phone,phoneMasked}----|                          |
```

---

## 5. Flutter 伪代码

```dart
Future<void> changePhone(String oldCode, String newPhone, String newCode) async {
  final headers = Options(headers: {"Authorization": "Bearer $jwt"});

  final start = await dio.post("$base/me/phone/change/start", options: headers);
  final changeId = start.data["changeId"] as String;

  await dio.post("$base/me/phone/change/verify-old",
    data: {"changeId": changeId, "smsCode": oldCode},
    options: headers);

  await dio.post("$base/me/phone/change/send-new",
    data: {"changeId": changeId, "newPhone": newPhone},
    options: headers);

  final done = await dio.post("$base/me/phone/change/confirm",
    data: {"changeId": changeId, "smsCode": newCode},
    options: headers);

  await dio.get("$base/me", options: headers);
  showSnack("手机号已更换为 ${done.data['phoneMasked']}");
}
```

**错误处理建议**

```dart
on DioException catch (e) {
  final code = e.response?.data?["code"];
  switch (code) {
    case "SMS_CODE_INVALID":       showSnack("验证码错误或已过期"); break;
    case "CHANGE_SESSION_EXPIRED": showSnack("操作超时，请重新开始"); break;
    case "PHONE_EXISTS":           showSnack("该手机号已被注册"); break;
    case "SAME_PHONE":             showSnack("新手机号不能与当前号码相同"); break;
    case "RATE_LIMITED":           showSnack("发送过于频繁，请稍后再试"); break;
    default:                       showSnack("操作失败");
  }
}
```

---

## 6. 错误码汇总

| code | HTTP | 说明 |
| --- | --- | --- |
| `SMS_CODE_INVALID` | 410 | 验证码错误或过期 |
| `CHANGE_SESSION_EXPIRED` | 410 | changeId 过期或步骤顺序错误 |
| `CHANGE_SESSION_FORBIDDEN` | 403 | changeId 不属于当前用户 |
| `INVALID_PHONE` | 400 | 手机号格式非法 |
| `SAME_PHONE` | 400 | 新号与当前号相同 |
| `PHONE_EXISTS` | 409 | 新号已被占用 |
| `RATE_LIMITED` | 429 | 短信频控 |
| `USER_NOT_FOUND` | 404 | 用户不存在 |
| `ACCOUNT_DISABLED` | 403 | 账号已禁用 |

---

## 7. 已知限制

1. **无换绑冷却**：本期未限制换绑频率；如需 30 天冷却可加 `last_phone_changed_at` 字段。
2. **无需登录密码**：本期仅 JWT + 双短信验证；如需三因子可后续加 `password` 字段。
3. **短信场景独立**：换绑短信不走公开 `POST /sms/send`，由专用接口内部发送，避免未授权发码。
