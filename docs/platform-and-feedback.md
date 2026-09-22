# 平台联系信息 & 用户反馈接口

> 版本：v1.1  
> Base URL（开发）：`http://47.239.60.107:8081`

---

## 1. 平台官方联系信息（含启动图 / 升级 / 灰度）

### GET /platform/contact

**无需登录**，用于「关于我们 / 帮助与反馈」页展示官网与客服邮箱。  
从 v1.1 起，**启动期一次性返回启动图、强制升级信息、灰度命中结果**，客户端可在 App 启动时只调一次。

**请求**

```http
GET /platform/contact HTTP/1.1
X-Client-Platform: android          # 服务端识别用,影响灰度目标平台
X-Device-Id: 7f3b...                # 灰度命中因子(可选;缺省视作全员命中)
X-App-Version: 1.2.0                # 客户端版本号(可选)
X-App-Version-Code: 45              # 客户端构建号(可选,优先用于强制升级判定)
X-App-Channel: google_play          # 安装渠道(可选,用于启动图分流)
```

也支持 query 参数降级(便于 web/curl 调用):

```
GET /platform/contact?deviceId=7f3b...&appVersion=1.2.0&appVersionCode=45&appChannel=google_play
```

> query 参数优先,缺省时回退到请求头。

**成功响应（200）**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "website": "https://99chat.example.com",
    "email": "support@99chat.example.com",
    "version": "1.2.0",
    "build": "45",
    "downloadUrl": "https://99chat.example.com/download",

    "platform": "android",
    "updateType": "FORCE",
    "minVersion": "1.1.0",
    "minVersionCode": 30,
    "changelog": "1. 修复转账页面点击无反应\n2. 新增深色模式",
    "grayPercent": 30,
    "inGray": true,

    "splash": {
      "enabled": true,
      "version": "20260907-01",
      "imageUrl": "https://.../splash.webp",
      "imageMd5": "...",
      "contentType": "image/webp",
      "width": 1080,
      "height": 1920,
      "bytes": 234567,
      "fit": "cover",
      "startAt": "2026-09-01T00:00:00Z",
      "endAt": "2026-09-30T23:59:59Z",
      "minAppVersion": "1.1.0",
      "updatedAt": "2026-09-06T10:00:00Z"
    }
  }
}
```

> 管理端直连 `/api/v1/platform/contact` 时无外层信封，字段相同。  
> 所有字段在 `null` 时被省略（`@JsonInclude(NON_NULL)`），旧客户端不会感知到新字段的存在。

**字段说明**

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `website` / `email` | string | 平台联系方式,只来自 `app_setting` / `application.yml` |
| `version` / `build` / `downloadUrl` | string | 客户端最新版本信息;优先 `app_client_version`,否则回退到平台配置 |
| `platform` | string | 服务端识别到的客户端平台(`android` / `ios` / `web`);识别失败时默认 `android`,客户端可据此核对 |
| `updateType` | string | `FORCE` = 强制升级,`OPTIONAL` = 可选升级 |
| `minVersion` | string | 强制升级的最低版本号(字符串,如 `1.1.0`) |
| `minVersionCode` | integer | 强制升级的最低构建号(整数,如 `30`);优先使用 |
| `changelog` | string | 更新内容,纯文本,换行符 `\n` |
| `grayPercent` | integer | 当前灰度比例 0~100;0 时省略 |
| `inGray` | boolean | 当前设备是否命中灰度;`deviceId` 缺省时永远 `true` |
| `splash` | object | 启动图配置;`enabled=false` 时客户端回退包内默认图 |

**强制升级判定规则**

1. 若服务端 `app_client_version.update_type = FORCE`:
   - 优先比较 `client.appVersionCode < row.minVersionCode` → 强制升级
   - 否则比较 `client.appVersion < row.minVersion`(按 `.` / `+` / `-` 拆段逐段数字比,空段视为 0)→ 强制升级
   - 客户端完全没传版本时 → 强制升级(降级策略)
2. 若 `update_type = OPTIONAL` → 永远 `OPTIONAL`,不管 `minVersion*`
3. 表中无启用记录 → 全部字段为空 / `OPTIONAL`

**灰度命中规则**

- `grayPercent <= 0` → `inGray = false`
- `grayPercent >= 100` → `inGray = true`
- `deviceId` 缺省 → `inGray = true`(视作全员命中,方便运营不下发 deviceId 也能全量)
- 命中算法:取 `SHA-256(grayHashSecret + ":" + deviceId)` 的前 4 字节模 100,与 `grayPercent` 比较
- 哈希密钥:配置项 `chat99.platform.gray-hash-secret` 或环境变量 `PLATFORM_GRAY_HASH_SECRET`;缺省回退为常量 `chat99`(跨环境不稳定,**生产建议显式配置**)

**配置项**（`application.yml` / 环境变量 / 数据库 `app_setting`）

| 配置 | 环境变量 | DB Key | 说明 |
| --- | --- | --- | --- |
| `chat99.platform.website` | `PLATFORM_WEBSITE` | `platform.website` | 官网 URL |
| `chat99.platform.email` | `PLATFORM_EMAIL` | `platform.email` | 官方邮箱 |
| `chat99.platform.version` | `PLATFORM_VERSION` | `platform.version` | 最新版本号，如 `1.0.0` |
| `chat99.platform.build` | `PLATFORM_BUILD` | `platform.build` | 构建号，如 `45` |
| `chat99.platform.download-url` | `PLATFORM_DOWNLOAD_URL` | `platform.download_url` | 下载链接 |
| `chat99.platform.gray-hash-secret` | `PLATFORM_GRAY_HASH_SECRET` | — | 灰度命中哈希密钥 |

> 升级 / 启动图信息来自 `app_client_version` / `app_splash_config` 表,由后台管理系统维护,不暴露在 `/api/v1/platform/config` 热更新里。

---

## 2. 用户反馈提交

### POST /feedback

**需 JWT**：`Authorization: Bearer <token>`

**Content-Type**：`multipart/form-data`

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `type` | ✅ | 反馈类型：`suggestion`（建议）、`bug`（错误/问题）、`other`（其他） |
| `content` | ✅ | 反馈正文，最长 2000 字 |
| `clientVersion` | ❌ | 客户端版本号，如 `1.2.3+45`；建议每次请求由 App 自动带上 |
| `screenshots` | ❌ | 相关截图，可传多张（同一字段名重复上传），最多 5 张 |

**图片要求**（与群头像上传一致）：

- 支持 `image/jpeg`、`image/png`、`image/webp`
- 客户端若只能发 `application/octet-stream`，文件名须带 `.jpg/.jpeg/.png/.webp`
- 单张最大 10 MB

**成功响应（200）**

```json
{
  "id": 1,
  "type": "suggestion",
  "content": "希望增加深色模式",
  "screenshotUrls": [
    "https://99chat.oss-cn-hongkong.aliyuncs.com/feedback/brbp11nv6s/1716543210_a1b2c3d4_preview.jpg"
  ],
  "clientVersion": "1.2.3+45",
  "createdAt": "2026-05-24T12:00:00Z"
}
```

**错误码**

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | content 为空 |
| 400 | `INVALID_FEEDBACK_TYPE` | type 不是 suggestion/bug/other |
| 400 | `CONTENT_TOO_LONG` | 正文超过 2000 字 |
| 400 | `TOO_MANY_SCREENSHOTS` | 截图超过 5 张 |
| 400 | `INVALID_IMAGE` | 不是有效图片 |
| 415 | `UNSUPPORTED_TYPE` | 图片 MIME/后缀不支持 |
| 413 | `FILE_TOO_LARGE` | 单张超过 10 MB |
| 503 | `OSS_NOT_CONFIGURED` | OSS 未配置（有截图时） |
| 401 | — | 未登录 |

**反馈类型对照**

| type 值 | 含义 |
| --- | --- |
| `suggestion` | 建议 |
| `bug` | 错误 / 问题反馈 |
| `other` | 其他 |

数据写入表 `user_feedback`，截图上传至 OSS 前缀 `feedback/{userId}/`。

---

## 3. Flutter 示例

### 获取官网与邮箱

```dart
final res = await dio.get("$base/platform/contact");
final data = res.data["data"] as Map<String, dynamic>;
final website = data["website"] as String;
final email = data["email"] as String;
final version = data["version"] as String? ?? "";
final build = data["build"] as String? ?? "";
final downloadUrl = data["downloadUrl"] as String? ?? "";
```

### 提交反馈（含截图）

```dart
final form = FormData.fromMap({
  "type": "bug",
  "content": "转账页面点击无反应",
  "clientVersion": "1.2.3+${packageInfo.buildNumber}", // 可选，建议自动带上
});
for (final path in screenshotPaths) {
  form.files.add(MapEntry(
    "screenshots",
    await MultipartFile.fromFile(path, filename: "shot.jpg"),
  ));
}
await dio.post(
  "$base/feedback",
  data: form,
  options: Options(headers: {"Authorization": "Bearer $jwt"}),
);
```

---

## 4. 运维说明

- 修改官网/邮箱/版本/构建号/下载链接：改环境变量或 `PATCH /api/v1/platform/config`（`key`: `website` / `email` / `version` / `build` / `downloadUrl`），或写入 `app_setting` 后无需重启（DB 优先）。
- 反馈数据目前仅入库，无管理后台列表接口；如需运营查看可后续加 `GET /admin/feedback`。
