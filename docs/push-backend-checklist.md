# 99chat 自建 Push — 后端实现清单

> 版本：v1.1（对齐客户端 v3.0）  
> 更新：2026-06-13  
> 状态：**已实现**（P0/P1 完成；P2 部分完成）  
> 客户端对接：[push-client.md](./push-client.md)  
> 运维配置：[im-chat-push-callback.md](./im-chat-push-callback.md)

---

## 实现状态总览

| 模块 | 状态 | 代码位置 |
|------|------|----------|
| Token API（§2） | ✅ | `push/PushController.java` |
| 心跳 API（§2.4） | ✅ | `user/PresenceController.java` |
| APNs 聊天/业务 Push（§3.2–3.5） | ✅ | `push/ApnsPushSender.java`、`push/PushService.java` |
| 极光 Android Push（§3） | ✅ | `push/JpushPushSender.java` |
| iOS VoIP Push（§3.6） | ✅ | `push/ApnsVoipPushSender.java`、`push/VoipPushService.java` |
| IM 聊天回调 Push（§3.2） | ✅ | `im/ImChatPushCallbackService.java` |
| 钱包/公告/欢迎 Push | ✅ | `notify/PlatformWalletNoticeService.java`、`notify/SystemNotifyService.java` |
| av_call 信令解析 + VoIP 触发 | ✅ | `call/AvCallImSignalingParser.java`、`push/VoipCallPushTrigger.java` |
| 在线跳过 + 去重 | ✅ | `user/PresenceService.java`、`im/ImPushDedupStore.java` |
| DB 配置 | ✅ | `push/PushConfigService.java`、`scripts/push-config-init.sql` |
| APNs collapse-id / thread-id | ✅ | `ImChatPushCallbackService`（按会话 `threadId`） |
| push-focus API | ✅ | `push/PushFocusController.java`、`push/PushFocusService.java` |
| 管理联调 | ✅ | `POST /admin/push/test`、`GET/PATCH /api/v1/push/config` |

---

## 0. 架构速览

```
通道 A：腾讯 IM SDK（长连接）— App 内消息、av_call 信令
通道 B：自建 Push — iOS APNs + PushKit VoIP；Android 极光（或客户端保活，服务端无额外逻辑）
```

---

## 1. 基础设施（运维待办）

### 1.1 通用

- [x] JWT 鉴权、`deviceId` 一致性、响应信封 `{code,message,data}`
- [ ] **确认生产 Base URL** 与客户端 `API_BASE_URL` 一致（待产品确认域名）
- [ ] **关闭腾讯 IM 控制台 offline push**（运维手动）

### 1.2 iOS APNs

- [x] 证书 `apns/tszs.p12`（普通）、`apns/Volp.p12`（VoIP）
- [x] Bundle ID：`chat.99chat.app`
- [x] 环境变量 `PUSH_APNS_PRODUCTION`（Debug=`false`，Release=`true`）

### 1.3 Android 极光

- [x] `push.jpush_*` 存 `app_setting` 数据库
- [x] 后端 `options.third_party_channel`（华为/荣耀/OPPO/vivo；小米需 `channel_id`）
- [ ] 厂商通道证书（运维在极光控制台配置）

### 1.4 腾讯 IM / TRTC 回调

- [x] `POST /webhook/im/message` — 聊天 Push + VoIP 触发
- [x] `POST /webhook/trtc/call-status` — 通话状态 + VoIP 触发（**遗留**；默认 `TRTC_CALLBACK_ENABLED=false`）
- [x] `POST /webhook/livekit` — LiveKit 房间事件 + `POST /calls/livekit/*` 通话（见 [livekit-call-client.md](./livekit-call-client.md)）
- [ ] IM 控制台配置回调 URL + Token（运维）

---

## 2. API（已实现）

| 接口 | 实现 |
|------|------|
| `POST /me/push-token` | upsert `(userId, deviceId)`，iOS 保存 `voipToken` |
| `POST /me/voip-push-token` | 单独更新 VoIP token |
| `DELETE /me/push-token` | 软删 `enabled=false` |
| `POST /me/heartbeat` | Redis 30s 节流（TCP `ping` 优先，本接口回退） |

无效 token 自动 `enabled=false`；注册/注销写 `app.log` 日志。

---

## 3. Push 下发逻辑（已实现）

### 3.1 通用规则

- [x] 离线 + `enabled=true` 才发
- [x] `push.skip_when_online` / `im.callback.chat_push_skip_when_online` 控制在线跳过
- [x] `msgKey` / `inviteId` Redis 去重（默认 48h TTL）

### 3.2 聊天 `type: im_chat`

- [x] IM 回调解析、摘要格式化、业务信令过滤
- [x] APNs `apns-collapse-id` = 会话 `threadId`（`c2c_*` / `group_*`）
- [x] APNs `aps.thread-id` = `c2c_{fromAccount}` / `group_{groupId}`
- [x] 极光 Android `extras.threadId` 供客户端归组（**勿**使用非法字段 `android.group`）

### 3.3–3.5 公告 / 钱包 / 欢迎

- [x] 先发 IM 自定义消息，再补系统 Push

### 3.6 音视频 `type: av_call`（iOS VoIP）

- [x] PushKit payload 含 `inviteId/callerId/calleeId/mediaType/roomId`
- [x] `inviteId` 去重；仅 `actionType=1` 且 `call_end=0` 触发

---

## 4. 配置项

| 配置 | 位置 |
|------|------|
| APNs 证书/环境 | `.env` → `PUSH_*` |
| 开关 / 极光 / IM 回调 | `app_setting` 或 `PATCH /api/v1/push/config` |
| 初始化 SQL | `scripts/push-config-init.sql` |

---

## 5. 联调命令

```bash
# 业务 Push 测试（需 Admin IP 白名单）
curl -X POST http://127.0.0.1:8081/admin/push/test \
  -H "Content-Type: application/json" \
  -d '{"toUserId":"USER_ID","title":"测试","body":"推送测试","data":{"type":"test"}}'

# 查看 token
mysql -e "SELECT user_id,device_id,platform,enabled,LEFT(push_token,24),voip_push_token IS NOT NULL AS has_voip FROM user_push_token;"

# 日志关键字
grep -E 'push token registered|push sent|apns rejected|im chat push|voip push sent' logs/app.log
```

---

## 6. 待确认（产品/运维）

- [ ] 生产环境最终域名
- [ ] IM 回调 `callback_token` 与控制台一致
- [x] 群聊 Push 时间窗口聚合 + 异步扇出（`GroupPushAggregationService`）
- [x] 会话免打扰 `PUT /me/conversation-notify`
- [ ] VoIP Push 重试策略

---

## 7. 关联文档

| 文档 | 内容 |
|------|------|
| [push-client.md](./push-client.md) | 客户端完整对接 v3.0 |
| [voip-push-client.md](./voip-push-client.md) | iOS VoIP 速查 |
| [im-chat-push-callback.md](./im-chat-push-callback.md) | IM 回调运维 |
| [call-recent-and-webhook.md](./call-recent-and-webhook.md) | TRTC 回调 |
