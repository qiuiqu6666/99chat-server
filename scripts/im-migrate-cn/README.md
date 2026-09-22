# IM 中国区旁路迁移（用户 + 群组）

第一期：从新加坡源应用只读导出，写入中国大陆目标应用。**不切换**现网 `/im/user-sig`、`app_setting.IM_*`、`chat99.im.rest-base-url`。

## 默认端点

| 角色 | SDKAppID | REST |
|------|----------|------|
| Source | `20042133` | `https://adminapisgp.im.qcloud.com/v4/` |
| Target | `1600155864` | `https://console.tim.qq.com/v4/` |

## 群 ID 策略（方案 A）

国内 `import_group` **禁止**自定义 GroupId 带 `@TGS#` 前缀（ErrorCode=10004）。

源侧系统分配的 `@TGS#xxxx` 会映射为稳定自定义 ID，例如：

- `@TGS#1P7Y2IM5CA` → `m1P7Y2IM5CA`
- Community：`@TGS#_m...`

映射写入 `state/group_id_map.jsonl`（`srcGroupId` / `dstGroupId`）。第一期业务库不改；切流期另开 PLAN 处理映射落地。

## 环境变量

```bash
export SRC_IM_SDK_APP_ID=20042133
export SRC_IM_KEY='<源密钥，只读自 app_setting，禁止写回>'
export SRC_IM_REST_BASE=https://adminapisgp.im.qcloud.com/v4/

export DST_IM_SDK_APP_ID=1600155864
export DST_IM_KEY='<国内密钥，建议轮换后使用；勿提交 git>'
export DST_IM_REST_BASE=https://console.tim.qq.com/v4/

export IM_ADMIN=administrator

# 可选：补充 users 表账号（只读）
export DB_HOST=127.0.0.1
export DB_PORT=3306
export DB_NAME=chat99
export DB_USERNAME=chat99
export DB_PASSWORD='...'
export INCLUDE_DB_USERS=1
```

也可把变量放进本目录未跟踪文件 `local.env`（已被 gitignore），由 `run.sh` 自动 `source`。

## 子命令

```bash
./run.sh export-snapshot
./run.sh export-community   # 社群（GroupType=Community + 库内 @TGS#_ 残留）
./run.sh import-users
./run.sh import-groups
./run.sh import-community
./run.sh verify
./run.sh all
```

可选：`./run.sh import-groups --force-reimport-group '@TGS#xxx'`（参数用**源** GroupId；工具会 destroy 对应 dst 再导入）。
社群同理：`./run.sh import-community --force-reimport-group '@TGS#_@TGS#cxxx'`。

## 错包群 ID（`@TGS#_@TGS#…`）

切流后普通群为 `m…`、社群为 `@TGS#_mc…`。若客户端/关键字仍按旧规则拼 `@TGS#_@TGS#` + …，会生成不存在的假 ID。
清理错包 `m…`：`python3 cleanup_bad_double_prefix_groups.py --apply`  
（若库内还有 `@TGS#_@TGS#c…` 等 dismissed 幽灵，在备份后删除 `group_id LIKE '@TGS#_@TGS#%'`。）
服务端已改关键字解析与 hydrate，避免再写入此类幽灵行。客户端请清本地会话缓存，以服务端返回的 `groupId` 为准。

## 假解散恢复 / 投影回灌

切流后若出现「IM 群仍在、库里 `dismissed=1` 且无 `group_member`」，**不要**只跑 `/admin/group-projection/sync`（会跳过本地解散）。应走假解散恢复：

```bash
# dry-run（本机 IP 白名单）
curl -sS -X POST 'http://127.0.0.1:8081/admin/group-projection/repair/false-dismiss/dry-run' \
  -H 'Content-Type: application/json' -d '{"scanAllDismissed":true}'

# apply（可改为 {"groupIds":["m…",...]} 分批）
curl -sS -X POST 'http://127.0.0.1:8081/admin/group-projection/repair/false-dismiss/apply' \
  -H 'Content-Type: application/json' -d '{"scanAllDismissed":true}'

# 可选收尾：未解散群再扫一遍
curl -sS -X POST 'http://127.0.0.1:8081/admin/group-projection/sync' \
  -H 'Content-Type: application/json' \
  -d '{"maxGroups":10000,"syncUsers":false}'
```

