# 99chat Admin API v1 — 好友列表 / 登录管控

> Base URL：`http://47.239.60.107:8081`（本地开发代理见前端 `.env.development`）  
> 前缀：`/api/v1`  
> JSON：**snake_case**（仅 `/api/v1` 管理端；App 接口仍为 camelCase）  
> 鉴权：`Authorization: Bearer <access_token>`（`aud=admin-api`）

---

## 约定

| 项 | 说明 |
|----|------|
| `user_uid` | `users.user_id` 字符串（腾讯云 IM 号，如 `v33m4qt9s2`），**勿传**数据库自增 `users.id` |
| 写接口权限 | `user.write` |
| 读列表权限 | `user.read` |
| 审计字段 | `admin_audit_logs.target_user_id`（varchar，IM 号） |

**账号状态**：本系统 `user_status` 仅 `1` 正常 / `0` 禁用；无 legacy `-2` 注销态，解禁统一恢复为 `1`。

---

## 0. 登录

### `POST /api/v1/auth/login`

```json
{ "username": "admin", "password": "admin123" }
```

**200**

```json
{
  "access_token": "...",
  "expires_in": 86400,
  "user": {
    "username": "admin",
    "permissions": ["user.read", "user.write"]
  }
}
```

默认账号首次启动写入 `admin_account`（`chat99.admin-api.default-username/password`）。

---

## 1. 用户列表

### `GET /api/v1/users`

权限：`user.read`

| Query | 说明 |
|-------|------|
| `page` | 默认 1 |
| `page_size` | 默认 10，最大 100 |
| `keyword` | 昵称、手机号、IM 号 `user_id` |
| `status` | `1` 正常 / `0` 禁用 |
| `is_online` | `1` 在线 / `0` 离线（5 分钟内活跃） |
| `sort` | `register_time_desc` |

| 字段 | 含义 |
|------|------|
| `user_uid` | IM 号字符串（与注册规则一致，10 位字母数字） |
| `user_status` | `1` / `0` |
| `user_avatar_file_name` | 优先 IM `Tag_Profile_IM_Image` 完整 URL |
| `wallet_balance` | USDT 总余额（2 位小数，兼容旧字段） |
| `wallet_frozen_amount` | USDT 冻结（待提现等，2 位小数） |
| `wallet_balances` | 多币种总余额，如 `{ "USDT": "100.00", "TRX": "12.500000", "CNY": "0.00" }` |
| `wallet_frozen_by_currency` | 各币种冻结；USDT 与 `wallet_frozen_amount` 一致，TRX/CNY 暂无冻结为 `0` |

---

## 2. 禁止登录

### `POST /api/v1/users/login-disabled`

权限：`user.write`

```json
{
  "user_uid": "v33m4qt9s2",
  "disabled": true,
  "clear_http_token": true
}
```

**200**

```json
{
  "ok": true,
  "user_uid": "v33m4qt9s2",
  "disabled": true,
  "user_status": 0,
  "http_token_cleared": true
}
```

| 场景 | 行为 |
|------|------|
| `disabled: true` + `clear_http_token: true` | `user_status=0`，清空信任设备（等效多端登出） |
| `disabled: false` | `user_status=1`，并设置 `bypass_device_check=true`，便于解禁后**立即**密码/短信登录（禁用时会清空信任设备，否则密码登录只会返回 `NEED_SMS`） |

**生效说明**：

- 禁用后，该用户**已有 JWT** 在后续业务请求中会 `403 ACCOUNT_DISABLED`。
- 客户端须**退出后重新登录**；不要继续用旧 Token。
- 密码登录若返回 `NEED_SMS`，需完成短信设备验证；后台「允许登录」已自动开启设备豁免至可直登。

### 客户端（99chat App）必做（与运营后台无关）

| 场景 | App 行为 |
|------|----------|
| 业务接口 `403` + `code: ACCOUNT_DISABLED` | **清除本地 JWT**，跳转登录页；提示 **该账号已禁用，请联系管理员**（勿写成「服务拒绝访问 / 开放设备验证发码接口」） |
| `POST /auth/login/password` 返回 `nextStep: NEED_SMS` | **不是失败**；跳转设备短信验证页 → `POST /sms/send`（`scene=DEVICE`）→ `POST /auth/login/password/verify` |
| 解禁后首次登录 | 必须**退出账号**后重新走登录流程 |

