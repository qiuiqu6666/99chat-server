/**
 * IM 管理后台：不注入演示用的「系统管理 / 监控 / 权限 / 外链 / 标签页」等动态菜单。
 * 需要后端驱动菜单时，在此按 `/get-async-routes` 约定返回路由数组即可。
 */
import { defineFakeRoute } from "vite-plugin-fake-server/client";

export default defineFakeRoute([
  {
    url: "/get-async-routes",
    method: "get",
    response: () => ({
      code: 0,
      message: "操作成功",
      data: [] as any[]
    })
  }
]);
