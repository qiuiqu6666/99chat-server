# 99Chat 运营后台（admin-console）

独立 Vue 3 运营后台，与旧 `admin/`（pure-admin）并存。菜单为六域两级结构。

## 技术栈

- Vue 3 + TypeScript + Vite 5
- Vue Router 4 / Pinia 2 / Element Plus 2 / Axios

## 启动

需要 Node.js 20+（本机若默认 Node 16，可使用 `/www/server/nodejs/node-v20.18.1-linux-x64/bin`）。

```bash
export PATH="/www/server/nodejs/node-v20.18.1-linux-x64/bin:$PATH"
cd /www/wwwroot/99chat-server/admin-console
npm install
npm run dev
```

开发地址：`http://127.0.0.1:5173`  
API 代理：`.env.development` 中 `VITE_PROXY_TARGET`（默认 `http://127.0.0.1:8080`）。

```bash
npm run build
```

生产站点使用 Vue History 路由。Nginx 必须把不存在的前端路径回退到 `index.html`，否则点击菜单后再刷新会 404。规则见 `deploy/nginx-spa-rewrite.conf`，宝塔伪静态文件：

`/www/server/panel/vhost/rewrite/adminapi.99chat.vip.conf`

## 目录要点

- `src/router/menu.ts`：侧栏六域菜单
- `src/router/routes.ts`：路由
- `src/api/*`：`/api/v1` 封装
- `src/views/*`：页面

## 鉴权

登录 `POST /api/v1/auth/login`，Token 存 `localStorage` key：`chat99_admin_token`。
