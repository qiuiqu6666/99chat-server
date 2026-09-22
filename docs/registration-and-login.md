# 99chat 注册与登录开发文档

> 版本：v1.0  
> 适用：99chat-server (Spring Boot 3.5) + 99chat (Flutter)  
> 范围：账号体系（注册 / 登录 / 昵称 / 设备信任 / 客户端追踪），不含好友、群组、消息

---

## 1. 总览

### 1.1 业务目标

- 支持**全球手机号**注册；`+86` 优先走阿里云号码认证，其他国家/地区走 NodeSMS 国际短信。
- 用户注册时**只填**：手机号、短信验证码、昵称、密码；**用户名不输入**，系统自动生成。
- 系统为每位用户分配一个 **10 位 `[a-z0-9]`** 的随机**平台 ID**，作为：
  - Tencent IM 的 `userId`
  - 加好友 / 搜索的对外标识
  - 账号密码登录的"账号"之一（另一种是手机号）
- 全局唯一**昵称**，**7 天**内只允许修改一次。
- 发送短信前执行 Redis 限频；当前接口未接入 Cloudflare Turnstile。
- 注册成功设置一张**固定默认头像**（运营提供）。
- 注册成功自动与系统号 **`99Messenger`** 建立双向好友并发送欢迎单聊（`chat99.system-notify`，详 [system-notify-and-announcements.md](./system-notify-and-announcements.md)）。
- 两种登录方式：
  1. **手机号 + 短信验证码**
  2. **账号 + 密码**（账号 = 手机号 / 平台 ID，UI 用 Tab 切换）
- **设备信任**：一个账号同时**只允许 1 个**信任设备（Telegram 风格）。新设备登录强制短信二次验证，验证通过后**取代**原信任设备。
- **永久豁免**：后台可对指定账号关闭设备校验（VIP / 测试账号 / 客服等）。
- 每次登录请求**可选**携带 `X-Client-Version` / `X-Client-Platform`，仅用于追踪，不强校验。
- **登录日志**：成功 / 失败均入 `login_log` 表（不仅是日志文件）。

### 1.2 时序图（核心 4 条主线）

#### A. 注册（手机号 + 短信 + 昵称 + 密码）

```
Flutter                Server                 Redis       SMS provider       Tencent IM
   |                     |                      |                  |                   |
   |--POST /sms/send---->|                      |                  |                   |
  |  {phone, scene}     |                      |                  |                   |
   |                     |--rate limit check--->|                  |                   |
   |                     |--SETEX sms:reg:phone 300 code---------->|                   |
  |                     |--SMS provider route---------------------->|                |
   |<----200 ok----------|                                                              |
   |                                                                                    |
   |--POST /auth/register>|                                                             |
   |  {phone,code,nick,pwd}|                                                            |
   |                     |--GET sms:reg:phone-->|                                       |
   |                     |--check nickname unique (DB)                                  |
   |                     |--generate platformId (36^10 + retry)                         |
   |                     |--BCrypt(pwd)                                                 |
   |                     |--INSERT users                                                |
   |                     |--account_import (REST API)------------------->|              |
   |                     |--add_subscriber 主公众号 + 欢迎单聊------------>|              |
   |                     |--issue JWT                                                   |
   |<----200 {token,userId,wallet.depositAddress}-|                                    |
```

#### B. 手机号 + 短信码登录

```
Flutter                Server                 Redis       SMS provider
   |--POST /sms/send----->|  (scene=login)                            |
  |                     |--rate limit check--->|                     |
   |                     |--SETEX sms:login:phone 300 code----------->|
   |<--200 ok-------------|                                            |
   |                                                                  |
   |--POST /auth/login/sms->|                                          |
   |   {phone,code,deviceId,X-Client-*}                                |
   |                     |--GET sms:login:phone->|                    |
   |                     |--users by phone                             |
   |                     |--upsert user_device (trusted=true)          |
   |                     |--登录成功 → INSERT login_log(success=1)     |
   |                     |--issue JWT                                  |
   |<--200 {token,userId,nextStep=OK}                                  |
```

