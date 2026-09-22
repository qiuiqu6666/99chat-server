# Web 扫码登录 — 移动端（App）完整对接文档

> 版本：v1.1  
> 日期：2026-08-02  
> 状态：后端已上线；本文只写 **Flutter / 原生 App** 侧  
> 读者：99chat App  
> Web 出码与轮询见：[qr-web-login-client.md](./qr-web-login-client.md)  
> 建议落地文件：`auth_api.dart`、`qr_web_login_payload.dart`、`qr_code_scanner_page.dart`、`qr_web_login_confirm_page.dart`、Dio 鉴权拦截器

---

## 0. 一句话职责

App **只负责**：识别网页登录码 →（已登录）登记扫码 → **二次确认/取消**。  
**不负责**：给 Web 发 token、替 Web 写本地登录态。Web 自己轮询拿 token 进首页。

---

## 1. 产品流程（App 视角）

```
用户打开网页登录页（已出码）
        ↓
已登录 App 打开扫一扫
        ↓
识别 type=web_login → POST /auth/login/qr/scan
        ↓
进入「确认登录网页版」页（必须二次确认，禁止扫到即授权）
        ↓
用户点「确认登录」→ POST confirm approve=true  → Toast → 返回
用户点「取消」    → POST confirm approve=false → Toast → 返回
```

| 场景 | App 行为 |
|------|----------|
| 已登录 + 合法 web_login 码 | scan → 确认页 → confirm |
| **未登录**扫到 web_login | **不要**调 scan；提示「请先登录 App」（产品明确不做：先登录再回来确认） |
| 个人/群二维码 | 走原有逻辑，与 web_login 互不影响 |
| 确认成功 | 只 Toast；**不要**在 App 内用 Web 的 token 换端 |

---

## 2. 状态机（App 需要知道的）

```
pending ──(本机 scan)──► scanned ──(approve=true)──► confirmed
                              └──(approve=false)──► cancelled
任意非终态超时 ──► expired
```

| 对本机含义 | |
|------------|--|
| scan 成功 | 进入确认页；可同用户重复 scan（幂等） |
| 另一账号已扫过 | `QR_SESSION_BOUND`，不要进确认页 |
| 码过期/不存在 | `QR_SESSION_EXPIRED` |
| 非 scanned 就 confirm | `QR_SESSION_INVALID_STATUS` |
| confirm 用户 ≠ 扫码用户 | `QR_SCANNER_MISMATCH` |

二维码 TTL 默认 **120 秒**（由 Web 创建；App 无需倒计时，失败时提示「让网页刷新」即可）。

---

## 3. 二维码载荷（扫码解析）

网页用服务端返回的 `qrPayload` **原样**画码。App 扫到的字符串应是 JSON（首版）：

```json
{
  "type": "web_login",
  "sessionId": "<uuid>",
  "v": 1
}
```

### 3.1 解析规则

1. `trim` 后尝试 `jsonDecode`。  
2. `type == "web_login"`（字符串精确匹配）→ 走网页登录分支。  
3. `sessionId` 为非空 `String`，否则视为无效码。  
4. `v` 当前为 `1`；未知版本可仍按 v1 处理或提示升级。  
5. **禁止**把整段 JSON 当 URL `launch`；**禁止**本地拼装/改写 `sessionId`。

### 3.2 与其它码分流（必做）

| type / 形态 | 走哪条链路 |
|-------------|------------|
| `web_login` | 本文：scan + 确认页 |
| `user` / 个人码 | 原加好友等 |
| `group` / 群码 | 原入群等 |
| 无法解析 | 原「无法识别」提示 |

建议独立工具：`qr_web_login_payload.dart` → `QrWebLoginPayload? tryParse(String raw)`。

---

## 4. 鉴权铁律（最高频踩坑）

### 4.1 哪些要登录

| 方法 | 路径 | App 是否调用 | Authorization |
|------|------|--------------|---------------|
| POST | `/auth/login/qr/session` | **否**（Web） | — |
| GET | `/auth/login/qr/session/{sessionId}` | **否**（Web） | — |
| POST | `/auth/login/qr/scan` | **是** | **必须** Bearer |
| POST | `/auth/login/qr/confirm` | **是** | **必须** Bearer |

### 4.2 Dio / 拦截器白名单（必改）

很多项目把「`/auth/login` 前缀」当成匿名登录区，**不挂 token**。  
后端已收窄：`qr/scan`、`qr/confirm` **不是**匿名接口。

若仍写成：

```dart
if (path.startsWith('/auth/login')) {
  // 不附带 Authorization
}
```

则 scan/confirm → **HTTP 401**，body 常为：

```json
{"code":"UNAUTHORIZED","message":"invalid or expired token"}
```

全局文案引擎很容易译成 **「验证已过期」**——这是假过期，与二维码 TTL 无关。

