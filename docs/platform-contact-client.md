# 平台启动元信息(contact) · 客户端对接

> 版本:v1.2  
> 适用方:**App / Flutter 客户端(必读)**、运营后台、运维  
> Base URL:`http://<host>:8081`  
> 服务端文档:见 [platform-and-feedback.md](./platform-and-feedback.md)(配置、判定规则、灰度算法)

---

## 1. 接口速览

| 项 | 值 |
| --- | --- |
| 路径 | `GET /api/v1/platform/contact`(免登录)|
| 兼容老路径 | `GET /platform/contact`(老客户端走信封;新版已扁平)|
| 请求 | 4 个可选 Header + 4 个对应 query 参数(优先级:query > header)|
| 响应 | 单一 JSON 对象,无外层信封;`null` 字段被省略(`@JsonInclude(NON_NULL)`) |
| 缓存 | 服务端 5 秒本地内存缓存(同五元组 key 命中复用)|
| 鉴权 | 不需要 |

---

## 2. 何时调用

| 时机 | 用途 |
| --- | --- |
| **App 冷启动** | 一次拉完所有启动期元数据 |
| **从后台恢复 + 在前台 > 1h** | 检查是否有新版本要升 |
| 收到推送后用户点击"打开设置" | 不必重新调(设置页静态展示即可)|

> 不要在每次页面进入时调用——服务端会缓存,但每次还是占带宽。**单次启动 + 关键节点**就够。

---

## 3. 请求

### 3.1 Headers(全部可选,但推荐都发)

| Header | 示例 | 来源 | 说明 |
| --- | --- | --- | --- |
| `X-Client-Platform` | `android` / `ios` / `web` | `Platform.isAndroid` 等 | 服务端据此选择 `app_client_version` / `app_splash_config` 表的对应平台记录 |
| `X-App-Version` | `1.2.3` | `packageInfo.version` | 用于强制升级比对 + 启动图最低版本分流 |
| `X-App-Version-Code` | `45` | `packageInfo.buildNumber`(整数)| **强制升级判定的优先依据** |
| `X-App-Channel` | `google_play` / `huawei` / `app_store` | 安装渠道 | 用于启动图按渠道分流 |
| `X-Device-Id` | `7f3b9e2a-...` | 首次启动生成的 UUID(持久化)| 灰度命中因子;**缺省视作全员命中** |

### 3.2 Query 参数(全部可选,优先级 > header)

```
GET /api/v1/platform/contact
  ?deviceId=7f3b9e2a-1234-5678-9abc-def012345678
  &appVersion=1.2.3
  &appVersionCode=45
  &appChannel=google_play
```

> query 参数和 header 同时传时,**query 优先**;用于 H5/扫码工具/调试场景。

### 3.3 cURL 示例

```bash
# 推荐:全 header
curl -H 'X-Client-Platform: android' \
     -H 'X-Device-Id: 7f3b9e2a-1234-5678-9abc-def012345678' \
     -H 'X-App-Version: 1.2.3' \
     -H 'X-App-Version-Code: 45' \
     -H 'X-App-Channel: google_play' \
     http://127.0.0.1:8081/api/v1/platform/contact
```

---

## 4. 响应

### 4.1 字段表

| 字段 | 类型 | 含义 | 客户端怎么用 |
| --- | --- | --- | --- |
| `website` | string | 官网 URL | 「关于我们」页打开 |
| `email` | string | 客服邮箱 | 「联系我们」展示 + 点击 `mailto:` |
| `version` | string | 服务端最新版本号,如 `3.0.1` | 「关于」页展示"最新版本" |
| `build` | string | 服务端最新构建号,如 `2` | 「关于」页展示"最新构建号" |
| `downloadUrl` | string | 最新版本下载链接 | 强制升级弹窗跳转 |
| `platform` | string | 服务端识别的客户端平台 | **调试用**:与服务端预期不一致时排查 Header |
| `updateType` | string | `FORCE` / `OPTIONAL` | 升级弹窗策略 |
| `minVersion` | string? | 强制升级最低版本字符串 | 仅在 `versionCode` 缺失时使用 |
| `minVersionCode` | int? | 强制升级最低构建号 | 优先用 |
| `changelog` | string? | 更新内容(纯文本,换行符 `\n`) | 强制升级弹窗正文 |
| `grayPercent` | int? | 当前灰度比例 0~100 | 调试用 |
| `inGray` | boolean | 当前设备是否命中灰度 | 仅调试;客户端不应据此改变行为(由服务端决定是否下发升级)|
| `splash` | object | 启动图配置 | 见 §4.3 |

### 4.2 完整响应示例(命中数据)

```json
{
  "website": "https://99chat.vip/",
  "email": "admin@99chat.chat",
  "version": "3.0.1",
  "build": "2",
  "downloadUrl": "https://down.99chat.vip",
  "platform": "ios",
  "updateType": "FORCE",
  "minVersion": "2",
  "minVersionCode": 2,
  "changelog": "体验全面升级\n...",
  "grayPercent": 100,
  "inGray": true,
  "splash": {
    "enabled": true,
    "version": "20260907-01",
    "imageUrl": "https://.../splash.webp",
    "imageMd5": "abc123...",
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
```

