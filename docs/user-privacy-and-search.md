# 用户隐私设置 + 用户搜索接口文档

> 版本：v1.0（2026-05-22）
> 适用：99chat-server v0.0.1-SNAPSHOT + 99chat (Flutter)
> 范围：5 个"添加我的方式"隐私开关 + 用户搜索接口（手机号 / UID）

---

## 1. 概述

### 1.1 5 个隐私开关

| 字段 | 含义 | 后端是否强校验 |
| --- | --- | --- |
| `allowViaQrCode` | 允许他人通过我的二维码添加 | ✅ GET `/users/{userId}/privacy`、POST `/users/add-friend/check`（`qr`） |
| `allowViaCard` | 允许他人通过名片转发添加 | ✅ GET `/users/{userId}/privacy`、POST `/users/add-friend/check`（`card`） |
| `allowViaGroup` | 允许群成员从群聊中添加 | ✅ GET `/users/{userId}/privacy`、POST `/users/add-friend/check`（`group`） |
| `allowViaPhone` | 允许他人通过手机号搜索/添加 | ✅ 搜索接口校验 |
| `allowViaUid` | 允许他人通过 UID 搜索/添加 | ✅ 搜索接口校验 |

新用户注册时**全部默认 `true`**。

> 「添加我时需验证」为独立开关，见 [§3.3 好友验证开关](#33-好友验证开关)。

> 腾讯 IM `addFriend` 仍不区分渠道；后端通过 **查询他人隐私** / **按渠道预检** 供客户端在加好友前拦截。联调详见 [backend-add-friend-via-card-integration.md](./backend-add-friend-via-card-integration.md)。

### 1.2 接口清单

| 接口 | 方法 | 路径 | 用途 |
| --- | --- | --- | --- |
| 读取隐私设置 | GET | `/me/privacy` | 进入"设置 → 隐私"页时拉取 |
| 修改隐私设置 | PUT | `/me/privacy` | 整页保存（全量覆盖） |
| 查询他人隐私 | GET | `/users/{userId}/privacy` | 名片/二维码/群加好友前拉取 |
| 按渠道预检 | POST | `/users/add-friend/check` | `channel`: card / qr / group；响应含 `friendAddRequiresVerify` |
| 读取好友验证开关 | GET | `/me/friend-add-verify` | 设置页「添加我时需验证」 |
| 修改好友验证开关 | PUT | `/me/friend-add-verify` | 单字段更新 |
| 查询他人验证开关 | GET | `/users/{userId}/friend-add-verify` | 加好友前 UI 提示 |
| 读取最后上线可见性 | GET | `/me/online-privacy-protection` | `everyone` / `friends_only` / `hidden` |
| 修改最后上线可见性 | PUT | `/me/online-privacy-protection` | 三档可见性 |
| 查询他人最后上线可见性 | GET | `/users/{userId}/online-privacy-protection` | 资料页/会话列表 UI 判断 |
| 搜索用户 | POST | `/users/search` | 输入手机号 / UID 添加好友 |

---

## 2. 通用规范

| 项 | 值 |
| --- | --- |
| Base URL（开发环境） | `http://47.239.60.107:8081` |
| 鉴权 | `Authorization: Bearer <JWT>`（沿用登录返回 token） |
| Content-Type | `application/json` |
| 响应格式 | JSON，UTF-8 |
| 失败响应体 | `{"code": "<错误码>", "message": "<错误码或描述>"}`，部分接口会附加 `retryAfter` |

---

## 3. 隐私接口

### 3.1 GET /me/privacy

**请求**

```http
GET /me/privacy HTTP/1.1
Authorization: Bearer <jwt>
```

**成功响应（200）**

```json
{
  "allowViaQrCode": true,
  "allowViaCard": true,
  "allowViaGroup": true,
  "allowViaPhone": true,
  "allowViaUid": true
}
```

### 3.2 PUT /me/privacy

**请求**

```http
PUT /me/privacy HTTP/1.1
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "allowViaQrCode": true,
  "allowViaCard": false,
  "allowViaGroup": true,
  "allowViaPhone": false,
  "allowViaUid": true
}
```

**说明**

- 必须包含全部 5 字段。任一字段缺失会被 Jakarta `@NotNull` 校验拒绝，返回 `400 INVALID_INPUT`。
- 全量覆盖语义；不支持部分更新。前端调用建议：UI 保存按钮提交时连同 5 个开关一起 PUT。

**成功响应（200）**

返回保存后的最新值（结构与 GET 完全一致）。

**错误码**

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 缺失字段或字段类型不对 |
| 404 | `USER_NOT_FOUND` | JWT 中的用户不存在（异常情况） |

---

### 3.3 好友验证开关

独立于「添加我的方式」5 项隐私开关；控制他人发起好友申请后是否需你手动同意。

| 接口 | 方法 | 路径 |
| --- | --- | --- |
| 读取 | GET | `/me/friend-add-verify` |
| 修改 | PUT | `/me/friend-add-verify` |
| 查询他人 | GET | `/users/{userId}/friend-add-verify` |

**GET / PUT 响应**

```json
{ "friendAddRequiresVerify": true }
```

| 值 | 含义 |
| --- | --- |
| `true`（默认） | 添加我时需验证，申请进入 pending |
| `false` | 无需验证，POST `/friend-requests` 直接 `auto_accepted` |

**PUT 请求体**

```json
{ "friendAddRequiresVerify": false }
```

---

### 3.4 最后上线时间可见性

独立于「添加我的方式」5 项隐私开关；控制他人是否能看到你的**最后上线时间**与**在线状态**（前端据此决定 UI 是否展示「在线」「最近活跃」等）。

| 接口 | 方法 | 路径 |
| --- | --- | --- |
| 读取 | GET | `/me/online-privacy-protection` |
| 修改 | PUT | `/me/online-privacy-protection` |
| 查询他人 | GET | `/users/{userId}/online-privacy-protection` |

**GET / PUT 响应**

```json
{ "lastActiveVisibility": "everyone" }
```

| 值 | UI 文案 | 含义 |
| --- | --- | --- |
| `everyone`（默认） | 所有人可查看 | 任意用户可见 |
| `friends_only` | 仅好友可查看 | 仅双向好友可见 |
| `hidden` | 不显示在线时间 | 他人均不可见 |

**PUT 请求体**

```json
{ "lastActiveVisibility": "friends_only" }
```

**后端联动**

- `GET /me/friends`：返回 `items[].lastActiveAt`（原始值）与 `items[].lastActiveVisibility`；是否展示由客户端决定
- `POST /users/search`：返回 `lastActiveAt`（原始值）与 `lastActiveVisibility`
- `POST /presence/last-seen`：返回 `lastSeen`（原始毫秒）与 `lastActiveVisibility`（按 userId 映射）
- TCP `presence_changed`：推送原始 `lastActiveAt` 与 `lastActiveVisibility`

客户端展示在线状态前，建议先读 `GET /users/{userId}/online-privacy-protection`，并结合当前用户是否与对方为双向好友判断。

---

## 4. 查询他人隐私与渠道预检

> 完整联调与验收见 [backend-add-friend-via-card-integration.md](./backend-add-friend-via-card-integration.md)。

### 4.1 GET /users/{userId}/privacy

返回**目标用户**的 5 项开关（结构与 `GET /me/privacy` 相同）。用户不存在 → `404 USER_NOT_FOUND`。

### 4.2 POST /users/add-friend/check

```json
{ "targetUserId": "xxx", "channel": "card" }
```

`channel`：`card` | `qr` | `group`。

**成功响应（200）**

```json
{
  "allowed": true,
  "reason": null,
  "friendAddRequiresVerify": true
}
```

| 字段 | 说明 |
| --- | --- |
| `allowed` | 该渠道是否允许加好友 |
| `reason` | 不允许时的错误码，如 `ADD_FRIEND_VIA_CARD_DISABLED` |
| `friendAddRequiresVerify` | 目标用户是否需验证；`false` 时可直接成为好友 |

不允许时仍为 `200` + `{ "allowed": false, "reason": "ADD_FRIEND_VIA_*_DISABLED", "friendAddRequiresVerify": ... }`。

---

## 5. 搜索接口

### 4.1 POST /users/search

**请求**

```http
POST /users/search HTTP/1.1
Authorization: Bearer <jwt>
Content-Type: application/json

{ "keyword": "ab12cd34ef", "phoneCountry": "CN" }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `keyword` | ✅ | 搜索词：E.164 手机号 / 纯数字本地手机号 / UID（首字母为字母）。**支持以 `@` 开头**，前缀会自动忽略，例如 `@ab12cd34ef` 等价于 `ab12cd34ef` |
| `phoneCountry` | 仅当 `keyword` 为本地手机号（纯数字）时必填，默认 `CN` | ISO 3166-1 alpha-2 区域码（如 `CN`、`HK`、`US`） |

**入参自动识别规则**

| `keyword` 模式 | 识别为 | 处理 |
| --- | --- | --- |
| 以 `@` 开头 | 自动去掉 `@` 后再按以下规则识别 |  |
| 以 `+` 开头 | E.164 手机号 | `libphonenumber.parse(keyword, null)` 解析 |
| 仅数字（`^[0-9]+$`） | 本地手机号 | 用 `phoneCountry`（默认 `CN`）解析 |
| 首字符为字母（`^[A-Za-z].*`） | UID | 精确匹配 `user.user_id` |
| 其他 | 拒绝 | `400 INVALID_INPUT` |

**成功响应（200）**

```json
{
  "userId": "ab12cd34ef",
  "nickname": "张三",
  "avatarUrl": "https://...",
  "phoneMasked": "+86138****1234",
  "lastActiveAt": 1700000000000
}
```

| 字段 | 说明 |
| --- | --- |
| `userId` | 对方平台 ID（10 位，字母+数字） |
| `nickname` | 对方昵称 |
| `avatarUrl` | 对方头像 URL |
| `phoneMasked` | 对方手机号脱敏后字符串（中间 4 位 `*`），**完整手机号不会返回** |
| `lastActiveAt` | 最后活跃时间戳（毫秒，UTC）；始终返回原始值，无活跃记录时为 `null` |
| `lastActiveVisibility` | 在线时间可见性：`everyone` / `friends_only` / `hidden` |

**404 USER_NOT_FOUND 的判定**

后端会在以下任一情况返回 `404 USER_NOT_FOUND`（**对调用方语义无差别**）：

1. 数据库未匹配到用户
2. 匹配到的是自己
3. 用户已被禁用（`status != 1`）
4. `keyword` 是手机号且对方 `allowViaPhone = false`
5. `keyword` 是 UID 且对方 `allowViaUid = false`

> 设计意图：对未知调用者保密"是否存在"与"是否拒绝"的差异，防止账号枚举。

**错误码**

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | `keyword` 为空 / 无法识别 / 手机号解析失败 |
| 404 | `USER_NOT_FOUND` | 见上述 5 种判定 |
| 429 | `SEARCH_BLOCKED` | 命中频控 24h 禁搜，body 含 `retryAfter`（秒）|

**429 响应示例**

```json
{
  "code": "SEARCH_BLOCKED",
  "message": "too many missed searches",
  "retryAfter": 84321
}
```

### 4.2 频控规则

| 维度 | 值 |
| --- | --- |
| 计数单位 | 每个登录用户独立统计 |
| 触发条件 | 滚动 **30 分钟** 窗口内，累计 **10 次** `USER_NOT_FOUND` |
| 命中后封禁 | **24 小时**全局禁搜，期间任何搜索直接 `429 SEARCH_BLOCKED` |
| 命中（找到）的搜索 | 不计入 miss、不重置计数 |
| 禁搜期间的搜索 | 直接拒绝，**不再累计 miss**，避免续期 |
| 配置项 | `chat99.search.miss-window-minutes / miss-threshold / block-hours` |
| 存储 | Redis（ZSET 滚动 + STRING TTL） |

---

## 6. 前端集成提示

### 5.1 隐私设置页（Flutter 伪代码）

```dart
// 进入页面：读取
final res = await dio.get(
  "$baseUrl/me/privacy",
  options: Options(headers: {"Authorization": "Bearer $jwt"}),
);
final p = res.data;
final viaQr     = p["allowViaQrCode"] as bool;
final viaCard   = p["allowViaCard"]   as bool;
final viaGroup  = p["allowViaGroup"]  as bool;
final viaPhone  = p["allowViaPhone"]  as bool;
final viaUid    = p["allowViaUid"]    as bool;

// 保存按钮：全量提交
await dio.put(
  "$baseUrl/me/privacy",
  data: {
    "allowViaQrCode": viaQr,
    "allowViaCard":   viaCard,
    "allowViaGroup":  viaGroup,
    "allowViaPhone":  viaPhone,
    "allowViaUid":    viaUid,
  },
  options: Options(headers: {"Authorization": "Bearer $jwt"}),
);
```

### 5.2 二维码 / 名片 / 群聊场景的 UI 校验

由于后端无法强校验这 3 个开关，**前端必须自行读取并校验**：

```dart
// 进入"扫一扫加好友"或"群成员详情"或"名片详情"页时
final p = await fetchPrivacyOf(targetUserId); // 你可以缓存或随群信息一起拉
if (!p.allowViaQrCode /* 或 .allowViaCard / .allowViaGroup */) {
  // 隐藏"加好友"按钮，或提示"对方未开放此方式添加"
}
```

> **注意**：当前后端**不提供** `GET /users/{userId}/privacy`（其他人的隐私）接口。如果客户端需要展示"对方禁用了此方式"的 UI，建议：
> - 二维码场景：扫码内容里携带 `allowViaQrCode` 标志
> - 群聊场景：通过 IM SDK 拿不到，可改为"先发起加好友请求，IM 用 `AllowType=NeedConfirm` 默认就需要对方确认"
> - 名片场景：同上

如果你需要后端提供"查询他人隐私"接口，请提需求，我们再单独评估（涉及反爬虫风险）。

### 5.3 搜索页（Flutter 伪代码）

```dart
/// 搜索用户（支持手机号 / UID / 带 @ 前缀的 UID）
Future<SearchResult?> search(String keyword, {String? phoneCountry}) async {
  // 前端不需要手动去 @，后端会自动忽略
  try {
    final res = await dio.post(
      "$baseUrl/users/search",
      data: {
        "keyword": keyword,       // 支持 "@ab12cd34ef" 或 "13800138000" 等
        if (phoneCountry != null) "phoneCountry": phoneCountry,
      },
      options: Options(headers: {"Authorization": "Bearer $jwt"}),
    );
    return SearchResult.fromJson(res.data);
  } on DioException catch (e) {
    final body = e.response?.data ?? {};
    final code = body["code"] as String?;
    switch (code) {
      case "USER_NOT_FOUND":
        showSnack("未找到该用户");
        return null;
      case "INVALID_INPUT":
        showSnack("请输入有效手机号或 UID");
        return null;
      case "SEARCH_BLOCKED":
        final retryAfter = body["retryAfter"] as int? ?? 0;
        final hours = (retryAfter / 3600).ceil();
        showSnack("搜索次数过多，请 $hours 小时后再试");
        return null;
      default:
        showSnack("搜索失败");
        return null;
    }
  }
}
```

---

## 7. 已知限制与未来演进

1. **前 3 个开关不能由后端强校验**：本期由客户端配合 UI 隐藏入口，后期若引入"加好友请求"自建流程，可在后端拦截。
2. **不查询他人隐私**：本期不提供 `GET /users/{userId}/privacy`，避免被遍历滥用。如确有需求（例如确认对方是否允许扫码加），后续可加上严格频控的查询接口。
3. **频控独立于 SMS 频控**：复用了 Redis，但 key 命名空间不同（`search:miss:* / search:block:*`），不互相影响。
4. **`lastActiveAt` 近实时**：TCP `auth`/`ping`（可选 `deviceId`）、登录、以及回退的 `POST /me/heartbeat` 更新 `users.last_active_at`（默认 30s 节流）；更新后向可查看的好友推送 `presence_changed`（TCP）。冷启动/视口补拉优先 TCP `presence_last_seen`，未连 TCP 用 `POST /presence/last-seen`。离线端需重连或主动拉取。
5. **未来扩展**：可加 `allowAddByLink`（邀请链接）、`searchableInRegion`（地域限制）等开关；表结构可直接加列。
