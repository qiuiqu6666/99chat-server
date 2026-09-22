# 99chat Admin API v1 — 好友列表 / 用户详情 / 登录管控

> **Base URL：** `http://47.239.60.107:8081`（本地开发代理见前端 `.env.development`）  
> **前缀：** `/api/v1`  
> **JSON：** snake_case（仅 `/api/v1` 管理端；App 接口仍为 camelCase）  
> **鉴权：** `Authorization: Bearer <access_token>`（`aud=admin-api`）  
> **前端页面：** 好友列表 `/im-admin/account/user-search` · 用户详情 `/im-admin/account/user-detail/:id`

---

## 约定

| 项 | 说明 |
|----|------|
| `user_uid` | `users.user_id` 字符串（**10 位字母数字**平台 ID，如 `v33m4qt9s2`），勿传数据库自增 `users.id` |
| 写接口权限 | `user.write` |
| 读列表权限 | `user.read` |
| 审计字段 | `admin_audit_logs.target_user_id`（varchar，IM 号） |

**账号状态：** 本系统 `user_status` 仅 `1` 正常 / `0` 禁用；无 legacy `-2` 注销态，解禁统一恢复为 `1`。

---

## 0. 登录

```
POST /api/v1/auth/login
```

```json
{ "username": "admin", "password": "admin123" }
```

**200：**

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

```
GET /api/v1/users
```

**权限：** `user.read`

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

**前端「可用余额」：** USDT = 总余额 − 冻结；TRX/CNY = 总余额 − 对应冻结（无则 = 总余额）。用于列表展示与「调整余额」弹窗。

---

## 2. 禁止登录

```
POST /api/v1/users/login-disabled
```

**权限：** `user.write`

**请求：**

```json
{
  "user_uid": "v33m4qt9s2",
  "disabled": true,
  "clear_http_token": true
}
```

**200：**

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
| `disabled: false` | `user_status=1`，并设置 `bypass_device_check=true`，便于解禁后立即密码/短信登录 |

**生效说明：**

- 禁用后，该用户已有 JWT 在后续业务请求中会 **403 `ACCOUNT_DISABLED`**
- 客户端须退出后重新登录；不要继续用旧 Token
- 密码登录若返回 `NEED_SMS`，需完成短信设备验证；后台「允许登录」已自动开启设备豁免至可直登

**客户端（99chat App）必做：**

| 场景 | App 行为 |
|------|----------|
| 业务接口 403 + `code: ACCOUNT_DISABLED` | 清除本地 JWT，跳转登录页；提示「该账号已禁用，请联系管理员」 |
| `POST /auth/login/password` 返回 `nextStep: NEED_SMS` | 跳转设备短信验证页 → `POST /sms/send`（`scene=DEVICE`）→ `POST /auth/login/password/verify` |
| 解禁后首次登录 | 必须退出账号后重新走登录流程 |

**运营后台：** 「允许登录」成功后前端提示：通知用户在 App 内退出并重新登录。

**审计：** `user.login_disabled.set`

---

## 3. 修改登录密码

```
POST /api/v1/users/login-password
```

**权限：** `user.write`

```json
{
  "user_uid": "v33m4qt9s2",
  "new_password": "newPass123"
}
```

- `new_password`：6～128 字节，BCrypt（与 App 一致）
- 成功后清空信任设备

> **前端校验更严：** 不少于 8 位，须同时含英文字母与数字（`loginPasswordPolicy.ts`）。

**200：** `{ "ok": true, "user_uid": "v33m4qt9s2" }`

**审计：** `user.login_password.reset`

---

## 4. 修改支付密码（资金密码）

```
POST /api/v1/users/fund-password
```

**权限：** `user.write`

```json
{
  "user_uid": "v33m4qt9s2",
  "new_fund_password": "123456"
}
```

- 必须 **固定 6 位纯数字**
- 写入 `user_wallet.pay_pin_hash`，重置错误计数与锁定

**200：** `{ "ok": true, "user_uid": "v33m4qt9s2" }`

**审计：** `user.fund_password.set`

---

## 5. 错误体（通用）

```json
{ "error": "user_not_found", "message": "user_not_found" }
```

