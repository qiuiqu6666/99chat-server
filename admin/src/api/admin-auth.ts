import { http } from "@/utils/http";
import type { AdminLoginOk } from "@/utils/adminAuth";
import {
  normalizeAdminLoginResponse,
  unwrapApiData
} from "@/utils/chat99AdminApi";

/** 管理员登录（99chat Admin API） */
export const adminLoginApi = async (data: {
  username: string;
  password: string;
}) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/auth/login",
    { data }
  );
  return normalizeAdminLoginResponse(raw) as AdminLoginOk;
};

/** 管理员退出：后端未实现时前端仍会清理本地登录态 */
export const adminLogoutApi = () =>
  http.request<Record<string, unknown>>("post", "/api/v1/auth/logout");

export type AdminAuthMeUser = {
  id: number;
  username: string;
  nickname?: string;
  display_name?: string;
  role: string;
  permissions: string[];
  last_login_time?: string;
  last_login_ip?: string;
  created_at?: string;
  status?: string | number;
};

/** 当前登录用户 */
export const getAdminAuthMe = async () => {
  const raw = await http.request<Record<string, unknown>>("get", "/api/v1/auth/me");
  const data = unwrapApiData(raw);
  const user = (data.user ?? data.admin ?? data) as AdminAuthMeUser;
  return { user };
};

export type AdminProfile = AdminAuthMeUser & {
  email?: string;
  phone?: string;
  avatar?: string;
};

export const getAdminProfile = async () => {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/admin/profile"
  );
  const data = unwrapApiData(raw);
  return (data.user ?? data.admin ?? data) as AdminProfile;
};

export const updateAdminProfile = (data: Partial<AdminProfile>) =>
  http.request<Record<string, unknown>>("put", "/api/v1/admin/profile", {
    data
  });

export const changeAdminPassword = (data: {
  old_password: string;
  new_password: string;
}) =>
  http.request<Record<string, unknown>>(
    "post",
    "/api/v1/auth/change-password",
    { data }
  );

export type AdminLoginLogItem = {
  id?: number | string;
  admin_user_id?: string | null;
  username?: string | null;
  username_attempted?: string | null;
  success?: number | boolean | string | null;
  ip?: string | null;
  geo_address?: string | null;
  user_agent?: string | null;
  fail_reason?: string | null;
  login_at?: number | string | null;
  admin_username?: string | null;
  admin_display_name?: string | null;
  region?: string;
  result?: string;
  message?: string;
  created_at?: string;
  login_time?: string;
};

export const getMyAdminLoginLogs = (params: {
  page?: number;
  page_size?: number;
} = {}) =>
  http.request<{
    items?: AdminLoginLogItem[];
    total?: number;
    page?: number;
    page_size?: number;
  }>("get", "/api/v1/auth/me/login-logs", { params });