**正确做法：公开路径白名单，而不是整前缀排除 token。**

```dart
bool isAnonymousAuthPath(String path) {
  // scan / confirm 必须带 token —— 不要列入匿名
  if (path == '/auth/login/qr/scan' || path == '/auth/login/qr/confirm') {
    return false;
  }
  return path == '/auth/register'
      || path == '/auth/login'
      || path == '/auth/login/sms'
      || path == '/auth/login/password'
      || path == '/auth/login/password/verify'
      || path == '/auth/login/qr/session'
      || path.startsWith('/auth/login/qr/session/') // 仅 Web poll
      || path == '/auth/password/reset'
      || path.startsWith('/auth/slider/');
}

// onRequest:
if (!isAnonymousAuthPath(path) && token != null && token.isNotEmpty) {
  options.headers['Authorization'] = 'Bearer $token';
}
```

### 4.3 未登录拦截

扫到 `web_login` 时若本地无有效 token：

- **不要**调用 scan  
- Toast / Dialog：「请先登录后再扫码登录网页版」  
- 可跳转登录页；**不要**做「登录成功后自动带回确认」除非产品改口

---

## 5. HTTP 约定

- Base URL：与现有 App API **同一环境**（须与出码 Web 打同一套后端 / 同一 Redis，否则必 `QR_SESSION_EXPIRED`）。  
- JSON：camelCase。  
- 成功响应可能包一层：

```json
{ "code": 0, "message": "ok", "data": { ... } }
```

用现有 `_authMap` / 统一解包取出业务字段。  
- 失败：HTTP 非 2xx，body `{ "code": "...", "message": "..." }`；**优先用 `code` 分支**，不要只靠 message 含 `expired`。  
- 建议 Header（与现网一致）：`X-Client-Platform` / `X-Client-Version` / `X-Device-Model`。

---

## 6. App 调用的两个接口

### 6.1 扫码登记 — `POST /auth/login/qr/scan`

**Header**

```http
Authorization: Bearer <app-access-token>
Content-Type: application/json
```

**请求**

```json
{ "sessionId": "<从二维码解析>" }
```

**成功 200**（可能在 `data` 内）

```json
{
  "sessionId": "…",
  "status": "scanned",
  "siteLabel": "网页版登录"
}
```

| 字段 | 用途 |
|------|------|
| `siteLabel` | 确认页标题/说明（当前服务端固定「网页版登录」） |
| `status` | 应为 `scanned` |

**行为**

| 情况 | 结果 |
|------|------|
| `pending` → 首次扫 | 200，绑定当前用户 |
| 同用户再扫（相机重复回调） | 200 幂等，可进确认页 |
| 其它用户已绑定 | 409 `QR_SESSION_BOUND` |
| 过期 / 已确认 / 已取消 / 不存在 | 409 `QR_SESSION_EXPIRED` |
| 未带或无效 token | 401 `UNAUTHORIZED` |

**客户端**

```text
scan 成功 → push QrWebLoginConfirmPage(sessionId, siteLabel)
scan 失败 → 按 §7 文案，不要进确认页
```

---

### 6.2 确认 / 取消 — `POST /auth/login/qr/confirm`

**Header**：同上，必须 Bearer。

**请求**

```json
{
  "sessionId": "…",
  "approve": true
}
```

| `approve` | 含义 |
|-----------|------|
| `true` | 确认网页登录 |
| `false` | 取消 |

**成功 200**

```json
{ "sessionId": "…", "status": "confirmed" }
```

取消时 `status` 为 `"cancelled"`。

**错误**

| HTTP | code | 说明 |
|------|------|------|
| 401 | `UNAUTHORIZED` | 未登录 / token 无效 |
| 403 | `QR_SCANNER_MISMATCH` | 确认者不是扫描者 |
| 403 | `ACCOUNT_DISABLED` | 账号禁用 |
| 403 | `DEVICE_BANNED` | Web 设备被封 |
| 409 | `QR_SESSION_EXPIRED` | 会话不存在或过期 |
| 409 | `QR_SESSION_INVALID_STATUS` | 非 `scanned`（未扫、已确认等） |

**客户端**

- 确认成功：Toast「已确认登录」→ `pop`  
- 取消成功：Toast「已取消」→ `pop`  
- **不要**在 App 保存 Web 的 token（App 也拿不到；token 只在 Web poll `confirmed` 时下发）

---

## 7. 错误码 → 文案（扫码登录专用表）

**必须与 SMS / 滑块 / 通用登录过期文案隔离。**  
扫码流程 catch 里先看 `code`，再决定文案；禁止：

- 把 `message` 含 `expired` 一律当成「验证已过期，请重新验证」  
- 把 401 直接复用短信挑战过期文案  