验收口径：`m…` alive 有成员数应接近映射表普通群数；`@TGS#_@TGS#%` 应为 0；存活社群为 `@TGS#_mc…`。  
一次实操结论见 `state/projection_hydrate_verdict.md`。

## 特权短 ID 重绑机器码

切流后开群特权主库多在 `m…`，若 `robot_group_binding` 仍是旧 `@TGS#…`，短 ID 群对机器人等于未配对。可对主库全部特权 `m…` 重绑：

```bash
# 先导出 privilege_m_ids_GZKH.txt，再：
python3 rebind_privilege_m_to_machine.py          # 全量
python3 rebind_privilege_m_to_machine.py --resume # 续跑
```

实操结论：`state/rebind_GZKH_verdict.md`（机器码 `GZKH-DJ3M-VKSB`，2982 成功）。

## 删除国内旧映射数字号

清理中国区 IM（`1600155864`）上残留的映射号 `100000`–`100628`：

```bash
python3 delete_numeric_im_accounts.py --dry-run
python3 delete_numeric_im_accounts.py --apply
python3 delete_numeric_im_accounts.py --verify --check-business <业务userId...>
```

结论：`state/delete_numeric_100000_100628_verdict.md`。

## H1 归档 groupId/msg_key 重映射

```bash
python3 apply_archive_group_id_remap.py          # dry-run
python3 apply_archive_group_id_remap.py --apply
```

未映射残留清单：`state/unmapped_archive_group_ids.tsv`（幽灵社群/无映射源 ID，不阻塞 H3）。

## H3 归档消息导入国内 IM

主源：修复后的 `chat_message_*`。目标 REST：`DST_IM_*`（`local.env`）。

```bash
./run-messages.sh import-c2c-msgs
./run-messages.sh import-group-msgs
./run-messages.sh import-all-msgs
# 冒烟：
./run-messages.sh import-group-msgs --group m2O5P4YN5CI
./run-messages.sh import-c2c-msgs --c2c userA:userB --limit 1
```

可选环境变量：`MSG_IMPORT_QPS`（默认 8）、`MSG_GROUP_BATCH`（默认 7，上限 7）。

断点：`state/ckpt_msg_c2c.txt`、`state/ckpt_msg_group.txt`  
失败：`state/msg_import_fail.jsonl`  
全量日志：`state/msg_import_full.log`

约束：群消息须按 SendTime 升序；若群内已有更新的现网消息，更早的历史无法再插到其前面（工具会跳过）。

全量导入看门狗（断点续跑）：

```bash
./watchdog-messages.sh
```

2026-08-08 验收：`ckpt_msg_c2c=1377`、`ckpt_msg_group=5214`，watchdog `COMPLETE`；失败 3 条见 `state/msg_import_fail.jsonl`（时间序冲突 / invalid / group_gone）。

## 一次性推送会话置顶（中国站）

把主库 `user_conversation_pin` 一次性推到国内 IM `recentcontact/top`（不改自建置顶真源）。  
IM 上不存在的 3 条旧 `@TGS#…` 会 SKIP。

```bash
cd /www/wwwroot/99chat-server/scripts/im-migrate-cn
python3 push_conversation_pins_to_cn_im.py --dry-run
python3 push_conversation_pins_to_cn_im.py --apply
python3 push_conversation_pins_to_cn_im.py --verify
```

产物：`state/push_pins_dry_run.json`、`state/push_pins_apply.jsonl`、`state/push_pins_apply_summary.json`、`state/push_pins_verdict.md`。

## 人工验收（verify 报告外）

- [ ] 国内 IM 抽检群/单聊漫游有历史（`group_msg_get_simple` / `admin_getroammsg`）
- [ ] `GET /im/snapshot` 群 conversationId 为 `group_m…` / `group_@TGS#_m…`
- [ ] 确认已理解国内群 ID ≠ 源 `@TGS#` ID
