# 昵称可用性检查 — 公开接口

> 版本：v1.0
> 适用：99chat（Flutter / iOS / Android / Web）
> 范围：**无需登录**，任意客户端均可调用，检查昵称是否已被占用
> 相关：[nickname-client.md](./nickname-client.md)（已登录用户的修改/冷却逻辑）

---

## 1. 业务规则

| 规则 | 说明 |
|------|------|
| 全局唯一 | 昵称在所有用户间全局唯一 |
| 格式校验 | trim 首尾空格后长度 **2–32** 字符 |
| 无冷却限制 | 本接口仅检查「是否可注册/使用」，不涉及 7 天冷却 |

本接口不修改任何数据，无频率限制（依赖全局防刷机制）。

---

## 2. 鉴权与通用约定

- **Base URL**：`http://<host>:8081`
- **Header**：`Authorization: Bearer <token>` — **无需填写**，接口允许匿名访问
- **Content-Type**：`application/json`（GET 请求忽略）
- **响应体**：`{ "code": 0, "message": "ok", "data": { ... } }`
- **错误体**：`{ "code": "...", "message": "..." }`

---

## 3. 接口

### `GET /nicknames/available`

查询指定昵称是否可使用（无鉴权）。

**Query**

| 参数 | 必填 | 说明 |
|------|------|------|
| `nickname` | 是 | 要检查的昵称（服务端自动 trim 空格） |

**示例**

```
GET /nicknames/available?nickname=%E6%96%B0%E6%98%B5%E5%8F%8B
GET /nicknames/available?nickname=TestUser123
```

### 响应

**昵称可用**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "available": true,
    "reason": null
  }
}
```

**昵称已被占用**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "available": false,
    "reason": "NICKNAME_EXISTS"
  }
}
```

### `reason` 枚举

| reason | 含义 | 客户端建议 |
|--------|------|-----------|
| `null` | 可用 | 允许使用 |
| `NICKNAME_EXISTS` | 已被其他用户占用 | 提示「该昵称已被使用」 |

### 格式错误

**400 Bad Request** — 昵称长度不在 2–32（trim 后）或参数缺失。

---

## 4. 使用场景

### 注册页 — 实时预填检查

用户在注册页输入昵称时，客户端可在输入停止后（防抖 300–500ms）调用本接口，实时反馈是否可用：

```dart
// Flutter 示例
final available = await api.get('/nicknames/available', params: {'nickname': input});
if (available['data']['available']) {
  // 可用，可继续提交
} else {
  // 显示「该昵称已被使用」
}
```

### 通用昵称预检查

任意未登录页面均可使用，例如：

- 游客试玩页展示默认昵称是否可用
- 多端同步时检查昵称是否冲突
- Web 端快速注册流程

---

## 5. 与 `/me/nickname/check` 的区别

| | `/nicknames/available` | `/me/nickname/check` |
|---|---|---|
| **鉴权** | 无需登录 | 需要登录（已登录用户） |
| **冷却检查** | 不检查 | 检查 7 天冷却期 |
| **返回字段** | `available` + `reason` | `available` + `reason` + `nextChangeableAt` |
| **适用场景** | 注册页、预填、通用检查 | 修改昵称前的合法性检查 |

---

## 6. 联调检查清单

- [ ] 无 Token 请求 `/nicknames/available` → 200，正常返回
- [ ] 查询已被占用的昵称 → `available: false, reason: NICKNAME_EXISTS`
- [ ] 查询可用昵称 → `available: true, reason: null`
- [ ] 提交格式错误的昵称（过长/过短） → 400 `INVALID_INPUT`
- [ ] 提交含首尾空格的昵称 → trim 后校验，返回实际结果

---

## 7. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-06-11 | 新增公开昵称可用性检查接口文档 |
