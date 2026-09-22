import type { DataInfo } from "./auth";

/** 99chat Admin API `POST /api/v1/auth/login` 成功响应（内部统一 snake_case） */
export type AdminLoginOk = {
  access_token: string;
  token_type?: string;
  expires_in: number;
  user: {
    id?: number;
    username: string;
    display_name?: string;
    role?: string;
    permissions: string[];
  };
};

export function normalizeAdminLoginData(raw: AdminLoginOk): DataInfo<Date> {
  const ttl = Math.max(60, Number(raw.expires_in) || 86400);
  const ms = Date.now() + ttl * 1000;
  const u = raw.user;
  const role = u.role?.trim() || "admin";
  return {
    accessToken: raw.access_token,
    refreshToken: raw.access_token,
    expires: new Date(ms),
    username: u.username,
    nickname: (u.display_name?.trim() || u.username) ?? "",
    roles: [role],
    permissions: Array.isArray(u.permissions) ? u.permissions : [],
    avatar: ""
  };
}

const errorText: Record<string, string> = {
  invalid_credentials: "用户名或密码错误",
  account_locked: "账户已锁定，请稍后再试",
  account_disabled: "账号已被停用",
  validation_error: "账号或密码格式不正确",
  server_misconfigured: "服务端配置异常，请联系管理员",
  unauthorized: "用户名或密码错误",
  invalid_token: "登录已过期，请重新登录",
  forbidden: "无权限执行此操作"
};

export function extractAdminLoginError(err: unknown): string {
  const ax = err as {
    response?: { data?: { error?: string; message?: string }; status?: number };
    message?: string;
  };
  const d = ax?.response?.data;
  const code = d?.error;
  if (typeof code === "string" && errorText[code]) return errorText[code];
  if (typeof code === "string") return code;
  const msg = d?.message;
  if (typeof msg === "string" && msg.trim()) return msg;
  if (typeof ax?.message === "string" && ax.message !== "Network Error")
    return ax.message;
  return "登录失败，请检查网络或联系管理员";
}
