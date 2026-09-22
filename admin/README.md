# 99chat 运营后台（Admin）

基于 [vue-pure-admin](https://github.com/pure-admin/vue-pure-admin) 7.x 定制的 IM / 钱包运营管理前端，对接 Java 管理 API（`/api/v1/*`）。

## 技术栈

- Vue 3 + Vite + TypeScript
- Element Plus + Pinia + Tailwind CSS
- 业务页面：`src/views/im-admin/`
- 路由：`src/router/modules/im-admin.ts`

## 环境要求

| 工具 | 版本 |
|------|------|
| **Node.js** | **≥ 20**（推荐 20.19.x；pnpm 10 需 ≥ 18.12） |
| pnpm | 10.x |

本机若默认 Node 为 16（常见于宝塔面板），请先切换到 Node 20：

```bash
# 方式一：临时加入 PATH
export PATH="/www/server/nodejs/v20.19.0/bin:$PATH"
node -v   # 应显示 v20.x

# 方式二：使用项目脚本（会 exec 后续命令）
source admin/scripts/use-node20.sh
pnpm dev
```

## 快速开始

```bash
cd admin
export PATH="/www/server/nodejs/v20.19.0/bin:$PATH"   # 如已是 Node 20 可省略
pnpm install
pnpm dev
```

开发服务默认：**http://localhost:8848/**（端口见 `.env.development` 中 `VITE_PORT`）。

## API 代理

本地开发时，Vite 将以下路径代理到 Java 后端（默认 `http://47.239.60.107:8081`）：

- `/api/**` — 管理 REST API
- `/refresh-token`、`/mine` 等鉴权相关路径

修改后端地址：编辑 `admin/.env.development` 中的 `VITE_ADMIN_API_PROXY_TARGET`。

生产构建使用 `admin/.env.production`（或 staging 对应文件）。

## 构建与预览

```bash
pnpm build          # 输出到 admin/dist
pnpm preview        # 本地预览构建产物
```

## 目录说明

```
admin/
├── src/
│   ├── api/              # im-*.ts 对接 /api/v1
│   ├── router/modules/   # im-admin 业务路由与侧栏菜单
│   └── views/im-admin/   # 用户、群组、钱包、消息等页面
├── scripts/use-node20.sh # Node 20 PATH 辅助脚本
└── vite.config.ts        # 开发代理配置
```

## 侧栏菜单结构（扁平两级）

| 一级 | 子页面数 | 说明 |
|------|----------|------|
| **工作台** | 1 | 指标 + **功能导航**（24 个入口一键直达） |
| **IM** | 8 | 用户、关系、群组、消息、设备、**隐私数据** |
| **资金** | 10 | 全部流水与链上、配置平铺，无三级折叠 |
| **运营** | 4 | 公告、反馈、发版 |
| **系统** | 3 | 平台配置、审计（个人设置在头像菜单 / 工作台导航） |

旧分组名（用户中心、资金中心、风控稽查等）保留为 **redirect**，书签仍可用。

## 已知未接入后端的能力

以下页面已做前端占位或「未接入」提示，需后端补 API 后才会显示数据：

- **文件管理**（用户详情 → 隐私与文件 Tab）：`/api/v1/storage/files`
- **公众号推送**：`/api/v1/official-push/*`

## 上游模板

UI 框架与工程化能力来自 vue-pure-admin；业务逻辑与路由已按 99chat 需求裁剪。上游文档：[pure-admin.cn](https://pure-admin.cn/)

## 许可证

MIT（继承 vue-pure-admin）；业务代码版权归项目方所有。
