import axios, { type AxiosRequestConfig } from "axios";
import { clearToken, getToken } from "@/utils/auth";
import router from "@/router";

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api/v1",
  timeout: 30000
});

http.interceptors.request.use(config => {
  const token = getToken();
  if (token) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  res => res,
  err => {
    const status = err?.response?.status;
    if (status === 401) {
      clearToken();
      if (router.currentRoute.value.path !== "/login") {
        router.replace({ path: "/login", query: { redirect: router.currentRoute.value.fullPath } });
      }
    }
    return Promise.reject(err);
  }
);

export async function request<T = unknown>(
  method: string,
  url: string,
  config?: AxiosRequestConfig
): Promise<T> {
  const res = await http.request<T>({ method, url, ...config });
  return res.data;
}

export function unwrap(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== "object") return {};
  const obj = raw as Record<string, unknown>;
  if (obj.data && typeof obj.data === "object" && !Array.isArray(obj.data)) {
    return obj.data as Record<string, unknown>;
  }
  return obj;
}

export default http;
