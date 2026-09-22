# 机器人玩家报表（adminjiqiren）

Vue 3 + PHP 只读查询 `jiqiren` 全库。

## 账号

- 用户名：`admin`
- 密码：`alun8888`

## 构建

需要 Node.js 20：

```bash
export PATH="/www/server/nodejs/node-v20.18.1-linux-x64/bin:$PATH"
cd /www/wwwroot/99chat-server/adminjiqiren
npm install
npm run build
```

访问：`https://99admin.9999chat.top/adminjiqiren/`

API：`https://99admin.9999chat.top/adminjiqiren/api/`

## 配置

复制 `api/config.example.php` 为 `api/config.local.php`，填入与主站 `.env` 一致的 `ROBOT_DB_*`。
