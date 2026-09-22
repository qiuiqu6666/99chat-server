# 99chat-server 对接文档索引

面向 **Flutter 客户端**、**运营后台** 与 **运维** 的 API 说明，均位于本目录。

| 文档 | 适用方 | 内容 |
|------|--------|------|
| [admin-panel-menu.md](./admin-panel-menu.md) | **运营后台** | 主菜单、二级菜单、路由、分期上线 |
| [admin-api-v1-im-user.md](./admin-api-v1-im-user.md) | **运营后台** | `/api/v1` 用户列表页、登录、写操作 |
| [registration-and-login.md](./registration-and-login.md) | 客户端 | 注册、登录、JWT、设备信任、短信 |
| [qr-web-login-client.md](./qr-web-login-client.md) | **客户端（推荐）** | Web 扫码登录：出码、轮询、App 确认、TokenResult |
| [qr-web-login-app.md](./qr-web-login-app.md) | **App 移动端（推荐）** | 扫码识别、鉴权拦截器、scan/confirm、错误文案、联调清单 |
| [wallet-client.md](./wallet-client.md) | **客户端（推荐）** | 钱包：USDT/平台币、充值、互兑、转账、红包、提现、IM 卡片 |
| [wallet-order-card-anti-replay-client.md](./wallet-order-card-anti-replay-client.md) | **客户端** | 钱包 IM 卡片防重放/防伪造（发送幂等、禁转发、REST 为准） |
| [wallet-order-card-anti-replay-server.md](./wallet-order-card-anti-replay-server.md) | **服务端** | 钱包 IM 卡片 BeforeSend 闸门、同单去重、代发方案 |
| [life-payment-client.md](./life-payment-client.md) | **客户端（推荐）** | 生活缴费：话费/水/电/燃气查询、下单、轮询、取消 |
| [life-payment-worker.md](./life-payment-worker.md) | **插件 Worker** | 生活缴费插件鉴权、领任务、心跳、回调 |
| [life-payment-yuanren.md](./life-payment-yuanren.md) | **服务端 / 运维** | 大猿人上游：话费充值 + 电费缴费 |
| [wallet-red-packet-claim-notice-client.md](./wallet-red-packet-claim-notice-client.md) | **客户端** | 群红包领取灰字通知（IM 定向 + TCP 卡片刷新） |
| [backend-wallet-api.md](./backend-wallet-api.md) | **客户端 + 后端** | 转账查单 GET、幂等 `clientOrderId`、unknown recover |
| [wallet.md](./wallet.md) | 后端 + 运维 | 钱包模块配置与 Admin API |
| [nickname-client.md](./nickname-client.md) | **客户端（推荐）** | 修改昵称：7 天冷却、唯一性、预检与 PATCH |
| [user-avatar.md](./user-avatar.md) | **客户端（推荐）** | 修改用户头像：`POST /me/avatar` |
| [backend-favorites-api.md](./backend-favorites-api.md) | **客户端（推荐）** | 消息收藏 `/me/favorites`（非表情收藏） |
| [message-archive-client.md](./message-archive-client.md) | **客户端（推荐）** | IM 消息归档历史查询、分页、清空与 MsgId 解析 |
| [ai-assistant-client.md](./ai-assistant-client.md) | **客户端（推荐）** | AI 助手独立页：历史、硬删、SSE 流式、闲聊带图、分析文件、生图/改图、分析好友/群/粘贴 |
| [device-challenge-sms.md](./device-challenge-sms.md) | 客户端 | 密码登录新设备验证发码（方案 A） |
| [cloud-sync-client.md](./cloud-sync-client.md) | **客户端（推荐）** | 通讯录 + 相册云端同步 — 流程、字段、示例、重试 |
| [cloud-sync-api.md](./cloud-sync-api.md) | 客户端 | 接口路径速查 |
| [cloud-sync-database.md](./cloud-sync-database.md) | 客户端 + 后端 | 落库时机与表结构 |
| [chat-attachment-api.md](./chat-attachment-api.md) | **客户端（推荐）** | 聊天大附件 `/me/chat/**`、自定义消息、错误码 |
| [chat-native-video-api.md](./chat-native-video-api.md) | **客户端（推荐）** | 超阈值原生视频后端代发 `TIMVideoFileElem` |
| [chat-attachment-ops.md](./chat-attachment-ops.md) | **后端 + 运维** | 独立私有桶、CORS、30 天清理、灰度开关 |
| [platform-wallet-notice-client.md](./platform-wallet-notice-client.md) | **客户端（推荐）** | 支付助手 `99Chat` · `platform_wallet_notice` 卡片 |
| [system-notify-and-announcements.md](./system-notify-and-announcements.md) | 客户端 + 运营 | 系统号 `99Messenger`、公告 `/me/announcements` |
| [official-account.md](./official-account.md) | 客户端 + 运营 | 腾讯云**原生公众号**（发现、订阅、广播）；与系统通知方案二选一或并存过渡 |
| [platform-contact-client.md](./platform-contact-client.md) | **客户端（推荐）** | 启动元信息：联系信息 + 启动图 + 强制升级 + 灰度命中，Flutter 示例 |
| [user-privacy-and-search.md](./user-privacy-and-search.md) | 客户端 | 隐私开关、用户搜索 |
| [backend-add-friend-via-card-integration.md](./backend-add-friend-via-card-integration.md) | 客户端 | 名片/二维码/群 — 查询他人隐私与加好友预检 |
| [group-avatar.md](./group-avatar.md) | 客户端 | 群头像上传 |
| [group-privacy-client.md](./group-privacy-client.md) | 客户端 | 群隐私保护开关 GET/PUT |
| [community-group-invite.md](./community-group-invite.md) | 客户端 | 社群邀请（历史 IM SDK 方案，见 group-member-invite） |
| [group-member-invite.md](./group-member-invite.md) | 客户端 | Public/Meeting/Community 邀请与审批（REST） |
| [group-member-change-client.md](./group-member-change-client.md) | 客户端 | 成员变动灰字：App `sendMessage`（旧 GroupTips 方案已废） |
| [group-profile-client.md](./group-profile-client.md) | 客户端 | 群资料/成员 REST 展示源 + TCP patch |
| [group-governance-client.md](./group-governance-client.md) | 客户端 | 建群/退群/踢人/解散/转让 REST |
| [group-live-implementation-plan.md](./group-live-implementation-plan.md) | **全员（实施）** | 群直播完整实施计划：里程碑 M0–M6、任务分解、切流、风险 |
| [group-live-architecture.md](./group-live-architecture.md) | **全员（推荐）** | 群直播完整架构：状态机、模块、DDL、Webhook、钱包、互斥、测试 |
| [group-live-app-api.md](./group-live-app-api.md) | **客户端（推荐）** | 群直播 App 接口：9 个 REST + curl/JSON 示例 + IM |
| [group-live-client.md](./group-live-client.md) | 客户端（旧草案） | 已被 app-api 取代；产品背景可参考 |
| [group-live-service/README.md](../../group-live-service/README.md) | **微服务** | `/www/wwwroot/group-live-service` 独立服务构建、配置、端口 |
| [group-live-server.md](./group-live-server.md) | **服务端** | 主服 vs group-live-service 分工、落地 checklist |
| [group-create-client.md](./group-create-client.md) | **客户端（推荐）** | 建群流程、防重复、失败恢复、迁移清单 |
| [online-presence-client.md](./online-presence-client.md) | **客户端（推荐）** | 在线时间 `lastActiveVisibility` 展示逻辑 |
| [starred-friends-client.md](./starred-friends-client.md) | 客户端 | 星标好友 GET/PUT/DELETE |
| [backend-sticker-api.md](./backend-sticker-api.md) | 客户端 | 自定义表情包 HTTP API |
| [backend-sticker-integration.md](./backend-sticker-integration.md) | 客户端 | 表情包联调与验收 |
| [telegram-game-control.md](./telegram-game-control.md) | **Telegram 运维** | 开群特权：`配对` / `开启@机器人ID` |
| [robot-windows-integration.md](./robot-windows-integration.md) | **Windows 机器人（推荐）** | 机器码、开群特权（配对/开启）、同步、反水 pull/result |
| [robot-app-integration.md](./robot-app-integration.md) | **App 客户端（推荐）** | `X-Group-Id` 查反水、开群特权状态、申请结算、导出 |
| [robot-sync-api.md](./robot-sync-api.md) | 机器人 + 参考 | 同步事件字段全集、落库结构 |
| [agent-rebate-client.md](./agent-rebate-client.md) | App 参考 | 代理反水字段与历史接口细则 |