| HTTP | error | 说明 |
|------|-------|------|
| 401 | `unauthorized` | 无 Token / Token 无效 |
| 403 | `forbidden` | 无 `user.write` |
| 404 | `user_not_found` | IM 号不存在 |
| 422 | `validation_error` | 参数校验失败 |

---

## 6. 联调顺序（建议）

1. `POST /api/v1/auth/login` → 取 `access_token`
2. `GET /api/v1/users` → 200，确认 `user_uid` 为 IM 字符串及 `wallet_balances`
3. `POST .../login-disabled`（禁用 → 列表 `user_status=0` → 解禁）
4. `POST .../login-password` → App 新密码登录
5. `POST .../fund-password` → App 支付密码验证
6. `POST .../create-by-count` → 账号列表一键复制
7. `POST .../wallet/balance-adjust` → 多币种增减与列表刷新

### 前端联调验收

- [ ] 登录后 `GET /api/v1/users` 200
- [ ] 列表 `wallet_balances` / 调账弹窗各币种可用余额正确
- [ ] 禁用/解禁与 `user_status` 同步；解禁后提示 App 重登
- [ ] 重置登录密码 / 资金密码可用
- [ ] `create-by-count` 返回 `accounts`，支持一键复制
- [ ] `balance-adjust` 成功及 `insufficient_available_balance` / `concurrent_wallet_update` 提示

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

