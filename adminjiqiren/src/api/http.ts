import axios from "axios";
import { clearToken, getToken } from "@/router";

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || "/adminjiqiren/api",
  timeout: 60000
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (resp) => {
    const body = resp.data;
    if (body && typeof body.code === "number" && body.code !== 0) {
      return Promise.reject(new Error(body.message || "请求失败"));
    }
    return resp;
  },
  (err) => {
    if (err?.response?.status === 401) {
      clearToken();
      if (!location.pathname.endsWith("/login")) {
        location.href = "/adminjiqiren/login";
      }
    }
    const msg =
      err?.response?.data?.message ||
      err?.message ||
      "网络错误";
    return Promise.reject(new Error(msg));
  }
);

export default http;