### 4.3 `splash` 子对象

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `enabled` | boolean | **false** 时客户端回退包内默认图 |
| `version` | string | 服务端启动图版本号,本地缓存 key |
| `imageUrl` | string? | webp 图片 URL |
| `imageMd5` | string? | 用于本地校验图片一致性 |
| `contentType` | string? | 固定 `image/webp` |
| `width` / `height` / `bytes` | int? | 图片尺寸与大小,可选展示 |
| `fit` | string? | `cover` / `contain` / `fill` / `fitWidth` / `fitHeight` |
| `startAt` / `endAt` | string? | ISO-8601 UTC,展示窗口(已由服务端过滤)|
| `minAppVersion` | string? | 最低 App 版本(已由服务端过滤) |
| `updatedAt` | string? | 服务端最后更新时间 |

### 4.4 最小响应(无数据时)

```json
{
  "website": "https://99chat.vip/",
  "email": "admin@99chat.chat",
  "version": "1.0.0",
  "build": "1",
  "downloadUrl": "https://99chat.com/download",
  "platform": "android",
  "updateType": "OPTIONAL",
  "inGray": false,
  "splash": {
    "enabled": false,
    "version": "default"
  }
}
```

**~242 字节**。`null` 字段(升級/启动图的非空子字段)被自动省略。

---

## 5. 客户端实现示例

### 5.1 Flutter / Dio

```dart
class PlatformContact {
  final String website;
  final String email;
  final String version;
  final String build;
  final String downloadUrl;
  final String platform;
  final String updateType;          // FORCE | OPTIONAL
  final int? minVersionCode;
  final String? minVersion;
  final String? changelog;
  final int? grayPercent;
  final bool? inGray;
  final SplashInfo splash;

  bool get isForceUpdate =>
      updateType == 'FORCE' &&
      (minVersionCode != null || (minVersion?.isNotEmpty ?? false));

  factory PlatformContact.fromJson(Map<String, dynamic> j) => PlatformContact(
        website: j['website'] as String,
        email: j['email'] as String,
        version: j['version'] as String,
        build: j['build'] as String,
        downloadUrl: j['downloadUrl'] as String,
        platform: j['platform'] as String,
        updateType: (j['updateType'] as String?) ?? 'OPTIONAL',
        minVersionCode: j['minVersionCode'] as int?,
        minVersion: j['minVersion'] as String?,
        changelog: j['changelog'] as String?,
        grayPercent: j['grayPercent'] as int?,
        inGray: j['inGray'] as bool?,
        splash: SplashInfo.fromJson(j['splash'] as Map<String, dynamic>? ?? {}),
      );
}

class SplashInfo {
  final bool enabled;
  final String version;
  final String? imageUrl;
  final String? imageMd5;
  // ... 其余字段

  factory SplashInfo.fromJson(Map<String, dynamic> j) => SplashInfo(
        enabled: (j['enabled'] as bool?) ?? false,
        version: (j['version'] as String?) ?? 'default',
        imageUrl: j['imageUrl'] as String?,
        imageMd5: j['imageMd5'] as String?,
        // ...
      );
}

/// 启动时调用一次,后续用本地缓存
Future<PlatformContact> fetchPlatformContact({
  required String baseUrl,
  required String deviceId,
  required PackageInfo pkg,
  required String channel,
}) async {
  final dio = Dio(BaseOptions(
    baseUrl: baseUrl,
    connectTimeout: const Duration(seconds: 5),
    receiveTimeout: const Duration(seconds: 5),
  ));
  final resp = await dio.get<Map<String, dynamic>>(
    '/api/v1/platform/contact',
    options: Options(headers: {
      'X-Client-Platform': Platform.isAndroid ? 'android' : 'ios',
      'X-Device-Id': deviceId,
      'X-App-Version': pkg.version,
      'X-App-Version-Code': pkg.buildNumber,
      'X-App-Channel': channel,
    }),
  );
  return PlatformContact.fromJson(resp.data!);
}
```

### 5.2 强制升级弹窗逻辑

```dart
Future<void> maybeShowForceUpdate(
  BuildContext ctx,
  PlatformContact info,
  int currentBuildNumber,
) async {
  if (info.updateType != 'FORCE') return;

  final shouldForce = info.minVersionCode != null
      ? currentBuildNumber < info.minVersionCode!
      : info.minVersion != null &&
          compareVersions(currentVersion, info.minVersion!) < 0;

  if (!shouldForce) return;

  await showDialog(
    context: ctx,
    barrierDismissible: false,
    builder: (_) => AlertDialog(
      title: const Text('需要更新'),
      content: SingleChildScrollView(
        child: Text(info.changelog ?? '请升级到最新版本'),
      ),
      actions: [
        TextButton(
          onPressed: () => launchUrl(Uri.parse(info.downloadUrl)),
          child: const Text('立即更新'),
        ),
      ],
    ),
  );
}

int compareVersions(String a, String b) {
  // 按 . / + / - 拆段逐段数字比,空段视为 0
  final pa = a.split(RegExp(r'[.+\-]'));
  final pb = b.split(RegExp(r'[.+\-]'));
  final n = pa.length > pb.length ? pa.length : pb.length;
  for (var i = 0; i < n; i++) {
    final sa = i < pa.length ? pa[i] : '0';
    final sb = i < pb.length ? pb[i] : '0';
    final ia = int.tryParse(sa);
    final ib = int.tryParse(sb);
    if (ia != null && ib != null) {
      final c = ia.compareTo(ib);
      if (c != 0) return c;
    } else {
      final c = sa.toLowerCase().compareTo(sb.toLowerCase());
      if (c != 0) return c;
    }
  }
  return 0;
}
```

