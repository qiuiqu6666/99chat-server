# LiveKit 自建部署（99chat）

> 版本：v1.1（2026-07-19）  
> 媒体栈：自建 [LiveKit Server](https://github.com/livekit/livekit)  
> **生产域名：`wss://trtc.99chat.vip`**（本机 `47.239.60.107`）  
> 业务对接：[livekit-call-client.md](./livekit-call-client.md)  
> 部署文件：[deploy/livekit/](../deploy/livekit/)

## 当前机状态（已落地）

| 项 | 值 |
|----|-----|
| 信令 | `https://trtc.99chat.vip` → Nginx TLS → `127.0.0.1:7880` |
| 进程 | `systemctl status livekit`（二进制 `/usr/local/bin/livekit-server`） |
| 配置 | `deploy/livekit/livekit.runtime.yaml`（由 `apply-config.sh` 生成） |
| 业务 env | `LIVEKIT_ENABLED=true`；**TRTC 已关闭**（`TRTC_CALLBACK_ENABLED=false`，代码保留） |
| Webhook | LiveKit → `https://api99chat.99chat.vip/webhook/livekit` |
| 证书 | Let's Encrypt（`certbot`，自动续期） |

云安全组务必放行：**UDP 50000–60000**、**UDP 3478（TURN）**、**TCP 7881**。

重启业务：`bash scripts/start-livekit-ready.sh`

---

## 1. 架构

```
App (LiveKit SDK)
    │ wss + UDP/TCP/TURN
    ▼
LiveKit SFU (docker)
    │ webhook (JWT)
    ▼
99chat-server  /webhook/livekit
    │ invite / accept / hangup
    ▼
腾讯 IM 自定义消息 lk_call + iOS VoIP
```

通话 **媒体** 走 LiveKit；**呼叫信令** 走 99chat API + IM `lk_call`；**最近通话** 仍用 `call_session` / `GET /calls/recent`。

---

## 2. 端口与网络

| 端口 | 协议 | 用途 |
|------|------|------|
| 443 / 7880 | TCP | HTTPS / WSS 信令（建议 443 + 反代） |
| 7881 | TCP | ICE/TCP |
| 50000–50100+ | UDP | RTP 媒体（生产建议加大范围） |
| 3478 / 5349 | UDP/TCP | TURN（`livekit.yaml` turn 段） |

防火墙必须放行 **UDP 媒体段**；仅开 443 会导致只能走 TURN、成本高且易卡顿。

---

## 3. 配置要点

### 3.1 LiveKit

编辑 [deploy/livekit/livekit.yaml](../deploy/livekit/livekit.yaml)：

- `keys`: API Key → Secret（与业务侧一致）
- `rtc.use_external_ip: true`（公网 / NAT）
- `turn.domain`: 解析到本机公网 IP 的域名
- `webhook.urls`: `https://<api-host>/webhook/livekit`
- `room.max_participants: 2`（本期 1v1）

### 3.2 99chat-server

```yaml
chat99:
  livekit:
    enabled: true
    host: wss://livekit.example.com
    api-key: ${LIVEKIT_API_KEY}
    api-secret: ${LIVEKIT_API_SECRET}
    token-ttl-seconds: 3600
    ring-timeout-seconds: 60
    webhook:
      enabled: true
```

环境变量：

| 变量 | 说明 |
|------|------|
| `LIVEKIT_ENABLED` | `true` 启用 |
| `LIVEKIT_HOST` | 客户端连接 URL（`wss://...`） |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | 与 LiveKit keys 一致 |
| `LIVEKIT_WEBHOOK_ENABLED` | 默认 `true` |

Secret 建议 ≥ 32 字节随机串。

---

## 4. 启动

```bash
cd deploy/livekit
cp .env.example .env
# 填写密钥与域名
docker compose up -d
```

可选 TLS：`docker compose --profile tls up -d`（需改 [Caddyfile](../deploy/livekit/Caddyfile) 域名）。

---

## 5. Webhook

LiveKit 使用 `Authorization: Bearer <JWT>`，JWT 含 body 的 `sha256`（Base64），以 API Secret 签名、`iss` = API Key。

99chat 入口：`POST /webhook/livekit`（无需业务 JWT）。处理：

- `participant_joined`（被叫进房 → 标记接听）
- `room_finished` → 通话终态

与客户端 `hangup` **幂等**（同一 `callId`）。

---

## 6. TRTC 下线

| 变量 | 新默认 | 说明 |
|------|--------|------|
| `TRTC_CALLBACK_ENABLED` | `false` | 关闭 TRTC 回调处理 |
| `TRTC_CALLBACK_BOOTSTRAP` | `false` | 不再向腾讯注册 Call 回调 |

并行期可临时设回 `true`。新客户端 **不得** 再初始化 TUICallKit/TRTC。

---

## 7. 验收

1. Staging LiveKit 健康检查通过  
2. Wi‑Fi ↔ 4G 互通音视频  
3. 关 UDP 后 TURN 仍可通话  
4. `POST /calls/livekit/invite` 后被叫收到 IM `lk_call` +（iOS）VoIP  
5. 挂断后 `GET /calls/recent` 有记录，TCP `call_recent_changed` 推送  