| code / 条件 | 推荐中文 |
|-------------|----------|
| 401 / `UNAUTHORIZED` | 请先登录后再扫码（或：登录状态已失效，请重新登录 App） |
| `QR_SESSION_EXPIRED` | 二维码已失效，请让网页刷新后再扫 |
| `QR_SESSION_BOUND` | 该二维码已被其他账号扫描 |
| `QR_SESSION_INVALID_STATUS` | 状态异常，请刷新网页二维码后重试 |
| `QR_SCANNER_MISMATCH` | 请使用扫码的账号进行确认 |
| `DEVICE_BANNED` | 该设备不可用，无法登录网页版 |
| `ACCOUNT_DISABLED` | 账号已禁用 |
| 网络错误 | 网络异常，请重试 |
| 404（极少，整站未部署） | 扫码登录暂不可用 |

---

## 8. UI / 页面规格

### 8.1 扫码页

- 识别 `web_login` 后：loading → 调 scan → 成功进确认页。  
- 防抖：同一 `sessionId` 短时间重复回调只处理一次（服务端同用户幂等，客户端仍建议锁）。  

### 8.2 确认页 `QrWebLoginConfirmPage`

| 元素 | 内容 |
|------|------|
| 标题 | 可用 `siteLabel`，或固定「确认登录网页版」 |
| 说明 | 一句话：确认后将在电脑/网页端登录你的账号 |
| 主按钮 | 确认登录 → `approve: true` |
| 次按钮 | 取消 → `approve: false` |
| 加载 | 请求中禁用双按钮，防重复提交 |

**禁止**：scan 成功即自动 confirm。

### 8.3 与个人/群码 UI

不要混用「添加好友」「加入群聊」确认框；网页登录必须独立确认页。

---

## 9. 建议代码结构（Flutter）

```text
lib/src/
  api/auth_api.dart
    - scanQrLoginSession(sessionId)
    - confirmQrLoginSession(sessionId, approve)
  utils/qr_web_login_payload.dart
    - QrWebLoginPayload.tryParse(raw) → { sessionId, v }
  pages/qr_web_login_confirm_page.dart
  qr_code_scanner_page.dart   # 分流 web_login
  http/ 或 interceptor         # §4.2 白名单
```

### 9.1 API 伪代码

```dart
Future<QrScanResult> scanQrLoginSession(String sessionId) async {
  final res = await dio.post('/auth/login/qr/scan', data: {
    'sessionId': sessionId,
  }); // 拦截器必须带 Bearer
  final map = _authMap(res.data);
  return QrScanResult(
    sessionId: map['sessionId'] as String,
    status: map['status'] as String,
    siteLabel: (map['siteLabel'] as String?) ?? '网页版登录',
  );
}

Future<QrConfirmResult> confirmQrLoginSession({
  required String sessionId,
  required bool approve,
}) async {
  final res = await dio.post('/auth/login/qr/confirm', data: {
    'sessionId': sessionId,
    'approve': approve,
  });
  final map = _authMap(res.data);
  return QrConfirmResult(
    sessionId: map['sessionId'] as String,
    status: map['status'] as String,
  );
}
```

### 9.2 扫码入口伪代码

```dart
final payload = QrWebLoginPayload.tryParse(raw);
if (payload != null) {
  if (!authStore.isLoggedIn) {
    showToast('请先登录后再扫码登录网页版');
    return;
  }
  try {
    final r = await authApi.scanQrLoginSession(payload.sessionId);
    openConfirmPage(sessionId: r.sessionId, siteLabel: r.siteLabel);
  } on ApiException catch (e) {
    showToast(qrLoginErrorMessage(e)); // §7 专用表
  }
  return;
}
// else: 原 user/group 逻辑
```

---

## 10. 联调检查清单（App）

- [ ] 拦截器：`/auth/login/qr/scan`、`/confirm` **有** `Authorization`  
- [ ] 抓包 scan：200 + `scanned` + `siteLabel`  
- [ ] 故意不带 token：401，文案为「请先登录…」，**不是**「验证已过期，请重新验证」  
- [ ] 同用户连续扫两次：均可进确认页（幂等）  
- [ ] 确认后 Web ≤2s 进首页；App 仅 Toast  
- [ ] 取消后 Web 显示取消；App Toast  
- [ ] 过期码：`QR_SESSION_EXPIRED` 文案正确  
- [ ] 个人/群二维码路径无回归  

**抓包自检命令（可选，替换 token / sessionId）：**

```bash
curl -sS -X POST 'https://<api-host>/auth/login/qr/scan' \
  -H 'Authorization: Bearer <app-token>' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"<from-qr>"}'
```

---

## 11. 环境一致性

