# 好友关系一致性修复（2026-09-23）

好友关系以本服务 `user_friend` 为准；`friend_contact_change` 是同事务产生的持久增量日志。保持当前双向删除的业务语义。腾讯 IM SNS 是异步副本，不作为客户端通讯录成员身份来源。

## 本次后端改动

- `GET /sync/contacts/changes?afterRevision=42&cursor=&limit=200` 现在从 revision 42 之后查询。之前统一控制器忽略 `afterRevision`，客户端完成分页并清空 cursor 后会从零重放日志。
- 非空 cursor 优先，客户端必须原样回传；其他同步域行为保持原样。
- 好友增量查询使用 REPEATABLE_READ 只读事务。空页只确认传入版本，不能根据稍后查询到的 MAX(revision) 跳过尚未交付的提交。
- 跨域/无效好友游标返回 410 INVALID_CURSOR，客户端可恢复全量同步。

无需新增服务端表或修改已有好友 API 响应结构。继续使用现有的变更日志和提交后实时通知。

## 配套客户端行为

HTTP 增删成功、TCP 通知、SDK 回调及成友消息均触发 contacts 同步，通知自身不直接写好友关系。SQLite、搜索索引和通讯录目录投影都从确认后的协议数据更新。增量使用 itemVersion 和删除墓碑拒绝旧写入；同一个账号的同步串行运行，运行期间的新触发会追加一轮。

客户端 contacts.db 升到 v7，保留可显示的本地好友行，清除旧同步进度，首次启动重新核对全量快照。完整快照发布会移除服务端已不存在的好友。快照未完整时保持原表。前台每 30 秒、重新连接、回到前台均补拉增量；断网期间保留最后确认的缓存。

本次没有新增推送 outbox。提交后通知丢失时由持久日志补拉恢复；30 秒是前台补偿触发间隔，不是离线或网络阻塞时的同步时限。

## 发布与验证

1. 先部署后端，使 afterRevision 生效；再发布配套客户端。
2. 两个账号同时在线，互加、互删、删后重加，比较服务端 user_friend、客户端 contacts.db 和通讯录。
3. 断开 TCP 后执行变更，恢复连接或回到前台，确认补拉恢复。制造旧通知重复/晚到，确认不会恢复已删除好友。
4. 验证旧客户端继续使用原 cursor/旧接口时行为不变。

接口层独立回归命令：

```sh
mvn -f scripts/contacts-contract-tests/pom.xml test
```

完整服务测试：

```sh
mvn -Dtest=SyncDomainControllerTest,MeFriendsChangesServiceTest,UserFriendServiceTest,FriendRequestServiceTest,FriendListRealtimePublisherTest test
```

当前仓库缺少主 pom 必需的 `scripts/bootstrap/server-0.0.1-SNAPSHOT.jar`。接口层测试可独立运行；完整编译、服务测试和实际 MySQL 事务/双端联调必须在补齐该产物后完成。本次未部署。
