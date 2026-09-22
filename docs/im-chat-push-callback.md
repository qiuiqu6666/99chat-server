# IM 聊天离线 Push — 服务端配置

> 版本：v2.0  
> 适用：运维 / 后端  
> **客户端对接**见 [push-client.md](./push-client.md) §9.2（`type: im_chat`）

---

## 1. 流程

```
用户 A 发 IM 消息（SDK）
    ↓
腾讯 IM 投递 / 存漫游
    ↓
C2C.CallbackAfterSendMsg / Group.CallbackAfterSendMsg
    ↓
POST /webhook/im/message（本服务）
    ↓
解析消息 → 跳过系统号/通话信令/业务卡片
    ↓
PushService → APNs（iOS）/ 极光（Android）
```

**不使用**腾讯云 IM 离线推送；聊天通知栏由自建 Push 负责。

---

## 2. IM 控制台配置（必做）

登录 [腾讯云 IM 控制台](https://console.trtc.io/chat/) → **回调配置**：

| 项 | 值 |
|----|-----|
| 回调 URL | `https://你的域名/webhook/im/message` |
| 开启鉴权 | 建议开启，Token 写入 `im.callback.callback_token` |
| 勾选事件 | **发单聊消息之后回调**（`C2C.CallbackAfterSendMsg`） |
|  | **群内发言之后回调**（`Group.CallbackAfterSendMsg`） |

保存后约 **2 分钟**生效。可用控制台「校验」按钮测试 URL 可达。

### 鉴权说明

- 开启鉴权后，URL 带 `Sign`、`RequestTime`  
- 算法：`Sign = sha256(Token + RequestTime)`（十六进制小写）  
- 服务端校验时间差 ≤ 60 秒  

未开启鉴权时，可配置 `IM_CALLBACK_TOKEN`，通过 query `token=` 或 Header `X-Callback-Token` 校验。

---

## 3. 服务端配置（数据库）

配置存于 `app_setting` 表，由 `PushConfigService` 读取；`application.yml` / `.env` 仅作缺省回退。

初始化：

```bash
mysql ... < scripts/push-config-init.sql
```

### 3.1 开关项（`app_setting.setting_key`）

| Key | 默认 | 说明 |
|-----|------|------|
| `push.enabled` | false | 总 Push 开关 |
| `push.skip_when_online` | true | 业务 Push：30s 内心跳则跳过 |
| `push.voip_enabled` | true | iOS VoIP 来电 Push 开关 |
| `push.jpush_enabled` | false | 极光 Push 开关 |
| `push.jpush_app_key` | 空 | 极光 AppKey |
| `push.jpush_master_secret` | 空 | 极光 Master Secret |
| `push.jpush_base_url` | `https://api.jpush.cn` | 极光 API 地址 |
| `im.callback.enabled` | true | IM 回调处理开关 |
| `im.callback.chat_push_enabled` | true | 聊天 Push 开关 |
| `im.callback.callback_token` | 空 | 与 IM 控制台鉴权 Token 一致 |
| `im.callback.allowed_sdk_app_ids` | 空 | SDKAppID 白名单（逗号分隔，空=用库内 IM_SDK_APP_ID） |
| `im.callback.chat_push_skip_when_online` | false | true=30s 内心跳则不发聊天 Push |
| `im.callback.skip_sender_ids` | administrator | 额外跳过发件人（逗号分隔） |
| `im.callback.max_group_members_per_push` | 0 | 0=不限制；>0 为熔断上限 |
| `im.callback.dedup_ttl_hours` | 48 | Redis 去重 TTL |
| `im.callback.group_push_agg_seconds_small` | 10 | ≤200 人群聚合窗口（秒） |
| `im.callback.group_push_agg_seconds_medium` | 30 | 201~2000 人群 |
| `im.callback.group_push_agg_seconds_large` | 60 | >2000 人群（万人群） |
| `im.callback.group_push_small_group_threshold` | 200 | 小群上限 |
| `im.callback.group_push_large_group_threshold` | 2000 | 大群下限 |
| `im.callback.group_member_cache_ttl_minutes` | 10 | 群成员列表 Redis 缓存 |
| `im.callback.group_push_flush_batch_size` | 300 | 每轮 flush 批大小 |

群聊 Push 经 Redis 聚合后异步扇出；免打扰见客户端 `PUT /me/conversation-notify`（表 `user_conversation_notify`）。

### 3.2 APNs 凭证（`.env`）

证书目录 `apns/`（勿提交 Git）：

| 文件 | 用途 |
|------|------|
| `apns/tszs.p12` | 普通 Push |
| `apns/Volp.p12` | VoIP Push |

```bash
PUSH_ENABLED=true
PUSH_APNS_ENABLED=true
PUSH_APNS_BUNDLE_ID=chat.99chat.app
PUSH_APNS_P12_PATH=/www/wwwroot/99chat-server/apns/tszs.p12
PUSH_APNS_P12_PASSWORD=***
PUSH_APNS_VOIP_P12_PATH=/www/wwwroot/99chat-server/apns/Volp.p12
PUSH_APNS_VOIP_P12_PASSWORD=***
PUSH_APNS_PRODUCTION=true
```

启动须加载 `.env`：`scripts/start-jar-with-env.sh`

### 3.3 极光凭证（数据库）

Android 极光 **AppKey / Master Secret 存 `app_setting`**，不使用环境变量：

| Key | 说明 |
|-----|------|
| `push.jpush_enabled` | 极光开关 |
| `push.jpush_app_key` | AppKey |
| `push.jpush_master_secret` | Master Secret |
| `push.jpush_base_url` | API 地址，默认 `https://api.jpush.cn` |

### 3.4 管理 API

```http
GET  /api/v1/push/config
PATCH /api/v1/push/config
Content-Type: application/json

{ "key": "pushEnabled", "value": "true" }
```

`key` 可选：`pushEnabled`、`skipWhenOnline`、`voipPushEnabled`、`jpushEnabled`、`jpushAppKey`、`jpushMasterSecret`、`jpushBaseUrl`、`imCallbackEnabled`、`chatPushEnabled`、`callbackToken`、`allowedSdkAppIds`、`chatPushSkipWhenOnline`、`skipSenderIds`、`maxGroupMembersPerPush`、`dedupTtlHours`。

自动跳过（无需配置）：

- `99Messenger`、`99Chat`（系统/钱包通知号，已有业务 Push）
- `av_call`、`rtc_call` 通话信令（VoIP Push 单独处理）
- `platform_wallet_notice`、`announcement`、`wallet_order` 等业务自定义消息
- `user_typing_status`、`red_packet_claim_notice`、`friend_became_friends` 静默自定义消息
- 静默群 Tips（改群名/头像/公告/简介、全员禁言、设/取消管理员）

---

## 4. 推送给客户端的 Payload

客户端按 [push-client.md §9.2](./push-client.md#92-聊天离线-push--type-im_chat) 处理。

| 字段 | 说明 |
|------|------|
| `type` | 固定 `im_chat` |
| `chatType` | `c2c` / `group` |
| `fromAccount` | 发送者 IM userId |
| `groupId` | 群聊时有值 |
| `msgKey` | IM 消息唯一键 |

---

## 5. VoIP 来电信令（TUICallKit `av_call`）

IM 回调 `C2C.CallbackAfterSendMsg` 中，`MsgBody[].MsgContent.Data` 为 **JSON 字符串**，TUICallKit 实际格式如下（已与线网 `call_callback_log` 对齐）：

**外层 envelope**

```json
{
  "actionType": 1,
  "businessID": 1,
  "data": "<内层 av_call JSON 字符串>",
  "inviteID": "d6d41bff0508da001befd65deb4032bf",
  "inviteeList": ["被叫 userId"],
  "inviter": "主叫 userId",
  "onlineUserOnly": false,
  "timeout": 30
}
```

**内层 `data`（解析后）**

```json
{
  "businessID": "av_call",
  "call_end": 0,
  "call_type": 1,
  "data": {
    "cmd": "audioCall",
    "inviter": "主叫 userId",
    "room_id": 854118195,
    "userIDs": ["被叫 userId"]
  },
  "room_id": 854118195,
  "version": 4
}
```

| 字段 | 说明 |
|------|------|
| `actionType` | `1`=邀请来电；`2`/`3`=拒接/取消（**不触发** VoIP Push） |
| `call_end` | `0`=进行中；`>0` 为终态（不触发 VoIP Push） |
| `call_type` | `1`=语音，`2`=视频 → VoIP payload `mediaType` |
| `inviteID` | 去重键，映射到 Push payload `inviteId` |
| `inviter` / `inviteeList` | 主叫 / 被叫；缺省时回退 `From_Account` / `To_Account` |

服务端 `AvCallImSignalingParser` 支持：`data` 为字符串或对象、顶层直接 `businessID=av_call`、数字 `room_id`。

触发 VoIP Push 条件：`actionType=1` 且 `call_end=0`，被叫须已上报 `voipToken`（见 [push-client.md §10](./push-client.md#10-音视频来电推送)）。

---

## 6. 与 TRTC 回调的区别

| 路径 | 用途 |
|------|------|
| `/webhook/trtc/call-status` | TUICallKit 通话状态 + `av_call` 信令 + VoIP Push |
| `/webhook/im/message` | **聊天消息**离线 Push |

两者独立配置，互不影响。

归档启用后，`AfterSendMsg` 先入 Kafka 再异步 Push，见 [message-archive-kafka.md](./message-archive-kafka.md)。

---

## 7. 管理端联调

```http
POST /admin/push/test
```

```json
{
  "toUserId": "abc12def34",
  "title": "测试推送",
  "body": "这是一条离线推送",
  "data": { "type": "test" }
}
```

需 Admin IP 白名单；目标用户须已通过 `/me/push-token` 上报 token。

---

## 8. 排查

| 现象 | 检查 |
|------|------|
| 完全无 Push | `push.enabled`、APNs/极光配置、客户端是否上报 token |
| 有业务 Push 无聊天 Push | IM 控制台 AfterSendMsg 回调；`im.callback.chat_push_enabled` |
| 回调 403 | `im.callback.callback_token` / Sign 是否与控制台一致 |
| 群聊部分人收不到 | 是否超过 `max_group_members_per_push` |
| 重复通知 | 客户端 `msgKey` 去重；Redis dedup 是否生效 |
| VoIP 无唤醒 | `push.voip_enabled`；客户端 `voipToken`；信令是否为 `actionType=1`；见 §5 |

日志关键字：`im chat push c2c`、`im chat push group`、`voip push sent`、`apns voip rejected`。

---

关联：[push-client.md](./push-client.md)、[voip-push-client.md](./voip-push-client.md)、[call-recent-and-webhook.md](./call-recent-and-webhook.md)