#### C. 账号 + 密码登录（含设备信任挑战）

```
Flutter                Server                  DB
   |--POST /auth/login/password->|                                          
   |  {account,pwd,deviceId,X-Client-*}                                     
   |                     |--users by phone OR platformId                    
   |                     |--BCrypt verify                                   
   |                     |--bypass_device_check?                            
   |                     |   yes → OK 路径                                  
   |                     |   no  → user_device by (userId, deviceId)?       
   |                     |          匹配 trusted=true → OK 路径             
   |                     |          否则 → 发起 SMS 挑战                    
   |--- 路径 1：OK ---|                                                     
   |<--200 {token,userId,nextStep=OK}                                       
   |--- 路径 2：NEED_SMS ---|                                                
   |<--200 {nextStep=NEED_SMS, challengeId, phoneMasked, phone}              
   |                                                                         
   |--POST /sms/send {scene=device, challengeId}->|                          
   |<--200 ok                                                                
   |--POST /auth/login/password/verify {challengeId, code, deviceId}->|      
   |                     |--校验 code、challengeId                          
   |                     |--替换 trusted device（旧的 trusted=false）        
   |                     |--issue JWT；INSERT login_log                      
   |<--200 {token,userId,nextStep=OK}                                        
```

#### D. 修改昵称（7 天冷却 + 全局唯一）

```
Flutter --PATCH /me/nickname {nickname}--> Server
                                            |--users by id
                                            |--last_nickname_changed_at + 7d > now? → 409 COOLDOWN
                                            |--exists by nickname? → 409 DUPLICATE
                                            |--UPDATE users SET nickname, last_nickname_changed_at = now()
                                            |--同步 Tencent IM profile（昵称）
                                            |<--200 {nickname, nextChangeableAt}
```

---

## 2. 数据模型

### 2.1 表：`users`（在现有结构上扩展）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK auto | 内部主键 |
| user_id | VARCHAR(10) | UNIQUE NOT NULL | **平台 ID**，10 位 `[a-z0-9]`，对外暴露，等于 Tencent IM userId |
| phone | VARCHAR(20) | UNIQUE NOT NULL | E.164 格式（如 `+8613800001111`） |
| phone_country | VARCHAR(4) |  | 国家码（如 `86`），便于路由短信供应商 |
| password_hash | VARCHAR(100) | NOT NULL | BCrypt cost 10 |
| nickname | VARCHAR(32) | UNIQUE NOT NULL | 全局唯一，长度 1–32（按需调整） |
| avatar_url | VARCHAR(255) | NOT NULL | 默认头像 URL |
| last_nickname_changed_at | DATETIME | NULL | 7 天冷却用，注册时不写，首次改昵称时写入 |
| bypass_device_check | TINYINT(1) | NOT NULL DEFAULT 0 | **永久豁免**位 |
| status | TINYINT | NOT NULL DEFAULT 1 | 1=正常，0=禁用 |
| created_at | DATETIME | NOT NULL | |
| updated_at | DATETIME | NOT NULL | |

索引：
- `UNIQUE KEY uk_user_id (user_id)`
- `UNIQUE KEY uk_phone (phone)`
- `UNIQUE KEY uk_nickname (nickname)`

### 2.2 表：`user_device`

> **设计意图**：每个 `user_id` 同一时刻**只**有 1 行 `is_trusted=1`。新设备验证通过后，把旧的置 0（保留行做审计），写入或更新当前 `device_id` 为 1。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK auto |  |
| user_id | VARCHAR(10) | NOT NULL | 关联 users.user_id |
| device_id | VARCHAR(64) | NOT NULL | 客户端生成 + secure_storage 持久化 |
| platform | VARCHAR(16) |  | iOS / Android / Web / Windows / macOS |
| model | VARCHAR(64) |  | 设备型号（可选） |
| is_trusted | TINYINT(1) | NOT NULL DEFAULT 0 | 是否当前信任设备 |
| trusted_at | DATETIME |  | 成为信任设备的时间 |
| last_login_at | DATETIME |  | |
| created_at | DATETIME | NOT NULL |  |

