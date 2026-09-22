# LiveKit staging 部署（99chat）

配套文档：[docs/livekit-self-host.md](../../docs/livekit-self-host.md)

## 快速启动

```bash
cd deploy/livekit
cp .env.example .env
# 编辑 .env 与 livekit.yaml 中的 keys / turn.domain
docker compose up -d
```

默认监听 `7880`（HTTP/WS）。生产请用 Nginx/Caddy 终结 TLS，对外提供 `wss://livekit.example.com`。

## 与 99chat-server 对接

```bash
export LIVEKIT_ENABLED=true
export LIVEKIT_HOST=wss://livekit.example.com
export LIVEKIT_API_KEY=...
export LIVEKIT_API_SECRET=...
```

在 `livekit.yaml` 的 `webhook.urls` 配置：

```yaml
webhook:
  urls:
    - https://api.example.com/webhook/livekit
  api_key: <same as LIVEKIT_API_KEY>
```

## 验收清单

1. `curl http://127.0.0.1:7880` 有响应
2. 两台手机（一 Wi‑Fi、一 4G）用官方示例或 App 进同一 room 互通
3. TURN 回落：禁用 UDP 后仍可通话
4. 服务端 invite → webhook `participant_joined` / `room_finished` 有日志