运营后台 **im-admin** 一般**无需改接口字段**（已是 snake_case + `user_uid` IM 号）；建议在「允许登录」成功后提示运营：通知用户在 App 内退出并重新登录。

审计：`user.login_disabled.set`

---

## 3. 修改登录密码

### `POST /api/v1/users/login-password`

权限：`user.write`

```json
{
  "user_uid": "v33m4qt9s2",
  "new_password": "newPass123"
}
```

- `new_password`：6～128 字节，BCrypt（与 App 一致）
- 成功后清空信任设备

**200**：`{ "ok": true, "user_uid": "v33m4qt9s2" }`

审计：`user.login_password.reset`

---

## 4. 修改支付密码（资金密码）

### `POST /api/v1/users/fund-password`

权限：`user.write`

```json
{
  "user_uid": "v33m4qt9s2",
  "new_fund_password": "123456"
}
```

- 必须 6 位纯数字
- 写入 `user_wallet.pay_pin_hash`，重置错误计数与锁定

**200**：`{ "ok": true, "user_uid": "v33m4qt9s2" }`

审计：`user.fund_password.set`

---

## 5. 错误体

```json
{ "error": "user_not_found", "message": "user_not_found" }
```

| HTTP | error | 说明 |
|------|-------|------|
| 401 | unauthorized | 无 Token / Token 无效 |
| 403 | forbidden | 无 `user.write` |
| 404 | user_not_found | IM 号不存在 |
| 422 | validation_error | 参数校验失败 |

---

## 6. 联调顺序（建议）

1. `POST /api/v1/auth/login` → 取 `access_token`
2. `GET /api/v1/users` → 200，确认 `user_uid` 为 IM 字符串
3. `POST .../login-disabled`（禁用 → 列表 `user_status=0` → 解禁）
4. `POST .../login-password` → App 新密码登录
5. `POST .../fund-password` → App 支付密码验证

---

## 7. 自测命令

