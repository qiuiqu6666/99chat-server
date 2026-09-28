# 钱包卡片改为后端发送

本次修改以 `c880d51` 为基础，覆盖普通转账、群转账、单聊红包、普通群红包、拼手气红包和专属红包。代码尚未部署。

## 流程

新版客户端调用 `POST /wallet/card-orders/transfer` 或 `POST /wallet/card-orders/red-packet`。请求字段沿用原接口，必须带原始 `clientOrderId` / `clientPacketId`。

`WalletServerCardOrders` 在同一个数据库事务中调用现有付款服务并保存 `wallet_card_outbox`。入队失败，付款事务一起回滚。相同发送人的同一客户端订单 ID 只创建一条任务；再次请求返回原结果，金额、收款人等不一致则返回冲突。发送人的数据库行锁串行化新版接口的并发提交；原付款服务仍负责密码、余额、手续费和收款权限验证。

任务提交后，独立调度线程发送腾讯 IM 自定义消息。数据取自服务端订单和用户资料，不接受前端提交卡片 JSON。单聊 `SyncOtherMachine=1`，同步发送人在线设备及漫游记录。卡片保留 `businessID=wallet_order` 和现有 customType，使用现有前端渲染及详情接口。

前端在请求付款前就保存 `serverManagedCard=true`。付款超时后保留原订单 ID，恢复时只调用 `GET /wallet/card-orders/{clientId}`，不重新付款，也不进入客户端补卡队列。查单接口只允许原发送人查询。响应中的 `status=COMPLETED` 表示付款及任务已提交；`cardDeliveryState` 单独表示发卡状态，不能把前者理解为 IM 已送达。红包领取和退款状态仍以原红包详情接口为准。

## 交付状态机与恢复

付款状态与传输状态独立：`paymentState=COMMITTED` 表示付款事务已提交；稳定的 `cardId` 是业务卡片身份；`cardDeliveryState` 是 IM 交付进度。卡片重试、历史核对和状态更新不调用付款服务。

- `PENDING`：未尝试，或上次明确未发送/被拒绝，可以安全重试；不再因为经过 240 秒就永久停止。
- `SENDING`：90 秒租约。HTTP 总超时 45 秒，禁用 HTTP 隐式重试。生产调度使用最多 4 个工作线程、无内存任务队列，满载任务留在数据库。
- `RECONCILING`：发送结果不明、缺消息定位信息或旧发送租约过期，持续核对原消息。
- `SENT`：REST、已认证回调或历史记录已确认原消息，且持有可更新的 MsgKey/MsgSeq。
- 旧 `REVIEW` 行自动进入核对，不必人工重置。不得将它们批量改成 `PENDING`。

历史查询严格校验响应，失败不能转为空列表。单聊按 LastMsgTime + LastMsgKey 续页，群聊按最小 MsgSeq - 1 续页；游标落库，一次处理一页，重启后继续。仅匹配发送人、接收目标、random、卡片业务类型和订单 ID 的原消息可以确认。找到后恢复原卡片状态同步；迟到的核对或发送失败不能覆盖已成功回调。明确发送前配置失败使用独立异常类型，不会误判为“已经可能发送”。

前端付款成功后继续保存尚未送达的恢复记录，GET 原订单确认交付；查询失败不会把已成功付款降级为未知，不会触发客户端发卡。恢复列表的容量限制只清理已完成记录，不丢弃未完成任务。支付提示明确区分“正在确认支付”“支付成功、卡片送达确认中”和已完成，不再把 pending/unknown 显示成支付成功。

### 业务卡片是聊天展示的真源

新增 `GET /wallet/card-orders/conversation?target=...&group=...`。它直接读取与付款同事务提交的卡片记录，独立于 IM 是否成功。单聊只返回认证用户与目标的双向订单；群聊检查本地有效成员和入群时间，不允许群外用户读取，也不把入群前其他成员的卡片交给新成员。分页以 createdAt + cardId 作为组合游标，防止同时间记录丢失/重复。红包状态从当前订单批量读取，共享卡片不会泄露个人领取态。

聊天页的 `WalletConversationCards` 通过独立展示层消费该接口：进入聊天、付款记录变化和每 4 秒读取一次；后台分页每轮最多 2 页，最新页优先。同一订单按 cardId 展示一次，晚到或重复的 IM 消息映射回这张业务卡片。SDK 原始消息、msgID、seq 和历史覆盖范围保持原样，业务展示记录不会写入 SDK 历史，也不参与 SDK 未读计数。账号失效后清空展示，迟到的请求不能跨账号写入。

这使“付款已提交但 IM 卡片发送超时”不再等于“聊天里没有卡片”：用户打开聊天且钱包 API 可用时，可以直接读取这笔已提交订单。IM 继续承担通知和兼容消息，不再决定业务卡片是否存在。旧版本客户端没有此展示能力，仍须按发布顺序升级。