### 5.3 启动图缓存与展示

```dart
/// 启动时下载并缓存启动图
Future<void> preloadSplash(PlatformContact info) async {
  if (!info.splash.enabled || info.splash.imageUrl == null) return;

  // 用 version 做本地 key,内容更新时自动失效
  final dir = await getApplicationDocumentsDirectory();
  final file = File('${dir.path}/splash_${info.splash.version}.webp');

  if (await file.exists()) {
    // 已缓存,直接用
    _splashPath = file.path;
    return;
  }

  // 下载并校验
  final resp = await Dio().get<List<int>>(
    info.splash.imageUrl!,
    options: Options(responseType: ResponseType.bytes),
  );
  await file.writeAsBytes(resp.data!);
  _splashPath = file.path;
}

String? _splashPath;
```

---

## 6. 业务规则(必须实现)

### 6.1 强制升级判定

```
if (info.updateType != 'FORCE') return false;
if (info.minVersionCode != null) {
    return currentBuildNumber < info.minVersionCode;
}
if (info.minVersion != null) {
    return compareVersions(currentVersion, info.minVersion) < 0;
}
return true;  // 服务端说 FORCE 但没给阈值,降级为强制
```

### 6.2 启动图缓存策略(建议)

| 策略 | 说明 |
| --- | --- |
| **按 `splash.version` 本地缓存** | 服务端图片更新时,自动失效 |
| **校验 `imageMd5`** | 防止 CDN 缓存污染导致旧图片停留 |
| **`bytes` > 5 MB 时弹提示** | 弱网场景可跳过本次启动图 |

### 6.3 错误处理

| HTTP | 处理 |
| --- | --- |
| 200 | 正常解析;无 `platform/upgrade/splash` 数据时,UI 走默认占位 |
| 4xx / 5xx | 不要因为这个接口失败阻挡启动流程;**静默降级** |
| 超时 | 同上;下次启动重试 |

---

## 7. 后台运营录入流程

1. **升级信息**:`app_client_version` 表,由 `/api/v1/admin/...` 后台维护(具体后台路径见运营文档)
   - 必填:`platform / version / version_code / update_type / enabled / gray_percent`
   - `enabled=0` 时**不**下发给客户端
2. **启动图**:`app_splash_config` 表,由后台维护 + OSS 上传
   - 必填:`image_url / enabled / platforms / start_at / end_at`

详见 [platform-and-feedback.md](./platform-and-feedback.md) §1「配置项」。

---

## 8. 联调 checklist

- [ ] 启动期调用一次,请求头完整
- [ ] 解析 `updateType=FORCE` 时正确弹窗(不被任何业务覆盖)
- [ ] `downloadUrl` 点击能在系统浏览器打开
- [ ] `splash.enabled=true` 时优先展示服务端图片;`false` 时回退包内默认
- [ ] `splash.version` 变化时自动重新下载
- [ ] 旧客户端(只解析 5 个老字段)仍能正常展示「关于」页
- [ ] 接口失败时不阻挡启动流程
- [ ] 升级弹窗的 `changelog` 多行渲染正常(`\n` → 换行)

---

## 9. 常见问题

### Q1:升级后服务端什么时候能识别我的新版本?

A:取决于**后台录入新版本记录**的时间,不是发版即生效。运营在后台录入 → 写入 `app_client_version` → 5 秒缓存过期后全网生效。

### Q2:`inGray=false` 但 `updateType=FORCE`,我需要升级吗?

A:**要**。`inGray` 只决定是否下发新版本,不影响"是不是必须升"。`updateType=FORCE` 是硬性的。

### Q3:能不能客户端自己判断要不要升级,不要服务端介入?

A:可以,但**不推荐**。自己拉版本号比对需要单独的接口(本接口也行,但要自己解析 minVersion),服务端已经做了为什么重复造轮子。运营要发新版时,服务端可立即控制客户端行为。

### Q4:`X-Device-Id` 没传会怎样?

A:`inGray=true`(视作全员命中)。这意味着**运营可以无门槛控制所有用户**。如果想避免灰度命中失误,**务必**传。

### Q5:灰度比例改了,客户端多久能感知?

A:服务端 5 秒缓存过期后。客户端无需处理,服务端会在下次返回时把 `grayPercent` 改成新值。