# 超级大群收费上线

1. 在 MySQL 执行 `scripts/migrate-community-create-payment.sql`。本项目默认不自动更新 JPA 表结构。
2. 部署服务端，再部署含支付确认的新客户端。默认每个 Community 收取 **10000 99 币**（钱包以分存储，配置值 `1000000`）。普通 Public 群免费，新建群向 IM 指定 `MaxMemberNum=6000`。
3. 在管理后台“业务开关 → 群加入 / 社群创建限制”设置超级大群创建币种与价格。可选 `99`、`USDT`、`TRX`；接口字段 `community_create_price_currency`、`community_create_price_minor`。服务端在创建时核对客户端确认的报价，改价后的旧请求会返回 `COMMUNITY_PRICE_CHANGED`。
4. 在腾讯云 IM 控制台启用 `Group.CallbackBeforeCreateGroup`，确保其回调 URL 指向本服务端、鉴权 Token 已配置，且回调异常按拒绝处理。该回调拒绝客户端 SDK 直接创建 Community；服务端使用配置的 REST 管理员账号创建。
5. 确认腾讯云 IM 套餐支持 Public 群 6000 人。新建 Public 群在 REST 请求中设置上限；已经创建且 IM `MaxMemberNum` 小于 6000 的群需另行调用 IM `modify_group_base_info` 调整。

每次收费建群使用客户端 UUID 生成固定的 Community 群 ID。服务端的 `community_create_payment` 记录和钱包账本在同一数据库事务中提交；同一个请求编号重试返回原群，避免重复扣费。IM 创建成功但数据库事务失败时，服务端尝试解散新建的 IM 群。若进程在跨系统操作间崩溃，应排查 `@TGS#_P` 开头但没有 `community_create_payment` 记录的 IM 群。
