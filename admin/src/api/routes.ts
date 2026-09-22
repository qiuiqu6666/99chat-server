import { http } from "@/utils/http";

type Result = {
  code: number;
  message: string;
  data: Array<any>;
};

const emptyRoutes: Result = {
  code: 0,
  message: "ok",
  data: []
};

/** 侧边栏路由：本仓库由 `router/modules/im-admin.ts` 静态挂载，不向 PHP 索要动态菜单。
 * 仅当 `.env` 中 `VITE_USE_BACKEND_ASYNC_ROUTES=true` 时才请求后端 `GET /get-async-routes`。 */
export const getAsyncRoutes = () => {
  if (import.meta.env.VITE_USE_BACKEND_ASYNC_ROUTES === "true") {
    return http
      .request<Result>("get", "/get-async-routes")
      .catch((): Result => ({
        ...emptyRoutes,
        code: -1,
        message: "get-async-routes 不可用，已跳过"
      }));
  }
  return Promise.resolve(emptyRoutes);
};
