# 99Chat 运营后台（admin-console）

基于 [SoybeanAdmin](https://github.com/soybeanjs/soybean-admin)（Vue 3、Vite、TypeScript、Pinia、Naive UI、UnoCSS）的运营后台。菜单为七个分组。接口前缀 `/api/v1`。

## 启动

需要 Node.js 20+ 与 pnpm 10+。

```bash
export PATH="/www/server/nodejs/node-v20.18.1-linux-x64/bin:$PATH"
cd /www/wwwroot/99chat-server/admin-console
pnpm install
pnpm dev
```

开发服务默认端口 `9527`。`/api` 代理到 `http://127.0.0.1:8081`。

```bash
pnpm build
```

生产使用 Vue History。Nginx 需把不存在的前端路径回退到 `index.html`，规则见 `deploy/nginx-spa-rewrite.conf`。

## 鉴权

登录 `POST /api/v1/auth/login`。Token 存在 `localStorage`，键名 `chat99_admin_token`。