索引：
- `UNIQUE KEY uk_user_device (user_id, device_id)`
- `KEY idx_user_trusted (user_id, is_trusted)`

### 2.3 表：`login_log`

> 成功 + 失败**都**入库；用于风控、追踪客户端版本。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK auto |  |
| user_id | VARCHAR(10) |  | 失败可能为空（账号不存在等） |
| account_input | VARCHAR(64) |  | 用户原始输入（手机号 / 平台 ID），用于追踪 |
| login_type | VARCHAR(24) | NOT NULL | `SMS` / `PASSWORD` / `PASSWORD_SMS_CHALLENGE` |
| client_version | VARCHAR(32) |  | 来自 `X-Client-Version` |
| client_platform | VARCHAR(16) |  | 来自 `X-Client-Platform` |
| device_id | VARCHAR(64) |  | |
| ip | VARCHAR(45) |  | 支持 IPv6 |
| user_agent | VARCHAR(255) |  | |
| success | TINYINT(1) | NOT NULL | |
| fail_reason | VARCHAR(64) |  | 错误码（见 §10） |
| created_at | DATETIME | NOT NULL |  |

索引：
- `KEY idx_user_created (user_id, created_at)`
- `KEY idx_created (created_at)`（清理 / 报表用）

### 2.4 Redis Key 约定

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `sms:reg:{phone}` | string(code) | 300s | 注册短信验证码 |
| `sms:login:{phone}` | string(code) | 300s | 登录短信验证码 |
| `sms:device:{challengeId}` | hash{phone,code,userId,deviceId} | 300s | 设备挑战短信 |
| `sms:rl:phone:{phone}` | counter | 60s | 单手机号 1 分钟限频 |
| `sms:rl:phone:day:{phone}` | counter | 86400s | 单手机号 24 小时上限 |
| `sms:rl:ip:{ip}` | counter | 60s | 单 IP 1 分钟限频 |
| `nickname:lock:{nickname}` | string | 5s | 注册昵称占位锁（防并发同名） |
| `pid:lock:{platformId}` | string | 5s | 平台 ID 生成时的占位锁 |

---

## 3. 配置项（`application.yml` 增量）

```yaml
chat99:
  sms:
    smsbao:
      base-url: https://api.smsbao.com
      domestic-path: /sms
      sign: ${SMSBAO_SIGN:【海口超聚电子】}
    node-sms:
      base-url: ${NODESMS_BASE_URL:https://apij.nodesms.com/sms/batch/send}
      account: ${NODESMS_ACCOUNT:}
      password-md5: ${NODESMS_PASSWORD_MD5:}
      sender-id: ${NODESMS_SENDER_ID:}
    code:
      length: 6
      ttl-seconds: 300
    # 万能验证码（注册/登录/重置/设备验证均可用）；生产设 SMS_MASTER_CODE= 可关闭
    master-code: ${SMS_MASTER_CODE:}
    rate:
      phone-per-minute: ${SMS_PHONE_PER_MINUTE:5}
      phone-per-day: ${SMS_PHONE_PER_DAY:50}
      ip-per-minute: ${SMS_IP_PER_MINUTE:30}
  platform-id:
    length: 10
    alphabet: abcdefghijklmnopqrstuvwxyz0123456789
    max-retry: 5
  nickname:
    cooldown-days: 7
    min-length: 2
    max-length: 32
  device:
    trust-mode: SINGLE      # 预留枚举：SINGLE / MULTI
  defaults:
    avatar-url: https://99chat.oss-cn-hongkong.aliyuncs.com/moren/default_c2c_head.png
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
```

> 国内 `+86` 号码在阿里云配置完整时走号码认证；否则走短信宝国内接口。非 `+86` 号码走 NodeSMS，密码使用 MD5 值。

---

## 4. 平台 ID 生成

