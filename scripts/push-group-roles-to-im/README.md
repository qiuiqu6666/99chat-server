# 一次性：本地群成员角色回灌到 IM

以 MySQL `group_member`（未解散群、未软删成员）为权威，把 **Admin/Member** 写入腾讯 IM `modify_group_member_info`。

- Owner(400)：跳过（不调用改 Role）
- Admin(300) → `Admin`
- Member(200) → `Member`（用于清掉 IM 侧多余管理员）

## 命令

```bash
cd /www/wwwroot/99chat-server/scripts/push-group-roles-to-im

python3 push_group_roles_to_im.py --dry-run
python3 push_group_roles_to_im.py --apply --delay-ms 50

# 单群试跑
python3 push_group_roles_to_im.py --dry-run --group-id '@TGS#2GTWUPRUA'
python3 push_group_roles_to_im.py --apply --group-id '@TGS#2GTWUPRUA'

# 抽查
python3 push_group_roles_to_im.py --verify --verify-sample 100
```

`--apply` 需要先有 dry-run 产物 `state/push_roles_rows.jsonl`。

## 环境

读 `/www/wwwroot/99chat-server/.env` 或本目录 `local.env`（`DB_*`、`IM_REST_BASE_URL`；IM 凭证可从 `app_setting` 回退）。