| 问题 | 现象 | 处理 |
|------|------|------|
| App API 与 Web 出码不是同一后端 | 一扫就 `QR_SESSION_EXPIRED` | 对齐 baseUrl / 环境 |
| App 未登录或 token 被拦 | 401 → 假「验证已过期」 | 改拦截器 §4.2 |
| 扫码内容不是服务端 `qrPayload` | 解析失败或错误 sessionId | Web 必须原样画码 |

---

## 12. 非目标（不要做）

- 未登录扫码 → 登录页 → 自动回来确认  
- 扫到即 authorize（无确认页）  
- App 轮询 `/auth/login/qr/session/{id}` 替 Web 拿 token  
- 用密码登录的 `NEED_SMS` 流程套在扫码确认上  

---

## 13. 后端对照（方便联调）

| 项 | 值 |
|----|-----|
| Scan | `POST /auth/login/qr/scan` |
| Confirm | `POST /auth/login/qr/confirm` |
| Redis | `auth:qr-login:{sessionId}`，TTL≈120s |
| 实现 | `QrLoginController` / `QrLoginService` |
| 确认成功 | `user_device.trust`（platform 默认 `web`）+ `login_log` 类型 `QR_WEB`（IP/UA 用网页创建会话时快照） |

完整双端说明仍见 [qr-web-login-client.md](./qr-web-login-client.md)。

---

## 14. 设备列表展示改法（方案 B：不强制 isOnline）

### 14.1 背景

`GET /me/devices` 行为：

| 字段 | 含义 |
|------|------|
| 能否出现在列表 | 该 `deviceId` **有未撤销的 JWT 会话**（与 token 寿命一致） |
| `isOnline` | Redis 设备心跳，**约 90 秒**；靠实时通道 / heartbeat 续期 |

扫码登录确认后：会写入 `user_device`、签发 Web 会话，并打 **一次** 心跳 → 约 90s 内 `isOnline=true`。  
网页端通常**不会**像手机一样持续 `deviceHeartbeat`，之后 `isOnline` 变 `false`，但**会话仍在，设备仍应留在列表里**。

若 UI 用 `isOnline == true` 才显示「他端桌面/网页已登录」，会出现「明明网页已登录却看不到 / 显示离线」的假象。

### 14.2 客户端原则（必改）

1. **是否展示他端登录**：看设备是否在 `/me/devices` 列表中（已有会话），**不要**用 `isOnline` 当门闩。  
2. **`isOnline`**：仅作绿点 /「最近活跃」装饰；Web 无心跳时允许为 `false`。  
3. **识别网页/桌面端**（便于文案「网页版」「电脑已登录」）：

```dart
bool isDesktopOrWebDevice(DeviceItem d) {
  final p = (d.platform ?? '').toLowerCase();
  return p == 'web'
      || p == 'windows'
      || p == 'macos'
      || p == 'mac'
      || p == 'linux'
      || p == 'desktop';
}
```

后端扫码确认后空 platform 会落成 **`web`**；展示名经服务端 `DeviceModelDisplayService` 可为 `Web`。

### 14.3 推荐 UI 逻辑

```dart
// 设备列表项（他端）
final otherDevices = items.where((d) => !d.isCurrent);

for (final d in otherDevices) {
  final title = d.model?.isNotEmpty == true ? d.model! : (d.platform ?? '设备');
  final subtitle = isDesktopOrWebDevice(d)
      ? (d.isOnline ? '网页/电脑 · 活跃' : '网页/电脑 · 已登录')  // 有会话即可
      : (d.isOnline ? '在线' : '已登录');

  // 踢出等操作：对该 deviceId 调现有 kick 接口即可（有会话就能踢）
}
```

| 错误做法 | 正确做法 |
|----------|----------|
| `if (!d.isOnline) hide` | 列表有该项就展示 |
| 「不在线 = 未登录」 | 「不在列表 = 无会话；在列表且 !isOnline = 已登录但不活跃」 |
| 只信任手机 platform | `web` / desktop 系列单独文案 |

### 14.4 与后端补全的关系

后端已做（无需客户端再造）：

- create 时 `platform` 空 → `web`  
- confirm 成功 → `login_log.login_type = QR_WEB`（IP/UA/version 用网页出码时快照，不是手机请求头）  
- `appVersion` / `lastLoginIp` 可从该登录日志回填到设备列表展示（现有 `UserDeviceAppService` 已读最新 `login_log`）

客户端 **不必** 为了显示他端而改 `isOnline` 语义；按 §14.2–14.3 放宽即可。

### 14.5 验收

- [ ] 手机确认网页登录后，App 设备列表出现 Web/电脑项（即使几十秒后绿点熄灭）  
- [ ] 该项可踢出；踢出后网页需重新扫码登录  
- [ ] 手机自身项仍可用 `isOnline` 表示活跃  
- [ ] 未改「全局把有会话当成在线」的后端语义（避免杀进程仍显示在线）