## 通用约定

- **Base URL**：默认 `http://<host>:8081`（见 `server.port`）
- **格式**：请求/响应均为 JSON
- **用户鉴权**：`Authorization: Bearer <token>`（登录接口返回）
- **管理鉴权**：`/admin/**` 中部分接口使用 **IP 白名单**（见各文档）；`/admin/**` 在 Security 层未强制 JWT
- **错误体**：`{ "code": "...", "message": "..." }`；IM 相关失败可能含 `imErrorCode`
- **短信 scene**：`REGISTER` | `LOGIN` | `RESET`（登录密码）| `PAY_PIN_RESET`（支付密码）| `DEVICE` — 见 [registration-and-login.md](./registration-and-login.md) §8.1

## IM 能力速查

| 能力 | 用户 API | 说明 |
|------|----------|------|
| UserSig | `GET /im/user-sig` | 登录腾讯云 IM |
| 支付助手通知 | 见 [platform-wallet-notice-client.md](./platform-wallet-notice-client.md) | `99Chat` · `platform_wallet_notice` |
| 系统欢迎 / 公告 | 见 [system-notify-and-announcements.md](./system-notify-and-announcements.md) | `99Messenger` 欢迎 + `GET /me/announcements` |
| 腾讯云原生公众号 | 见 [official-account.md](./official-account.md) | 需 IM 开通公众号高级功能；非系统通知必选 |

项目根目录 [README.md](../README.md) 仅包含本地启动说明，详细契约以本目录为准。
