import { request, unwrap } from '../http';

export type LoginResult = {
  access_token: string;
  expires_in: number;
  user: { username: string; permissions: string[] };
};

/**
 * Login against 99Chat admin auth.
 *
 * Soybean 的登录表单参数名是 userName，后端要 username。
 */
export async function fetchLogin(userName: string, password: string) {
  try {
    const raw = await request<Record<string, unknown>>('post', '/auth/login', {
      data: { username: userName, password }
    });
    const data = unwrap(raw);
    const token = String(data.access_token ?? data.accessToken ?? data.token ?? '');
    return {
      data: {
        token,
        refreshToken: ''
      },
      error: null
    };
  } catch (error) {
    return { data: null, error };
  }
}

/** Current admin profile */
export async function fetchGetUserInfo() {
  try {
    const raw = await request<Record<string, unknown>>('get', '/auth/me');
    const data = unwrap(raw);
    const user = (data.user || data) as Record<string, unknown>;
    const rawPerms = user.permissions;
    const perms = Array.isArray(rawPerms)
      ? (rawPerms as string[])
      : typeof rawPerms === 'string'
        ? rawPerms
            .split(',')
            .map(s => s.trim())
            .filter(Boolean)
        : [];
    const username = String(user.username ?? '');
    const superRole = import.meta.env.VITE_STATIC_SUPER_ROLE;
    return {
      data: {
        userId: username,
        userName: username,
        roles: [superRole],
        buttons: perms
      },
      error: null
    };
  } catch (error) {
    return { data: null, error };
  }
}

export function fetchLogout() {
  return request('post', '/auth/logout');
}

export function fetchRefreshToken(_refreshToken: string) {
  return Promise.resolve({
    data: null as { token: string; refreshToken: string } | null,
    error: new Error('refresh token is not used') as Error | null
  });
}

export function fetchCustomBackendError(code: string, msg: string) {
  return request('get', '/auth/error', { params: { code, msg } });
}
