# 生活缴费 — 大猿人上游（话费 / 电费）

> 对接文档原文：[api.html](http://1.12.207.83/uploads/api.html)  
> 相关：[life-payment-client.md](./life-payment-client.md)、[life-payment-worker.md](./life-payment-worker.md)

## 1. 行为

| 业务 | 开启大猿人后 |
|------|----------------|
| 手机充值 `mobile` | 用户扣款后 **不再派 Worker recharge**，改为调大猿人 `/index/recharge` |
| 电费缴费 `electric` | **查户仍走 Worker query**；缴费扣款后改调大猿人，不再派 Worker pay |
| 水费 / 燃气 | 不变，仍走 Worker |

商户订单号 `out_trade_num` = 本系统 `order_no`。

异步回调：`POST /webhook/life-payment/yuanren/notify`（表单），验签通过后回纯文本 `success`。  
另有定时 `check` 轮询兜底。

## 2. 配置（`.env` / `application.yml`）

```bash
LIFE_PAYMENT_YUANREN_ENABLED=true
LIFE_PAYMENT_YUANREN_BASE_URL=http://你的域名/yrapi.php
LIFE_PAYMENT_YUANREN_USERID=商户ID
LIFE_PAYMENT_YUANREN_APIKEY=商户密钥
# 必须公网可达
LIFE_PAYMENT_YUANREN_NOTIFY_URL=https://你的API域名/webhook/life-payment/yuanren/notify
LIFE_PAYMENT_YUANREN_ELECTRIC_PRODUCT_ID=电费产品ID
```

话费面额 → 产品 ID（YAML）：

```yaml
chat99:
  life-payment:
    yuanren:
      mobile-product-ids:
        "50": "1001"
        "100": "1002"
        "200": "1003"
```

电费也可按面额覆盖：`electric-product-ids`。

开启但缺 `base-url/userid/apikey/notify-url` 时，下单会直接报错（避免静默回 Worker）。

## 3. 状态映射

| 大猿人 state | 本系统 |
|--------------|--------|
| 0 充值中 | `running` |
| 1 成功 / 3 部分成功 | `success` + 记成功账户 |
| 2 失败 / -1 取消 | `failed` + **自动退款** |

明确下单失败（`errno!=0`）同样退款关单。网络不确定时保持 `running`，靠回调/轮询收口。

## 4. 上线检查

1. 在大猿人后台确认话费/电费 `product_id`  
2. 配齐环境变量与面额映射  
3. 重启主服务 `:8081`  
4. 测一笔小额话费：看订单变 `running` → 回调/轮询变 `success`  
5. 电费：查户仍依赖 Worker；缴费走大猿人  

水燃继续准备 Worker；电费查户 Worker 不能下线。