```bash
BASE=http://47.239.60.107:8081
TOKEN=$(curl -sS -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -sS "$BASE/api/v1/users?page=1&page_size=1" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 8. 创建用户 / 批量创建

与 App `POST /auth/register` 一致的后端副作用：IM `account_import`、表情包初始化、系统通知好友、支付助手好友、**TRON 充值地址（HD 派生）**。

| 项 | 说明 |
|----|------|
| 登录密码 | **创建时在请求体提交**，本批/本条共用同一 `password`（6～128 位），不从 `.env` 读取 |
| 手机号 | 自动分配 **12 开头的 11 位**号码（如 `12012345678`），存库 E.164 `+8612…` |
| `user_uid` | 与 App 注册相同：**10 位字母数字**平台 ID（如 `v33m4qt9s2`），可作登录账号 |

### `POST /api/v1/users/create`

权限：`user.write`

```json
{
  "nickname": "测试用户A",
  "password": "统一登录密码",
  "sex": "1"
}
```

**200**

```json
{
  "ok": true,
  "user_uid": "v33m4qt9s2",
  "nickname": "测试用户A",
  "phone": "+8612012345678",
  "phone_num": "8612012345678",
  "trx_address": "TXxx…",
  "deposit_address": "TXxx…",
  "usdt_contract": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
  "min_deposit_usdt": "1"
}
```

| 字段 | 说明 |
|------|------|
| `trx_address` / `deposit_address` | 用户 TRON 充值地址（相同） |
| `usdt_contract` | USDT TRC20 合约 |
| `min_deposit_usdt` | 最小充值展示值 |

错误：`duplicate_nickname`（409）、`validation_error`（422）、`WALLET_NOT_CONFIGURED`（502，未配置 `TRON_DEPOSIT_MNEMONIC`）

审计：`user.create`

### `POST /api/v1/users/create-batch`

权限：`user.write`；单次最多 **100** 条。

```json
{
  "password": "本批统一登录密码",
  "users": [
    { "nickname": "用户1", "sex": "1" },
    { "nickname": "用户2", "sex": "0" }
  ]
}
```

- 顶层 **`password` 必填**：本批所有用户使用同一登录密码
- `users[]` 仅需 `nickname`（及可选 `sex`），无需逐条传密码

**200**

```json
{
  "items": [
    {
      "index": 0,
      "ok": true,
      "user_uid": "…",
      "nickname": "用户1",
      "phone_num": "8612098765432",
      "trx_address": "TX…",
      "deposit_address": "TX…",
      "usdt_contract": "TR7NH…",
      "min_deposit_usdt": "1",
      "error": null,
      "message": null,
      "field": null
    }
  ],
  "total": 1,
  "success_count": 1,
  "fail_count": 0
}
```

失败条目 `ok: false`，含 `error` / `message` / `field`（如 `nickname`）。

### `POST /api/v1/users/create-by-count`（推荐：仅密码 + 数量）

权限：`user.write`；单次 **1～100** 个。

仅需提交统一登录密码与创建数量，昵称自动生成（如 `用户284701001`），手机号仍为 12 开头的 11 位。

```json
{
  "password": "统一登录密码",
  "count": 10,
  "sex": "1"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `password` | 是 | 本批账号共用登录密码（6～128 位） |
| `count` | 是 | 创建数量，1～100 |
| `sex` | 否 | 性别，写入审计 |

**200**

```json
{
  "ok": true,
  "password": "统一登录密码",
  "count": 10,
  "success_count": 10,
  "fail_count": 0,
  "accounts": [
    {
      "user_uid": "v33m4qt9s2",
      "nickname": "用户284701001",
      "phone": "+8612887278095",
      "phone_num": "8612887278095",
      "password": "统一登录密码",
      "trx_address": "TY8BTP4ChskSugxG9gf9eiWYqaHchVagy7",
      "deposit_address": "TY8BTP4ChskSugxG9gf9eiWYqaHchVagy7",
      "usdt_contract": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
      "min_deposit_usdt": "1"
    }
  ]
}
```

| `accounts[]` 字段 | 说明 |
|-------------------|------|
| `user_uid` | IM 号（与注册一致），App 可用作平台 ID 登录 |
| `phone` / `phone_num` | 绑定手机（E.164 / 无 `+`） |
| `password` | 与本批提交的登录密码相同 |
| `trx_address` | TRON 充值地址 |

`ok: false` 表示未全部成功；`accounts` 仍包含已创建账号，`fail_count` 为失败条数。

审计：`user.create_by_count`

### 异步账号生成任务（管理后台默认）

管理后台不再等待同步接口完成，改用持久化任务。任务和账号明细保留 30 天；
统一密码仅在任务执行期间以 AES-GCM 密文保存，任务终态立即清除，查询和导出均不返回密码。

部署时执行 `scripts/migrate-admin-user-generation-tasks.sql`，并配置独立的
`ACCOUNT_GENERATION_ENCRYPTION_KEY`。未配置时兼容回退到 `INTEGRATION_API_TOKEN`；
生产环境仍建议使用独立随机密钥。

#### `POST /api/v1/users/generation-tasks`

权限：`user.write`。请求体沿用 `{ "password": "...", "count": 100 }`，接口立即返回
`task_no`、`status=pending`、`requested_count` 和 `created_at`。

#### `GET /api/v1/users/generation-tasks`

权限：`user.read`。支持 `task_no`、`created_by`、`status`、`created_from`、
`created_to`、`page`、`page_size`。状态包括：

- `pending`：排队中
- `running`：生成中
- `success`：全部成功
- `partial_failed`：部分失败
- `failed`：全部失败或任务级错误

#### `GET /api/v1/users/generation-tasks/{task_no}`

权限：`user.read`。返回任务统计和 `accounts` 明细，字段包含 UID、昵称、充值地址、
USDT 合约、状态及失败原因，不包含登录密码。管理前端使用这些数据生成 Excel。

---

## 9. 修改余额（多币种调账）

### `GET /api/v1/users/wallet?user_uid={IM号}`

权限：`user.read`。用户详情页「钱包」表格专用（调账弹窗读可用余额也可用此接口）。

**200 示例**

```json
{
  "user_uid": "e04olerlr3",
  "deposit_address": "TY8BTP4ChskSugxG9gf9eiWYqaHchVagy7",
  "trx_address": "TY8BTP4ChskSugxG9gf9eiWYqaHchVagy7",
  "usdt_contract": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
  "min_deposit_usdt": "1",
  "currencies": [
    {
      "currency": "USDT",
      "balance_available": "90.00",
      "balance_frozen": "10.00",
      "balance_total": "100.00",
      "wallet_address": "TY8B…",
      "wallet_address_label": "TRON 充值地址"
    },
    {
      "currency": "TRX",
      "balance_available": "12.500000",
      "balance_frozen": "0.000000",
      "balance_total": "12.500000",
      "wallet_address": "TY8B…",
      "wallet_address_label": "TRON 充值地址"
    },
    {
      "currency": "CNY",
      "balance_available": "0.00",
      "balance_frozen": "0.00",
      "balance_total": "0.00",
      "wallet_address": null,
      "wallet_address_label": null
    }
  ]
}
```

| 字段 | 表格列 |
|------|--------|
| `currency` | 币种（`USDT` / `TRX` / `CNY`） |
| `balance_available` | **可用余额**（总余额 − 冻结；USDT 冻结含待提现） |
| `balance_frozen` | **冻结** |
| `balance_total` | 总余额（可选展示） |
| `wallet_address` | 钱包地址；CNY 为 `null`（平台内账户） |
| `wallet_address_label` | USDT/TRX 为 `TRON 充值地址`，CNY 为 `null` |

也可用列表/详情里的 `wallet_balances` + `wallet_frozen_by_currency` 自行相减，但**不含充值地址**，推荐本接口。

---

### `POST /api/v1/users/wallet/balance-adjust`

权限：`user.write`

```json
{
  "user_uid": "e04olerlr3",
  "currency": "USDT",
  "direction": "add",
  "amount": "100.00",
  "remark": "运营手工调账"
}
```

| 字段 | 说明 |
|------|------|
| `user_uid` | IM 号（`users.user_id`） |
| `currency` | `USDT` / `TRX` / `CNY`（大写） |
| `direction` | `add` 增加 / `subtract` 减少 |
| `amount` | 正数；USDT/CNY 最多 2 位小数，TRX 最多 6 位小数 |
| `remark` | 可选备注 |

**业务规则**

| 场景 | 行为 |
|------|------|
| `add` | 对应币种余额增加 |
| `subtract` | 对应币种余额减少 |
| USDT 调减 | 上限 = 余额 − 待提现冻结 |
| TRX / CNY 调减 | 可用余额不足 → `insufficient_available_balance` |
| 并发冲突 | `concurrent_wallet_update`（409） |

**200**

```json
{
  "ok": true,
  "user_uid": "e04olerlr3",
  "currency": "USDT",
  "direction": "add",
  "amount": "100.00",
  "balance_before": "0.00",
  "balance_after": "100.00",
  "transaction_no": "TX1777123456789"
}
```

| HTTP | error | 说明 |
|------|-------|------|
| 422 | `validation_error` | 参数非法、币种不支持、精度错误 |
| 422 | `insufficient_available_balance` | 调减超过可用余额 |
| 409 | `concurrent_wallet_update` | 乐观锁冲突 |

审计：`user.wallet.balance_adjust`（`target_user_id` = IM 号）

**币种存储说明**：USDT → `balance_usdt_micro`；TRX → `balance_trx_sun`；CNY → `balance_platform_fen`（与 App 平台币分共用，展示为人民币 2 位小数）。

---

## 10. 用户详情页（§11 联调）

### `GET /api/v1/users/detail?user_uid={IM号}`

权限：`user.read`。一次返回首屏聚合：

| 字段 | 说明 |
|------|------|
| `profile` | 与 §1 列表单行字段一致（含 `wallet_balances`） |
| `friends` | `{ items, total, limit, truncated }`，首屏最多 150 |
| `groups` | `{ items, total, limit, truncated }`，首屏最多 80 |
| `devices` | 设备/登录历史合并（`user_device` + `login_log`），最多 100 |
| `accounts_same_ip` | `{ shared_ips, items, hint }` 同 IP 关联账号 |

### 好友列表 / 群列表（用户详情 Tab）

**推荐（一次拉两个列表）**

`GET /api/v1/users/social?user_uid={IM号}&page=1&page_size=20`

权限：`user.read`

**200**

```json
{
  "friends": {
    "items": [
      {
        "friend_uid": "abc12def34",
        "nickname": "好友昵称",
        "add_time": 1710000000,
        "friend_avatar_file_name": "https://..."
      }
    ],
    "total": 7,
    "page": 1,
    "page_size": 20,
    "truncated": false
  },
  "groups": {
    "items": [
      {
        "group_id": "@TGS#_xxx",
        "group_name": "测试群",
        "group_type": "普通成员",
        "member_count": 128,
        "join_time": 1710000000,
        "face_url": "https://..."
      }
    ],
    "total": 11,
    "page": 1,
    "page_size": 20,
    "truncated": false
  }
}
```

**分开拉取**

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/api/v1/users/friends?user_uid=&page=&page_size=` | user.read |
| GET | `/api/v1/users/groups?user_uid=&page=&page_size=` | user.read |

| 好友字段 | 说明 |
|----------|------|
| `friend_uid` | 好友 IM 号 |
| `nickname` | 昵称（IM 资料或本库 `users.nickname`） |
| `add_time` | 加好友时间（Unix 秒） |
| `friend_avatar_file_name` | IM 头像 URL |

| 群字段 | 说明 |
|--------|------|
| `group_id` | 群 ID（查群消息 `g_id` 用此值） |
| `group_name` | 群名称 |
| `group_type` | 该用户在此群的身份：`群主` / `管理员` / `普通成员`（来源 IM `SelfInfo.Role`） |
| `member_count` | 当前群成员人数（IM `MemberNum`，拉不到时为 `null`） |
| `join_time` | 入群时间（Unix 秒，可能为 0） |
| `face_url` | 群头像 URL |

数据来源：腾讯云 IM `friend_get` / `get_joined_group_list` + `get_group_info`。

### P2 其它分页扩展

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/api/v1/users/login-logs?user_uid=&page=&page_size=` | user.read |
| GET | `/api/v1/users/related-by-ip?user_uid=&page=&page_size=` | user.read |
| GET | `/api/v1/users/related-by-device?user_uid=&device_id=` | user.read |
| GET | `/api/v1/users/login-logs?user_uid=&page=&page_size=` | user.read |
| GET | `/api/v1/users/related-by-ip?user_uid=&page=&page_size=` | user.read |
| GET | `/api/v1/users/related-by-device?user_uid=&device_id=` | user.read |

### P1 详情页写接口 / 消息 / 相册

#### 通讯录与相册查询性能

- 相册列表按 `status + created_at`、用户相册按
  `user_id + status + created_at` 联合索引查询。
- 后台相册图片使用懒加载，视频不预加载媒体正文；视频统一使用轻量 OSS
  占位缩略图，新上传自动写入，历史缺失数据由独立后台任务补齐。
- 通讯录同步时批量计算并保存 `is_platform_user`、`matched_user_id`，
  管理端筛选直接分页查询，不再扫描 2000 条后在内存过滤。
- 历史未匹配通讯录每 5 分钟增量复核一次，用于覆盖联系人晚于通讯录同步注册的场景。
- 部署需执行 `scripts/migrate-album-contact-query-optimization.sql`。

| 方法 | 路径 | 权限 |
|------|------|------|
| POST | `/api/v1/users/login-unfreeze` | user.write（解除支付密码锁定；body：`user_uid` 或 `login_key` 手机号/IM号） |
| GET | `/api/v1/messages/c2c?user_a=&user_b=&keyword=` | user.read（腾讯云 IM 漫游） |
| GET | `/api/v1/messages/group?g_id=&keyword=` | group.read |
| GET | `/api/v1/users/phone-album/config?user_uid=` | user.read |
| GET | `/api/v1/users/phone-album/list?user_uid=&page=&page_size=` | user.read |

默认管理员权限已包含 `group.read`（新装或启动时自动补齐）。

---

## 11. 其它写接口（列表页扩展）

| 方法 | 路径 | 权限 |
|------|------|------|
| POST | `/api/v1/users/create-by-count` | user.write |