# 调增 USDT
curl -sS -X POST "$BASE/api/v1/users/wallet/balance-adjust" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"user_uid":"e04olerlr3","currency":"USDT","direction":"add","amount":"10.00"}'
```

---

## 8. 创建用户

与 App `POST /auth/register` 一致的后端副作用：IM `account_import`、表情包初始化、系统通知好友、支付助手好友、TRON 充值地址（HD 派生）。

| 项 | 说明 |
|----|------|
| 登录密码 | 请求体提交，本批共用同一 `password`（6～128 位） |
| 手机号 | 自动分配 12 开头 11 位（如 `12012345678`），存库 E.164 `+8612…` |
| `user_uid` | 与 App 注册相同：10 位字母数字平台 ID，可作登录账号 |

### POST `/api/v1/users/create`

**权限：** `user.write`

```json
{
  "nickname": "测试用户A",
  "password": "统一登录密码",
  "sex": "1"
}
```

**200：**

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

**错误：** `duplicate_nickname`（409）、`validation_error`（422）、`WALLET_NOT_CONFIGURED`（502）

**审计：** `user.create`

### POST `/api/v1/users/create-batch`

**权限：** `user.write`；单次最多 **100** 条。

```json
{
  "password": "本批统一登录密码",
  "users": [
    { "nickname": "用户1", "sex": "1" },
    { "nickname": "用户2", "sex": "0" }
  ]
}
```

- 顶层 `password` 必填；`users[]` 仅需 `nickname`（及可选 `sex`）

**200：** `{ "items": [...], "total", "success_count", "fail_count" }`（失败条目 `ok: false`，含 `error` / `message` / `field`）

### POST `/api/v1/users/create-by-count`（前端默认入口）

**权限：** `user.write`；单次 **1～100** 个。仅需密码 + 数量，昵称自动生成。

```json
{
  "password": "统一登录密码",
  "count": 10,
  "sex": "1"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `password` | 是 | 本批共用登录密码 |
| `count` | 是 | 1～100 |
| `sex` | 否 | 写入审计 |

**200：**

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
      "trx_address": "TY8B…",
      "deposit_address": "TY8B…",
      "usdt_contract": "TR7NH…",
      "min_deposit_usdt": "1"
    }
  ]
}
```

| `accounts[]` 字段 | 说明 |
|-------------------|------|
| `user_uid` | IM 号，App 可用作平台 ID 登录 |
| `phone` / `phone_num` | 绑定手机 |
| `password` | 与本批提交的登录密码相同 |
| `trx_address` | TRON 充值地址 |

`ok: false` 表示未全部成功；`accounts` 仍含已创建账号。

**审计：** `user.create_by_count`

**前端：** 弹窗仅「登录密码 + 生成数量」；结果支持一键复制 TSV。

---

## 9. 修改余额（多币种调账）

```
POST /api/v1/users/wallet/balance-adjust
```

**权限：** `user.write`

**依赖：** `GET /api/v1/users` 返回 §1 钱包字段（弹窗读余额，无需单独查询接口）。

**请求：**

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
| `remark` | 可选备注（前端暂未填） |

**业务规则：**

| 场景 | 行为 |
|------|------|
| `add` | 对应币种余额增加 |
| `subtract` | 对应币种余额减少 |
| USDT 调减 | 上限 = 余额 − 待提现冻结 |
| TRX / CNY 调减 | 可用不足 → `insufficient_available_balance` |
| 并发冲突 | `concurrent_wallet_update`（409） |

**200：**

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

**审计：** `user.wallet.balance_adjust`（`target_user_id` = IM 号）

**币种存储（后端）：**

| 币种 | 存储字段 |
|------|----------|
| USDT | `balance_usdt_micro` |
| TRX | `balance_trx_sun` |
| CNY | `balance_platform_fen`（与 App 平台币分共用，展示 2 位小数） |

**前端：** 弹窗选择币种 → 增加/减少 → 金额；成功后刷新列表。

---

## 11. 用户详情页 — 后端待办

> **前端：** `src/views/im-admin/account/user-detail.vue`  
> **路由：** `/im-admin/account/user-detail/:id`（`:id` = IM 字符串 `user_uid`）  
> **字段约定：** 与 §1 列表一致；`user_uid` 传 `users.user_id`（10 位字母数字），勿传自增 id。  
> **详细契约：** 见项目根目录 **`前端对接文档.md`** §3.4（`users/detail`）、§3.8（消息）、手机相册小节。

**联调探测（2026-05-30，`47.239.60.107:8081`）：** 下列除标注「已有」外，均为 Spring **`404 Not Found`**（路由未实现）；列表页 §2～§9 写接口与 `GET /users` 已可用。

### 11.1 P0 — 阻塞进页（必须先做）

- [ ] **`GET /api/v1/users/detail?user_uid={IM号}`**（`user.read`）  
  聚合首屏，一次返回：
  - `profile`：与 §1 列表字段一致（含 `wallet_balances` / `wallet_frozen_by_currency`）
  - `friends`：`{ total, limit, truncated, items[] }`（limit 建议 150）
  - `groups`：`{ total, limit, truncated, items[] }`（limit 建议 80）
  - `devices`：`{ total, limit, truncated, items[] }`（`missu_user_device_history`，含 `device_type`、`device_token_masked`，不含 `http_token`）
  - `accounts_same_ip`：`shared_ips[]` + `{ total, limit, truncated, items[] }`（不含本人）；无 IP 时 `hint: no_login_ip_in_history`
  - **404** `user_not_found`；**422** `user_uid` 缺失/非法

### 11.2 P1 — 详情页已有 UI，缺接口则功能不可用

| 接口 | 权限 | 前端触发 | 状态 |
|------|------|----------|------|
| `POST /api/v1/users/login-unfreeze` | `user.write` | 「登录试错冻结解冻」弹窗（body：`user_uid` 或 `login_key`） | 待实现 |
| `GET /api/v1/messages/c2c` | `user.read` | 好友表 →「查询内容」单聊抽屉（`user_a`/`user_b` 为 IM 字符串；可选 `keyword`） | 待实现 |
| `GET /api/v1/messages/group` | `group.read` | 群表 →「查询内容」群消息抽屉（`g_id` 1～32 位字母数字） | 待实现 |
| `GET /api/v1/users/phone-album/config` | `user.read` | 「查看相册文件」抽屉 | 待实现 |
| `GET /api/v1/users/phone-album/list` | `user.read` | 相册文件分页（`page` / `page_size` / `has_more`） | 待实现 |
| `GET /api/v1/users/wallet` | `user.read` | 独立钱包查询（可选）；**详情页已改用 `detail.profile` / 内嵌 `wallet`** | 可选 |
| `POST /api/v1/users/login-disabled` | `user.write` | 禁用 / 允许登录 | **已有**（§2，与列表共用） |

> **权限说明：** 登录账号需含 `user.read`；群消息另需 `group.read`（可在 `auth/login` 的 `permissions` 中下发）。  
> **详情页数据源：** 仅 **`GET /users/detail`** 一次请求；币种表优先用响应内 **`wallet.currencies[]`**，否则由 **`profile.wallet_balances` + `profile.wallet_frozen_by_currency`** 推算可用/冻结。调账仍用 `POST .../wallet/balance-adjust`（§9）。

### 11.3 P2 — 首屏截断后的分页扩展（详情页有 hint，暂未接 UI）

当 `detail` 某段 `truncated: true` 时，前端提示继续调独立分页接口：

- [ ] **`GET /api/v1/users/friends`** — `user_uid` + 分页（字段与 `detail.friends.items[]` 单行一致）
- [ ] **`GET /api/v1/users/groups`** — `user_uid` + 分页（字段与 `detail.groups.items[]` 单行一致）
- [ ] **`GET /api/v1/users/related-by-ip`** — 同 IP 关联账号全量（与 `detail.accounts_same_ip.items[]` 一致）
- [ ] **`GET /api/v1/users/login-logs?user_uid=…`** — 设备历史全量分页（与 `detail.devices` 同源；详情页 hint「可后续扩展分页」）

可选（同 IP/设备页 `same-ip-device.vue` 已引用，详情页未直接调）：

- [ ] **`GET /api/v1/users/related-by-device`** — 同 `hardware_id` 关联账号

### 11.4 前端联调验收（用户详情）

- [ ] `GET /users/detail` 200，`profile.user_uid` 为 IM 字符串
- [ ] 好友 / 群组 / 设备 / 同 IP 四块首屏有数据；`truncated` 与 `total` 合理
- [ ] 禁用 / 解禁登录后 `profile.user_status` 刷新正确
- [ ] `GET /users/wallet` 200；表格币种 / 可用 / 冻结 / 地址与 `currencies[]` 一致
- [ ] 单聊 / 群聊抽屉分页与 `keyword` 检索可用
- [ ] 手机相册 config + list 可打开预览/缩略图 URL
- [ ] （P2）截断场景下独立分页接口与 `detail` 首屏字段一致

### 11.5 自测命令（详情相关）

```bash
BASE=http://47.239.60.107:8081
TOKEN=$(curl -sS -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 替换为列表里真实的 IM 号
UID=e04olerlr3

curl -sS "$BASE/api/v1/users/detail?user_uid=$UID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

curl -sS "$BASE/api/v1/users/wallet?user_uid=$UID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

curl -sS "$BASE/api/v1/messages/c2c?user_a=$UID&user_b=OTHER_UID&page=1&page_size=30" \
  -H "Authorization: Bearer $TOKEN"

curl -sS "$BASE/api/v1/users/phone-album/list?user_uid=$UID&page=1&page_size=100" \
  -H "Authorization: Bearer $TOKEN"
```

### 11.6 用户钱包（详情页币种表）

```
GET /api/v1/users/wallet?user_uid={IM号}
```

**权限：** `user.read`

| 表格列 | JSON 字段 |
|--------|-----------|
| 币种 | `currencies[].currency` → `USDT` / `TRX` / `CNY` |
| 可用余额 | `currencies[].balance_available` |
| 冻结 | `currencies[].balance_frozen` |
| 钱包地址 | `currencies[].wallet_address`；CNY 为 `null`（平台内账户） |
| 地址说明 | `currencies[].wallet_address_label`；USDT/TRX 为 `"TRON 充值地址"` |

**顶层字段：** `deposit_address` / `trx_address`（TRON 充值地址，USDT/TRX 共用）、`usdt_contract`、`min_deposit_usdt`。

**业务说明：**

- USDT 可用 = 总余额 − 待提现冻结（与 §9 调账弹窗一致）
- TRX / CNY 冻结当前为 `0`
- 列表 / 详情 `profile` 里的 `wallet_balances` 为**总余额**，不含充值地址；详情币种表**只用本接口**

**响应示例：**

```json
{
  "user_uid": "e04olerlr3",
  "deposit_address": "TY8B…",
  "trx_address": "TY8B…",
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

---

## 12. 本地开发

```bash
npm run dev
# 前端 http://localhost:8848
# 代理 → http://47.239.60.107:8081
```

默认账号：`admin` / `admin123`
