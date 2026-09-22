import { home } from "@/router/enums";

const Layout = () => import("@/layout/index.vue");

/** 根路由：登录后与访问 `/` 均进入 IM 首页看板 */
export default {
  path: "/",
  name: "Home",
  component: Layout,
  redirect: "/im-admin/dashboard/overview",
  meta: {
    showLink: false,
    rank: home
  }
} satisfies RouteConfigsTable;
