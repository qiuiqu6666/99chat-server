import { request } from '../request';

/** get constant routes */
export function fetchGetConstantRoutes() {
  return request<App.Service.Response<Api.Route.MenuRoute[]>>({ url: '/route/getConstantRoutes' });
}

/** get user routes */
export function fetchGetUserRoutes() {
  return request<App.Service.Response<Api.Route.UserRoute>>({ url: '/route/getUserRoutes' });
}

/**
 * whether the route is exist
 *
 * @param routeName route name
 */
export function fetchIsRouteExist(routeName: string) {
  return request<App.Service.Response<boolean>>({ url: '/route/isRouteExist', params: { routeName } });
}