外部边界仍需准确表述：IM 与钱包数据库没有共同事务，通知/会话预览仍可能延迟；钱包 API 也离线时无法实时加载新的业务卡片。IM 历史可删除或过期，因此查不到原消息时持续核对、不会盲目补发。该传输核对不阻断新客户端通过业务卡片接口展示已提交付款，也不重新扣款。未做生产网络故障联调，不能宣称任意故障下实时可见或外部 exactly-once。

参考：[单聊历史及删除语义](https://cloud.tencent.com/document/product/269/42794)、[群历史和分页语义](https://cloud.tencent.cn/document/product/269/2738)。

监控未收敛任务：

```sql
SELECT id, sender_id, client_id, target_id, state, attempts,
       first_attempt_at, next_attempt_at, reconcile_cursor, message_key, last_error
FROM wallet_card_outbox
WHERE state IN ('REVIEW', 'RECONCILING')
   OR (state IN ('PENDING', 'SENDING') AND next_attempt_at < UTC_TIMESTAMP() - INTERVAL 5 MINUTE);
```

本次不批量补发旧客户端历史订单。

## 发布顺序

1. 在与钱包订单相同的数据库依次执行 `scripts/migrate-wallet-card-outbox.sql`、`scripts/migrate-wallet-card-state.sql`、`scripts/migrate-wallet-card-reconciliation.sql`。后两个为一次性增量迁移，已执行后不要重复执行。默认 JPA 配置为 `ddl-auto=none`，不会自动建表。
2. 先发布所有钱包 API、调度器及 IM 回调实例。所有实例必须共享订单和 outbox 数据库。若开启 `wallet.proxy-enabled`，内网 wallet-service 也必须提供新付款接口及 conversation 业务卡片接口；当前仓库只包含本地主服实现和代理入口。
3. 先配置有效的回调密钥，再发布：缺少密钥现在返回 503，所有 IM 回调都不再匿名放行。腾讯 IM 必须启用单聊/群聊 BeforeSend 和 AfterSend 回调，并指向已更新的认证入口。控制台关闭“允许客户端禁用前回调”“允许客户端禁用后回调”，将 BeforeSend 回调失败策略配置为不下发消息，防止超时或伪造禁用标识绕过。服务端专用 REST 发送只跳过 BeforeSend，保留 AfterSend 的送达确认、归档和业务推送。
4. 在测试环境用测试账户验证所有六种交易、发起人多设备同步、杀进程恢复、断网、群内高流量、禁言/删除会话/退出群等真实权限场景。服务端 REST 会绕过客户端发送前检查，现有付款接口的权限规则需在部署环境核对。
5. 发布新版前端并设置最低钱包客户端版本。新版只使用新接口，不在 404/超时后回退到旧付款接口。旧 POST `/wallet/transfer`、`/wallet/red-packet/send` 现在在扣款前返回 HTTP 426 / `WALLET_SERVER_CARD_REQUIRED`，避免先扣款再被阻止发卡。历史查询、领取接口保留。发布期间旧客户端发送钱包业务会被拒绝，应提前安排升级提示和维护窗口。

BeforeSend 对所有客户端钱包卡片统一返回 `WALLET_CARD_SERVER_MANAGED`，不接受自报 serverManagedCard、管理员名、现存订单 ID、白名单或 disabled/log-only 开关作为放行依据。解析同时识别 customType 和 type，阻止一个无害字段遮住另一个钱包类型。旧客户端补发历史卡片也会被拒绝，不自动补发历史订单。

上述强制发送拦截依赖所有回调实例升级及控制台策略，不能仅凭代码测试宣称生产已生效。BeforeSend 处理发送请求，不是腾讯 SDK 编辑消息的前置拦截器；钱包金额、领取权限及个人领取状态始终以钱包 API 为准。前端已禁止后端判定无效的订单被 IM 本地缓存重新恢复成有效卡片。控制台策略说明见[第三方回调简介](https://cloud.tencent.com/document/product/269/1522)。

回滚时不要先撤回新后端接口、删除 outbox 表或清空任务；已发布的新客户端还会依赖它们。可以停止推广新版客户端，保留后端处理已提交任务。

## 本地验证（2026-09-28）

- 前端：53 项不同测试通过（两组运行有 10 项金额测试重叠，未重复计数），覆盖原订单恢复/出站/去重、领完卡片、账号隔离、状态缓存失效、旧请求迟到和伪造本地成功状态不能覆盖后端无效结果。定向分析没有 error；保留既有 visible-for-testing 警告及样式/依赖提示，未声称分析完全干净。
- 后端：49 项通过（41 项钱包测试 + 8 项认证/真实回调处理器测试）。使用真实 H2/JPA 事务验证 outbox 回滚、唯一约束、多 worker 租约、回调优先于迟到超时、去重窗口、全部红包类型、重复支付请求、入队失败回滚、强制拒绝伪造卡片、领取进度/领完/退款更新、服务重启后重试最新状态和多实例状态更新串行化。IM 网络和付款业务服务在相关边界使用 mock，没有真实扣款或生产 IM 调用。
- 钱包新增实现及涉及依赖的定向源码编译通过；IM 发送方法编译通过。
- 完整 Maven 构建未通过：仓库缺少 `scripts/bootstrap/server-0.0.1-SNAPSHOT.jar`。跳过 bootstrap 后，既有反编译源码存在编译错误；包含 webhook 的定向编译也被既有 `ImGroupMessageMonitorService` 的 6 处类型错误阻挡。未修改这些无关代码，不能把上述定向测试视为完整服务构建或线上联调通过。

基础编译包补齐后应在正式构建环境执行：

```powershell
.\mvnw.cmd '-Dtest=WalletCardDeliveryTest,WalletCardImBodyTest,WalletCardUpdateBodyTest,WalletServerCardOrdersTest,WalletServerCardGuardTest,WalletOrderCardSendGuardServiceTest,WalletCardStateSyncTest,WalletLegacyCardEndpointTest,WalletCardCallbackBoundaryTest,ImGroupBeforeSendMsgCallbackServiceTest' test
```

## 领取后同步原卡片

`WalletCardDelivery` 同时保存 REST 返回和认证 AfterSend 回调中的 `MsgKey` / `MsgSeq`；即使回调抢先确认成功，也继续补全 REST 返回的定位信息。单聊更新使用发送人、收款人和 MsgKey；群聊使用 GroupId、MsgSeq。

`WalletCardStateSync` 使用独立调度线程，只读取已提交的红包数据库状态。活动订单正常每 5 秒检查，更新 status、remainingCount、remainingAmount、claimedCount 和 cardStateVersion。普通群红包/拼手气红包领完显示 empty；定向已入账订单保留 success；过期、退款分别显示 expired/refunded。共享卡片不会把某一个人的“我已领取”错误广播给全群，个人状态仍查详情 API。

状态变化通过腾讯管理员[修改单聊历史消息](https://cloud.tencent.com/document/api/269/74740)或[修改群聊历史消息](https://intl.cloud.tencent.com/zh/document/product/1047/47948)替换原消息，不重新发卡。前端接收现有 SDK 消息修改事件，清理该订单的所有状态缓存别名并重新查单；较早版本的异步结果不能覆盖新版本。

更新失败保留待同步任务，默认 30 秒后读取最新状态重试；进程重启不会丢任务。更新持有单条 outbox 行锁覆盖有总超时的网络调用，使多实例不能同时写同一卡片；不锁定余额和红包领取行。终态成功后 5 分钟再修复一次，再将 next_sync_at 置空停止历史轮询。无法定位原消息时只记录 `MESSAGE_LOCATOR_MISSING`，绝不另发一张替代卡。新 worker 对缺少定位的发送结果自动核对历史；已有旧 SENT 行仍缺定位时，需在部署前核对并转入 RECONCILING，不能转为 PENDING。业务卡片展示不依赖该定位。

这属于最终一致同步，积压/网络故障时不保证固定几秒内完成；调用结果不明后由重试及延迟修复收敛，不能把 IM 文案作为资金真相。监控：

```sql
SELECT id, state, message_key, message_seq, next_sync_at, last_sync_at, sync_error
FROM wallet_card_outbox
WHERE sync_error IS NOT NULL
   OR (state = 'SENT' AND next_sync_at < UTC_TIMESTAMP() - INTERVAL 5 MINUTE);
```

## 本轮状态机验证

本轮后端定向测试 71 次执行全部通过（继承的 8 项用例运行两遍，合计 63 项不同测试），含真实 H2/JPA 事务、本地 HTTP 严格响应和分页请求、后台任务重启恢复、会话权限与业务卡片分页。前端 46 项测试通过，覆盖订单交付恢复、容量、账号隔离、领取版本乱序、清空聊天、晚到和重复 IM 回执、聊天展示投影及金额。

业务卡片只进入聊天展示层，不写入 SDK 历史和未读序列。已有可靠 IM 回执时保留它的消息 ID、序号和时间用于滚动定位；只有业务记录时使用 local_wallet_card 标识，不能作为已取得最新 IM 消息的证明。同一订单多个回执只展示一次。清空聊天的时间线同时过滤业务卡片，后台刷新不会恢复已清空的旧卡片。

HTTP 5xx/408 等提交结果不明时保留原付款意图 ID；已取得后端成功结果后，本地记录保存失败不能将付款显示为失败。卡片后台恢复只处理交付，不重新执行扣款。

完整 Maven 构建仍受上述缺失 bootstrap 包及既有源码错误阻挡；未部署，也未进行真实腾讯 IM 故障联调。代码图检查覆盖已跟踪文件，新文件另外由源码检查和上述测试覆盖；前端全量差异包含此前大量修改，不能将本轮定向验证当成全应用回归。