- 字符集：`a-z0-9`（共 36），长度 10 → 容量 36^10 ≈ 3.65 × 10^15。
- 算法：`SecureRandom` 抽 10 个字符 → `INSERT … ON DUPLICATE KEY` 失败则重试，最多 `max-retry` 次。
- 重试上限触发 → 5xx 报警（理论上几乎不会发生）。
- 黑词过滤（可选，二期）：`fuck`、`admin`、`root` 等子串拒绝；当前先不做。
- 是否对外保密：**不**。这是公开的"加好友 ID"。

```java
public String allocate() {
    for (int i = 0; i < maxRetry; i++) {
        String candidate = randomString(length, alphabet);
        if (!userRepository.existsByUserId(candidate)) {
            return candidate;          // 真正落库时仍以 DB 唯一约束兜底
        }
    }
    throw new IllegalStateException("platformId allocation exhausted");
}
```

---

## 5. 短信供应商路由

```
phone E.164  ->  parse country code via libphonenumber
                    ├── 86 + Aliyun configured -> Aliyun PNVS
                    ├── 86 + Aliyun unavailable -> smsbao /sms
                    └── 其它 -> NodeSMS /sms/batch/send
```

- 国际短信用英文模板，避免被运营商拦截。
- NodeSMS 请求使用 JSON：`account`、`password`、`mobiles`、`content`，可选 `senderId`、`shortCode`；返回 HTTP 成功且 JSON `code` 为 `0` 才视为发送成功。
- 供应商未配置、网络失败或供应商返回非零状态时分别映射为 `SMS_PROVIDER_NOT_CONFIGURED`、`SMS_PROVIDER_IO` 或 `SMS_PROVIDER_FAILED:*`。
- **不**记录验证码到日志，手机号仅以脱敏形式记录。

---

## 7. 限频与风控

| 维度 | 阈值 | 实现 |
|---|---|---|
| 单手机号 | 60s/5 条 | Redis INCR + EXPIRE 60 |
| 单手机号 | 24h/50 条 | Redis INCR `sms:rl:phone:day:{phone}` |
| 单 IP | 60s/30 条 | Redis INCR `sms:rl:ip:{ip}` |
| 同账号密码错误 | 5 次/10 分钟 → 锁账号 15 分钟 | 二期；当前先记录 `login_log` |

返回 `429 RATE_LIMITED` 并附 `retryAfter` 秒。

---

## 8. API 契约

> 全部 JSON。错误返回 `{ code, message }`。  
> 鉴权接口在 Header 加 `Authorization: Bearer <jwt>`。  
> 客户端可选 Header：`X-Client-Version`（如 `1.0.3+15`）、`X-Client-Platform`（`iOS|Android|Web|Windows|macOS`）。

### 8.1 业务接口（8 个）

#### `POST /sms/send`
发送短信验证码。

```json
{
  "phone": "+8613800001111",
  "scene": "REGISTER",            // 见下表
  "challengeId": null              // 仅 scene=DEVICE 时必填
}
```

200 → `{ "ok": true }`  

**`scene` 取值**

| scene | 用途 | 手机号要求 | 后续接口 |
|-------|------|------------|----------|
| `REGISTER` | 注册 | 未注册 | `POST /auth/register` |
| `LOGIN` | 短信登录 | 已注册 | `POST /auth/login/sms` |
| `RESET` | **登录密码**找回/重置 | 已注册 | `POST /auth/password/reset` |
| `PAY_PIN_RESET` | **支付密码**找回（6 位 PIN） | 已注册 | `POST /wallet/pay-pin/reset`（需 JWT） |
| `DEVICE` | 新设备验证 | 与 challenge 绑定号一致 | `POST /auth/login/password/verify` |

验证码 Redis 前缀按 scene 隔离（如 `sms:reset:` 与 `sms:paypin:`），**不可混用**。

`scene=DEVICE` 时：`phone` 必填且为完整 E.164（不可含 `*`），`challengeId` 必填；`phone` 须与 challenge 绑定号一致。

