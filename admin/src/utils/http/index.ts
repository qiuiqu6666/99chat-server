import Axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type CustomParamsSerializer
} from "axios";
import type {
  PureHttpError,
  RequestMethods,
  PureHttpResponse,
  PureHttpRequestConfig
} from "./types.d";
import { stringify } from "qs";
import { message } from "@/utils/message";
import { $t, transformI18n } from "@/plugins/i18n";
import { getToken, formatToken } from "@/utils/auth";
import { useUserStoreHook } from "@/store/modules/user";

// 相关配置请参考：www.axios-js.com/zh-cn/docs/#axios-request-config-1
const defaultConfig: AxiosRequestConfig = {
  // 请求超时时间
  timeout: 10000,
  headers: {
    Accept: "application/json, text/plain, */*",
    "Content-Type": "application/json",
    "X-Requested-With": "XMLHttpRequest"
  },
  // 数组格式参数序列化（https://github.com/axios/axios/issues/5142）
  paramsSerializer: {
    serialize: stringify as unknown as CustomParamsSerializer
  }
};

class PureHttp {
  constructor() {
    const base =
      typeof import.meta.env.VITE_ADMIN_API_BASE === "string" &&
      import.meta.env.VITE_ADMIN_API_BASE.trim() !== ""
        ? import.meta.env.VITE_ADMIN_API_BASE.trim().replace(/\/$/, "")
        : "";
    PureHttp.axiosInstance.defaults.baseURL = base;
    this.httpInterceptorsRequest();
    this.httpInterceptorsResponse();
  }

  /** `token`过期后，暂存待执行的请求 */
  private static requests = [];

  /** 防止重复刷新`token` */
  private static isRefreshing = false;

  /** 初始化配置对象 */
  private static initConfig: PureHttpRequestConfig = {};

  /** 保存当前`Axios`实例对象 */
  private static axiosInstance: AxiosInstance = Axios.create(defaultConfig);

  /** 重连原始请求 */
  private static retryOriginalRequest(config: PureHttpRequestConfig) {
    return new Promise(resolve => {
      PureHttp.requests.push((token: string) => {
        config.headers["Authorization"] = formatToken(token);
        resolve(config);
      });
    });
  }

  /** 请求拦截 */
  private httpInterceptorsRequest(): void {
    PureHttp.axiosInstance.interceptors.request.use(
      async (config: PureHttpRequestConfig): Promise<any> => {
        // 优先判断post/get等方法是否传入回调，否则执行初始化设置等回调
        if (typeof config.beforeRequestCallback === "function") {
          config.beforeRequestCallback(config);
          return config;
        }
        if (PureHttp.initConfig.beforeRequestCallback) {
          PureHttp.initConfig.beforeRequestCallback(config);
          return config;
        }
        /** 99chat Admin API 无 refresh-token；仅 legacy 路由尝试刷新 */
        const u = config.url || "";
        const isAdminApi = u.includes("/api/v1/");
        const skipToken =
          u.endsWith("/refresh-token") ||
          u.endsWith("/login") ||
          u.includes("/api/v1/auth/login");
        return skipToken
          ? config
          : new Promise(resolve => {
              const data = getToken();
              const token = data?.accessToken;
              if (token) {
                const now = Date.now();
                const exp = Number(data.expires);
                const expired =
                  Number.isFinite(exp) && exp > 0 ? exp - now <= 0 : false;

                /** Admin API：不调用不存在的 /refresh-token，过期也先带 Token 请求 */
                if (expired && !isAdminApi) {
                  if (!PureHttp.isRefreshing) {
                    PureHttp.isRefreshing = true;
                    useUserStoreHook()
                      .handRefreshToken({ refreshToken: data.refreshToken })
                      .then(res => {
                        const newToken = res.data.accessToken;
                        config.headers["Authorization"] = formatToken(newToken);
                        PureHttp.requests.forEach(cb => cb(newToken));
                        PureHttp.requests = [];
                      })
                      .catch(_err => {
                        PureHttp.requests = [];
                        useUserStoreHook().logOut();
                        message(transformI18n($t("login.pureLoginExpired")), {
                          type: "warning"
                        });
                      })
                      .finally(() => {
                        PureHttp.isRefreshing = false;
                      });
                  }
                  resolve(PureHttp.retryOriginalRequest(config));
                } else {
                  config.headers["Authorization"] = formatToken(token);
                  resolve(config);
                }
              } else {
                resolve(config);
              }
            });
      },
      error => {
        return Promise.reject(error);
      }
    );
  }

  /** 响应拦截 */
  private httpInterceptorsResponse(): void {
    const instance = PureHttp.axiosInstance;
    instance.interceptors.response.use(
      (response: PureHttpResponse) => {
        const $config = response.config;
        // 优先判断post/get等方法是否传入回调，否则执行初始化设置等回调
        if (typeof $config.beforeResponseCallback === "function") {
          $config.beforeResponseCallback(response);
          return response.data;
        }
        if (PureHttp.initConfig.beforeResponseCallback) {
          PureHttp.initConfig.beforeResponseCallback(response);
          return response.data;
        }
        return response.data;
      },
      (error: PureHttpError) => {
        const $error = error;
        $error.isCancelRequest = Axios.isCancel($error);
        const status = $error.response?.status;
        const reqUrl =
          ($error.response?.config?.url ?? $error.config?.url ?? "") + "";
        const errBody = $error.response?.data as
          | { error?: string; message?: string }
          | undefined;
        const errCode = errBody?.error;
        const isAdminApi = reqUrl.includes("/api/v1/");
        const isAuthPassReq =
          reqUrl.includes("/api/v1/auth/login") ||
          reqUrl.includes("/api/v1/auth/logout");

        if (
          status === 401 &&
          !isAuthPassReq &&
          getToken()?.accessToken &&
          isAdminApi
        ) {
          if (errCode === "invalid_token" || errCode === "unauthorized" || !errCode) {
            useUserStoreHook().logOut();
            message(errBody?.message || "登录已过期，请重新登录", {
              type: "warning"
            });
            return Promise.reject($error);
          }
        }

        if (
          status === 401 &&
          !isAuthPassReq &&
          getToken()?.accessToken &&
          !isAdminApi
        ) {
          useUserStoreHook().logOut();
          message(transformI18n($t("login.pureLoginExpired")), {
            type: "warning"
          });
        }
        return Promise.reject($error);
      }
    );
  }

  /** 通用请求工具函数 */
  public request<T>(
    method: RequestMethods,
    url: string,
    param?: AxiosRequestConfig,
    axiosConfig?: PureHttpRequestConfig
  ): Promise<T> {
    const config = {
      method,
      url,
      ...param,
      ...axiosConfig
    } as PureHttpRequestConfig;

    // 单独处理自定义请求/响应回调
    return new Promise((resolve, reject) => {
      PureHttp.axiosInstance
        .request(config)
        .then((response: undefined) => {
          resolve(response);
        })
        .catch(error => {
          reject(error);
        });
    });
  }

  /** 单独抽离的`post`工具函数 */
  public post<T, P>(
    url: string,
    params?: AxiosRequestConfig<P>,
    config?: PureHttpRequestConfig
  ): Promise<T> {
    return this.request<T>("post", url, params, config);
  }

  /** 单独抽离的`get`工具函数 */
  public get<T, P>(
    url: string,
    params?: AxiosRequestConfig<P>,
    config?: PureHttpRequestConfig
  ): Promise<T> {
    return this.request<T>("get", url, params, config);
  }
}

export const http = new PureHttp();
