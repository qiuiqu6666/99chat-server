# 群红包领取 — 前端转圈优化

> 版本：v1  
> 适用：Flutter 客户端  
> 关联：[wallet-client.md](./wallet-client.md) §9.2、[wallet-red-packet-claim-notice-client.md](./wallet-red-packet-claim-notice-client.md)

领取接口已经先返回，入账、详情、余额、灰字还在后面。转圈如果把这四件事串在一个 `Future` 里，接口几十毫秒回来，按钮仍会转几秒。

---

## 1. 只让领取按钮等这一次请求

```
点击红包
    ↓
按钮 Loading（只包这一下）
    ↓
POST /wallet/red-packet/{id}/claim
    ↓
HTTP 一结束就停转圈
    ↓
200：立刻显示「已领取 {金额}」
409：立刻显示「已领取」（重复点、超时重试都算）
410 / 403：停转圈，按错误码提示
    ↓
余额、详情、灰字各自刷新，不再控制这个 Loading
```

停转圈的条件只有 claim 这次 HTTP 结束。下面任何一项都不要 `await` 进同一个 Loading：

- `GET /wallet/red-packet/{id}`
- `GET /wallet/red-packet/{id}/claims`
- `GET /wallet/red-packet/{id}/claim-state`
- 钱包余额
- 腾讯灰字 `red_packet_claim_notice`
- `id > 0`

灰字只有发包人收得到，领取人等不到这条消息。

---

## 2. 现在接口实际返回什么

路径：`POST /wallet/red-packet/{id}/claim`  
鉴权：JWT。无请求体，无交易密码。金额单位是分。

**200**（份额已经抢到）：

```json
{
  "id": 744832938472,
  "packetId": 3525,
  "userId": "xyz99abcde",
  "amount": 1888,
  "createdAt": "2026-09-25T12:53:40Z"
}
```

| 字段 | 现在的含义 |
|------|------------|
| `id` | 抢到时生成的业务号，和稍后写入 MySQL 的是同一个数。不要等它再变一次 |
| `amount` | 本次抢到的金额，单位分。`1888` 显示为 `18.88` |
| `packetId` | 红包数字 id |
| `createdAt` | 抢到的时间 |

200 且 `amount > 0` 就是抢到了。`id` 已经是最终业务号，余额可能还要再等一会儿。

错误体：

```json
{ "code": "ALREADY_CLAIMED", "message": "..." }
```

| HTTP | `code` | 按钮 |
|------|--------|------|
| 409 | `ALREADY_CLAIMED` | 停转圈，当成已经领过 |
| 410 | `RED_PACKET_EMPTY` | 停转圈，显示已抢完 |
| 410 | `RED_PACKET_EXPIRED` | 停转圈，显示已过期 |
| 403 | `NOT_GROUP_MEMBER` | 停转圈，提示不在群里 |

超时后允许再调一次 claim。第二次如果是 409，按成功处理，不要再转圈、不要当失败 Toast。

---

## 3. 抢到之后，别的接口还会说「没领」

入账比领取接口晚，正常大约几百毫秒，忙的时候到数秒。这段时间里：

| 接口 | 这段时间可能看到 |
|------|------------------|
| `GET .../claim-state` | `claimState` 仍是 `CAN_OPEN`，`myClaimAmount` 为 `null` |
| `GET .../red-packet/{id}` | 同上，`claims` 里还没有自己 |
| 余额 | 还没有加上这次金额 |
| 灰字 | 更晚，而且领取人通常收不到 |

本地已经记过「这笔我领了」之后，随后拉到的 `CAN_OPEN` 不能把气泡改回「开」，也不能再把按钮转起来。

建议按红包 id 存一条本地记录，至少保留到 `claim-state` 变成 `RECEIVED`：

```text
packetId → amount（分）、claimedAt
```

合并规则：

| 本地记录 | `claim-state` | 气泡 / 按钮 |
|----------|---------------|-------------|
| 有 | `CAN_OPEN` | 保持「已领取」，展示本地金额 |
| 有 | `RECEIVED` | 「已领取」，金额以 `myClaimAmount` 为准，可清本地记录 |
| 无 | `CAN_OPEN` | 「开」 |
| 无 | `RECEIVED` | 「已领取」 |
| 无 | `EMPTY` | 「已抢完」或已过期 |

详情页若在入账前打开：列表里先插入自己这一条（金额用本次 claim 的 `amount`，文案可用「入账中」）。列表里出现自己的正式记录后，去掉这条临时行。详情页自己的加载和领取按钮的加载分开。

---

## 4. 余额

200 之后可以去刷余额，不要等它回来再停转圈。

余额数字以余额接口为准。claim 返回的 `amount` 用来显示「已领取 18.88」，不要先加进钱包余额再等接口对账。接口还没加上时，领取结果旁可以写「入账中」。余额接口已经包含这笔之后，去掉「入账中」。

---

## 5. 参考写法

```dart
Future<void> onOpenRedPacket(String packetId) async {
  setClaimButtonLoading(packetId, true);
  try {
    final claim = await api.claimRedPacket(packetId);
    // 200 就到这里。id 已是最终业务号，余额可能尚未入账。
    rememberClaim(packetId, claim.amount);
    showClaimed(packetId, claim.amount);
  } on WalletApiException catch (e) {
    if (e.statusCode == 409) {
      rememberClaim(packetId, cachedAmount(packetId));
      showClaimed(packetId, cachedAmount(packetId));
    } else if (e.statusCode == 410 && e.code == 'RED_PACKET_EMPTY') {
      showEmpty(packetId);
    } else if (e.statusCode == 410) {
      showExpired(packetId);
    } else if (e.statusCode == 403) {
      showNotGroupMember();
    } else {
      showClaimFailed();
    }
  } finally {
    setClaimButtonLoading(packetId, false);
  }
  unawaited(refreshBalance());
  unawaited(refreshClaimState(packetId));
}
```

`refreshClaimState` 的结果按第 3 节合并，不要覆盖本地「已领取」。

---

## 6. 自测

- [ ] 200：按钮马上停，显示金额，不重试 claim，不等余额
- [ ] 200 之后立刻打开详情，列表还没有自己：仍显示已领取，不继续转领取按钮
- [ ] 200 之后 `claim-state` 仍是 `CAN_OPEN`：气泡保持已领取
- [ ] 连点两次：第二次 409，不转圈，不报失败
- [ ] 请求超时后再请求得到 409：当成已领取
- [ ] 410：停转圈，显示已抢完或已过期
- [ ] 余额晚几秒才增加：领取结果已经显示，余额旁或结果旁为入账中，按钮不转
- [ ] 等不到灰字：领取人界面不因此转圈