支付密码重置完整流程见 [wallet-client.md §4](./wallet-client.md#4-支付密码)。

400 `INVALID_INPUT`（DEVICE 缺 challengeId）/ `INVALID_PHONE` / `SCENE_NOT_ALLOWED`  
410 `CHALLENGE_EXPIRED`  
429 `RATE_LIMITED`（含单 challenge 发码次数上限，默认 5 次）

设备验证详见 [device-challenge-sms.md](./device-challenge-sms.md)。

#### `POST /auth/register`

```json
{
  "phone": "+8613800001111",
  "smsCode": "123456",
  "nickname": "小明",
  "password": "至少6位"
}
```

200 → `{ "token": "...", "userId": "abc12def34", "expiresIn": 7776000, "nextStep": "OK", "wallet": { ... } }`  

`wallet`（可选字段，见 [wallet-client.md](./wallet-client.md)）：

```json
"wallet": {
  "depositAddress": "T...",
  "usdtContract": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
  "minDepositUsdt": "1"
}
```

400 `INVALID_INPUT`  
409 `PHONE_EXISTS` / `NICKNAME_EXISTS`  
410 `SMS_CODE_INVALID`（不存在 / 错误 / 过期）  
503 `WALLET_NOT_CONFIGURED`（未配置 `TRON_DEPOSIT_MNEMONIC`）

副作用：
- 写 `users`
- 调用 Tencent IM REST `account_import`（同步 nickname、faceUrl=默认头像）
- 自动订阅主公众号（`@TOA#_@TOA#d4CH`，可配置）并发送欢迎单聊：`欢迎使用99 Messenger。`（失败不影响注册，见 [official-account.md](./official-account.md)）

#### `POST /auth/login/sms`
手机号 + 短信码登录。

```json
{
  "phone": "+8613800001111",
  "smsCode": "123456",
  "deviceId": "uuid-on-device"
}
```

200 → `{ "token": "...", "userId": "...", "nextStep": "OK", "expiresIn": 7776000 }`  
410 `SMS_CODE_INVALID`  
404 `USER_NOT_FOUND`

副作用：
- `user_device` upsert，老 trusted 置 0，新 trusted 置 1
- `login_log`：success=1, login_type=`SMS`

#### `POST /auth/login/password`
账号 + 密码登录（账号 = 手机号 / 平台 ID）。

```json
{
  "account": "+8613800001111",   // 也接受 "abc12def34"、"13800001111"
  "password": "...",
  "deviceId": "uuid-on-device",
  "phoneCountry": "CN"           // 可选；仅当 account 为不带 "+" 的本地手机号时使用
}
```

**account 判别规则**：
- account 以 `+` 开头 → 按 E.164 手机号解析，`phoneCountry` 被忽略
- account 全为数字 → 按 `phoneCountry`（缺省 `CN`，ISO 3166-1 alpha-2）当作本地手机号解析为 E.164 后查询
- account 含字母 → 按平台 ID 查询

解析失败统一返回 `401 BAD_CREDENTIALS`。

200 → 两种返回：

```jsonc
// 设备已信任 / 永久豁免
{ "token": "...", "userId": "...", "nextStep": "OK", "expiresIn": 7776000 }

// 新设备 → 需要短信挑战（方案 A：含完整 E.164 供发码）
{ "nextStep": "NEED_SMS", "challengeId": "uuid-c", "phoneMasked": "+86138****1111", "phone": "+861381380001111" }
```

`phone` 为用户绑定手机（E.164），与 `account` 登录名无关；`phoneMasked` 仅展示。详见 [device-challenge-sms.md](./device-challenge-sms.md)。

401 `BAD_CREDENTIALS`  
403 `ACCOUNT_DISABLED`

副作用：
- 成功 → `login_log success=1, login_type=PASSWORD`
- NEED_SMS → 写 `sms:device:{challengeId}`（含 phone、code、userId、deviceId）；客户端再调 `POST /sms/send` `scene=DEVICE`，`phone` 须与响应一致
- 失败 → `login_log success=0, fail_reason=BAD_CREDENTIALS`

#### `POST /auth/login/password/verify`
密码登录的设备挑战二步验证。

```json
{
  "challengeId": "uuid-c",
  "smsCode": "123456",
  "deviceId": "uuid-on-device"
}
```

200 → `{ "token": "...", "userId": "...", "nextStep": "OK" }`  
410 `SMS_CODE_INVALID` / `CHALLENGE_EXPIRED`

副作用：
- 旧 trusted 置 0，新 trusted 置 1
- `login_log success=1, login_type=PASSWORD_SMS_CHALLENGE`

#### `GET /me`
返回当前用户信息（已有，需扩展字段）。

200 → `{ userId, phone, phoneMasked, nickname, avatarUrl, lastNicknameChangedAt, bypassDeviceCheck }`

#### `GET /me/nickname/check?nickname=新昵称`

> 客户端完整说明见 [nickname-client.md](./nickname-client.md)

修改前预检（需 JWT）。

200 → `{ "available": true, "reason": null, "nextChangeableAt": "2026-05-30T10:00:00Z" }`  
200 → `{ "available": false, "reason": "NICKNAME_EXISTS", "nextChangeableAt": null }`  
200 → `{ "available": false, "reason": "NICKNAME_COOLDOWN", "nextChangeableAt": "2026-05-26T10:00:00Z" }`

`reason`：`NICKNAME_EXISTS`（已被其他用户占用）| `NICKNAME_COOLDOWN`（7 天内已改过）| `null`（可改）。

规则：**全局唯一**（`users.nickname` UNIQUE）；**7 天内仅可改一次**（`last_nickname_changed_at` + `chat99.nickname.cooldown-days`，注册后首次修改不受限）。

#### `PATCH /me/nickname`

```json
{ "nickname": "新昵称" }
```

200 → `{ "nickname": "新昵称", "nextChangeableAt": "2026-05-26T10:00:00Z" }`  
409 `NICKNAME_EXISTS`  
409 `NICKNAME_COOLDOWN` → 含 `nextChangeableAt` 字段

副作用：同步 Tencent IM `profile_update`；昵称会 trim 首尾空格。

#### `GET /im/user-sig`
（已有）签发 UserSig，需 JWT。

200 → `{ sdkAppId, userId, userSig, expiresIn }`

### 8.2 后台接口（4 个，加 `/admin` 前缀；权限二期实现，先用 IP 白名单）

#### `POST /admin/users/{userId}/bypass-device`
开启永久豁免。

```json
{ "enabled": true, "reason": "VIP user" }
```

200 → `{ "userId": "...", "bypassDeviceCheck": true }`

#### `POST /admin/users/{userId}/devices/reset`
强制清空所有信任设备（下次登录在任何设备都需短信验证）。

200 → `{ "cleared": 1 }`

#### `POST /admin/users/{userId}/disable`
禁用 / 启用账号。

```json
{ "disabled": true, "reason": "abuse" }
```

#### `GET /admin/users/{userId}/login-logs?from=&to=&limit=`
查询某账号的登录日志（最多 200 条）。

---

## 9. 客户端版本追踪

- Flutter 端用 `package_info_plus` 读取 version + build number，组装 `X-Client-Version`，例如 `1.0.3+15`。
- 平台用 `Platform.isIOS / isAndroid / kIsWeb / Platform.isWindows / Platform.isMacOS` 推导 `X-Client-Platform`。
- 全局 dio 拦截器统一注入。
- 服务端只读不校验：
  - 登录类接口 → 写 `login_log.client_version / client_platform`
  - 其他接口 → 仅 INFO 日志（不入库）

---

## 10. 错误码对照表

| HTTP | code | 含义 |
|---|---|---|
| 400 | INVALID_INPUT | 字段缺失 / 格式错（含 INVALID_PHONE） |
| 400 | SCENE_NOT_ALLOWED | scene 非法（如 DEVICE 缺 challengeId） |
| 401 | BAD_CREDENTIALS | 密码登录账号或密码错 |
| 401 | UNAUTHORIZED | JWT 缺失 / 失效 |
| 403 | ACCOUNT_DISABLED | status=0；`message`：**该账号已禁用，请联系管理员** |
| 403 | ADMIN_FORBIDDEN | 后台接口非授权 IP |
| 404 | USER_NOT_FOUND |  |
| 409 | PHONE_EXISTS | 手机号已注册 |
| 409 | NICKNAME_EXISTS | 昵称被占用 |
| 409 | NICKNAME_COOLDOWN | 7 天冷却未到 |
| 410 | SMS_CODE_INVALID | 验证码错误 / 过期 / 不存在 |
| 410 | CHALLENGE_EXPIRED | 设备挑战 challengeId 过期 |
| 429 | RATE_LIMITED | 限频，附 `retryAfter` |
| 500 | INTERNAL | 兜底 |

---

## 11. 实施阶段拆分

> 文档先行，写代码按下列顺序，每段都能跑通再进下一段。

### 阶段 1：基础设施
1. `brew install redis`，加 `spring-boot-starter-data-redis`
2. 加依赖：`libphonenumber`（E.164 解析）、`caffeine`（本地配置缓存可选）、Tencent IM Server REST 用 OkHttp 直连即可
3. DB 迁移：扩展 `users`、新增 `user_device`、`login_log`
4. `application.yml` 增量配置 + `.env.sample`

### 阶段 2：发短信链路（不接业务）
5. NodeSMS / smsbao / 阿里云短信客户端与号码路由
6. `POST /sms/send` 含限频 + Redis 验证码

### 阶段 3：注册
8. 平台 ID 生成器 + 单元测试（重试逻辑、字符集、长度）
9. `POST /auth/register`：校验 → 生成 platformId → BCrypt → 落库 → 调用 Tencent IM `account_import` → 发 JWT
10. 默认头像静态资源放 `src/main/resources/static/avatar/default.png`，由 nginx / CDN 替换为外网 URL

### 阶段 4：登录（双模式 + 设备信任）
11. `POST /auth/login/sms`
12. `POST /auth/login/password` + `nextStep` 分支
13. `POST /auth/login/password/verify`
14. `user_device` 信任替换逻辑（事务保证只剩 1 个 trusted=1）

### 阶段 5：昵称
15. `PATCH /me/nickname`：唯一约束 + 7 天冷却 + 同步 IM profile

### 阶段 6：后台
16. 4 个 `/admin/*` 接口，先 IP 白名单守门，后续接管理员账号体系

### 阶段 7：Flutter 端
17. 注册页：手机号（国家区号选择器 → libphonenumber 校验）+ 验证码 + 昵称 + 密码
18. 登录页：Tab 切换（手机+短信 / 账号+密码）；密码模式遇 `NEED_SMS` 跳挑战页
19. 设备 ID：`flutter_secure_storage` + uuid 生成，首次启动写入持久化
20. 全局 dio 拦截器注入 `X-Client-Version` / `X-Client-Platform`

### 阶段 8：观测与风控（二期）
21. `login_log` 报表 / 异常登录提醒
22. 密码错误次数锁
23. platformId 黑词表
24. JWT 黑名单（强制下线）

---

## 12. 安全清单

- [ ] IM key、JWT secret、短信供应商凭据全部走环境变量
- [ ] DB 密码不再用 `chat99/chat99`
- [ ] BCrypt cost ≥ 10
- [ ] 短信验证码不进任何日志；手机号脱敏（`+86138****1111`）后进日志
- [ ] CORS 白名单：仅生产域名
- [ ] HTTPS 终结于网关，应用层 `server.forward-headers-strategy=framework`
- [ ] `/admin/*` IP 白名单 + 后续 RBAC
- [ ] Tencent IM REST 调用启用 `random` 鉴权参数 + 后端独立 SDKAppID 管理员角色
- [ ] 默认头像放 CDN，避免应用层流量

---

## 13. 后续可演进方向（占位，本期不做）

- 多设备模式（trust-mode=MULTI），每个设备独立 trusted 标
- 邮箱注册 / OAuth (Apple, Google, WeChat)
- 设备指纹替代纯 deviceId
- 异常登录邮件 / IM 通知
- 账号注销 / GDPR 数据导出
- 短信发送 / 注册 / 重置密码接口同步支持本地手机号 + phoneCountry
