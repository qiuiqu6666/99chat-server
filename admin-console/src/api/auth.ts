import { request, unwrap } from "./http";

export type LoginResult = {
  access_token: string;
  expires_in: number;
  user: { username: string; permissions: string[] };
};

export async function loginApi(username: string, password: string): Promise<LoginResult> {
  const raw = await request<Record<string, unknown>>("post", "/auth/login", {
    data: { username, password }
  });
  const data = unwrap(raw);
  const userRaw = (data.user || {}) as Record<string, unknown>;
  const perms = Array.isArray(userRaw.permissions)
    ? (userRaw.permissions as string[])
    : [];
  return {
    access_token: String(data.access_token ?? data.accessToken ?? data.token ?? ""),
    expires_in: Number(data.expires_in ?? data.expiresIn ?? 86400),
    user: {
      username: String(userRaw.username ?? ""),
      permissions: perms
    }
  };
}

export async function meApi(): Promise<{ username: string; permissions: string[] }> {
  const raw = await request<Record<string, unknown>>("get", "/auth/me");
  const data = unwrap(raw);
  const user = (data.user || data) as Record<string, unknown>;
  const rawPerms = user.permissions;
  const perms = Array.isArray(rawPerms)
    ? (rawPerms as string[])
    : typeof rawPerms === "string"
      ? rawPerms.split(",").map(s => s.trim()).filter(Boolean)
      : [];
  return {
    username: String(user.username ?? ""),
    permissions: perms
  };
}

export function logoutApi() {
  return request("post", "/auth/logout");
}

export function changePasswordApi(oldPassword: string, newPassword: string) {
  return request("post", "/auth/change-password", {
    data: { old_password: oldPassword, new_password: newPassword }
  });
}
