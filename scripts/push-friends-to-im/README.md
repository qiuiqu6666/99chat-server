# 一次性：本地双向好友回灌到 IM SNS

把 MySQL `user_friend` 中 **双方有效** 的好友关系推到腾讯 IM（`sns/friend_add`），并按方向把 **有值备注** 写到 IM（`sns/friend_update` / `Tag_SNS_IM_Remark`）。

**不写** 昵称/头像；**不清空** IM 空备注边；**不做**日常双写。

## 环境变量

从 `/www/wwwroot/99chat-server/.env` 或本目录 `local.env` 读取：

| 变量 | 说明 |
|------|------|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | 读库 |
| `IM_REST_BASE_URL` | REST 根，缺省 `https://adminapisgp.im.qcloud.com/v4/` |
| `IM_SDK_APP_ID` / `IM_KEY` | 可选；缺失时从 `app_setting` 只读回退 |
| `IM_ADMIN` 或 `IM_REST_ADMIN_ACCOUNT` | 默认 `administrator` |

## 命令

```bash
cd /www/wwwroot/99chat-server/scripts/push-friends-to-im

# 1) 必须先 dry-run（只读库，不写 IM）
python3 push_mutual_friends_to_im.py --dry-run

# 2) 确认摘要后再 apply（需 dry-run 产物）
python3 push_mutual_friends_to_im.py --apply

# 小批试跑
python3 push_mutual_friends_to_im.py --dry-run --max-pairs 10
python3 push_mutual_friends_to_im.py --apply --max-pairs 10 --delay-ms 100

# 可选核对
python3 push_mutual_friends_to_im.py --verify --verify-sample 50
```

`--apply` 若缺少 `state/push_friends_pairs.jsonl` 会拒绝执行。

## 产物

| 文件 | 说明 |
|------|------|
| `state/push_friends_dry_run_summary.json` | 计数摘要 |
| `state/push_friends_pairs.jsonl` | 每行一对 |
| `state/push_friends_apply.jsonl` | apply 明细 |
| `state/push_friends_apply_summary.json` | apply 汇总 |
| `state/push_friends_verify_summary.json` | verify 汇总 |

## 注意

1. 回灌前确认 `USER_FRIEND_SYNC_SCHEDULED` / 启动同步关闭，避免 IM→本地 反向覆盖备注。
2. `friend_add` 对已是好友（`30001`）视为成功。
3. 本脚本不接入主服务启动路径。

## 全量备注（含单边）

把 `user_friend` 中 **全部有效有值备注** 写到 IM（`sns/friend_update` / `Tag_SNS_IM_Remark`）。双边先 `Add_Type_Both`，单边先 `Add_Type_Single`（`ForceAddFlags=1`）。**有值才写**；空备注 **不清空** IM；**不写** 昵称/头像。

```bash
cd /www/wwwroot/99chat-server/scripts/push-friends-to-im

python3 push_all_remarks_to_im.py --dry-run
python3 push_all_remarks_to_im.py --apply --delay-ms 50
python3 push_all_remarks_to_im.py --verify --delay-ms 50
```

`--apply` 若缺少 `state/push_remarks_edges.jsonl` 会拒绝。`USER_FRIEND_SYNC_SCHEDULED` 为 true 时拒绝 apply。

| 文件 | 说明 |
|------|------|
| `state/push_remarks_dry_run_summary.json` | 计数摘要 |
| `state/push_remarks_edges.jsonl` | 每行一条有向有值备注边 |
| `state/push_remarks_apply.jsonl` | apply 明细 |
| `state/push_remarks_apply_summary.json` | apply 汇总 |
| `state/push_remarks_verify_summary.json` | verify 汇总 |
